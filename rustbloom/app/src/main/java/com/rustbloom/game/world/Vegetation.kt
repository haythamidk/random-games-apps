package com.rustbloom.game.world

import com.rustbloom.game.engine.Mat
import com.rustbloom.game.engine.MeshBuilder
import com.rustbloom.game.engine.MeshData
import com.rustbloom.game.engine.Rng
import com.rustbloom.game.engine.TWO_PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Vegetation and resource node types. [collide] is the trunk/rock collision radius at scale 1 (0 = walk through).
 * [hits] is how many tool hits it takes to fully harvest (0 = decorative).
 */
enum class VegType(
    val displayName: String,
    val collide: Float,
    val hits: Int,
    val shadow: Boolean,
    val translucency: Float,
    val sway: Float,
    val decorative: Boolean = false,
    val respawnDays: Float = 3f,
    val pickup: Boolean = false,
) {
    OAK("Broadleaf Tree", 0.45f, 6, true, 0.6f, 0.35f, respawnDays = 4f),
    PINE("Needle Pine", 0.35f, 5, true, 0.5f, 0.25f, respawnDays = 4f),
    DEAD_TREE("Dead Tree", 0.3f, 3, true, 0f, 0.05f, respawnDays = 5f),
    SWAMP_TREE("Fen Willow", 0.5f, 6, true, 0.6f, 0.3f, respawnDays = 4f),
    BERRY_BUSH("Berry Bush", 0f, 1, false, 0.7f, 0.6f, respawnDays = 1.5f, pickup = true),
    FIBER_PLANT("Fiber Reeds", 0f, 1, false, 0.8f, 1.0f, respawnDays = 1f, pickup = true),
    ROCK_SMALL("Loose Stones", 0f, 1, false, 0f, 0f, respawnDays = 2f, pickup = true),
    BOULDER("Boulder", 1.1f, 6, true, 0f, 0f, respawnDays = 6f),
    SCRAP_HEAP("Scrap Heap", 0.9f, 4, true, 0f, 0f, respawnDays = 3f),
    THORN_BUSH("Rustthorn", 0.3f, 2, false, 0.3f, 0.2f, respawnDays = 2f),
    HERB("Bloodmoss Herb", 0f, 1, false, 0.8f, 0.8f, respawnDays = 2f, pickup = true),
    MUSHROOM("Fen Caps", 0f, 1, false, 0.5f, 0f, respawnDays = 1.5f, pickup = true),
    CRYSTAL("Cryo Crystal", 0.5f, 4, true, 0f, 0f, respawnDays = 5f),
    GRASS("Grass", 0f, 0, false, 0.9f, 1.4f, decorative = true),
    FLOWERS("Wildflowers", 0f, 0, false, 0.9f, 1.2f, decorative = true),
    FERN("Fern", 0f, 0, false, 0.8f, 0.9f, decorative = true),
    SHRUB("Shrub", 0f, 0, false, 0.7f, 0.5f, decorative = true);

    val isTree get() = this == OAK || this == PINE || this == DEAD_TREE || this == SWAMP_TREE
}

/** Builds the procedural meshes for each vegetation type, high and low detail. */
object VegetationMeshes {

    fun build(type: VegType, lod: Int): MeshData {
        val mb = MeshBuilder(1024)
        val hi = lod == 0
        when (type) {
            VegType.OAK -> oak(mb, hi)
            VegType.PINE -> pine(mb, hi)
            VegType.DEAD_TREE -> deadTree(mb, hi)
            VegType.SWAMP_TREE -> swampTree(mb, hi)
            VegType.BERRY_BUSH -> berryBush(mb, hi)
            VegType.FIBER_PLANT -> reeds(mb, hi)
            VegType.ROCK_SMALL -> smallRocks(mb)
            VegType.BOULDER -> boulder(mb, hi)
            VegType.SCRAP_HEAP -> scrapHeap(mb)
            VegType.THORN_BUSH -> thorn(mb, hi)
            VegType.HERB -> herb(mb)
            VegType.MUSHROOM -> mushrooms(mb)
            VegType.CRYSTAL -> crystal(mb)
            VegType.GRASS -> grass(mb, hi)
            VegType.FLOWERS -> flowers(mb)
            VegType.FERN -> fern(mb, hi)
            VegType.SHRUB -> shrub(mb, hi)
        }
        return mb.build()
    }

