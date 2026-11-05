package com.rustbloom.game.world

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
    FLOWERS("Wildflowers", 0f, 0, false, 0.9f, 1.2f, decorative = true);

    val isTree get() = this == OAK || this == PINE || this == DEAD_TREE || this == SWAMP_TREE
}

/** Builds the procedural meshes for each vegetation type, high and low detail. */
object VegetationMeshes {

    fun build(type: VegType, lod: Int): MeshData {
        val mb = MeshBuilder(512)
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
        }
        return mb.build()
    }

    private fun oak(mb: MeshBuilder, hi: Boolean) {
        mb.color(0x4a3726)
        mb.cylinder(0f, -0.3f, 0f, 0.42f, 0.28f, 3.8f, if (hi) 7 else 5, caps = false)
        if (hi) {
            // branches
            mb.push().trs(0f, 3.0f, 0f, 0.4f, 0f, 0.9f).cylinder(0f, 0f, 0f, 0.16f, 0.08f, 1.8f, 5, caps = false).pop()
            mb.push().trs(0f, 3.3f, 0f, 2.6f, 0f, -0.8f).cylinder(0f, 0f, 0f, 0.14f, 0.07f, 1.6f, 5, caps = false).pop()
        }
        val blobs = if (hi) 6 else 3
        val rng = Rng(77)
        for (i in 0 until blobs) {
            val a = i * TWO_PI / blobs + rng.range(-0.3f, 0.3f)
            val d = if (i == 0) 0f else rng.range(0.9f, 1.7f)
            val shade = rng.range(0.85f, 1.1f)
            mb.color(0.22f * shade, 0.36f * shade, 0.13f * shade)
            val r = rng.range(1.5f, 2.1f)
            mb.sphere(cos(a) * d, rng.range(4.4f, 5.6f), sin(a) * d, r, r * 0.8f, r, if (hi) 5 else 3, if (hi) 7 else 5, 0.22f, i * 13, flat = true)
        }
        mb.color(0.27f, 0.42f, 0.16f)
        mb.sphere(0f, 6.4f, 0f, 1.6f, 1.2f, 1.6f, if (hi) 4 else 3, if (hi) 6 else 5, 0.2f, 99, flat = true)
    }

    private fun pine(mb: MeshBuilder, hi: Boolean) {
        mb.color(0x3d2b1f)
        mb.cylinder(0f, -0.3f, 0f, 0.3f, 0.12f, 7.5f, if (hi) 6 else 4, caps = false)
        val layers = if (hi) 5 else 3
        for (i in 0 until layers) {
            val t = i.toFloat() / layers
            val shade = 0.85f + t * 0.25f
            mb.color(0.11f * shade, 0.24f * shade, 0.15f * shade)
            val r = 2.6f * (1f - t * 0.75f)
            val y = 1.6f + t * 6.2f
            mb.push().trs(0f, y, 0f, i * 0.7f)
            mb.cylinder(0f, 0f, 0f, r, 0.05f, 2.6f - t * 0.8f, if (hi) 8 else 6, caps = true, flat = true)
            mb.pop()
        }
    }

    private fun deadTree(mb: MeshBuilder, hi: Boolean) {
        mb.color(0x5b5048)
        mb.cylinder(0f, -0.3f, 0f, 0.32f, 0.14f, 5.2f, if (hi) 6 else 4, caps = false, flat = true)
        mb.push().trs(0f, 2.8f, 0f, 0.5f, 0f, 0.8f).cylinder(0f, 0f, 0f, 0.12f, 0.04f, 2.0f, 4, caps = false, flat = true).pop()
        mb.push().trs(0f, 3.6f, 0f, 2.8f, 0f, -0.7f).cylinder(0f, 0f, 0f, 0.1f, 0.03f, 1.7f, 4, caps = false, flat = true).pop()
        if (hi) mb.push().trs(0f, 4.4f, 0f, 4.4f, 0f, 0.6f).cylinder(0f, 0f, 0f, 0.08f, 0.02f, 1.2f, 4, caps = false, flat = true).pop()
    }

    private fun swampTree(mb: MeshBuilder, hi: Boolean) {
        mb.color(0x3b3426)
        // root flare
        mb.cylinder(0f, -0.3f, 0f, 0.9f, 0.45f, 1.2f, if (hi) 7 else 5, caps = false, flat = true)
        mb.cylinder(0f, 0.9f, 0f, 0.45f, 0.3f, 3.6f, if (hi) 7 else 5, caps = false)
        val rng = Rng(5)
        val n = if (hi) 7 else 4
        for (i in 0 until n) {
            val a = i * TWO_PI / n
            val shade = rng.range(0.85f, 1.1f)
            mb.color(0.26f * shade, 0.33f * shade, 0.14f * shade)
            // drooping foliage
            mb.sphere(cos(a) * 1.6f, 4.6f, sin(a) * 1.6f, 1.5f, 1.9f, 1.5f, if (hi) 4 else 3, if (hi) 6 else 4, 0.25f, i, flat = true)
        }
        mb.color(0.3f, 0.38f, 0.16f)
        mb.sphere(0f, 5.6f, 0f, 1.8f, 1.2f, 1.8f, 3, 6, 0.2f, 42, flat = true)
    }

    private fun berryBush(mb: MeshBuilder, hi: Boolean) {
        mb.color(0.2f, 0.34f, 0.14f)
        mb.sphere(0f, 0.55f, 0f, 0.9f, 0.6f, 0.9f, 3, if (hi) 7 else 5, 0.25f, 3, flat = true)
        mb.sphere(0.45f, 0.4f, 0.3f, 0.6f, 0.45f, 0.6f, 3, 5, 0.25f, 4, flat = true)
        if (hi) {
            mb.color(0.75f, 0.08f, 0.18f, 0.05f)
            val rng = Rng(9)
            for (i in 0 until 9) {
                val a = rng.range(0f, TWO_PI)
                val y = rng.range(0.4f, 0.95f)
                mb.box(cos(a) * 0.82f, y, sin(a) * 0.82f, 0.13f, 0.13f, 0.13f)
            }
        }
    }

    private fun reeds(mb: MeshBuilder, hi: Boolean) {
        val rng = Rng(21)
        val n = if (hi) 14 else 7
        for (i in 0 until n) {
            val shade = rng.range(0.8f, 1.15f)
            mb.color(0.45f * shade, 0.5f * shade, 0.22f * shade)
            val a = rng.range(0f, TWO_PI)
            val d = rng.range(0f, 0.45f)
            mb.blade(cos(a) * d, sin(a) * d, 0.09f, rng.range(1.0f, 1.7f), rng.range(0f, TWO_PI), rng.range(0.1f, 0.35f))
        }
        if (hi) {
            mb.color(0.5f, 0.37f, 0.2f)
            for (i in 0 until 3) {
                val a = rng.range(0f, TWO_PI)
                mb.box(cos(a) * 0.2f, 1.5f + i * 0.1f, sin(a) * 0.2f, 0.06f, 0.25f, 0.06f)
            }
        }
    }

    private fun smallRocks(mb: MeshBuilder) {
        mb.color(0.45f, 0.44f, 0.42f)
        mb.sphere(0f, 0.12f, 0f, 0.35f, 0.25f, 0.3f, 3, 5, 0.3f, 1, flat = true)
        mb.color(0.5f, 0.48f, 0.45f)
        mb.sphere(0.4f, 0.08f, 0.2f, 0.22f, 0.16f, 0.2f, 2, 5, 0.3f, 2, flat = true)
        mb.sphere(-0.3f, 0.06f, 0.35f, 0.18f, 0.12f, 0.2f, 2, 4, 0.3f, 3, flat = true)
    }

    private fun boulder(mb: MeshBuilder, hi: Boolean) {
        mb.color(0.42f, 0.41f, 0.39f)
        mb.sphere(0f, 0.5f, 0f, 1.5f, 1.2f, 1.3f, if (hi) 5 else 3, if (hi) 8 else 5, 0.22f, 7, flat = true)
        if (hi) {
            mb.color(0.3f, 0.4f, 0.2f)
            mb.sphere(0.2f, 1.45f, 0.1f, 0.9f, 0.18f, 0.8f, 2, 6, 0.2f, 8, flat = true)
        }
    }

    private fun scrapHeap(mb: MeshBuilder) {
        val rng = Rng(31)
        for (i in 0 until 7) {
            val shade = rng.range(0.7f, 1.1f)
            if (i % 3 == 0) mb.color(0.45f * shade, 0.25f * shade, 0.14f * shade) else mb.color(0.38f * shade, 0.38f * shade, 0.4f * shade)
            mb.push().trs(rng.range(-0.8f, 0.8f), rng.range(0.1f, 0.6f), rng.range(-0.8f, 0.8f), rng.range(0f, 3f), rng.range(-0.5f, 0.5f), rng.range(-0.5f, 0.5f))
            mb.box(0f, 0f, 0f, rng.range(0.4f, 1.2f), rng.range(0.15f, 0.5f), rng.range(0.3f, 0.9f))
            mb.pop()
        }
        mb.color(0.9f, 0.55f, 0.2f, 0.6f)
        mb.box(0.1f, 0.75f, 0f, 0.12f, 0.12f, 0.12f)
    }

    private fun thorn(mb: MeshBuilder, hi: Boolean) {
        mb.color(0.36f, 0.42f, 0.25f)
        mb.cylinder(0f, 0f, 0f, 0.3f, 0.2f, 1.6f, 6, flat = true)
        mb.push().trs(0.25f, 0.7f, 0f, 0f, 0f, -0.9f).cylinder(0f, 0f, 0f, 0.16f, 0.1f, 0.8f, 5, flat = true).pop()
        mb.push().trs(-0.2f, 0.9f, 0.1f, 2.5f, 0f, -0.9f).cylinder(0f, 0f, 0f, 0.14f, 0.09f, 0.6f, 5, flat = true).pop()
        if (hi) {
            mb.color(0.85f, 0.45f, 0.15f, 0.1f)
            mb.sphere(0f, 1.7f, 0f, 0.18f, 0.18f, 0.18f, 3, 5, 0f, 0, flat = true)
        }
    }

    private fun herb(mb: MeshBuilder) {
        val rng = Rng(41)
        for (i in 0 until 8) {
            mb.color(0.55f, 0.12f, 0.12f)
            mb.blade(rng.range(-0.15f, 0.15f), rng.range(-0.15f, 0.15f), 0.18f, rng.range(0.35f, 0.6f), rng.range(0f, TWO_PI), 0.2f)
        }
        mb.color(0.9f, 0.3f, 0.35f, 0.3f)
        mb.box(0f, 0.55f, 0f, 0.1f, 0.1f, 0.1f)
    }

    private fun mushrooms(mb: MeshBuilder) {
        val rng = Rng(51)
        for (i in 0 until 4) {
            val x = rng.range(-0.3f, 0.3f); val z = rng.range(-0.3f, 0.3f)
            val h = rng.range(0.15f, 0.35f)
            mb.color(0.85f, 0.8f, 0.7f)
            mb.cylinder(x, 0f, z, 0.04f, 0.035f, h, 5, caps = false)
            mb.color(0.55f, 0.75f, 0.7f, 0.5f)
            mb.sphere(x, h, z, 0.14f, 0.07f, 0.14f, 3, 6, 0f, 0)
        }
    }

    private fun crystal(mb: MeshBuilder) {
        val rng = Rng(61)
        mb.color(0.45f, 0.44f, 0.46f)
        mb.sphere(0f, 0.2f, 0f, 0.9f, 0.5f, 0.8f, 3, 6, 0.25f, 1, flat = true)
        for (i in 0 until 5) {
            mb.color(0.55f, 0.85f, 1.0f, 0.55f)
            mb.push().trs(rng.range(-0.4f, 0.4f), 0.3f, rng.range(-0.4f, 0.4f), rng.range(0f, 6f), rng.range(-0.4f, 0.4f), rng.range(-0.4f, 0.4f))
            mb.cylinder(0f, 0f, 0f, 0.16f, 0.0f, rng.range(0.8f, 1.6f), 5, caps = false, flat = true)
            mb.pop()
        }
    }

    private fun grass(mb: MeshBuilder, hi: Boolean) {
        val rng = Rng(71)
        val n = if (hi) 11 else 6
        for (i in 0 until n) {
            val shade = rng.range(0.85f, 1.2f)
            mb.color(0.33f * shade, 0.5f * shade, 0.18f * shade)
            val a = rng.range(0f, TWO_PI)
            val d = rng.range(0f, 0.55f)
            mb.blade(cos(a) * d, sin(a) * d, 0.11f, rng.range(0.35f, 0.75f), rng.range(0f, TWO_PI), rng.range(0.05f, 0.2f))
        }
    }

    private fun flowers(mb: MeshBuilder) {
        val rng = Rng(81)
        val palette = intArrayOf(0xf2d14b, 0xe86fa0, 0xf0f0f0, 0x8fa8ff, 0xff8a3c)
        for (i in 0 until 6) {
            mb.color(0.3f, 0.45f, 0.18f)
            val x = rng.range(-0.4f, 0.4f); val z = rng.range(-0.4f, 0.4f)
            val h = rng.range(0.25f, 0.45f)
            mb.blade(x, z, 0.05f, h, rng.range(0f, TWO_PI), 0.03f)
            mb.color(palette[rng.nextInt(palette.size)], 0.06f)
            mb.box(x, h, z, 0.1f, 0.05f, 0.1f)
        }
    }
}
