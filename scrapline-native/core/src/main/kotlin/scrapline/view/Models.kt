package scrapline.view

import scrapline.sim.EntityKind
import scrapline.sim.Item
import scrapline.sim.NodeKind
import kotlin.math.cos
import kotlin.math.sin

// Palette: sun-bleached machine shells, teal trim, oxide red and amber for anything that matters.
private val SHELL = rgb(0xE8E0CC)
private val SHELL_DARK = rgb(0xB9B19E)
private val TRIM = rgb(0x2F8F86)
private val DARK = rgb(0x30363C)
private val STEEL = rgb(0x6C7780)
private val OXIDE = rgb(0xB8432C)
private val AMBER = rgb(0xF2A93B)
private val WOOD = rgb(0x7A5A3E)
private val WOOD_PALE = rgb(0xC9A87A)
private val SANDSTONE = rgb(0xC98F5E)
private val SANDSTONE_DARK = rgb(0xA8724A)

private const val PI = Math.PI.toFloat()

/** Every prop shape, built once at unit scale with its base at y = 0 and its front toward -Z. */
object Models {
    val nodes: Array<Tris?> = arrayOfNulls(5)
    val entities: Array<Tris?> = arrayOfNulls(EntityKind.COUNT)
    val cactus = Tris()
    val bush = Tris()
    val pebbles = Tris()
    val stray = Tris()
    val flame = Tris()
    val light = Tris()
    val cargo = Tris()
    val glow = Tris()
    /** What the player sees in their hand, by item kind. */
    val heldAxe = Tris()
    val heldPick = Tris()
    val heldLinker = Tris()
    val heldBox = Tris()
    val heldHand = Tris()