    private fun bark(mb: MeshBuilder, c: Int) = mb.color(c).mat(Mat.BARK)
    private fun leaves(mb: MeshBuilder, r: Float, g: Float, b: Float) = mb.color(r, g, b).mat(Mat.LEAVES)

    private fun branch(mb: MeshBuilder, y: Float, yaw: Float, tilt: Float, len: Float, r: Float, seg: Int) {
        mb.push().trs(0f, y, 0f, yaw, 0f, tilt).cylinder(0f, 0f, 0f, r, r * 0.45f, len, seg, caps = false).pop()
    }

    private fun oak(mb: MeshBuilder, hi: Boolean) {
        bark(mb, 0x6b5340)
        val seg = if (hi) 9 else 6
        // root flare and trunk
        mb.cylinder(0f, -0.4f, 0f, 0.62f, 0.4f, 0.9f, seg, caps = false)
        mb.cylinder(0f, 0.45f, 0f, 0.4f, 0.27f, 3.4f, seg, caps = false)
        val rng = Rng(77)
        if (hi) for (i in 0 until 5) branch(mb, 3.0f + i * 0.3f, i * 1.3f + rng.range(0f, 0.6f), rng.range(0.6f, 0.85f), rng.range(1.0f, 1.4f), 0.13f, 6)
        val blobs = if (hi) 11 else 5
        for (i in 0 until blobs) {
            val a = i * TWO_PI / blobs * 2.3f + rng.range(-0.4f, 0.4f)
            val d = if (i == 0) 0f else rng.range(0.9f, 2.2f)
            val shade = rng.range(0.82f, 1.12f)
            leaves(mb, 0.22f * shade, 0.33f * shade, 0.12f * shade)
            val r = rng.range(1.25f, 1.9f)
            mb.sphere(cos(a) * d, rng.range(4.2f, 6.2f), sin(a) * d, r, r * 0.82f, r, if (hi) 7 else 4, if (hi) 10 else 6, 0.12f, i * 13)
        }
        // inner leaf layer keeps the crown from looking hollow
        leaves(mb, 0.15f, 0.23f, 0.08f)
        if (hi) mb.sphere(0f, 5.1f, 0f, 1.5f, 1.2f, 1.5f, 4, 7, 0.1f, 5)
    }

    private fun pine(mb: MeshBuilder, hi: Boolean) {
        bark(mb, 0x5a4232)
        mb.cylinder(0f, -0.3f, 0f, 0.34f, 0.08f, 9f, if (hi) 8 else 5, caps = false)
        val layers = if (hi) 8 else 4
        for (i in 0 until layers) {
            val t = i.toFloat() / layers
            val shade = 0.8f + t * 0.3f
            mb.color(0.16f * shade, 0.3f * shade, 0.18f * shade).mat(Mat.PINE)
            val r = 2.7f * (1f - t * 0.82f) + 0.2f
            val y = 1.5f + t * 7.4f
            mb.push().trs(0f, y, 0f, i * 0.9f)
            // drooping skirt: outer ring lower than the trunk attachment
            mb.cylinder(0f, -0.5f, 0f, r, 0.08f, 2.0f - t * 0.6f, if (hi) 12 else 7, caps = false)
            mb.pop()
        }
    }

    private fun deadTree(mb: MeshBuilder, hi: Boolean) {
        bark(mb, 0x7a7068)
        mb.cylinder(0f, -0.3f, 0f, 0.34f, 0.12f, 5.4f, if (hi) 7 else 5, caps = false)
        branch(mb, 2.6f, 0.5f, 0.8f, 2.1f, 0.13f, 5)
        branch(mb, 3.4f, 2.8f, -0.7f, 1.8f, 0.11f, 5)
        if (hi) { branch(mb, 4.3f, 4.4f, 0.6f, 1.3f, 0.08f, 4); branch(mb, 1.8f, 1.6f, 1.1f, 1.1f, 0.09f, 4) }
    }

