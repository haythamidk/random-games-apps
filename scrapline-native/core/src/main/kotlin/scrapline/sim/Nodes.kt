package scrapline.sim

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** Kinds of hand-gatherable resource node. 0 is unused so a zero means "none". */
object NodeKind {
    const val DEADWOOD = 1
    const val STONE = 2
    const val ORE = 3
    const val COAL = 4
}

class NodeDef(
    val name: String,
    val item: Int,
    /** Units the node yields before it is used up. */
    val hp: Int,
    /** Collision and targeting radius at scale 1, metres. */
    val radius: Float,
    /** Height of the targetable volume at scale 1, metres. */
    val height: Float,
    /** Tool class that works this node quickly. */
    val gatherClass: Int,
    /** True if bare hands cannot gather it at all. */
    val needsTool: Boolean,
)

val NODE_DEFS = arrayOf(
    NodeDef("", 0, 0, 0f, 0f, 0, false),
    NodeDef("Deadwood", Item.WOOD, 8, 0.38f, 3.4f, GATHER_WOOD, false),
    NodeDef("Stone Outcrop", Item.STONE, 7, 0.85f, 1.1f, GATHER_ROCK, false),
    NodeDef("Ore Vein", Item.IRON_ORE, 7, 0.9f, 1.2f, GATHER_ROCK, true),
    NodeDef("Coal Seam", Item.COAL, 7, 0.9f, 1.0f, GATHER_ROCK, true),
)

/** Side length of a spatial bucket, metres. */
const val BUCKET = 8
private const val BUCKETS_PER_SIDE = WORLD_SIZE / BUCKET

fun bucketIndex(x: Float, z: Float): Int {
    val bx = floor(x / BUCKET).toInt().coerceIn(0, BUCKETS_PER_SIDE - 1)
    val bz = floor(z / BUCKET).toInt().coerceIn(0, BUCKETS_PER_SIDE - 1)
    return bz * BUCKETS_PER_SIDE + bx
}

/** A ground patch a drill can extract from without ever running dry. */
class Deposit(val x: Float, val z: Float, val radius: Float, val item: Int)

private class Scatter(val kind: Int, val clusters: Int, val perCluster: Int, val spread: Float, val minFromSpawn: Float)

private val SCATTER = arrayOf(
    Scatter(NodeKind.DEADWOOD, 40, 3, 7f, 9f),
    Scatter(NodeKind.STONE, 30, 3, 5f, 10f),
    Scatter(NodeKind.ORE, 9, 4, 4f, 30f),
    Scatter(NodeKind.COAL, 8, 4, 4f, 26f),
)

/** All resource nodes, struct-of-arrays. A node with hp 0 is depleted. */
class Nodes(world: World, seed: Int) {
    val count: Int
    val kind: IntArray
    val x: FloatArray
    val y: FloatArray
    val z: FloatArray
    val scale: FloatArray
    val rot: FloatArray
    val hp: IntArray
    /** Node ids per spatial bucket, row-major. Built once; depleted nodes stay listed. */
    val buckets: Array<IntArray>
    val deposits = ArrayList<Deposit>()

    init {
        val rng = Rng(seed xor 0x5eed)
        val xs = ArrayList<Float>()
        val zs = ArrayList<Float>()
        val kinds = ArrayList<Int>()
        val half = WORLD_SIZE / 2f

        fun tooClose(px: Float, pz: Float, minD: Float): Boolean {
            for (i in xs.indices) if (hypot(xs[i] - px, zs[i] - pz) < minD) return true
            return false
        }

        // A few guaranteed nodes near spawn so the first minute never starts with a hike.
        val starters = arrayOf(
            intArrayOf(NodeKind.DEADWOOD, -10, -6), intArrayOf(NodeKind.DEADWOOD, -12, -3),
            intArrayOf(NodeKind.DEADWOOD, 9, -9), intArrayOf(NodeKind.STONE, 8, 7),
            intArrayOf(NodeKind.STONE, 11, 5), intArrayOf(NodeKind.STONE, -7, 10),
        )
        for (s in starters) {
            xs.add(half + s[1]); zs.add(half + s[2]); kinds.add(s[0])
        }

        // Two deposits within a short walk of spawn, each ringed by a few hand-minable
        // nodes of the same resource, so the first factory has somewhere to go.
        deposits.add(Deposit(half + 24.5f, half - 16.5f, 3.2f, Item.IRON_ORE))
        deposits.add(Deposit(half - 22.5f, half + 20.5f, 3.2f, Item.COAL))
        for (d in deposits.toList()) {
            val kind = if (d.item == Item.IRON_ORE) NodeKind.ORE else NodeKind.COAL
            for (k in 0 until 4) {
                val a = k / 4f * (Math.PI.toFloat() * 2f) + 0.4f
                xs.add(d.x + cos(a) * (d.radius + 2.2f)); zs.add(d.z + sin(a) * (d.radius + 2.2f)); kinds.add(kind)
            }
        }

        for (s in SCATTER) {
            for (c in 0 until s.clusters) {
                var cx = 0f
                var cz = 0f
                var ok = false
                var tries = 0
                while (tries < 30 && !ok) {
                    cx = rng.range(44f, WORLD_SIZE - 44f)
                    cz = rng.range(44f, WORLD_SIZE - 44f)
                    ok = hypot(cx - half, cz - half) >= s.minFromSpawn && world.slopeAt(cx, cz) < 0.5f
                    tries++
                }
                if (!ok) continue
                // Every other ore and coal cluster sits beside a deposit of its own.
                if ((s.kind == NodeKind.ORE || s.kind == NodeKind.COAL) && c % 2 == 0) {
                    deposits.add(Deposit(floor(cx) + 0.5f, floor(cz) + 0.5f, 2.6f, if (s.kind == NodeKind.ORE) Item.IRON_ORE else Item.COAL))
                }
                for (n in 0 until s.perCluster) {
                    for (t in 0 until 12) {
                        val a = rng.range(0f, Math.PI.toFloat() * 2f)
                        val r = rng.range(0.5f, s.spread)
                        val px = cx + cos(a) * r
                        val pz = cz + sin(a) * r
                        if (world.slopeAt(px, pz) > 0.55f) continue
                        if (hypot(px - half, pz - half) < 6f) continue
                        if (tooClose(px, pz, 2.4f)) continue
                        if (deposits.any { hypot(it.x - px, it.z - pz) < it.radius + 0.4f }) continue
                        xs.add(px); zs.add(pz); kinds.add(s.kind)
                        break
                    }
                }
            }
        }

        count = xs.size
        kind = IntArray(count) { kinds[it] }
        x = FloatArray(count) { xs[it] }
        z = FloatArray(count) { zs[it] }
        y = FloatArray(count) { world.heightAt(xs[it], zs[it]) }
        scale = FloatArray(count)
        rot = FloatArray(count)
        hp = IntArray(count)
        val lists = Array(BUCKETS_PER_SIDE * BUCKETS_PER_SIDE) { ArrayList<Int>() }
        for (i in 0 until count) {
            scale[i] = rng.range(0.85f, 1.25f)
            rot[i] = rng.range(0f, Math.PI.toFloat() * 2f)
            hp[i] = NODE_DEFS[kind[i]].hp
            lists[bucketIndex(x[i], z[i])].add(i)
        }
        buckets = Array(lists.size) { lists[it].toIntArray() }
    }
}
