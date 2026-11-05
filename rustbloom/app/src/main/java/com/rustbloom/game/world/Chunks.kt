package com.rustbloom.game.world

import com.rustbloom.game.engine.INSTANCE_FLOATS
import com.rustbloom.game.engine.InstanceBuffer
import com.rustbloom.game.engine.Mesh
import com.rustbloom.game.engine.MeshBuilder
import com.rustbloom.game.engine.MeshData
import com.rustbloom.game.engine.Rng
import com.rustbloom.game.engine.TWO_PI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

const val CHUNK_SIZE = 64f
const val CHUNK_SIZE_I = 64

/** A harvestable or decorative world object placed deterministically. */
class Node(
    val type: VegType,
    val x: Float, val y: Float, val z: Float,
    val yaw: Float, val scale: Float, val tint: Float,
    val key: Long,
) {
    @Volatile var alive = true
    var hitsLeft = type.hits
}

/** Persistent record of harvested nodes: key -> game time (in days) at which it respawns. */
class HarvestRegistry {
    val respawnAt = ConcurrentHashMap<Long, Double>()

    fun isAlive(key: Long, nowDays: Double): Boolean {
        val t = respawnAt[key] ?: return true
        if (nowDays >= t) { respawnAt.remove(key); return true }
        return false
    }

    fun harvest(key: Long, nowDays: Double, days: Float) { respawnAt[key] = nowDays + days }
}

fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)
fun nodeKey(cx: Int, cz: Int, i: Int): Long = ((cx.toLong() and 0xfffff) shl 40) or ((cz.toLong() and 0xfffff) shl 20) or (i.toLong() and 0xfffff)

class Chunk(val cx: Int, val cz: Int) {
    val x0 = cx * CHUNK_SIZE
    val z0 = cz * CHUNK_SIZE
    var nodes: List<Node> = emptyList()
    var decor: List<Node> = emptyList()
    var nodesReady = false
    var terrain: Mesh? = null
    var water: Mesh? = null
    var lod = -1
    var requestedLod = -1
    var minY = 0f
    var maxY = 0f
    val inst = arrayOfNulls<InstanceBuffer>(VegType.entries.size)
    var instDirty = true
    var hasWater = false

    fun release() {
        terrain?.release(); terrain = null
        water?.release(); water = null
        for (i in inst.indices) { inst[i]?.release(); inst[i] = null }
    }
}

class ChunkBuild(
    val cx: Int, val cz: Int, val lod: Int,
    val terrain: MeshData, val water: MeshData?,
    val nodes: List<Node>?, val decor: List<Node>?,
    val minY: Float, val maxY: Float,
)

/**
 * Streams terrain chunks around the player. Mesh data is generated on worker threads and uploaded on the GL thread.
 */