    private fun swampTree(mb: MeshBuilder, hi: Boolean) {
        bark(mb, 0x4f4636)
        mb.cylinder(0f, -0.3f, 0f, 1.0f, 0.45f, 1.3f, if (hi) 9 else 6, caps = false)
        mb.cylinder(0f, 0.9f, 0f, 0.45f, 0.3f, 3.6f, if (hi) 8 else 5, caps = false)
        val rng = Rng(5)
        val n = if (hi) 9 else 4
        for (i in 0 until n) {
            val a = i * TWO_PI / n
            val shade = rng.range(0.85f, 1.1f)
            leaves(mb, 0.3f * shade, 0.38f * shade, 0.16f * shade)
            mb.sphere(cos(a) * 1.7f, 4.4f, sin(a) * 1.7f, 1.4f, 2.1f, 1.4f, if (hi) 6 else 3, if (hi) 8 else 5, 0.15f, i)
        }
        leaves(mb, 0.32f, 0.4f, 0.17f)
        mb.sphere(0f, 5.6f, 0f, 1.9f, 1.3f, 1.9f, if (hi) 5 else 3, if (hi) 8 else 5, 0.12f, 42)
    }

    private fun berryBush(mb: MeshBuilder, hi: Boolean) {
        leaves(mb, 0.24f, 0.38f, 0.16f)
        mb.sphere(0f, 0.55f, 0f, 0.95f, 0.65f, 0.95f, if (hi) 6 else 4, if (hi) 9 else 6, 0.15f, 3)
        mb.sphere(0.5f, 0.42f, 0.3f, 0.6f, 0.45f, 0.6f, 4, 7, 0.15f, 4)
        mb.color(0.1f, 0.18f, 0.06f)
        mb.sphere(0f, 0.5f, 0f, 0.7f, 0.45f, 0.7f, 3, 6, 0.1f, 6)
        if (hi) {
            mb.color(0.78f, 0.06f, 0.15f, 0.04f)
            val rng = Rng(9)
            for (i in 0 until 14) {
                val a = rng.range(0f, TWO_PI)
                val y = rng.range(0.35f, 1.0f)
                mb.sphere(cos(a) * 0.85f, y, sin(a) * 0.85f, 0.07f, 0.07f, 0.07f, 2, 4, 0f, i)
            }
        }
    }

    private fun reeds(mb: MeshBuilder, hi: Boolean) {
        val rng = Rng(21)
        val n = if (hi) 22 else 9
        for (i in 0 until n) {
            val shade = rng.range(0.8f, 1.15f)
            mb.color(0.5f * shade, 0.55f * shade, 0.25f * shade).mat(Mat.GRASS_BLADE)
            val a = rng.range(0f, TWO_PI)
            val d = rng.range(0f, 0.5f)
            mb.blade(cos(a) * d, sin(a) * d, 0.07f, rng.range(1.0f, 1.8f), rng.range(0f, TWO_PI), rng.range(0.1f, 0.4f))
        }
        if (hi) {
            mb.color(0.45f, 0.3f, 0.16f)
            for (i in 0 until 4) {
                val a = rng.range(0f, TWO_PI)
                mb.cylinder(cos(a) * 0.2f, 1.4f + i * 0.1f, sin(a) * 0.2f, 0.035f, 0.03f, 0.28f, 5)
            }
        }
    }

    private fun smallRocks(mb: MeshBuilder) {
        mb.color(0.62f, 0.6f, 0.57f).mat(Mat.ROCK)
        mb.sphere(0f, 0.1f, 0f, 0.35f, 0.24f, 0.3f, 4, 6, 0.25f, 1)
        mb.sphere(0.4f, 0.06f, 0.2f, 0.22f, 0.15f, 0.2f, 3, 6, 0.25f, 2)
        mb.sphere(-0.3f, 0.05f, 0.35f, 0.18f, 0.12f, 0.2f, 3, 5, 0.25f, 3)
    }

    private fun boulder(mb: MeshBuilder, hi: Boolean) {
        mb.color(0.6f, 0.58f, 0.55f).mat(Mat.ROCK)
        mb.sphere(0f, 0.45f, 0f, 1.5f, 1.15f, 1.3f, if (hi) 8 else 4, if (hi) 11 else 6, 0.18f, 7)
        if (hi) {
            mb.sphere(1.0f, 0.2f, 0.6f, 0.7f, 0.55f, 0.6f, 4, 7, 0.2f, 8)
            mb.color(0.28f, 0.38f, 0.16f).mat(Mat.GRASS)
            mb.sphere(0.2f, 1.42f, 0.1f, 0.95f, 0.16f, 0.8f, 3, 8, 0.15f, 9)
        }
    }

