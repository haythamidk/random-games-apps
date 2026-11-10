package com.worldwake.game.world

import com.worldwake.game.engine.Mat
import com.worldwake.game.engine.MeshBuilder
import com.worldwake.game.engine.MeshData
import com.worldwake.game.engine.PI_F
import com.worldwake.game.engine.Rng
import com.worldwake.game.engine.TWO_PI
import com.worldwake.game.engine.dome
import com.worldwake.game.game.Item
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything scattered over the terrain and drawn instanced. [range]: 0 = close decor, 1 = mid, 2 = far landmark.
 */
enum class PropType(
    val grow: Grow = Grow.NONE,
    val isTree: Boolean = false,
    val decorative: Boolean = false,
    val shadow: Boolean = true,
    val sway: Float = 0f,
    val translucency: Float = 0f,
    val collide: Float = 0f,
    val item: Item? = null,
    val range: Int = 1,
    val frostTop: Float = 0f,
    val glow: Float = 1f,
) {
    BOULDER(collide = 1.05f, range = 2, frostTop = 1f),
    ROCK_SMALL(shadow = false, frostTop = 1f),
    SPIRE(collide = 1.5f, range = 2, frostTop = 0.8f),
    COLUMNS(collide = 2.6f, range = 2, frostTop = 0.8f),
    SHARD_BIG(collide = 1.3f, range = 2, translucency = 0.5f),
    SHARD_SMALL(shadow = false, translucency = 0.5f),
    VENT(collide = 1.2f, range = 2),
    ICE_SPIKE(grow = Grow.FROST, shadow = false, translucency = 0.6f),

    ORE_FERRITE(item = Item.FERRITE, shadow = false),
    ORE_SILICA(item = Item.SILICA, shadow = false, translucency = 0.4f),
    ORE_ICE(item = Item.ICE, shadow = false, translucency = 0.6f),
    ORE_COBALT(item = Item.COBALT, shadow = false),
    ORE_MAGNESIUM(item = Item.MAGNESIUM, shadow = false),
    ORE_TITANIUM(item = Item.TITANIUM, shadow = false),
    ORE_ALUMINIUM(item = Item.ALUMINIUM, shadow = false),
    ORE_SULFUR(item = Item.SULFUR, shadow = false),
    ORE_LUMEN(item = Item.LUMEN, shadow = false, translucency = 0.5f),
    ORE_OSMIUM(item = Item.OSMIUM, shadow = false),
    ORE_PYRE(item = Item.PYRE, shadow = false),

    MOSS_TUFT(grow = Grow.MOSS, decorative = true, shadow = false, range = 0),
    GRASS(grow = Grow.GRASS, decorative = true, shadow = false, sway = 1f, translucency = 0.9f, range = 0),
    FLOWER_LANTERN(grow = Grow.FLOWER, decorative = true, shadow = false, sway = 0.8f, translucency = 0.5f, range = 0),
    FLOWER_STAR(grow = Grow.FLOWER, decorative = true, shadow = false, sway = 0.8f, translucency = 0.6f, range = 0),
    REED(grow = Grow.REED, decorative = true, shadow = false, sway = 0.9f, translucency = 0.7f, range = 0),
    GLOWCAP(grow = Grow.FUNGI, decorative = true, shadow = false, range = 0),
    BUSH(grow = Grow.BUSH, sway = 0.3f, translucency = 0.6f),
    TREE_EMBER(grow = Grow.TREE, isTree = true, sway = 0.35f, translucency = 0.75f, collide = 0.34f, range = 2),
    TREE_VEIL(grow = Grow.TREE, isTree = true, sway = 0.6f, translucency = 0.8f, collide = 0.4f, range = 2),
    TREE_SPINDLE(grow = Grow.TREE, isTree = true, sway = 0.25f, translucency = 0.7f, collide = 0.3f, range = 2),
    ;

    val isOre get() = item != null
}

