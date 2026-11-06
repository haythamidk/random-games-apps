package com.hadal.game.world

import com.hadal.game.engine.INSTANCE_FLOATS
import com.hadal.game.engine.InstanceBuffer
import com.hadal.game.engine.Mesh
import com.hadal.game.engine.MeshData
import com.hadal.game.engine.Rng
import com.hadal.game.engine.TERRAIN_FLOATS
import com.hadal.game.engine.TWO_PI
import com.hadal.game.engine.Workers
import com.hadal.game.engine.smoothstep
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val CHUNK_SIZE = 64f

class Node(
    val type: FloraType,
    val x: Float, val y: Float, val z: Float,
    val yaw: Float, val scale: Float, val tint: Float,
    val key: Long,
) {
    @Volatile var alive = true
}

/** Harvested node keys and the play time (seconds) at which they grow back. */
class HarvestRegistry {
    val respawnAt = ConcurrentHashMap<Long, Double>()
    fun isAlive(key: Long, now: Double): Boolean {
        val t = respawnAt[key] ?: return true
        if (now >= t) { respawnAt.remove(key); return true }
        return false
    }
    fun harvest(key: Long, now: Double, minutes: Float) { respawnAt[key] = now + minutes * 60.0 }
}

fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)
fun nodeKey(cx: Int, cz: Int, i: Int): Long = ((cx.toLong() and 0xfffff) shl 40) or ((cz.toLong() and 0xfffff) shl 20) or (i.toLong() and 0xfffff)

class Chunk(val cx: Int, val cz: Int) {
    var nodes: List<Node> = emptyList()
    var nodesReady = false
    var terrain: Mesh? = null
    var lod = -1
    var requestedLod = -1
    var minY = 0f
    var maxY = 0f
    val inst = arrayOfNulls<InstanceBuffer>(FloraType.entries.size)
    var instDirty = true

    fun release() {
        terrain?.release(); terrain = null
        for (i in inst.indices) { inst[i]?.release(); inst[i] = null }
    }
}

class ChunkBuild(val cx: Int, val cz: Int, val lod: Int, val terrain: MeshData, val nodes: List<Node>?, val minY: Float, val maxY: Float)

/** Streams sea floor chunks around the sub; meshes are generated on worker threads and uploaded on the GL thread. */
class ChunkManager(private val harvest: HarvestRegistry) {
    val chunks = HashMap<Long, Chunk>()
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val done = ConcurrentLinkedQueue<ChunkBuild>()
    private val pool = Workers.gen

    var viewRadius = 3
    var density = 1f
    @Volatile var now = 0.0
    private val wanted = ArrayList<IntArray>()
    private var lastCx = Int.MIN_VALUE
    private var lastCz = Int.MIN_VALUE
    private var lastR = -1

    private fun lodFor(d: Int) = if (d <= 1) 0 else if (d <= 2) 1 else 2

    /** GL thread. Returns how many near chunks are still missing. */
    fun update(px: Float, pz: Float, maxUploads: Int): Int {
        val pcx = floor(px / CHUNK_SIZE).toInt(); val pcz = floor(pz / CHUNK_SIZE).toInt()
        val r = viewRadius
        if (pcx != lastCx || pcz != lastCz || r != lastR) {
            lastCx = pcx; lastCz = pcz; lastR = r
            wanted.clear()
            for (dz in -r..r) for (dx in -r..r) {
                if (dx * dx + dz * dz > (r + 0.5f) * (r + 0.5f)) continue
                wanted.add(intArrayOf(pcx + dx, pcz + dz, max(abs(dx), abs(dz))))
            }
            wanted.sortBy { it[2] }
        }
        var nearMissing = 0
        for (w in wanted) {
            val k = chunkKey(w[0], w[1])
            val lod = lodFor(w[2])
            var c = chunks[k]
            if (c == null) { c = Chunk(w[0], w[1]); chunks[k] = c }
            if (c.lod != lod && c.requestedLod != lod && !pending.contains(k)) {
                c.requestedLod = lod
                pending.add(k)
                val needNodes = !c.nodesReady
                val cx = w[0]; val cz = w[1]
                val dens = density
                pool.execute {
                    try { done.add(buildChunk(cx, cz, lod, needNodes, dens)) } catch (t: Throwable) {
                        android.util.Log.e("Hadal", "chunk build failed", t); pending.remove(chunkKey(cx, cz))
                    }
                }
            }
            if (c.terrain == null && w[2] <= 1) nearMissing++
        }
        var uploads = 0
        while (uploads < maxUploads) {
            val b = done.poll() ?: break
            val k = chunkKey(b.cx, b.cz)
            pending.remove(k)
            val c = chunks[k] ?: continue
            c.terrain?.release()
            c.terrain = Mesh(b.terrain)
            c.lod = b.lod
            c.minY = b.minY; c.maxY = b.maxY
            if (b.nodes != null && !c.nodesReady) {
                c.nodes = b.nodes
                for (n in c.nodes) if (n.type.harvestable && !harvest.isAlive(n.key, now)) n.alive = false
                c.nodesReady = true; c.instDirty = true
            }
            uploads++
        }
        val it = chunks.entries.iterator()
        val unloadR = r + 2
        while (it.hasNext()) {
            val e = it.next(); val c = e.value
            if (abs(c.cx - pcx) > unloadR || abs(c.cz - pcz) > unloadR) {
                if (!pending.contains(e.key)) { c.release(); it.remove() }
            }
        }
        for (c in chunks.values) if (c.nodesReady && c.instDirty) rebuildInstances(c)
        return nearMissing
    }

