package com.rustbloom.game.render

import com.rustbloom.game.engine.MeshBuilder
import com.rustbloom.game.engine.MeshData
import com.rustbloom.game.engine.TWO_PI
import com.rustbloom.game.game.BuildType
import com.rustbloom.game.game.Item
import com.rustbloom.game.game.ToolKind
import kotlin.math.cos
import kotlin.math.sin

/** Meshes for player-built pieces, projectiles and first-person viewmodels. */
object PropMeshes {

    private const val PLANK = 0x8a6440
    private const val PLANK_DARK = 0x6b4b30
    private const val ROPE = 0xb8a070

    /** Returns (solid, glow) meshes, origin at base center, facing +Z. */
    fun build(t: BuildType): Pair<MeshData, MeshData?> {
        val mb = MeshBuilder(512)
        var glow: MeshBuilder? = null
        when (t) {
            BuildType.FOUNDATION -> {
                mb.color(PLANK)
                mb.boxBase(0f, 0f, 0f, 3f, 0.4f, 3f)
                mb.color(PLANK_DARK)
                for (i in 0 until 6) mb.box(-1.25f + i * 0.5f, 0.41f, 0f, 0.04f, 0.02f, 2.95f)
                mb.color(0x5a5048)
                for (x in listOf(-1.35f, 1.35f)) for (z in listOf(-1.35f, 1.35f)) mb.boxBase(x, -1.5f, z, 0.3f, 1.6f, 0.3f)
            }
            BuildType.WALL -> {
                mb.color(PLANK)
                mb.boxBase(0f, 0f, 0f, 3f, 3f, 0.25f)
                mb.color(PLANK_DARK)
                for (i in 0 until 5) mb.box(0f, 0.3f + i * 0.6f, 0.13f, 3f, 0.05f, 0.02f)
                mb.boxBase(-1.45f, 0f, 0f, 0.14f, 3f, 0.3f)
                mb.boxBase(1.45f, 0f, 0f, 0.14f, 3f, 0.3f)
            }
            BuildType.DOORWAY -> {
                mb.color(PLANK)
                mb.boxBase(-1.05f, 0f, 0f, 0.9f, 3f, 0.25f)
                mb.boxBase(1.05f, 0f, 0f, 0.9f, 3f, 0.25f)
                mb.boxBase(0f, 2.3f, 0f, 1.2f, 0.7f, 0.25f)
                mb.color(PLANK_DARK)
                mb.boxBase(-0.62f, 0f, 0f, 0.12f, 2.3f, 0.32f)
                mb.boxBase(0.62f, 0f, 0f, 0.12f, 2.3f, 0.32f)
            }
            BuildType.ROOF -> {
                mb.color(0x8f7a4a)
                mb.boxBase(0f, 0f, 0f, 3.1f, 0.25f, 3.1f)
                mb.color(0xa08850)
                for (i in 0 until 6) mb.box(-1.3f + i * 0.52f, 0.3f, 0f, 0.4f, 0.1f, 3.1f)
            }
            BuildType.CAMPFIRE -> {
                mb.color(0x6a6a6a)
                for (i in 0 until 9) {
                    val a = i * TWO_PI / 9
                    mb.box(cos(a) * 0.6f, 0.1f, sin(a) * 0.6f, 0.26f, 0.22f, 0.26f)
                }
                mb.color(0x4a3220)
                for (i in 0 until 4) {
                    val a = i * TWO_PI / 4 + 0.4f
                    mb.push().trs(0f, 0.2f, 0f, a, 0.5f, 0f).box(0f, 0f, 0.2f, 0.12f, 0.12f, 0.7f).pop()
                }
                mb.color(0x151210)
                mb.box(0f, 0.04f, 0f, 0.8f, 0.06f, 0.8f)
                glow = MeshBuilder(128)
                glow.color(1f, 0.55f, 0.15f, 1f)
                glow.cylinder(0f, 0.15f, 0f, 0.3f, 0.0f, 0.85f, 6, caps = false, flat = true)
                glow.color(1f, 0.85f, 0.35f, 1f)
                glow.cylinder(0.05f, 0.15f, 0.04f, 0.16f, 0.0f, 0.55f, 5, caps = false, flat = true)
            }
            BuildType.WORKBENCH -> {
                mb.color(PLANK)
                mb.boxBase(0f, 0.85f, 0f, 2f, 0.12f, 1f)
                mb.color(PLANK_DARK)
                for (x in listOf(-0.9f, 0.9f)) for (z in listOf(-0.4f, 0.4f)) mb.boxBase(x, 0f, z, 0.12f, 0.85f, 0.12f)
                mb.boxBase(0f, 0.3f, 0f, 1.8f, 0.06f, 0.8f)
                mb.color(0x8d8d8d)
                mb.box(-0.5f, 1.0f, 0.1f, 0.3f, 0.15f, 0.2f)
                mb.color(0x6a4a2a)
                mb.box(0.4f, 0.95f, -0.15f, 0.6f, 0.05f, 0.08f)
            }
            BuildType.TINKER_BENCH -> {
                mb.color(0x5a5f66)
                mb.boxBase(0f, 0.9f, 0f, 2.2f, 0.14f, 1.2f)
                mb.color(0x3b4046)
                mb.boxBase(-0.9f, 0f, 0f, 0.3f, 0.9f, 1.1f)
                mb.boxBase(0.9f, 0f, 0f, 0.3f, 0.9f, 1.1f)
                mb.color(0x8a4b2a)
                mb.box(0f, 1.25f, -0.4f, 1.6f, 0.5f, 0.1f)
                mb.color(0xc8c3b8)
                mb.box(0.5f, 1.08f, 0.15f, 0.4f, 0.2f, 0.3f)
                glow = MeshBuilder(64)
                glow.color(0.3f, 0.9f, 1f, 1f)
                glow.box(-0.4f, 1.3f, -0.34f, 0.5f, 0.2f, 0.03f)
                glow.box(0.5f, 1.2f, 0.31f, 0.2f, 0.05f, 0.02f)
            }
            BuildType.BED -> {
                mb.color(PLANK_DARK)
                mb.boxBase(0f, 0f, 0f, 1.2f, 0.35f, 2.2f)
                mb.color(0xa08060)
                mb.boxBase(0f, 0.35f, 0f, 1.1f, 0.15f, 2.1f)
                mb.color(0x7a5a3a)
                mb.boxBase(0f, 0.5f, -0.25f, 1.1f, 0.08f, 1.5f)
                mb.color(0xd8d0c0)
                mb.boxBase(0f, 0.5f, 0.8f, 0.8f, 0.14f, 0.4f)
            }
            BuildType.CHEST -> {
                mb.color(PLANK)
                mb.boxBase(0f, 0f, 0f, 1.2f, 0.7f, 0.8f)
                mb.color(PLANK_DARK)
                mb.boxBase(0f, 0.7f, 0f, 1.24f, 0.2f, 0.84f)
                mb.color(0x8d8d8d)
                mb.box(0f, 0.65f, 0.42f, 0.16f, 0.2f, 0.04f)
                mb.box(-0.55f, 0.45f, 0f, 0.04f, 0.9f, 0.86f)
                mb.box(0.55f, 0.45f, 0f, 0.04f, 0.9f, 0.86f)
            }
            BuildType.TORCH_POST -> {
                mb.color(PLANK_DARK)
                mb.cylinder(0f, 0f, 0f, 0.1f, 0.07f, 2f, 6)
                mb.color(ROPE)
                mb.cylinder(0f, 1.8f, 0f, 0.12f, 0.12f, 0.2f, 6)
                glow = MeshBuilder(64)
                glow.color(1f, 0.6f, 0.2f, 1f)
                glow.cylinder(0f, 2f, 0f, 0.13f, 0.0f, 0.45f, 5, caps = false, flat = true)
            }
            BuildType.RAIN_CATCHER -> {
                mb.color(PLANK_DARK)
                for (i in 0 until 4) {
                    val a = i * TWO_PI / 4 + 0.78f
                    mb.boxBase(cos(a) * 0.6f, 0f, sin(a) * 0.6f, 0.1f, 1.2f, 0.1f)
                }
                mb.color(0xa0744e)
                mb.flatQuad(-0.7f, 1.4f, -0.7f, -0.7f, 1.4f, 0.7f, 0.7f, 1.4f, 0.7f, 0.7f, 1.4f, -0.7f)
                mb.color(0x8a6440)
                mb.cylinder(0f, 0.2f, 0f, 0.35f, 0.4f, 0.6f, 8)
                mb.color(0x3a6a8a)
                mb.disc(0f, 0.81f, 0f, 0.33f, 8, true)
            }
            BuildType.SPIKES -> {
                mb.color(PLANK_DARK)
                mb.box(0f, 0.3f, 0f, 3f, 0.15f, 0.15f)
                mb.color(0x9a7a50)
                for (i in 0 until 6) {
                    val x = -1.25f + i * 0.5f
                    mb.push().trs(x, 0.2f, 0f, 0f, if (i % 2 == 0) 0.6f else -0.6f, 0f)
                    mb.cylinder(0f, 0f, 0f, 0.07f, 0.0f, 1.4f, 4, caps = false, flat = true)
                    mb.pop()
                }
            }
            BuildType.SHOCK_PYLON -> {
                mb.color(0x3b4046)
                mb.cylinder(0f, 0f, 0f, 0.4f, 0.3f, 0.4f, 8, flat = true)
                mb.color(0x6a7178)
                mb.cylinder(0f, 0.4f, 0f, 0.12f, 0.1f, 2f, 6)
                mb.color(0xd2803c)
                for (i in 0 until 5) mb.cylinder(0f, 0.7f + i * 0.35f, 0f, 0.2f, 0.2f, 0.06f, 8)
                glow = MeshBuilder(64)
                glow.color(0.4f, 0.85f, 1f, 1f)
                glow.sphere(0f, 2.55f, 0f, 0.22f, 0.22f, 0.22f, 4, 6, 0f, 0)
            }
            BuildType.BOUNTY_BOARD -> {
                mb.color(PLANK_DARK)
                mb.boxBase(-0.9f, 0f, 0f, 0.12f, 2.2f, 0.12f)
                mb.boxBase(0.9f, 0f, 0f, 0.12f, 2.2f, 0.12f)
                mb.color(PLANK)
                mb.box(0f, 1.5f, 0f, 1.9f, 1.1f, 0.08f)
                mb.color(0xe8e0c8)
                mb.box(-0.5f, 1.6f, 0.05f, 0.4f, 0.5f, 0.01f)
                mb.box(0.1f, 1.45f, 0.05f, 0.4f, 0.55f, 0.01f)
                mb.box(0.6f, 1.65f, 0.05f, 0.35f, 0.4f, 0.01f)
            }
        }
        return Pair(mb.build(), glow?.build())
    }