object PropMeshes {
    fun build(t: PropType, lod: Int): MeshData {
        val b = MeshBuilder(512)
        val rng = Rng(4000L + t.ordinal * 71)
        when (t) {
            PropType.BOULDER -> {
                b.color(0.5f, 0.52f, 0.58f).mat(Mat.ROCK)
                b.sphere(0f, 0.55f, 0f, 1.25f, 0.85f, 1.05f, if (lod == 0) 7 else 5, if (lod == 0) 10 else 7, 0.22f, 7)
                if (lod == 0) b.sphere(0.9f, 0.25f, 0.5f, 0.55f, 0.4f, 0.5f, 5, 7, 0.25f, 9)
            }
            PropType.ROCK_SMALL -> {
                b.color(0.48f, 0.5f, 0.56f).mat(Mat.ROCK)
                b.sphere(0f, 0.12f, 0f, 0.36f, 0.22f, 0.3f, 4, 6, 0.3f, 3, flat = true)
                b.sphere(0.32f, 0.07f, 0.18f, 0.18f, 0.12f, 0.16f, 3, 5, 0.3f, 5, flat = true)
            }
            PropType.SPIRE -> spire(b, rng, lod)
            PropType.COLUMNS -> columns(b, rng, lod)
            PropType.SHARD_BIG -> {
                val n = if (lod == 0) 7 else 5
                for (i in 0 until n) {
                    val a = rng.range(0f, TWO_PI)
                    val lean = if (i == 0) 0.08f else rng.range(0.25f, 0.75f)
                    val len = if (i == 0) 4.6f else rng.range(1.6f, 3.4f)
                    val w = len * rng.range(0.13f, 0.18f)
                    val glow = i % 3 == 2
                    if (glow) b.color(0.35f, 0.95f, 1f, 0.55f) else b.color(rng.range(0.35f, 0.5f), rng.range(0.82f, 0.95f), 1f).mat(Mat.CRYSTAL)
                    crystal(b, cos(a) * 0.35f, -0.2f, sin(a) * 0.35f, w, len, a, lean)
                }
            }
            PropType.SHARD_SMALL -> {
                for (i in 0 until 3) {
                    val a = rng.range(0f, TWO_PI)
                    if (i == 1) b.color(0.4f, 0.95f, 1f, 0.5f) else b.color(0.42f, 0.88f, 1f).mat(Mat.CRYSTAL)
                    crystal(b, cos(a) * 0.1f, -0.05f, sin(a) * 0.1f, 0.07f, rng.range(0.35f, 0.7f), a, rng.range(0.1f, 0.6f))
                }
            }
            PropType.VENT -> {
                b.color(0.62f, 0.56f, 0.3f).mat(Mat.ROCK)
                val seg = if (lod == 0) 10 else 7
                b.cylinder(0f, -0.3f, 0f, 1.5f, 1.0f, 1.0f, seg, caps = false)
                b.cylinder(0f, 0.7f, 0f, 1.0f, 0.62f, 1.1f, seg, caps = false)
                b.cylinder(0f, 1.8f, 0f, 0.62f, 0.5f, 0.6f, seg, caps = false)
                b.color(0.95f, 0.85f, 0.25f)
                b.torusLip(0f, 2.4f, 0f, 0.5f, 0.1f, seg)
                b.color(1f, 0.55f, 0.2f, 1.4f)
                b.disc(0f, 2.25f, 0f, 0.44f, seg, true)
            }
            PropType.ICE_SPIKE -> {
                val n = if (lod == 0) 5 else 3
                for (i in 0 until n) {
                    val a = rng.range(0f, TWO_PI)
                    b.color(0.8f, 0.86f, 1f).mat(Mat.CRYSTAL)
                    val d = if (i == 0) 0f else rng.range(0.25f, 0.6f)
                    blade(b, cos(a) * d, sin(a) * d, rng.range(0.16f, 0.26f), if (i == 0) 2.1f else rng.range(0.7f, 1.5f), 0.9f + rng.range(-0.25f, 0.25f), 0.22f)
                }
            }
            PropType.ORE_FERRITE -> nuggets(b, rng, 0.3f, 0.26f, 0.3f, 0.19f, 4, Mat.METAL, flat = true)
            PropType.ORE_COBALT -> nuggets(b, rng, 0.16f, 0.34f, 0.95f, 0.17f, 4, Mat.METAL, flat = true)
            PropType.ORE_MAGNESIUM -> nuggets(b, rng, 0.8f, 0.86f, 0.98f, 0.16f, 5, Mat.METAL, flat = true)
            PropType.ORE_OSMIUM -> nuggets(b, rng, 0.12f, 0.42f, 0.46f, 0.15f, 4, Mat.METAL, flat = false)
            PropType.ORE_ALUMINIUM -> nuggets(b, rng, 0.86f, 0.74f, 0.8f, 0.2f, 3, Mat.GRAVEL, flat = false)
            PropType.ORE_TITANIUM -> {
                for (i in 0 until 4) {
                    b.color(0.62f, 0.64f, 0.7f).mat(Mat.METAL)
                    b.push().trs(rng.range(-0.2f, 0.2f), 0.04f + i * 0.045f, rng.range(-0.2f, 0.2f), rng.range(0f, 6f), rng.range(-0.15f, 0.15f), rng.range(-0.15f, 0.15f))
                    b.cylinder(0f, 0f, 0f, 0.22f - i * 0.02f, 0.2f - i * 0.02f, 0.045f, 6, flat = true)
                    b.pop()
                }
            }
            PropType.ORE_SILICA -> {
                for (i in 0 until 5) {
                    val a = rng.range(0f, TWO_PI)
                    b.color(0.9f, 0.9f, 0.86f).mat(Mat.CRYSTAL)
                    crystal(b, cos(a) * 0.12f, 0f, sin(a) * 0.12f, 0.06f, rng.range(0.2f, 0.42f), a, rng.range(0.2f, 0.9f))
                }
            }
            PropType.ORE_ICE -> {
                for (i in 0 until 3) {
                    b.color(0.7f, 0.9f, 1f).mat(Mat.CRYSTAL)
                    b.push().trs(rng.range(-0.18f, 0.18f), 0.12f, rng.range(-0.18f, 0.18f), rng.range(0f, 6f), rng.range(-0.4f, 0.4f), rng.range(-0.4f, 0.4f))
                    b.box(0f, 0f, 0f, rng.range(0.16f, 0.28f), rng.range(0.16f, 0.26f), rng.range(0.16f, 0.28f))
                    b.pop()
                }
            }
            PropType.ORE_SULFUR -> {
                for (i in 0 until 6) {
                    val a = rng.range(0f, TWO_PI)
                    b.color(0.98f, 0.86f, 0.18f).mat(Mat.CRYSTAL)
                    crystal(b, cos(a) * 0.13f, 0f, sin(a) * 0.13f, 0.075f, rng.range(0.14f, 0.3f), a, rng.range(0f, 0.7f))
                }
            }
            PropType.ORE_LUMEN -> {
                b.color(0.3f, 0.34f, 0.4f).mat(Mat.ROCK)
                b.sphere(0f, 0.06f, 0f, 0.24f, 0.12f, 0.22f, 3, 6, 0.25f, 5, flat = true)
                for (i in 0 until 4) {
                    val a = rng.range(0f, TWO_PI)
                    b.color(0.3f, 1f, 0.95f, 1.2f)
                    crystal(b, cos(a) * 0.08f, 0.05f, sin(a) * 0.08f, 0.05f, rng.range(0.2f, 0.4f), a, rng.range(0f, 0.5f))
                }
            }
            PropType.ORE_PYRE -> {
                b.color(0.2f, 0.17f, 0.2f).mat(Mat.ROCK)
                b.sphere(0f, 0.14f, 0f, 0.27f, 0.2f, 0.24f, 4, 7, 0.28f, 11, flat = true)
                b.color(1f, 0.42f, 0.5f, 1.5f)
                for (i in 0 until 5) {
                    val a = rng.range(0f, TWO_PI); val e = rng.range(0.2f, 1.2f)
                    b.sphere(cos(a) * sin(e) * 0.21f, 0.14f + cos(e) * 0.15f, sin(a) * sin(e) * 0.19f, 0.05f, 0.05f, 0.05f, 3, 5)
                }
            }
            PropType.MOSS_TUFT -> {
                b.color(0.62f, 0.14f, 0.3f).mat(Mat.MOSS)
                b.sphere(0f, 0.03f, 0f, 0.5f, 0.16f, 0.42f, 4, 7, 0.25f, 3)
                if (lod == 0) { b.color(0.8f, 0.3f, 0.42f).mat(Mat.MOSS); b.sphere(0.35f, 0.02f, 0.25f, 0.26f, 0.12f, 0.24f, 3, 6, 0.25f, 5) }
            }
            PropType.GRASS -> {
                val n = if (lod == 0) 18 else 9
                for (i in 0 until n) {
                    val a = rng.range(0f, TWO_PI); val d = rng.range(0f, 0.62f)
                    val tone = rng.nextFloat()
                    b.color(0.9f - tone * 0.08f, 0.72f - tone * 0.2f, 0.48f + tone * 0.06f).mat(Mat.GRASS_BLADE)
                    b.blade(cos(a) * d, sin(a) * d, rng.range(0.035f, 0.06f), rng.range(0.28f, 0.62f), rng.range(0f, PI_F), rng.range(-0.22f, 0.22f))
                }
            }
            PropType.FLOWER_LANTERN -> {
                for (i in 0 until 3) {
                    val x = rng.range(-0.22f, 0.22f); val z = rng.range(-0.22f, 0.22f); val h = rng.range(0.45f, 0.75f)
                    b.color(0.5f, 0.2f, 0.3f)
                    b.cylinder(x, 0f, z, 0.012f, 0.01f, h, 4, caps = false)
                    b.color(0.35f, 0.75f, 1f, 1.1f)
                    b.sphere(x + 0.05f, h - 0.03f, z, 0.055f, 0.075f, 0.055f, 4, 6)
                    b.color(0.9f, 0.95f, 1f, 1.6f)
                    b.sphere(x + 0.05f, h - 0.09f, z, 0.02f, 0.02f, 0.02f, 3, 4)
                }
            }
            PropType.FLOWER_STAR -> {
                for (i in 0 until 4) {
                    val x = rng.range(-0.25f, 0.25f); val z = rng.range(-0.25f, 0.25f); val h = rng.range(0.25f, 0.5f)
                    b.color(0.62f, 0.3f, 0.3f)
                    b.cylinder(x, 0f, z, 0.01f, 0.008f, h, 4, caps = false)
                    for (k in 0 until 5) {
                        val a = k * TWO_PI / 5f
                        b.color(1f, 0.93f, 0.88f, 0.18f)
                        b.push().trs(x, h, z, a, 0f, 0.35f)
                        b.flatQuad(0f, 0f, -0.025f, 0.1f, 0.01f, -0.012f, 0.1f, 0.01f, 0.012f, 0f, 0f, 0.025f)
                        b.flatQuad(0f, 0f, 0.025f, 0.1f, 0.01f, 0.012f, 0.1f, 0.01f, -0.012f, 0f, 0f, -0.025f)
                        b.pop()
                    }
                    b.color(1f, 0.75f, 0.2f, 0.7f)
                    b.sphere(x, h + 0.01f, z, 0.022f, 0.016f, 0.022f, 3, 5)
                }
            }
            PropType.REED -> {
                for (i in 0 until 6) {
                    val x = rng.range(-0.3f, 0.3f); val z = rng.range(-0.3f, 0.3f); val h = rng.range(1.0f, 1.7f)
                    b.color(0.4f, 0.5f, 0.55f).mat(Mat.GRASS_BLADE)
                    b.blade(x, z, 0.05f, h, rng.range(0f, PI_F), rng.range(-0.2f, 0.2f))
                    if (i % 2 == 0) {
                        b.color(0.95f, 0.55f, 0.65f)
                        b.sphere(x, h * 0.93f, z, 0.035f, 0.14f, 0.035f, 4, 5)
                    }
                }
            }
            PropType.GLOWCAP -> {
                for (i in 0 until 3) {
                    val x = rng.range(-0.25f, 0.25f); val z = rng.range(-0.25f, 0.25f); val h = rng.range(0.18f, 0.42f)
                    val r = h * rng.range(0.45f, 0.6f)
                    b.color(0.85f, 0.82f, 0.9f)
                    b.cylinder(x, 0f, z, 0.035f, 0.025f, h, 6, caps = false)
                    b.color(0.2f, 0.6f, 0.62f)
                    b.dome(x, h - 0.02f, z, r, r * 0.6f, 3, 8)
                    b.color(0.4f, 1f, 0.9f, 1.3f)
                    b.disc(x, h - 0.02f, z, r * 0.92f, 8, false)
                }
            }
            PropType.BUSH -> {
                val n = if (lod == 0) 4 else 3
                b.color(0.55f, 0.5f, 0.52f).mat(Mat.BARK)
                b.cylinder(0f, 0f, 0f, 0.07f, 0.04f, 0.5f, 5, caps = false)
                b.color(0.5f, 0.16f, 0.18f)
                b.sphere(0f, 0.55f, 0f, 0.62f, 0.42f, 0.62f, 5, 8)
                for (i in 0 until n) {
                    val a = i * TWO_PI / n + rng.range(0f, 0.6f)
                    b.color(rng.range(0.9f, 1f), rng.range(0.38f, 0.5f), rng.range(0.36f, 0.46f)).mat(Mat.LEAVES)
                    b.sphere(cos(a) * 0.35f, 0.55f + rng.range(-0.1f, 0.15f), sin(a) * 0.35f, 0.52f, 0.42f, 0.5f, 5, 7, 0.2f, i + 3)
                }
                if (lod == 0) {
                    b.color(0.35f, 0.9f, 1f, 1f)
                    for (i in 0 until 7) {
                        val a = rng.range(0f, TWO_PI); val e = rng.range(0.3f, 1.3f)
                        b.sphere(cos(a) * sin(e) * 0.8f, 0.6f + cos(e) * 0.45f, sin(a) * sin(e) * 0.8f, 0.035f, 0.035f, 0.035f, 3, 4)
                    }
                }
            }
            PropType.TREE_EMBER -> emberTree(b, rng, lod)
            PropType.TREE_VEIL -> veilTree(b, rng, lod)
            PropType.TREE_SPINDLE -> spindleTree(b, rng, lod)
        }
        return b.build()
    }