    fun refreshRespawns() {
        for (c in chunks.values) {
            if (!c.nodesReady) continue
            for (n in c.nodes) if (!n.alive && harvest.isAlive(n.key, now)) { n.alive = true; c.instDirty = true }
        }
    }

    fun markDirty(x: Float, z: Float) {
        chunks[chunkKey(floor(x / CHUNK_SIZE).toInt(), floor(z / CHUNK_SIZE).toInt())]?.instDirty = true
    }

    private fun rebuildInstances(c: Chunk) {
        val types = FloraType.entries
        val counts = IntArray(types.size)
        for (n in c.nodes) if (n.alive) counts[n.type.ordinal]++
        val arrays = Array(types.size) { FloatArray(counts[it] * INSTANCE_FLOATS) }
        val fill = IntArray(types.size)
        for (n in c.nodes) {
            if (!n.alive) continue
            val t = n.type.ordinal
            val a = arrays[t]; val o = fill[t] * INSTANCE_FLOATS
            a[o] = n.x; a[o + 1] = n.y; a[o + 2] = n.z; a[o + 3] = n.yaw
            a[o + 4] = n.scale; a[o + 5] = n.tint; a[o + 6] = n.type.sway; a[o + 7] = (n.key and 1023).toFloat()
            fill[t]++
        }
        for (t in types.indices) {
            if (counts[t] == 0) { c.inst[t]?.release(); c.inst[t] = null; continue }
            val ib = c.inst[t] ?: InstanceBuffer().also { c.inst[t] = it }
            ib.upload(arrays[t], counts[t])
        }
        c.instDirty = false
    }

    inline fun forEachNodeNear(x: Float, z: Float, r: Float, fn: (Node) -> Unit) {
        val c0x = floor((x - r) / CHUNK_SIZE).toInt(); val c1x = floor((x + r) / CHUNK_SIZE).toInt()
        val c0z = floor((z - r) / CHUNK_SIZE).toInt(); val c1z = floor((z + r) / CHUNK_SIZE).toInt()
        val r2 = r * r
        for (cx in c0x..c1x) for (cz in c0z..c1z) {
            val c = chunks[chunkKey(cx, cz)] ?: continue
            if (!c.nodesReady) continue
            for (n in c.nodes) {
                if (!n.alive) continue
                val dx = n.x - x; val dz = n.z - z
                if (dx * dx + dz * dz <= r2) fn(n)
            }
        }
    }

    fun ready(px: Float, pz: Float): Boolean {
        val pcx = floor(px / CHUNK_SIZE).toInt(); val pcz = floor(pz / CHUNK_SIZE).toInt()
        for (dz in -1..1) for (dx in -1..1) {
            val c = chunks[chunkKey(pcx + dx, pcz + dz)] ?: return false
            if (c.terrain == null || !c.nodesReady) return false
        }
        return true
    }