class ChunkManager(private val harvest: HarvestRegistry) {
    val chunks = HashMap<Long, Chunk>()
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val done = ConcurrentLinkedQueue<ChunkBuild>()
    private val pool = Executors.newFixedThreadPool(2, ThreadFactory { r -> Thread(r, "chunk-gen").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } })

    var viewRadius = 4
    private val wanted = ArrayList<IntArray>()
    private var lastCx = Int.MIN_VALUE
    private var lastCz = Int.MIN_VALUE
    private var lastR = -1
    var highDetailNear = true
    var decorDensity = 1f
    @Volatile var nowDays = 0.0

    fun chunkAt(x: Float, z: Float): Chunk? = chunks[chunkKey(floor(x / CHUNK_SIZE).toInt(), floor(z / CHUNK_SIZE).toInt())]

    private fun lodFor(dist: Int): Int = when {
        dist <= 1 -> if (highDetailNear) 0 else 1
        dist <= 3 -> 1
        else -> 2
    }

    /** Must run on the GL thread. Returns the number of chunks still loading near the player. */
    fun update(px: Float, pz: Float, maxUploads: Int): Int {
        val pcx = floor(px / CHUNK_SIZE).toInt()
        val pcz = floor(pz / CHUNK_SIZE).toInt()
        val r = viewRadius
        // request missing / LOD changes, nearest first (list cached while the player stays in one chunk)
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
                val density = decorDensity
                pool.execute {
                    try {
                        done.add(buildChunk(cx, cz, lod, needNodes, density))
                    } catch (t: Throwable) {
                        android.util.Log.e("Rustbloom", "chunk build failed", t)
                        pending.remove(chunkKey(cx, cz))
                    }
                }
            }
            if (c.terrain == null && w[2] <= 1) nearMissing++
        }
        // uploads
        var uploads = 0
        while (uploads < maxUploads) {
            val b = done.poll() ?: break
            val k = chunkKey(b.cx, b.cz)
            pending.remove(k)
            val c = chunks[k] ?: continue
            c.terrain?.release()
            c.terrain = Mesh(b.terrain)
            c.water?.release()
            c.water = b.water?.let { Mesh(it) }
            c.hasWater = b.water != null
            c.lod = b.lod
            c.minY = b.minY; c.maxY = b.maxY
            if (b.nodes != null && !c.nodesReady) {
                c.nodes = b.nodes
                c.decor = b.decor ?: emptyList()
                c.nodesReady = true
                c.instDirty = true
            }
            uploads++
        }
        // unload far chunks
        val it = chunks.entries.iterator()
        val unloadR = r + 2
        while (it.hasNext()) {
            val e = it.next()
            val c = e.value
            if (abs(c.cx - pcx) > unloadR || abs(c.cz - pcz) > unloadR) {
                if (!pending.contains(e.key)) { c.release(); it.remove() }
            }
        }
        // instance buffers
        for (c in chunks.values) if (c.nodesReady && c.instDirty) rebuildInstances(c)
        return nearMissing
    }

    fun refreshRespawns() {
        for (c in chunks.values) {
            if (!c.nodesReady) continue
            for (n in c.nodes) {
                if (!n.alive && harvest.isAlive(n.key, nowDays)) {
                    n.alive = true
                    n.hitsLeft = n.type.hits
                    c.instDirty = true
                }
            }
        }
    }

    fun markDirty(x: Float, z: Float) { chunkAt(x, z)?.instDirty = true }

    private fun rebuildInstances(c: Chunk) {
        val types = VegType.entries
        val counts = IntArray(types.size)
        for (n in c.nodes) if (n.alive) counts[n.type.ordinal]++
        for (n in c.decor) counts[n.type.ordinal]++
        val arrays = Array(types.size) { FloatArray(counts[it] * INSTANCE_FLOATS) }
        val fill = IntArray(types.size)
        fun put(n: Node) {
            val t = n.type.ordinal
            val a = arrays[t]
            val o = fill[t] * INSTANCE_FLOATS
            a[o] = n.x; a[o + 1] = n.y; a[o + 2] = n.z; a[o + 3] = n.yaw
            a[o + 4] = n.scale; a[o + 5] = n.tint; a[o + 6] = n.type.sway; a[o + 7] = 0f
            fill[t]++
        }
        for (n in c.nodes) if (n.alive) put(n)
        for (n in c.decor) put(n)
        for (t in types.indices) {
            if (counts[t] == 0) {
                c.inst[t]?.release(); c.inst[t] = null
                continue
            }
            val ib = c.inst[t] ?: InstanceBuffer().also { c.inst[t] = it }
            ib.upload(arrays[t], counts[t])
        }
        c.instDirty = false
    }

    fun forEachNodeNear(x: Float, z: Float, r: Float, fn: (Node) -> Unit) {
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

    fun releaseAll() {
        for (c in chunks.values) c.release()
        chunks.clear()
        pending.clear()
        done.clear()
        lastR = -1
    }

    /** Called when GL context is lost: GPU objects are gone; drop references without deleting. */
    fun contextLost() {
        lastR = -1
        chunks.clear()
        pending.clear()
        done.clear()
    }

    fun shutdown() { pool.shutdownNow() }

    // ------------------------------------------------------------------ generation (worker thread)

    private fun buildChunk(cx: Int, cz: Int, lod: Int, withNodes: Boolean, density: Float): ChunkBuild {
        val n = when (lod) { 0 -> 64; 1 -> 32; else -> 16 }
        val step = CHUNK_SIZE / n
        val x0 = cx * CHUNK_SIZE
        val z0 = cz * CHUNK_SIZE
        val g = n + 3 // one extra sample each side for normals
        val hs = FloatArray(g * g)
        for (j in 0 until g) for (i in 0 until g) {
            hs[j * g + i] = WorldGen.height(x0 + (i - 1) * step, z0 + (j - 1) * step)
        }
        val mb = MeshBuilder((n + 1) * (n + 1) + 4 * (n + 1))
        val col = FloatArray(4)
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val base = mb.vertexCount
        for (j in 0..n) for (i in 0..n) {
            val h = hs[(j + 1) * g + (i + 1)]
            val hl = hs[(j + 1) * g + i]; val hr = hs[(j + 1) * g + i + 2]
            val hd = hs[j * g + i + 1]; val hu = hs[(j + 2) * g + i + 1]
            var nx = hl - hr; var ny = 2f * step; var nz = hd - hu
            val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            nx /= l; ny /= l; nz /= l
            val wx = x0 + i * step; val wz = z0 + j * step
            WorldGen.terrainColor(wx, wz, h, col)
            mb.rawVertex(wx, h, wz, nx, ny, nz, col[0], col[1], col[2], col[3])
            minY = min(minY, h); maxY = max(maxY, h)
        }
        val row = n + 1
        for (j in 0 until n) for (i in 0 until n) {
            val a = base + j * row + i
            val b = a + 1
            val d = a + row
            val c = d + 1
            mb.tri(a, d, c)
            mb.tri(a, c, b)
        }
        // skirts to hide LOD cracks
        val skirt = 1.5f + step
        fun edge(idx: IntArray) {
            val first = mb.vertexCount
            for (k in idx) {
                val o = k
                val vx = tmpVert(mb, o)
                mb.rawVertex(vx[0], vx[1] - skirt, vx[2], vx[3], vx[4], vx[5], vx[6], vx[7], vx[8], vx[9])
            }
            for (k in 0 until idx.size - 1) {
                val top0 = idx[k]; val top1 = idx[k + 1]
                val bot0 = first + k; val bot1 = first + k + 1
                // double sided so cracks are hidden from either side
                mb.tri(top0, bot1, bot0); mb.tri(top0, top1, bot1)
                mb.tri(top0, bot0, bot1); mb.tri(top0, bot1, top1)
            }
        }
        edge(IntArray(row) { base + it })
        edge(IntArray(row) { base + n * row + it })
        edge(IntArray(row) { base + it * row })
        edge(IntArray(row) { base + it * row + n })
        val terrain = mb.build()

        // water surface where terrain dips below the water line
        var water: MeshData? = null
        if (minY < WorldGen.WATER + 0.3f) {
            val wn = 16
            val ws = CHUNK_SIZE / wn
            val wb = MeshBuilder((wn + 1) * (wn + 1))
            for (j in 0..wn) for (i in 0..wn) {
                val wx = x0 + i * ws; val wz = z0 + j * ws
                val h = WorldGen.height(wx, wz)
                val depth = WorldGen.WATER - h
                wb.rawVertex(wx, WorldGen.WATER, wz, 0f, 1f, 0f, depth, 0f, 0f, 0f)
            }
            val wr = wn + 1
            var any = false
            for (j in 0 until wn) for (i in 0 until wn) {
                val a = j * wr + i
                val b = a + 1; val d = a + wr; val c = d + 1
                // skip quads that are entirely well above water
                val vmax = max(max(depthAt(wb, a), depthAt(wb, b)), max(depthAt(wb, c), depthAt(wb, d)))
                if (vmax < -0.6f) continue
                wb.tri(a, d, c); wb.tri(a, c, b)
                any = true
            }
            if (any) water = wb.build()
        }

        var nodes: List<Node>? = null
        var decor: List<Node>? = null
        if (withNodes) {
            val pair = generateNodes(cx, cz, density)
            nodes = pair.first
            decor = pair.second
        }
        return ChunkBuild(cx, cz, lod, terrain, water, nodes, decor, minY, maxY)
    }

    private val tmpV = ThreadLocal.withInitial { FloatArray(10) }
    private fun tmpVert(mb: MeshBuilder, index: Int): FloatArray {
        val out = tmpV.get()!!
        mb.readVertex(index, out)
        return out
    }

    private fun depthAt(mb: MeshBuilder, index: Int): Float = mb.vertexFloat(index, 6)

    private fun generateNodes(cx: Int, cz: Int, density: Float): Pair<List<Node>, List<Node>> {
        val nodes = ArrayList<Node>()
        val decor = ArrayList<Node>()
        val rng = Rng(Rng.hash(cx, cz, 911))
        val x0 = cx * CHUNK_SIZE; val z0 = cz * CHUNK_SIZE
        val nrm = FloatArray(3)
        val cell = 4f
        val cells = (CHUNK_SIZE / cell).toInt()
        var idx = 0
        for (j in 0 until cells) for (i in 0 until cells) {
            val x = x0 + (i + rng.nextFloat()) * cell
            val z = z0 + (j + rng.nextFloat()) * cell
            val roll = rng.nextFloat()
            val yaw = rng.nextFloat() * TWO_PI
            val sv = rng.nextFloat()
            val tint = rng.nextFloat()
            val h = WorldGen.height(x, z)
            if (h < 0.4f) {
                // reeds can grow in shallow water
                if (h > -0.8f && roll < 0.05f && WorldGen.marshness(x, z) > 0.3f) {
                    nodes.add(Node(VegType.FIBER_PLANT, x, h, z, yaw, 0.9f + sv * 0.4f, tint, nodeKey(cx, cz, idx++)))
                }
                continue
            }
            if (WorldGen.poiClearance(x, z) < 4f) continue
            WorldGen.normal(x, z, nrm, 1.5f)
            val slope = 1f - nrm[1]
            if (slope > 0.45f) continue
            val biome = WorldGen.biomeAt(x, z, h)
            val type = pick(biome, roll) ?: continue
            if (type.isTree && slope > 0.3f) continue
            val scale = when {
                type.isTree -> 0.75f + sv * 0.6f
                type == VegType.BOULDER -> 0.7f + sv * 0.9f
                else -> 0.8f + sv * 0.5f
            }
            nodes.add(Node(type, x, h - (if (type.isTree) 0f else 0.05f), z, yaw, scale, tint, nodeKey(cx, cz, idx++)))
        }
        for (n in nodes) if (!harvest.isAlive(n.key, nowDays)) n.alive = false
        // decorative grass and flowers on a finer grid
        if (density > 0.01f) {
            val dcell = 2.2f / kotlin.math.sqrt(density)
            val dc = (CHUNK_SIZE / dcell).toInt()
            val drng = Rng(Rng.hash(cx, cz, 4242))
            for (j in 0 until dc) for (i in 0 until dc) {
                val x = x0 + (i + drng.nextFloat()) * dcell
                val z = z0 + (j + drng.nextFloat()) * dcell
                val roll = drng.nextFloat()
                val yaw = drng.nextFloat() * TWO_PI
                val sv = drng.nextFloat()
                val h = WorldGen.height(x, z)
                if (h < 0.3f) continue
                val biome = WorldGen.biomeAt(x, z, h)
                val pGrass = when (biome) {
                    Biome.MEADOW -> 0.75f; Biome.FOREST -> 0.35f; Biome.MARSH -> 0.55f; Biome.PINE -> 0.25f
                    Biome.BEACH -> 0.08f; Biome.DESERT -> 0.04f; else -> 0f
                }
                if (roll > pGrass) continue
                if (WorldGen.poiClearance(x, z) < 2f) continue
                WorldGen.normal(x, z, nrm, 1.5f)
                if (nrm[1] < 0.8f) continue
                val type = if (biome == Biome.MEADOW && roll < 0.06f) VegType.FLOWERS else VegType.GRASS
                decor.add(Node(type, x, h - 0.05f, z, yaw, 0.8f + sv * 0.7f, drng.nextFloat(), 0L))
            }
        }
        return Pair(nodes, decor)
    }

    private fun pick(b: Biome, r: Float): VegType? {
        val table: Array<Pair<VegType, Float>> = when (b) {
            Biome.MEADOW -> MEADOW
            Biome.FOREST -> FOREST
            Biome.PINE -> PINE_T
            Biome.SNOW -> SNOW
            Biome.DESERT -> DESERT
            Biome.MARSH -> MARSH
            Biome.BEACH -> BEACH
            Biome.OCEAN -> return null
        }
        var acc = 0f
        for ((t, p) in table) {
            acc += p
            if (r < acc) return t
        }
        return null
    }

    companion object {
        private val MEADOW = arrayOf(
            VegType.OAK to 0.018f, VegType.BERRY_BUSH to 0.014f, VegType.FIBER_PLANT to 0.03f,
            VegType.ROCK_SMALL to 0.016f, VegType.BOULDER to 0.005f, VegType.HERB to 0.002f, VegType.DEAD_TREE to 0.002f,
        )
        private val FOREST = arrayOf(
            VegType.OAK to 0.15f, VegType.PINE to 0.015f, VegType.DEAD_TREE to 0.006f, VegType.BERRY_BUSH to 0.016f,
            VegType.FIBER_PLANT to 0.014f, VegType.ROCK_SMALL to 0.012f, VegType.BOULDER to 0.007f, VegType.MUSHROOM to 0.012f,
            VegType.HERB to 0.004f,
        )
        private val PINE_T = arrayOf(
            VegType.PINE to 0.12f, VegType.DEAD_TREE to 0.01f, VegType.ROCK_SMALL to 0.016f, VegType.BOULDER to 0.012f,
            VegType.FIBER_PLANT to 0.01f, VegType.BERRY_BUSH to 0.006f, VegType.CRYSTAL to 0.002f,
        )
        private val SNOW = arrayOf(
            VegType.PINE to 0.02f, VegType.DEAD_TREE to 0.006f, VegType.ROCK_SMALL to 0.012f, VegType.BOULDER to 0.014f,
            VegType.CRYSTAL to 0.008f,
        )
        private val DESERT = arrayOf(
            VegType.THORN_BUSH to 0.016f, VegType.DEAD_TREE to 0.005f, VegType.SCRAP_HEAP to 0.009f,
            VegType.ROCK_SMALL to 0.016f, VegType.BOULDER to 0.009f,
        )
        private val MARSH = arrayOf(
            VegType.SWAMP_TREE to 0.04f, VegType.FIBER_PLANT to 0.06f, VegType.HERB to 0.014f, VegType.MUSHROOM to 0.016f,
            VegType.ROCK_SMALL to 0.004f, VegType.BERRY_BUSH to 0.004f,
        )
        private val BEACH = arrayOf(
            VegType.ROCK_SMALL to 0.012f, VegType.FIBER_PLANT to 0.012f, VegType.DEAD_TREE to 0.003f, VegType.BOULDER to 0.003f,
        )
    }
}