    /** A hexagonal crystal: prism with a pointed tip, leaning away from vertical. */
    private fun crystal(b: MeshBuilder, x: Float, y: Float, z: Float, w: Float, len: Float, yaw: Float, lean: Float) {
        b.push().trs(x, y, z, yaw, lean, 0f)
        b.cylinder(0f, 0f, 0f, w, w * 0.92f, len * 0.78f, 6, caps = false, flat = true)
        b.cylinder(0f, len * 0.78f, 0f, w * 0.92f, 0f, len * 0.22f, 6, caps = false, flat = true)
        b.pop()
    }

    /** A thin ice blade leaning into the prevailing wind. */
    private fun blade(b: MeshBuilder, x: Float, z: Float, w: Float, h: Float, yaw: Float, lean: Float) {
        b.push().trs(x, -0.05f, z, yaw, lean, 0f)
        b.cylinder(0f, 0f, 0f, w, w * 0.45f, h * 0.7f, 4, caps = false, flat = true)
        b.cylinder(0f, h * 0.7f, 0f, w * 0.45f, 0f, h * 0.3f, 4, caps = false, flat = true)
        b.pop()
    }

    private fun nuggets(b: MeshBuilder, rng: Rng, r: Float, g: Float, bl: Float, size: Float, n: Int, mat: Int, flat: Boolean) {
        for (i in 0 until n) {
            val a = i * TWO_PI / n + rng.range(0f, 0.8f)
            val d = if (i == 0) 0f else rng.range(0.14f, 0.24f)
            val s = size * (if (i == 0) 1f else rng.range(0.5f, 0.8f))
            val sh = rng.range(0.85f, 1.15f)
            b.color(r * sh, g * sh, bl * sh).mat(mat)
            b.sphere(cos(a) * d, s * 0.6f, sin(a) * d, s, s * 0.75f, s * 0.9f, 4, 6, 0.3f, i * 3 + 1, flat)
        }
    }