    fun arrow(): MeshData {
        val mb = MeshBuilder(64)
        mb.color(0x9c7444)
        mb.box(0f, 0f, 0f, 0.025f, 0.025f, 0.75f)
        mb.color(0x8d8d8d)
        mb.push().trs(0f, 0f, 0.37f, 0f, 1.5708f, 0f).cylinder(0f, 0f, 0f, 0.035f, 0.0f, 0.1f, 4, caps = false).pop()
        mb.color(0xe0e0d0)
        mb.box(0f, 0.03f, -0.33f, 0.005f, 0.05f, 0.12f)
        mb.box(0.03f, 0f, -0.33f, 0.05f, 0.005f, 0.12f)
        return mb.build()
    }

    fun bolt(): MeshData {
        val mb = MeshBuilder(64)
        mb.color(1f, 0.6f, 0.25f, 1f)
        mb.sphere(0f, 0f, 0f, 0.18f, 0.18f, 0.4f, 3, 6, 0f, 0)
        return mb.build()
    }

    fun cube(): MeshData {
        val mb = MeshBuilder(32)
        mb.color(1f, 1f, 1f, 1f)
        mb.box(0f, 0f, 0f, 1f, 1f, 1f)
        return mb.build()
    }

    fun skyTriangle(): MeshData {
        val mb = MeshBuilder(3)
        mb.rawVertex(-1f, -1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        mb.rawVertex(3f, -1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        mb.rawVertex(-1f, 3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        mb.tri(0, 1, 2)
        return mb.build()
    }

    /** Rain/snow particle seeds: 2 vertices per drop (line streaks), seed stored in position. */
    fun particles(count: Int): MeshData {
        val mb = MeshBuilder(count * 2)
        val r = com.rustbloom.game.engine.Rng(1234)
        for (i in 0 until count) {
            val x = r.nextFloat(); val y = r.nextFloat(); val z = r.nextFloat()
            val a = mb.rawVertex(x, y, z, 0f, 0f, 0f, 1f, 1f, 1f, 0.4f + r.nextFloat() * 0.6f)
            val b = mb.rawVertex(x, y, z, 1f, 0f, 0f, 1f, 1f, 1f, 0.4f + r.nextFloat() * 0.6f)
            mb.tri(a, b, a) // indices unused for lines; we draw arrays
        }
        return mb.build()
    }

    // ------------------------------------------------------------------ viewmodels (camera space: -Z forward, +Y up)

    fun arm(): MeshData {
        val mb = MeshBuilder(64)
        mb.color(0x5a4a3a)
        mb.box(0f, -0.02f, 0.16f, 0.075f, 0.075f, 0.3f)
        mb.color(0xc8946c)
        mb.box(0f, 0.0f, -0.03f, 0.065f, 0.075f, 0.09f)
        return mb.build()
    }

    fun tool(item: Item): MeshData {
        val mb = MeshBuilder(128)
        when (item.tool) {
            ToolKind.AXE -> {
                mb.color(0x7a5a3a)
                mb.box(0f, 0.18f, -0.08f, 0.04f, 0.55f, 0.04f)
                if (item.tier >= 2) mb.color(0x9a9a9a) else mb.color(0x7d7d7d)
                mb.box(0f, 0.42f, -0.14f, 0.03f, 0.14f, 0.16f)
                mb.color(ROPE)
                mb.box(0f, 0.38f, -0.08f, 0.05f, 0.05f, 0.05f)
            }
            ToolKind.PICK -> {
                mb.color(0x7a5a3a)
                mb.box(0f, 0.18f, -0.08f, 0.04f, 0.55f, 0.04f)
                if (item.tier >= 2) mb.color(0x9a9a9a) else mb.color(0x7d7d7d)
                mb.box(0f, 0.44f, -0.08f, 0.04f, 0.05f, 0.36f)
            }
            ToolKind.SPEAR -> {
                mb.color(if (item.tier >= 3) 0x5a6a78 else 0x8a6a42)
                mb.box(0f, 0f, -0.45f, 0.035f, 0.035f, 1.4f)
                when (item.tier) {
                    1 -> mb.color(0x6a6a6a)
                    2 -> mb.color(0x9a8a7a)
                    else -> mb.color(0.6f, 0.95f, 1f, 0.8f)
                }
                mb.push().trs(0f, 0f, -1.15f, 0f, -1.5708f, 0f).cylinder(0f, 0f, 0f, 0.05f, 0.0f, 0.25f, 4, caps = false, flat = true).pop()
                mb.color(ROPE)
                mb.box(0f, 0f, -1.1f, 0.05f, 0.05f, 0.06f)
            }
            ToolKind.BOW -> {
                val c = if (item.tier >= 3) 0x7ab8c8 else 0x7a5430
                mb.color(c)
                for (i in -4..4) {
                    val a = i / 4f
                    val y = a * 0.45f
                    val z = -kotlin.math.abs(a) * kotlin.math.abs(a) * 0.12f + 0.06f
                    mb.box(0f, y, -z, 0.035f, 0.12f, 0.035f)
                }
                mb.color(0xe8e0d0)
                mb.box(0f, 0f, 0.08f, 0.006f, 0.9f, 0.006f)
            }
            ToolKind.TORCH -> {
                mb.color(0x6a4a2a)
                mb.box(0f, 0.15f, -0.05f, 0.05f, 0.45f, 0.05f)
                mb.color(ROPE)
                mb.box(0f, 0.38f, -0.05f, 0.08f, 0.1f, 0.08f)
                mb.color(1f, 0.6f, 0.2f, 1f)
                mb.push().trs(0f, 0.42f, -0.05f).cylinder(0f, 0f, 0f, 0.07f, 0.0f, 0.22f, 5, caps = false, flat = true).pop()
                mb.color(1f, 0.9f, 0.4f, 1f)
                mb.push().trs(0f, 0.42f, -0.05f).cylinder(0f, 0f, 0f, 0.035f, 0.0f, 0.14f, 4, caps = false, flat = true).pop()
            }
            ToolKind.WATERSKIN -> {
                mb.color(0x8a6440)
                mb.sphere(0f, 0.05f, -0.08f, 0.09f, 0.12f, 0.07f, 4, 6, 0.05f, 2)
                mb.color(0x4a3a2a)
                mb.box(0f, 0.18f, -0.08f, 0.03f, 0.05f, 0.03f)
            }
            ToolKind.NONE -> {
                val c = item.color
                mb.color(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, if (item == Item.CORE || item == Item.OVERLOAD_CORE || item == Item.SIGNAL_KEY) 0.6f else 0f)
                mb.box(0f, 0.04f, -0.1f, 0.09f, 0.09f, 0.09f)
            }
        }
        return mb.build()
    }

    fun arrowNocked(): MeshData {
        val mb = MeshBuilder(32)
        mb.color(0x9c7444)
        mb.box(0f, 0f, -0.25f, 0.015f, 0.015f, 0.7f)
        mb.color(0x8d8d8d)
        mb.box(0f, 0f, -0.62f, 0.03f, 0.03f, 0.06f)
        return mb.build()
    }
}