    private fun scrapHeap(mb: MeshBuilder) {
        val rng = Rng(31)
        for (i in 0 until 9) {
            val shade = rng.range(0.75f, 1.1f)
            if (i % 3 == 0) mb.color(0.55f * shade, 0.32f * shade, 0.18f * shade).mat(Mat.RUST) else mb.color(0.48f * shade, 0.48f * shade, 0.5f * shade).mat(Mat.METAL)
            mb.push().trs(rng.range(-0.8f, 0.8f), rng.range(0.1f, 0.6f), rng.range(-0.8f, 0.8f), rng.range(0f, 3f), rng.range(-0.5f, 0.5f), rng.range(-0.5f, 0.5f))
            if (i % 4 == 1) mb.cylinder(0f, -0.4f, 0f, 0.18f, 0.18f, 0.9f, 8) else mb.box(0f, 0f, 0f, rng.range(0.4f, 1.2f), rng.range(0.1f, 0.4f), rng.range(0.3f, 0.9f))
            mb.pop()
        }
        mb.color(0.9f, 0.55f, 0.2f, 0.6f)
        mb.box(0.1f, 0.75f, 0f, 0.1f, 0.1f, 0.1f)
    }

    private fun thorn(mb: MeshBuilder, hi: Boolean) {
        mb.color(0.42f, 0.48f, 0.3f)
        mb.cylinder(0f, 0f, 0f, 0.3f, 0.2f, 1.6f, 8)
        mb.push().trs(0.25f, 0.7f, 0f, 0f, 0f, -0.9f).cylinder(0f, 0f, 0f, 0.16f, 0.1f, 0.8f, 7).pop()
        mb.push().trs(-0.2f, 0.9f, 0.1f, 2.5f, 0f, -0.9f).cylinder(0f, 0f, 0f, 0.14f, 0.09f, 0.6f, 7).pop()
        if (hi) {
            mb.color(0.9f, 0.85f, 0.7f)
            val rng = Rng(3)
            for (i in 0 until 16) {
                val a = rng.range(0f, TWO_PI); val y = rng.range(0.2f, 1.5f)
                mb.push().trs(cos(a) * 0.27f, y, sin(a) * 0.27f, -a, 0f, 1.5708f).cylinder(0f, 0f, 0f, 0.012f, 0f, 0.12f, 3, caps = false).pop()
            }
            mb.color(0.9f, 0.45f, 0.15f, 0.1f)
            mb.sphere(0f, 1.7f, 0f, 0.18f, 0.18f, 0.18f, 4, 6, 0f, 0)
        }
    }

    private fun herb(mb: MeshBuilder) {
        val rng = Rng(41)
        for (i in 0 until 10) {
            mb.color(0.58f, 0.13f, 0.14f).mat(Mat.GRASS_BLADE)
            mb.blade(rng.range(-0.15f, 0.15f), rng.range(-0.15f, 0.15f), 0.16f, rng.range(0.35f, 0.6f), rng.range(0f, TWO_PI), 0.2f)
        }
        mb.color(0.95f, 0.3f, 0.35f, 0.3f)
        mb.sphere(0f, 0.55f, 0f, 0.06f, 0.06f, 0.06f, 3, 5, 0f, 1)
    }

    private fun mushrooms(mb: MeshBuilder) {
        val rng = Rng(51)
        for (i in 0 until 5) {
            val x = rng.range(-0.3f, 0.3f); val z = rng.range(-0.3f, 0.3f)
            val h = rng.range(0.15f, 0.35f)
            mb.color(0.88f, 0.84f, 0.75f)
            mb.cylinder(x, 0f, z, 0.04f, 0.033f, h, 7, caps = false)
            mb.color(0.55f, 0.78f, 0.72f, 0.5f)
            mb.sphere(x, h, z, 0.14f, 0.07f, 0.14f, 4, 8, 0f, 0)
        }
    }

    private fun crystal(mb: MeshBuilder) {
        val rng = Rng(61)
        mb.color(0.6f, 0.6f, 0.62f).mat(Mat.ROCK)
        mb.sphere(0f, 0.2f, 0f, 0.9f, 0.5f, 0.8f, 4, 7, 0.2f, 1)
        for (i in 0 until 6) {
            mb.color(0.55f, 0.85f, 1.0f, 0.55f)
            mb.push().trs(rng.range(-0.4f, 0.4f), 0.3f, rng.range(-0.4f, 0.4f), rng.range(0f, 6f), rng.range(-0.4f, 0.4f), rng.range(-0.4f, 0.4f))
            mb.cylinder(0f, 0f, 0f, 0.16f, 0.0f, rng.range(0.8f, 1.6f), 6, caps = false, flat = true)
            mb.pop()
        }
    }