    private fun spire(b: MeshBuilder, rng: Rng, lod: Int) {
        // a leaning stack of weathered slate, like a stone finger
        val seg = if (lod == 0) 8 else 6
        var y = -1.2f
        var r = 1.7f
        var ox = 0f; var oz = 0f
        val parts = 6
        for (i in 0 until parts) {
            val h = rng.range(1.6f, 2.6f)
            val r2 = r * rng.range(0.72f, 0.92f)
            val sh = rng.range(0.85f, 1.1f)
            b.color(0.5f * sh, 0.52f * sh, 0.6f * sh).mat(Mat.ROCK)
            b.push().trs(ox, y, oz, rng.range(0f, 3f), 0f, 0f)
            b.cylinder(0f, 0f, 0f, r, r2, h, seg, caps = i == parts - 1, flat = true)
            // overhanging cap ledge between layers gives the stacked look
            if (i < parts - 1 && lod == 0) b.cylinder(0f, h - 0.12f, 0f, r2 * 1.12f, r2 * 1.05f, 0.2f, seg, caps = true, flat = true)
            b.pop()
            y += h
            r = r2 * rng.range(0.95f, 1.1f)
            ox += rng.range(-0.22f, 0.3f); oz += rng.range(-0.22f, 0.22f)
        }
        b.color(0.46f, 0.48f, 0.56f).mat(Mat.ROCK)
        b.sphere(1.7f, 0.2f, 0.6f, 0.9f, 0.6f, 0.8f, 5, 7, 0.25f, 4)
    }