    init {
        // --- resource nodes
        nodes[NodeKind.DEADWOOD] = Tris().apply {
            val bark = rgb(0x6E5846)
            val pale = rgb(0x8C7560)
            cyl(0.3f, 0.17f, 1.5f, 6, 0f, 0f, 0f, bark)
            cyl(0.17f, 0.08f, 1.3f, 6, 0f, 1.5f, 0f, bark)
            cyl(0.08f, 0f, 0.7f, 5, 0f, 2.8f, 0f, pale)
            var from = size
            cyl(0.1f, 0.03f, 1.2f, 5, 0f, 0f, 0f, pale)
            rotate(from, 2, -0.9f).rotate(from, 1, 0.3f)
            shift(from, 0.05f, 1.4f, 0f)
            from = size
            cyl(0.09f, 0.02f, 1.0f, 5, 0f, 0f, 0f, pale)
            rotate(from, 2, 0.8f).rotate(from, 1, 1.9f)
            shift(from, 0f, 1.9f, 0f)
            from = size
            cyl(0.07f, 0.02f, 0.8f, 5, 0f, 0f, 0f, bark)
            rotate(from, 2, -0.7f).rotate(from, 1, 3.6f)
            shift(from, 0f, 2.3f, 0f)
        }
        nodes[NodeKind.STONE] = Tris().apply {
            rock(0.9f, 3, 1f, 0.62f, 0.9f, 0f, 0f, 0f, SANDSTONE, SANDSTONE_DARK)
            rock(0.45f, 9, 1f, 0.7f, 1f, 0.75f, 0f, 0.35f, SANDSTONE_DARK, SANDSTONE)
        }
        nodes[NodeKind.ORE] = Tris().apply {
            rock(0.9f, 5, 1f, 0.72f, 0.95f, 0f, 0f, 0f, rgb(0x5A4A46), rgb(0xB5623A))
            rock(0.28f, 6, 1f, 1f, 1f, 0.45f, 0.55f, 0.3f, rgb(0xD07A45))
            rock(0.24f, 7, 1f, 1f, 1f, -0.5f, 0.4f, -0.2f, rgb(0xD07A45))
        }
        nodes[NodeKind.COAL] = Tris().apply {
            rock(0.85f, 11, 1.1f, 0.6f, 0.9f, 0f, 0f, 0f, rgb(0x1F2226), rgb(0x30343A))
            rock(0.4f, 12, 1f, 0.6f, 1f, 0.6f, 0f, 0.45f, rgb(0x2A2E33))
        }

        // --- scenery (no gameplay)
        cactus.apply {
            val g = rgb(0x5E8C4A)
            val g2 = rgb(0x4C7A3E)
            cyl(0.22f, 0.2f, 2.0f, 7, 0f, 0f, 0f, g, g2)
            cyl(0.2f, 0.02f, 0.25f, 7, 0f, 2.0f, 0f, g2)
            cyl(0.11f, 0.11f, 0.5f, 6, 0.45f, 0.9f, 0f, g, g2)
            box(0.45f, 0.16f, 0.16f, 0.24f, 0.95f, 0f, g2)
            cyl(0.1f, 0.1f, 0.6f, 6, -0.42f, 1.2f, 0f, g, g2)
            box(0.42f, 0.15f, 0.15f, -0.22f, 1.25f, 0f, g2)
        }
        bush.apply {
            val dry = rgb(0xA89A5C)
            val dry2 = rgb(0x8F8450)
            for (i in 0 until 7) {
                val from = size
                cyl(0.035f, 0f, 0.55f + 0.1f * (i % 3), 3, 0f, 0f, 0f, if (i % 2 == 0) dry else dry2)
                rotate(from, 2, 0.55f).rotate(from, 1, i * 0.9f)
            }
        }
        pebbles.apply {
            rock(0.16f, 21, 1f, 0.6f, 1f, 0f, -0.03f, 0f, SANDSTONE_DARK)
            rock(0.11f, 22, 1f, 0.6f, 1f, 0.3f, -0.03f, 0.15f, SANDSTONE)
            rock(0.08f, 23, 1f, 0.6f, 1f, -0.15f, -0.02f, 0.3f, SANDSTONE_DARK)
        }

        // --- entities
        entities[EntityKind.WORKBENCH] = Tris().apply {
            box(0.96f, 0.1f, 0.74f, 0f, 0.9f, 0f, WOOD_PALE)
            for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) box(0.08f, 0.85f, 0.08f, sx * 0.4f, 0.425f, sz * 0.29f, DARK)
            box(0.84f, 0.05f, 0.6f, 0f, 0.3f, 0f, STEEL)
            box(0.2f, 0.16f, 0.2f, 0.28f, 1.03f, 0.14f, OXIDE)
            box(0.3f, 0.04f, 0.1f, -0.2f, 0.97f, -0.1f, STEEL)
        }
        entities[EntityKind.CRATE] = Tris().apply {
            box(0.84f, 0.62f, 0.84f, 0f, 0.31f, 0f, WOOD)
            box(0.9f, 0.12f, 0.9f, 0f, 0.68f, 0f, WOOD_PALE)
            for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) box(0.1f, 0.64f, 0.1f, sx * 0.4f, 0.32f, sz * 0.4f, DARK)
            box(0.22f, 0.1f, 0.04f, 0f, 0.48f, -0.43f, STEEL)
        }
        entities[EntityKind.WALL] = Tris().apply {
            box(1.0f, 0.25f, 0.46f, 0f, 0.125f, 0f, DARK)
            box(1.0f, 2.0f, 0.36f, 0f, 1.25f, 0f, SHELL)
            box(1.0f, 0.3f, 0.38f, 0f, 1.5f, 0f, TRIM)
            box(1.0f, 0.12f, 0.46f, 0f, 2.31f, 0f, SHELL_DARK)
        }
        entities[EntityKind.CAMPFIRE] = Tris().apply {
            var from = size
            box(0.74f, 0.1f, 0.12f, 0f, 0.12f, 0f, rgb(0x5A4A3E))
            rotate(from, 1, 0.6f)
            from = size
            box(0.74f, 0.1f, 0.12f, 0f, 0.2f, 0f, rgb(0x4D4036))
            rotate(from, 1, -0.7f)
            for (i in 0 until 7) {
                val a = i / 7f * PI * 2f
                rock(0.15f, 30 + i, 1f, 0.8f, 1f, cos(a) * 0.42f, -0.02f, sin(a) * 0.42f, if (i % 2 == 0) SANDSTONE_DARK else rgb(0x6C5F55))
            }
        }
        entities[EntityKind.SMELTER] = Tris().apply {
            box(0.94f, 0.9f, 0.94f, 0f, 0.45f, 0f, rgb(0x8A4A38))
            box(1.0f, 0.12f, 1.0f, 0f, 0.96f, 0f, DARK)
            cyl(0.22f, 0.17f, 0.6f, 6, 0f, 1.0f, 0f, DARK)
            box(0.52f, 0.36f, 0.06f, 0f, 0.5f, -0.48f, rgb(0xF2A93B, MAT_GLOW))
            box(0.62f, 0.06f, 0.08f, 0f, 0.72f, -0.48f, DARK)
            box(0.42f, 0.14f, 0.24f, 0f, 0.24f, -0.58f, STEEL)
        }
        entities[EntityKind.DRILL] = Tris().apply {
            box(0.94f, 0.36f, 0.94f, 0f, 0.18f, 0f, DARK)
            box(0.56f, 0.8f, 0.56f, 0f, 0.76f, 0f, SHELL)
            box(0.58f, 0.14f, 0.58f, 0f, 0.62f, 0f, TRIM)
            cyl(0.16f, 0.16f, 0.22f, 6, 0f, 1.16f, 0f, OXIDE)
            box(0.42f, 0.18f, 0.26f, 0f, 0.3f, -0.56f, STEEL)
            for (sx in intArrayOf(-1, 1)) box(0.08f, 0.9f, 0.08f, sx * 0.4f, 0.8f, 0.38f, STEEL)
        }
        entities[EntityKind.BELT] = Tris().apply {
            box(0.86f, 0.1f, 1.0f, 0f, 0.32f, 0f, rgb(0x2B3036))
            box(0.07f, 0.2f, 1.0f, -0.465f, 0.36f, 0f, TRIM)
            box(0.07f, 0.2f, 1.0f, 0.465f, 0.36f, 0f, TRIM)
            for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) box(0.1f, 0.28f, 0.1f, sx * 0.38f, 0.14f, sz * 0.38f, DARK)
            // Arrow on the bed pointing the way items travel.
            val y = 0.375f
            val c = rgb(0xE8E0CC)
            tri(0f, y, -0.26f, -0.18f, y, 0.08f, 0.18f, y, 0.08f, c)
        }
        entities[EntityKind.SENSOR] = Tris().apply {
            cyl(0.24f, 0.2f, 0.08f, 6, 0f, 0f, 0f, DARK)
            cyl(0.06f, 0.045f, 1.2f, 5, 0f, 0.08f, 0f, STEEL)
            rock(0.2f, 41, 1f, 1f, 1f, 0f, 1.25f, 0f, TRIM, rgb(0x3BA79D))
        }
        entities[EntityKind.TURRET] = Tris().apply {
            cyl(0.48f, 0.42f, 0.5f, 6, 0f, 0f, 0f, DARK)
            box(0.52f, 0.42f, 0.64f, 0f, 0.73f, 0f, SHELL)
            box(0.54f, 0.1f, 0.66f, 0f, 0.6f, 0f, OXIDE)
            val from = size
            cyl(0.07f, 0.06f, 0.7f, 6, 0f, 0f, 0f, DARK)
            rotate(from, 0, -PI / 2f)
            shift(from, 0f, 0.8f, -0.3f)
            box(0.12f, 0.1f, 0.12f, 0f, 0.99f, 0.1f, AMBER)
        }
        entities[EntityKind.LAMP] = Tris().apply {
            cyl(0.2f, 0.16f, 0.08f, 6, 0f, 0f, 0f, DARK)
            cyl(0.06f, 0.045f, 2.0f, 5, 0f, 0.08f, 0f, STEEL)
            box(0.34f, 0.12f, 0.34f, 0f, 2.16f, 0f, DARK)
        }
        entities[EntityKind.SWITCH] = Tris().apply {
            box(0.14f, 0.9f, 0.14f, 0f, 0.45f, 0f, DARK)
            box(0.32f, 0.3f, 0.16f, 0f, 1.0f, 0f, SHELL)
            box(0.1f, 0.14f, 0.06f, 0f, 1.0f, -0.1f, OXIDE)
        }

        // --- dynamic bits
        stray.apply {
            // A flat, dark kite with two fins; its own shape, centred on its origin.
            val hull = rgb(0x23282E)
            val fin = rgb(0x4A2F2A)
            tri(0f, 0.18f, 0f, 0.5f, 0f, 0.1f, 0f, 0f, -0.75f, hull)
            tri(0f, 0.18f, 0f, 0f, 0f, -0.75f, -0.5f, 0f, 0.1f, hull)
            tri(0f, 0.18f, 0f, -0.5f, 0f, 0.1f, 0f, 0f, 0.6f, hull)
            tri(0f, 0.18f, 0f, 0f, 0f, 0.6f, 0.5f, 0f, 0.1f, hull)
            tri(0f, -0.14f, 0f, 0f, 0f, -0.75f, 0.5f, 0f, 0.1f, hull)
            tri(0f, -0.14f, 0f, -0.5f, 0f, 0.1f, 0f, 0f, -0.75f, hull)
            tri(0f, -0.14f, 0f, 0f, 0f, 0.6f, -0.5f, 0f, 0.1f, hull)
            tri(0f, -0.14f, 0f, 0.5f, 0f, 0.1f, 0f, 0f, 0.6f, hull)
            box(0.5f, 0.04f, 0.3f, -0.68f, 0.02f, 0.2f, fin)
            box(0.5f, 0.04f, 0.3f, 0.68f, 0.02f, 0.2f, fin)
            box(0.08f, 0.24f, 0.4f, 0f, 0.2f, 0.35f, fin)
            box(0.26f, 0.07f, 0.05f, 0f, 0.05f, -0.6f, rgb(0xFF5A45, MAT_GLOW))
        }
        flame.cyl(0.2f, 0f, 0.6f, 5, 0f, 0.2f, 0f, rgb(0xFFB84A, MAT_GLOW))
        flame.cyl(0.11f, 0f, 0.36f, 4, 0f, 0.22f, 0f, rgb(0xFFE9A8, MAT_GLOW))
        light.rock(0.11f, 2, 1f, 1f, 1f, 0f, -0.06f, 0f, rgb(0xFFFFFF, MAT_GLOW))
        cargo.box(0.26f, 0.2f, 0.26f, 0f, 0.1f, 0f, rgb(0xFFFFFF))
        // Pool of light: a disc whose colour fades to black at the rim, drawn additively.
        glow.apply {
            val centre = rgb(0x6B3D14, MAT_GLOW)
            val rim = rgb(0x000000, MAT_GLOW)
            val n = 18
            for (i in 0 until n) {
                val a0 = i * PI * 2f / n
                val a1 = (i + 1) * PI * 2f / n
                vertex(0f, 0f, 0f, 0f, 1f, 0f, centre)
                vertex(cos(a1), 0f, sin(a1), 0f, 1f, 0f, rim)
                vertex(cos(a0), 0f, sin(a0), 0f, 1f, 0f, rim)
            }
        }

        // --- held items, modelled around the grip at the origin, pointing up
        heldHand.apply {
            box(0.09f, 0.1f, 0.12f, 0f, 0f, 0f, rgb(0x8A8F96))
            box(0.1f, 0.3f, 0.1f, 0.02f, -0.2f, 0.06f, rgb(0x5E646B))
        }
        heldAxe.apply {
            box(0.045f, 0.62f, 0.045f, 0f, 0.2f, 0f, WOOD)
            box(0.05f, 0.16f, 0.2f, 0f, 0.44f, -0.1f, STEEL)
            box(0.03f, 0.2f, 0.06f, 0f, 0.44f, -0.22f, rgb(0xC9D2D8))
        }
        heldPick.apply {
            box(0.045f, 0.62f, 0.045f, 0f, 0.2f, 0f, WOOD)
            box(0.05f, 0.07f, 0.5f, 0f, 0.48f, 0f, STEEL)
            box(0.04f, 0.05f, 0.1f, 0f, 0.45f, -0.28f, rgb(0xC9D2D8))
            box(0.04f, 0.05f, 0.1f, 0f, 0.45f, 0.28f, rgb(0xC9D2D8))
        }
        heldLinker.apply {
            box(0.07f, 0.22f, 0.07f, 0f, 0.06f, 0f, DARK)
            box(0.1f, 0.1f, 0.26f, 0f, 0.2f, -0.08f, SHELL)
            box(0.04f, 0.04f, 0.1f, 0f, 0.2f, -0.26f, rgb(0x6FE6DA, MAT_GLOW))
        }
        heldBox.apply {
            box(0.2f, 0.2f, 0.2f, 0f, 0.14f, -0.02f, WOOD_PALE)
            box(0.21f, 0.05f, 0.21f, 0f, 0.14f, -0.02f, TRIM)
        }
    }

    /** Colour of an item riding a belt. */
    fun cargoColor(item: Int): Float = when (item) {
        Item.WOOD -> rgb(0x8A6A4C)
        Item.STONE -> rgb(0xC98F5E)
        Item.IRON_ORE -> rgb(0xB5623A)
        Item.COAL -> rgb(0x1D2226)
        Item.IRON_INGOT -> rgb(0xC3CED4)
        Item.GEAR -> rgb(0xAAB6BD)
        Item.CIRCUIT -> rgb(0x2F7D6D)
        else -> rgb(0xBFAE90)
    }
}