    private fun grass(mb: MeshBuilder, hi: Boolean) {
        val rng = Rng(71)
        val n = if (hi) 20 else 9
        for (i in 0 until n) {
            val shade = rng.range(0.82f, 1.2f)
            val dry = rng.range(0f, 0.25f)
            mb.color((0.3f + dry * 0.5f) * shade, 0.46f * shade, (0.15f + dry * 0.1f) * shade).mat(Mat.GRASS_BLADE)
            val a = rng.range(0f, TWO_PI)
            val d = rng.range(0f, 0.6f)
            mb.blade(cos(a) * d, sin(a) * d, 0.06f + rng.range(0f, 0.04f), rng.range(0.3f, 0.8f), rng.range(0f, TWO_PI), rng.range(0.08f, 0.28f))
        }
    }

    private fun flowers(mb: MeshBuilder) {
        val rng = Rng(81)
        val palette = intArrayOf(0xf2d14b, 0xe86fa0, 0xf0f0f0, 0x8fa8ff, 0xff8a3c)
        for (i in 0 until 7) {
            mb.color(0.32f, 0.47f, 0.18f).mat(Mat.GRASS_BLADE)
            val x = rng.range(-0.4f, 0.4f); val z = rng.range(-0.4f, 0.4f)
            val h = rng.range(0.25f, 0.45f)
            mb.blade(x, z, 0.04f, h, rng.range(0f, TWO_PI), 0.03f)
            mb.color(palette[rng.nextInt(palette.size)], 0.05f)
            mb.sphere(x, h, z, 0.06f, 0.025f, 0.06f, 2, 6, 0f, i)
        }
        grass(mb, false)
    }

    private fun fern(mb: MeshBuilder, hi: Boolean) {
        val rng = Rng(91)
        val fronds = if (hi) 9 else 5
        for (i in 0 until fronds) {
            val a = i * TWO_PI / fronds + rng.range(-0.2f, 0.2f)
            val shade = rng.range(0.85f, 1.1f)
            mb.color(0.22f * shade, 0.42f * shade, 0.14f * shade).mat(Mat.GRASS_BLADE)
            // arching frond made of three segments with leaflets
            var px = 0f; var py = 0f; var pz = 0f
            val segs = if (hi) 5 else 3
            for (sgi in 0 until segs) {
                val t = sgi.toFloat() / segs
                val nx = px + cos(a) * 0.17f; val nz = pz + sin(a) * 0.17f
                val ny = py + 0.16f * (1f - t * 1.6f)
                val w = 0.16f * (1f - t * 0.6f)
                mb.flatQuad(px - sin(a) * w, py, pz + cos(a) * w, nx - sin(a) * w * 0.8f, ny, nz + cos(a) * w * 0.8f,
                    nx + sin(a) * w * 0.8f, ny, nz - cos(a) * w * 0.8f, px + sin(a) * w, py, pz - cos(a) * w)
                mb.flatQuad(px + sin(a) * w, py, pz - cos(a) * w, nx + sin(a) * w * 0.8f, ny, nz - cos(a) * w * 0.8f,
                    nx - sin(a) * w * 0.8f, ny, nz + cos(a) * w * 0.8f, px - sin(a) * w, py, pz + cos(a) * w)
                px = nx; py = ny; pz = nz
            }
        }
    }

    private fun shrub(mb: MeshBuilder, hi: Boolean) {
        val rng = Rng(101)
        bark(mb, 0x5a4636)
        for (i in 0 until 3) mb.push().trs(0f, 0f, 0f, i * 2.1f, 0f, 0.35f).cylinder(0f, 0f, 0f, 0.05f, 0.03f, 0.8f, 4, caps = false).pop()
        val n = if (hi) 4 else 2
        for (i in 0 until n) {
            val sh = rng.range(0.85f, 1.1f)
            leaves(mb, 0.3f * sh, 0.42f * sh, 0.17f * sh)
            mb.sphere(rng.range(-0.3f, 0.3f), rng.range(0.55f, 0.85f), rng.range(-0.3f, 0.3f), 0.55f, 0.42f, 0.55f, if (hi) 5 else 3, if (hi) 7 else 5, 0.15f, i)
        }
    }
}