    private fun columns(b: MeshBuilder, rng: Rng, lod: Int) {
        // a cluster of hexagonal basalt columns
        val n = if (lod == 0) 13 else 8
        for (i in 0 until n) {
            val a = rng.range(0f, TWO_PI)
            val d = if (i == 0) 0f else rng.range(0.6f, 2.6f)
            val h = (12.5f - d * 3.2f) * rng.range(0.6f, 1.1f) + 1f
            val r = rng.range(0.5f, 0.8f)
            val sh = rng.range(0.78f, 1.08f)
            b.color(0.46f * sh, 0.5f * sh, 0.58f * sh).mat(Mat.ROCK)
            b.push().trs(cos(a) * d, -1f, sin(a) * d, rng.range(0f, 1f), rng.range(-0.05f, 0.05f), rng.range(-0.05f, 0.05f))
            b.cylinder(0f, 0f, 0f, r, r * 0.96f, h, 6, caps = true, flat = true)
            b.pop()
        }
    }

    private fun trunk(b: MeshBuilder, rng: Rng, h: Float, r0: Float, r1: Float, bend: Float, seg: Int, out: FloatArray) {
        val parts = 4
        var x = 0f; var z = 0f; var y = -0.2f
        val dir = rng.range(0f, TWO_PI)
        for (i in 0 until parts) {
            val t0 = i.toFloat() / parts; val t1 = (i + 1f) / parts
            val ra = r0 + (r1 - r0) * t0; val rb = r0 + (r1 - r0) * t1
            val nx = cos(dir) * bend * t1 * t1; val nz = sin(dir) * bend * t1 * t1
            val hh = (h + 0.2f) / parts
            b.push().trs(x, y, z, 0f, 0f, 0f)
            // sheared segment: build as a cylinder, then offset the top by leaning
            val lean = kotlin.math.atan2(nx - x, hh); val lean2 = kotlin.math.atan2(nz - z, hh)
            b.trs(0f, 0f, 0f, 0f, lean2, -lean)
            b.cylinder(0f, 0f, 0f, ra, rb, hh * 1.03f, seg, caps = false)
            b.pop()
            x = nx; z = nz; y += hh
        }
        out[0] = x; out[1] = y; out[2] = z
    }