    fun releaseAll() { for (c in chunks.values) c.release(); chunks.clear(); pending.clear(); done.clear(); lastR = -1 }
    fun contextLost() { lastR = -1; chunks.clear(); pending.clear(); done.clear() }

    // ------------------------------------------------------------------ generation (worker threads)

    fun buildChunk(cx: Int, cz: Int, lod: Int, withNodes: Boolean, density: Float): ChunkBuild {
        val n = when (lod) { 0 -> 32; 1 -> 16; else -> 8 }
        val step = CHUNK_SIZE / n
        val x0 = cx * CHUNK_SIZE; val z0 = cz * CHUNK_SIZE
        val g = n + 3
        val hs = FloatArray(g * g)
        for (j in 0 until g) for (i in 0 until g) hs[j * g + i] = SeaGen.height(x0 + (i - 1) * step, z0 + (j - 1) * step)
        val row = n + 1
        val vcount = row * row + 4 * row
        val vb = FloatArray(vcount * TERRAIN_FLOATS)
        val ib = IntArray(n * n * 6 + 4 * (row - 1) * 12)
        val sp = FloatArray(4)
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var vi = 0
        for (j in 0..n) for (i in 0..n) {
            val h = hs[(j + 1) * g + (i + 1)]
            val hl = hs[(j + 1) * g + i]; val hr = hs[(j + 1) * g + i + 2]
            val hd = hs[j * g + i + 1]; val hu = hs[(j + 2) * g + i + 1]
            var nx = hl - hr; var ny = 2f * step; var nz = hd - hu
            val l = sqrt(nx * nx + ny * ny + nz * nz); nx /= l; ny /= l; nz /= l
            val wx = x0 + i * step; val wz = z0 + j * step
            SeaGen.splat(wx, wz, h, sp)
            // cavity: how much lower this vertex sits than its neighbours (darkens crevices)
            val cav = smoothstep(0f, 2.5f, (hl + hr + hd + hu) * 0.25f - h)
            val o = vi * TERRAIN_FLOATS
            vb[o] = wx; vb[o + 1] = h; vb[o + 2] = wz
            vb[o + 3] = nx; vb[o + 4] = ny; vb[o + 5] = nz
            vb[o + 6] = sp[0]; vb[o + 7] = sp[1]; vb[o + 8] = sp[2]; vb[o + 9] = sp[3]
            vb[o + 10] = cav; vb[o + 11] = 0f; vb[o + 12] = 0f; vb[o + 13] = 0f
            vi++
            minY = min(minY, h); maxY = max(maxY, h)
        }
        var ii = 0
        for (j in 0 until n) for (i in 0 until n) {
            val a = j * row + i; val b = a + 1; val d = a + row; val c = d + 1
            ib[ii++] = a; ib[ii++] = d; ib[ii++] = c
            ib[ii++] = a; ib[ii++] = c; ib[ii++] = b
        }
        val skirt = 2f + step
        fun edge(idx: IntArray) {
            val first = vi
            for (k in idx) {
                System.arraycopy(vb, k * TERRAIN_FLOATS, vb, vi * TERRAIN_FLOATS, TERRAIN_FLOATS)
                vb[vi * TERRAIN_FLOATS + 1] -= skirt
                vi++
            }
            for (k in 0 until idx.size - 1) {
                val t0 = idx[k]; val t1 = idx[k + 1]; val b0 = first + k; val b1 = first + k + 1
                ib[ii++] = t0; ib[ii++] = b1; ib[ii++] = b0
                ib[ii++] = t0; ib[ii++] = t1; ib[ii++] = b1
                ib[ii++] = t0; ib[ii++] = b0; ib[ii++] = b1
                ib[ii++] = t0; ib[ii++] = b1; ib[ii++] = t1
            }
        }
        edge(IntArray(row) { it }); edge(IntArray(row) { n * row + it })
        edge(IntArray(row) { it * row }); edge(IntArray(row) { it * row + n })
        val terrain = MeshData(vb, vi, ib, ii, TERRAIN_FLOATS)
        val nodes = if (withNodes) generateNodes(cx, cz, density) else null
        return ChunkBuild(cx, cz, lod, terrain, nodes, minY, maxY)
    }

