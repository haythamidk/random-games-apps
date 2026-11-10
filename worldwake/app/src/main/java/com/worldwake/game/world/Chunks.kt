package com.worldwake.game.world

import com.worldwake.game.engine.INSTANCE_FLOATS
import com.worldwake.game.engine.InstanceBuffer
import com.worldwake.game.engine.Mesh
import com.worldwake.game.engine.MeshBuilder
import com.worldwake.game.engine.MeshData
import com.worldwake.game.engine.Rng
import com.worldwake.game.engine.TERRAIN_FLOATS
import com.worldwake.game.engine.TWO_PI
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
    val type: PropType,
    val x: Float, val y: Float, val z: Float,
    val yaw: Float, val scale: Float, val tint: Float,
    val key: Long,
    /** Growth threshold 0..1: the prop appears once its growth channel passes this. */
    val grow: Float = 0f,
) {
    @Volatile var alive = true
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
    val inst = arrayOfNulls<InstanceBuffer>(PropType.entries.size)
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
    private val pool = com.worldwake.game.engine.Workers.gen

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
                        android.util.Log.e("Worldwake", "chunk build failed", t)
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
                    c.instDirty = true
                }
            }
        }
    }

    fun markDirty(x: Float, z: Float) { chunkAt(x, z)?.instDirty = true }

    private fun rebuildInstances(c: Chunk) {
        val types = PropType.entries
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
            a[o + 4] = n.scale; a[o + 5] = n.tint; a[o + 6] = n.type.sway; a[o + 7] = n.grow
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

    fun shutdown() {}

    // ------------------------------------------------------------------ generation (worker thread)

    internal fun buildChunk(cx: Int, cz: Int, lod: Int, withNodes: Boolean, density: Float): ChunkBuild {
        val n = when (lod) { 0 -> 64; 1 -> 32; else -> 16 }
        val step = CHUNK_SIZE / n
        val x0 = cx * CHUNK_SIZE
        val z0 = cz * CHUNK_SIZE
        val g = n + 3 // one extra sample each side for normals
        val hs = FloatArray(g * g)
        for (j in 0 until g) for (i in 0 until g) {
            hs[j * g + i] = WorldGen.height(x0 + (i - 1) * step, z0 + (j - 1) * step)
        }
        // terrain vertices: position, normal, splat weights A and B
        val row = n + 1
        val vcount = row * row + 4 * row
        val vb = FloatArray(vcount * TERRAIN_FLOATS)
        val ib = IntArray(n * n * 6 + 4 * (row - 1) * 12)
        val sp = FloatArray(8)
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var vi = 0
        for (j in 0..n) for (i in 0..n) {
            val h = hs[(j + 1) * g + (i + 1)]
            val hl = hs[(j + 1) * g + i]; val hr = hs[(j + 1) * g + i + 2]
            val hd = hs[j * g + i + 1]; val hu = hs[(j + 2) * g + i + 1]
            var nx = hl - hr; var ny = 2f * step; var nz = hd - hu
            val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            nx /= l; ny /= l; nz /= l
            val wx = x0 + i * step; val wz = z0 + j * step
            WorldGen.splat(wx, wz, h, sp)
            val o = vi * TERRAIN_FLOATS
            vb[o] = wx; vb[o + 1] = h; vb[o + 2] = wz
            vb[o + 3] = nx; vb[o + 4] = ny; vb[o + 5] = nz
            for (k in 0 until 8) vb[o + 6 + k] = sp[k]
            vi++
            minY = min(minY, h); maxY = max(maxY, h)
        }
        var ii = 0
        for (j in 0 until n) for (i in 0 until n) {
            val a = j * row + i
            val b = a + 1
            val d = a + row
            val c = d + 1
            ib[ii++] = a; ib[ii++] = d; ib[ii++] = c
            ib[ii++] = a; ib[ii++] = c; ib[ii++] = b
        }
        // skirts hide cracks between LOD levels (double sided)
        val skirt = 1.5f + step
        fun edge(idx: IntArray) {
            val first = vi
            for (k in idx) {
                System.arraycopy(vb, k * TERRAIN_FLOATS, vb, vi * TERRAIN_FLOATS, TERRAIN_FLOATS)
                vb[vi * TERRAIN_FLOATS + 1] -= skirt
                vi++
            }
            for (k in 0 until idx.size - 1) {
                val top0 = idx[k]; val top1 = idx[k + 1]
                val bot0 = first + k; val bot1 = first + k + 1
                ib[ii++] = top0; ib[ii++] = bot1; ib[ii++] = bot0
                ib[ii++] = top0; ib[ii++] = top1; ib[ii++] = bot1
                ib[ii++] = top0; ib[ii++] = bot0; ib[ii++] = bot1
                ib[ii++] = top0; ib[ii++] = bot1; ib[ii++] = top1
            }
        }
        edge(IntArray(row) { it })
        edge(IntArray(row) { n * row + it })
        edge(IntArray(row) { it * row })
        edge(IntArray(row) { it * row + n })
        val terrain = MeshData(vb, vi, ib, ii, TERRAIN_FLOATS)

        // water surface where terrain dips below the water line
        var water: MeshData? = null
        if (minY < WorldGen.WATER + 0.3f) {
            val wn = 32
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
                if (vmax < -0.4f) continue
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
            val gr = rng.nextFloat()
            val key = nodeKey(cx, cz, idx++)
            if (x * x + z * z > WorldGen.WORLD_R * WorldGen.WORLD_R) continue
            val h = WorldGen.height(x, z)
            if (WorldGen.poiClearance(x, z) < 3f) continue
            WorldGen.normal(x, z, nrm, 1.5f)
            val slope = 1f - nrm[1]
            if (slope > 0.42f) continue
            val biome = WorldGen.biomeAt(x, z)
            val type = pick(biome, roll, h) ?: continue
            if (type.isTree && (slope > 0.28f || h < 0.7f)) continue
            if (type.grow != Grow.NONE && type.grow != Grow.FROST) {
                // life needs fertile ground
                if (gr > 0.25f + WorldGen.fertility(x, z, h) * 0.75f) continue
            }
            if (h < 0.3f && (type.grow != Grow.NONE || type == PropType.SPIRE || type == PropType.COLUMNS || type == PropType.VENT)) continue
            val scale = when {
                type.isTree -> 0.78f + sv * 0.6f
                type == PropType.BOULDER -> 0.7f + sv * 1.1f
                type == PropType.SPIRE -> 0.7f + sv * 1.0f
                type == PropType.COLUMNS -> 0.7f + sv * 0.7f
                type == PropType.SHARD_BIG -> 0.6f + sv * 1.0f
                type == PropType.ICE_SPIKE -> 0.6f + sv * 1.1f
                type.isOre -> 0.95f + sv * 0.3f
                else -> 0.8f + sv * 0.5f
            }
            val growThr = if (type.grow == Grow.NONE) 0f else rng.nextFloat() * 0.82f
            nodes.add(Node(type, x, h - (if (type.isOre) 0.02f else 0.05f), z, yaw, scale, tint, key, growThr))
        }
        for (n in nodes) if (n.type.isOre && !harvest.isAlive(n.key, nowDays)) n.alive = false
        // native ground life on a finer grid: it only shows once its growth channel wakes
        if (density > 0.01f) {
            val dcell = 1.8f / kotlin.math.sqrt(density)
            val dc = (CHUNK_SIZE / dcell).toInt()
            val drng = Rng(Rng.hash(cx, cz, 4242))
            for (j in 0 until dc) for (i in 0 until dc) {
                val x = x0 + (i + drng.nextFloat()) * dcell
                val z = z0 + (j + drng.nextFloat()) * dcell
                val roll = drng.nextFloat()
                val yaw = drng.nextFloat() * TWO_PI
                val sv = drng.nextFloat()
                val tn = drng.nextFloat()
                val gt = drng.nextFloat()
                val pick = drng.nextFloat()
                if (x * x + z * z > WorldGen.RIM_R * WorldGen.RIM_R) continue
                val h = WorldGen.height(x, z)
                if (h < -0.7f) continue
                if (h < 0.35f) {
                    // reeds stand in the shallows
                    if (roll < 0.3f) decor.add(Node(PropType.REED, x, h - 0.05f, z, yaw, 0.8f + sv * 0.6f, tn, 0L, gt * 0.82f))
                    continue
                }
                val fert = WorldGen.fertility(x, z, h)
                if (roll > fert * 0.78f) continue
                if (WorldGen.poiClearance(x, z) < 1.5f) continue
                WorldGen.normal(x, z, nrm, 1.5f)
                if (nrm[1] < 0.8f) continue
                val biome = WorldGen.biomeAt(x, z)
                val type = when {
                    pick < 0.045f -> PropType.FLOWER_LANTERN
                    pick < 0.1f -> PropType.FLOWER_STAR
                    pick < 0.2f -> PropType.MOSS_TUFT
                    pick < 0.25f && (biome == Biome.SPIRES || biome == Biome.SHARDS || biome == Biome.HIGHLAND) -> PropType.GLOWCAP
                    else -> PropType.GRASS
                }
                decor.add(Node(type, x, h - 0.05f, z, yaw, 0.8f + sv * 0.7f, tn, 0L, gt * 0.82f))
            }
        }
        return Pair(nodes, decor)
    }

    private fun pick(b: Biome, r: Float, h: Float): PropType? {
        val table: Array<Pair<PropType, Float>> = when (b) {
            Biome.FLATS -> if (h < 3.2f) SHORE else FLATS
            Biome.SPIRES -> SPIRES
            Biome.SHARDS -> SHARDS
            Biome.VENTS -> VENTS
            Biome.HIGHLAND -> HIGHLAND
            Biome.SINK -> SINK
            Biome.RIM -> RIM
        }
        var acc = 0f
        for ((t, p) in table) {
            acc += p
            if (r < acc) return t
        }
        return null
    }

    companion object {
        private val FLATS = arrayOf(
            PropType.ORE_FERRITE to 0.011f, PropType.ORE_SILICA to 0.009f, PropType.ORE_COBALT to 0.007f, PropType.ORE_MAGNESIUM to 0.007f,
            PropType.ORE_ICE to 0.008f, PropType.ORE_TITANIUM to 0.004f, PropType.BOULDER to 0.006f, PropType.ROCK_SMALL to 0.02f,
            PropType.ICE_SPIKE to 0.035f, PropType.TREE_EMBER to 0.05f, PropType.TREE_SPINDLE to 0.008f, PropType.BUSH to 0.02f,
        )
        private val SHORE = arrayOf(
            PropType.ORE_ICE to 0.016f, PropType.ORE_SILICA to 0.01f, PropType.ORE_FERRITE to 0.008f, PropType.ORE_COBALT to 0.007f,
            PropType.ORE_MAGNESIUM to 0.006f, PropType.BOULDER to 0.005f, PropType.ROCK_SMALL to 0.02f, PropType.ICE_SPIKE to 0.02f,
            PropType.TREE_VEIL to 0.05f, PropType.TREE_EMBER to 0.015f, PropType.BUSH to 0.025f,
        )
        private val SPIRES = arrayOf(
            PropType.SPIRE to 0.03f, PropType.COLUMNS to 0.012f, PropType.BOULDER to 0.014f, PropType.ORE_ALUMINIUM to 0.013f,
            PropType.ORE_TITANIUM to 0.011f, PropType.ORE_FERRITE to 0.008f, PropType.ROCK_SMALL to 0.03f, PropType.TREE_SPINDLE to 0.04f,
            PropType.BUSH to 0.008f,
        )
        private val SHARDS = arrayOf(
            PropType.SHARD_BIG to 0.022f, PropType.SHARD_SMALL to 0.05f, PropType.ORE_LUMEN to 0.013f, PropType.ORE_SILICA to 0.014f,
            PropType.ORE_COBALT to 0.009f, PropType.ROCK_SMALL to 0.012f, PropType.TREE_VEIL to 0.006f,
        )
        private val VENTS = arrayOf(
            PropType.VENT to 0.009f, PropType.ORE_SULFUR to 0.02f, PropType.ORE_PYRE to 0.009f, PropType.BOULDER to 0.01f,
            PropType.ROCK_SMALL to 0.03f, PropType.ORE_FERRITE to 0.006f,
        )
        private val HIGHLAND = arrayOf(
            PropType.COLUMNS to 0.008f, PropType.BOULDER to 0.02f, PropType.ORE_OSMIUM to 0.009f, PropType.ORE_TITANIUM to 0.008f,
            PropType.ORE_MAGNESIUM to 0.008f, PropType.ICE_SPIKE to 0.03f, PropType.ROCK_SMALL to 0.025f, PropType.TREE_SPINDLE to 0.03f,
        )
        private val SINK = arrayOf(
            PropType.ORE_ICE to 0.03f, PropType.ROCK_SMALL to 0.012f, PropType.BOULDER to 0.004f, PropType.ICE_SPIKE to 0.012f,
        )
        private val RIM = arrayOf(
            PropType.BOULDER to 0.02f, PropType.ICE_SPIKE to 0.03f, PropType.ROCK_SMALL to 0.02f,
        )
    }
}