    private fun emberTree(b: MeshBuilder, rng: Rng, lod: Int) {
        val top = FloatArray(3)
        b.color(0.9f, 0.88f, 0.86f).mat(Mat.BARK)
        trunk(b, rng, 4.4f, 0.3f, 0.13f, 0.7f, if (lod == 0) 8 else 5, top)
        // root flare
        b.cylinder(0f, -0.25f, 0f, 0.5f, 0.3f, 0.5f, if (lod == 0) 8 else 5, caps = false)
        // branches
        val nb = if (lod == 0) 4 else 2
        for (i in 0 until nb) {
            val a = i * TWO_PI / nb + rng.range(0f, 1f)
            b.color(0.88f, 0.86f, 0.84f).mat(Mat.BARK)
            b.push().trs(top[0] * 0.7f, top[1] - 1.3f, top[2] * 0.7f, a, 0.95f, 0f)
            b.cylinder(0f, 0f, 0f, 0.055f, 0.02f, 1.5f, 5, caps = false)
            b.pop()
        }
        val n = if (lod == 0) 8 else 5
        for (i in 0 until n) {
            val a = i * TWO_PI / (n - 1) + rng.range(0f, 0.5f)
            val d = if (i == 0) 0f else rng.range(1.2f, 1.9f)
            val y = top[1] + (if (i == 0) 0.9f else rng.range(-0.5f, 0.55f))
            val s = if (i == 0) 1.75f else rng.range(1.15f, 1.55f)
            val tone = rng.nextFloat()
            val bx = top[0] + cos(a) * d; val bz = top[2] + sin(a) * d
            // a dark heart inside each clump, so gaps in the leaves show depth instead of sky
            b.color(0.42f, 0.05f, 0.1f)
            b.sphere(bx, y, bz, s * 0.8f, s * 0.6f, s * 0.8f, 4, if (lod == 0) 7 else 5)
            b.color(0.78f + tone * 0.2f, 0.12f + tone * 0.22f, 0.2f + tone * 0.08f).mat(Mat.LEAVES)
            b.sphere(bx, y, bz, s, s * 0.78f, s, if (lod == 0) 6 else 4, if (lod == 0) 9 else 6, 0.14f, i + 20)
        }
    }