    private fun nearPoi(x: Float, z: Float, pad: Float): Boolean {
        for (p in SeaGen.pois) {
            val dx = x - p.x; val dz = z - p.z
            val r = p.radius + pad
            if (dx * dx + dz * dz < r * r) return true
        }
        return false
    }

    private fun generateNodes(cx: Int, cz: Int, density: Float): List<Node> {
        val out = ArrayList<Node>()
        val rng = Rng(Rng.hash(cx, cz, 911))
        val x0 = cx * CHUNK_SIZE; val z0 = cz * CHUNK_SIZE
        val nrm = FloatArray(3)
        var idx = 0
        fun add(t: FloraType, x: Float, z: Float, scale: Float, sink: Float = 0.1f) {
            val h = SeaGen.height(x, z)
            out.add(Node(t, x, h - sink * scale, z, rng.range(0f, TWO_PI), scale, rng.range(0f, 1f), nodeKey(cx, cz, idx++)))
        }
        val samples = (150 * density).toInt().coerceAtLeast(40)
        for (s in 0 until samples) {
            val x = x0 + rng.range(0f, CHUNK_SIZE); val z = z0 + rng.range(0f, CHUNK_SIZE)
            if (nearPoi(x, z, 6f)) { rng.nextFloat(); continue }
            val h = SeaGen.height(x, z)
            val zone = SeaGen.zone(x, z, h)
            SeaGen.normal(x, z, nrm)
            val steep = nrm[1] < 0.75f
            val d1 = SeaGen.density(x, z, 1f); val d2 = SeaGen.density(x, z, 7f)
            val roll = rng.nextFloat()
            when (zone) {
                Zone.SHELF -> when {
                    roll > 0.965f -> add(FloraType.SCRAP, x, z, 1f)
                    !steep && d1 > 0.55f && roll < 0.55f -> add(FloraType.KELP, x, z, rng.range(0.7f, 1.35f), 0f)
                    !steep && roll < 0.3f -> add(FloraType.SEAGRASS, x, z, rng.range(0.7f, 1.3f), 0f)
                    d2 > 0.5f && roll < 0.42f -> add(listOf(FloraType.CORAL_FAN, FloraType.CORAL_BRAIN, FloraType.CORAL_BRANCH)[rng.nextInt(3)], x, z, rng.range(0.6f, 1.4f))
                    roll < 0.46f -> add(FloraType.ROCK, x, z, rng.range(0.5f, 1.6f), 0.3f)
                    roll < 0.48f -> add(FloraType.BOULDER, x, z, rng.range(0.6f, 1.2f), 0.4f)
                    roll < 0.5f -> add(FloraType.SCRAP, x, z, 1f)
                    roll < 0.513f -> add(FloraType.COPPER, x, z, rng.range(0.8f, 1.1f), 0.2f)
                    roll < 0.52f -> add(FloraType.BIOPOD, x, z, 1f, 0f)
                    roll < 0.523f -> add(FloraType.CLAM, x, z, 1f, 0f)
                }
                Zone.TWILIGHT -> when {
                    roll > 0.965f -> add(FloraType.SCRAP, x, z, 1f)
                    d1 > 0.5f && roll < 0.2f -> add(FloraType.SPONGE, x, z, rng.range(0.6f, 1.5f))
                    d2 > 0.55f && roll < 0.3f -> add(FloraType.CORAL_FAN, x, z, rng.range(0.8f, 1.8f))
                    roll < 0.38f -> add(FloraType.ROCK, x, z, rng.range(0.6f, 2f), 0.3f)
                    roll < 0.405f -> add(FloraType.BOULDER, x, z, rng.range(0.7f, 1.6f), 0.4f)
                    steep && roll < 0.42f -> add(FloraType.PILLAR, x, z, rng.range(0.6f, 1.2f), 0.6f)
                    roll < 0.43f -> add(FloraType.SCRAP, x, z, 1f)
                    roll < 0.445f -> add(FloraType.COPPER, x, z, rng.range(0.8f, 1.2f), 0.2f)
                    roll < 0.46f -> add(FloraType.BIOPOD, x, z, 1f, 0f)
                    roll < 0.47f -> add(FloraType.SEA_LILY, x, z, rng.range(0.7f, 1.3f), 0f)
                    roll < 0.474f -> add(FloraType.CLAM, x, z, 1f, 0f)
                }
                Zone.MIDNIGHT -> when {
                    roll > 0.975f -> add(FloraType.SCRAP, x, z, 1f)
                    d1 > 0.45f && roll < 0.14f -> add(FloraType.NODULE, x, z, rng.range(0.8f, 1.3f), 0f)
                    roll < 0.2f -> add(FloraType.SEA_LILY, x, z, rng.range(0.7f, 1.5f), 0f)
                    roll < 0.24f -> add(FloraType.ANEMONE, x, z, rng.range(0.7f, 1.4f), 0f)
                    roll < 0.32f -> add(FloraType.ROCK, x, z, rng.range(0.6f, 2f), 0.3f)
                    roll < 0.335f -> add(FloraType.BOULDER, x, z, rng.range(0.8f, 1.7f), 0.4f)
                    steep && roll < 0.35f -> add(FloraType.PILLAR, x, z, rng.range(0.7f, 1.3f), 0.6f)
                    roll < 0.36f -> add(FloraType.BIOPOD, x, z, 1f, 0f)
                    roll < 0.367f -> add(FloraType.SCRAP, x, z, 1f)
                    roll < 0.371f -> add(FloraType.CLAM, x, z, 1f, 0f)
                }
                Zone.VENTS -> when {
                    roll < 0.035f -> add(FloraType.CHIMNEY, x, z, rng.range(0.7f, 1.4f), 0.3f)
                    roll < 0.2f -> add(FloraType.TUBEWORMS, x, z, rng.range(0.7f, 1.5f), 0f)
                    d1 > 0.5f && roll < 0.27f -> add(FloraType.CRYSTAL, x, z, rng.range(0.8f, 1.3f), 0f)
                    roll < 0.36f -> add(FloraType.ROCK, x, z, rng.range(0.6f, 2f), 0.3f)
                    roll < 0.38f -> add(FloraType.NODULE, x, z, 1f, 0f)
                    roll < 0.39f -> add(FloraType.ANEMONE, x, z, 1f, 0f)
                }
                Zone.RIM, Zone.HADAL -> when {
                    steep && roll < 0.05f -> add(FloraType.PILLAR, x, z, rng.range(0.8f, 1.5f), 0.6f)
                    roll < 0.14f -> add(FloraType.ROCK, x, z, rng.range(0.8f, 2.4f), 0.3f)
                    roll < 0.2f -> add(FloraType.ANEMONE, x, z, rng.range(0.8f, 1.5f), 0f)
                    roll < 0.24f -> add(FloraType.SEA_LILY, x, z, rng.range(1f, 1.8f), 0f)
                    zone == Zone.HADAL && roll < 0.265f -> add(FloraType.ALLOY, x, z, 1f, 0f)
                    zone == Zone.RIM && roll < 0.252f -> add(FloraType.ALLOY, x, z, 1f, 0f)
                    roll < 0.29f -> add(FloraType.NODULE, x, z, 1f, 0f)
                    roll < 0.3f -> add(FloraType.CRYSTAL, x, z, 1f, 0f)
                }
            }
        }
        // whale fall skeletons and wreck debris around points of interest
        for (p in SeaGen.pois) {
            val pcx = floor(p.x / CHUNK_SIZE).toInt(); val pcz = floor(p.z / CHUNK_SIZE).toInt()
            if (pcx != cx || pcz != cz) continue
            when (p.type) {
                PoiType.WHALEFALL -> {
                    add(FloraType.BONES, p.x, p.z, 1.3f, 0.2f)
                    for (k in 0 until 10) { val a = rng.range(0f, TWO_PI); val d = rng.range(4f, 14f); add(if (k % 2 == 0) FloraType.CLAM else FloraType.BIOPOD, p.x + kotlin.math.cos(a) * d, p.z + kotlin.math.sin(a) * d, 1f, 0f) }
                }
                PoiType.MERIDIAN, PoiType.WRECK, PoiType.CINDER -> {
                    for (k in 0 until 14) { val a = rng.range(0f, TWO_PI); val d = p.radius + rng.range(2f, 22f); add(FloraType.SCRAP, p.x + kotlin.math.cos(a) * d, p.z + kotlin.math.sin(a) * d, rng.range(0.9f, 1.3f)) }
                }
                else -> {}
            }
        }
        return out
    }
}