    private fun veilTree(b: MeshBuilder, rng: Rng, lod: Int) {
        val top = FloatArray(3)
        b.color(0.8f, 0.78f, 0.82f).mat(Mat.BARK)
        trunk(b, rng, 3.6f, 0.36f, 0.16f, 0.5f, if (lod == 0) 8 else 5, top)
        b.cylinder(0f, -0.25f, 0f, 0.6f, 0.36f, 0.5f, if (lod == 0) 8 else 5, caps = false)
        // umbrella crown
        b.color(0.5f, 0.12f, 0.3f)
        b.dome(top[0], top[1] - 0.45f, top[2], 2.2f, 1.1f, 3, if (lod == 0) 10 else 7)
        b.color(0.92f, 0.36f, 0.6f).mat(Mat.LEAVES)
        b.dome(top[0], top[1] - 0.5f, top[2], 2.5f, 1.3f, if (lod == 0) 5 else 3, if (lod == 0) 12 else 8)
        // hanging veils
        val n = if (lod == 0) 16 else 9
        for (i in 0 until n) {
            val a = i * TWO_PI / n + rng.range(-0.1f, 0.1f)
            val d = rng.range(1.5f, 2.45f)
            val len = rng.range(1.6f, 3.1f)
            val w = rng.range(0.35f, 0.55f)
            val tone = rng.nextFloat()
            b.color(0.95f - tone * 0.15f, 0.3f + tone * 0.2f, 0.62f + tone * 0.12f).mat(Mat.FROND)
            val x = top[0] + cos(a) * d; val z = top[2] + sin(a) * d
            val y1 = top[1] - 0.35f + (2.5f - d) * 0.35f
            val tx = -sin(a) * w; val tz = cos(a) * w
            val nx = cos(a); val nz = sin(a)
            val v0 = b.vertex(x - tx, y1, z - tz, nx, 0.3f, nz)
            val v1 = b.vertex(x + tx, y1, z + tz, nx, 0.3f, nz)
            val v2 = b.vertex(x + tx * 0.8f + nx * 0.15f, y1 - len, z + tz * 0.8f + nz * 0.15f, nx, 0.3f, nz)
            val v3 = b.vertex(x - tx * 0.8f + nx * 0.15f, y1 - len, z - tz * 0.8f + nz * 0.15f, nx, 0.3f, nz)
            b.quad(v0, v1, v2, v3)
        }
    }

    private fun spindleTree(b: MeshBuilder, rng: Rng, lod: Int) {
        val top = FloatArray(3)
        b.color(0.74f, 0.7f, 0.74f).mat(Mat.BARK)
        trunk(b, rng, 9f, 0.28f, 0.07f, 0.4f, if (lod == 0) 7 else 5, top)
        // stacked parasol tiers, wider near the bottom
        val tiers = if (lod == 0) 5 else 4
        for (i in 0 until tiers) {
            val t = i.toFloat() / (tiers - 1)
            val y = 3.4f + t * 5.6f
            val r = 2.1f - t * 1.45f
            val tone = rng.nextFloat()
            b.color(0.98f - tone * 0.08f, 0.6f + tone * 0.12f, 0.42f + tone * 0.1f).mat(Mat.LEAVES)
            val ox = top[0] * (y / 9f) * (y / 9f); val oz = top[2] * (y / 9f) * (y / 9f)
            b.dome(ox, y - 0.15f, oz, r, r * 0.42f, if (lod == 0) 4 else 3, if (lod == 0) 11 else 7)
            b.color(0.55f, 0.28f, 0.2f)
            b.dome(ox, y - 0.13f, oz, r * 0.86f, r * 0.33f, 3, if (lod == 0) 9 else 6)
            if (lod == 0) {
                b.color(1f, 0.85f, 0.6f, 0.5f)
                for (k in 0 until 4) {
                    val a = k * TWO_PI / 4f + i
                    b.sphere(ox + cos(a) * r * 0.85f, y - 0.2f, oz + sin(a) * r * 0.85f, 0.06f, 0.1f, 0.06f, 3, 4)
                }
            }
        }
    }
}

/** A raised ring (used for vent lips and machine collars). */
fun MeshBuilder.torusLip(cx: Float, cy: Float, cz: Float, R: Float, r: Float, seg: Int): MeshBuilder {
    cylinder(cx, cy - r, cz, R + r, R + r, r * 2f, seg, caps = false)
    cylinder(cx, cy + r, cz, R + r, R - r, 0.001f, seg, caps = false)
    return this
}
