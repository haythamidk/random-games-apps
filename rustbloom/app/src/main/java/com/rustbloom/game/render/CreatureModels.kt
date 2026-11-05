package com.rustbloom.game.render

import com.rustbloom.game.engine.Mat4
import com.rustbloom.game.engine.MatClass
import com.rustbloom.game.engine.MeshBuilder
import com.rustbloom.game.engine.assignMaterials
import com.rustbloom.game.engine.MeshData
import com.rustbloom.game.game.CState
import com.rustbloom.game.game.Creature
import com.rustbloom.game.game.CreatureType
import kotlin.math.sin
import kotlin.math.min

enum class PartKind { BODY, HEAD, LEG, ARM, TAIL, WING }

class PartDef(
    val mesh: MeshData,
    val px: Float, val py: Float, val pz: Float,
    val kind: PartKind,
    val phase: Float = 0f,
    val amp: Float = 0.6f,
    val sideways: Boolean = false,
)

class CreatureModel(val parts: List<PartDef>, val specular: Float)

/** Procedural creature construction and per-frame posing. */
object CreatureModels {

    private const val METAL = 0x3b4046
    private const val PANEL = 0x6a7178
    private const val PLATE = 0xc8c3b8
    private const val RUST = 0x8a4b2a
    private const val CABLE = 0x24262a

    fun build(type: CreatureType): CreatureModel {
        classifier = when {
            type.machine -> MatClass::machine
            type == CreatureType.FISH || type == CreatureType.SHARK || type == CreatureType.BIRD -> { _, _, _ -> -1 }
            else -> MatClass::animal
        }
        return buildModel(type)
    }

    private fun buildModel(type: CreatureType): CreatureModel = when (type) {
        CreatureType.GRAZEHORN -> grazehorn()
        CreatureType.TUSKER -> tusker()
        CreatureType.STRIDER -> strider()
        CreatureType.SCUTTLER -> scuttler()
        CreatureType.PROWLER -> prowler()
        CreatureType.WARDEN -> warden(1f)
        CreatureType.COLOSSUS -> colossus()
        CreatureType.HARE -> hare()
        CreatureType.FOX -> fox()
        CreatureType.GOAT -> goat()
        CreatureType.WOLF -> wolf()
        CreatureType.BEAR -> bear()
        CreatureType.BIRD -> bird()
        CreatureType.FISH -> fish()
        CreatureType.SHARK -> shark()
        CreatureType.SKYRAY -> skyray()
        CreatureType.TIDECRAWLER -> tidecrawler()
    }

    /** Material classifier for the model being built (machines get metal, animals hide). */
    private var classifier: (Float, Float, Float) -> Int = MatClass::machine

    private inline fun part(block: MeshBuilder.() -> Unit): MeshData {
        val mb = MeshBuilder(256)
        mb.block()
        return mb.build().assignMaterials(classifier)
    }

    private fun legMesh(len: Float, r0: Float, r1: Float, color: Int, hoof: Int, flat: Boolean = false) = part {
        color(color)
        cylinder(0f, -len, 0f, r1, r0, len, 6, caps = true, flat = flat)
        color(hoof)
        box(0f, -len + 0.06f, 0.02f, r1 * 2.4f, 0.12f, r1 * 2.8f)
    }

    private fun grazehorn(): CreatureModel {
        val body = part {
            color(0x8b6a48)
            sphere(0f, 1.05f, 0f, 0.36f, 0.4f, 0.78f, 5, 8, 0.05f, 1)
            color(0xd8c7a8)
            sphere(0f, 0.92f, 0.05f, 0.28f, 0.28f, 0.6f, 4, 7, 0f, 2)
            color(0xe8e0d0)
            sphere(0f, 1.2f, -0.78f, 0.1f, 0.12f, 0.1f, 3, 5, 0f, 3)
        }
        val head = part {
            color(0x8b6a48)
            push().trs(0f, 0f, 0f, 0f, -0.6f, 0f).cylinder(0f, 0f, 0f, 0.16f, 0.12f, 0.55f, 6).pop()
            box(0f, 0.48f, 0.26f, 0.2f, 0.22f, 0.42f)
            color(0x2a2016)
            box(0.11f, 0.53f, 0.3f, 0.04f, 0.05f, 0.05f)
            box(-0.11f, 0.53f, 0.3f, 0.04f, 0.05f, 0.05f)
            color(0xcfc0a0)
            for (s in listOf(-1f, 1f)) {
                push().trs(0.08f * s, 0.6f, 0.15f, 0f, -0.5f, 0.4f * s).cylinder(0f, 0f, 0f, 0.03f, 0.015f, 0.55f, 4).pop()
                push().trs(0.18f * s, 0.85f, 0.05f, 0f, -0.2f, 0.9f * s).cylinder(0f, 0f, 0f, 0.02f, 0.01f, 0.3f, 4).pop()
            }
        }
        val leg = legMesh(0.95f, 0.07f, 0.045f, 0x7a5c3e, 0x2a2016)
        return CreatureModel(listOf(
            PartDef(body, 0f, 0f, 0f, PartKind.BODY),
            PartDef(head, 0f, 1.2f, 0.62f, PartKind.HEAD),
            PartDef(leg, 0.2f, 0.95f, 0.5f, PartKind.LEG, 0f),
            PartDef(leg, -0.2f, 0.95f, 0.5f, PartKind.LEG, 3.14f),
            PartDef(leg, 0.2f, 0.95f, -0.5f, PartKind.LEG, 3.14f),
            PartDef(leg, -0.2f, 0.95f, -0.5f, PartKind.LEG, 0f),
        ), 0.1f)
    }

    private fun tusker(): CreatureModel {
        val body = part {
            color(0x4a3a30)
            sphere(0f, 0.62f, 0f, 0.45f, 0.42f, 0.75f, 5, 8, 0.12f, 4, flat = true)
            color(0x30251f)
            sphere(0f, 0.95f, 0.15f, 0.2f, 0.12f, 0.6f, 3, 6, 0.2f, 5, flat = true)
        }
        val head = part {
            color(0x4a3a30)
            box(0f, 0f, 0.2f, 0.4f, 0.38f, 0.5f)
            color(0x6a5040)
            box(0f, -0.06f, 0.5f, 0.24f, 0.2f, 0.2f)
            color(0xe8e0c8)
            push().trs(0.14f, -0.05f, 0.48f, 0f, -1.1f, -0.3f).cylinder(0f, 0f, 0f, 0.035f, 0.01f, 0.3f, 4).pop()
            push().trs(-0.14f, -0.05f, 0.48f, 0f, -1.1f, 0.3f).cylinder(0f, 0f, 0f, 0.035f, 0.01f, 0.3f, 4).pop()
            color(0x101010)
            box(0.15f, 0.08f, 0.42f, 0.04f, 0.04f, 0.04f)
            box(-0.15f, 0.08f, 0.42f, 0.04f, 0.04f, 0.04f)
        }
        val leg = legMesh(0.45f, 0.08f, 0.06f, 0x3e3028, 0x1a1410)
        return CreatureModel(listOf(
            PartDef(body, 0f, 0f, 0f, PartKind.BODY),
            PartDef(head, 0f, 0.7f, 0.72f, PartKind.HEAD),
            PartDef(leg, 0.24f, 0.45f, 0.42f, PartKind.LEG, 0f, 0.8f),
            PartDef(leg, -0.24f, 0.45f, 0.42f, PartKind.LEG, 3.14f, 0.8f),
            PartDef(leg, 0.24f, 0.45f, -0.42f, PartKind.LEG, 3.14f, 0.8f),
            PartDef(leg, -0.24f, 0.45f, -0.42f, PartKind.LEG, 0f, 0.8f),
        ), 0.05f)
    }

    private fun strider(): CreatureModel {
        val body = part {
            color(METAL)
            box(0f, 1.65f, 0f, 0.7f, 0.55f, 1.6f)
            color(PLATE)
            box(0f, 1.98f, 0.15f, 0.76f, 0.12f, 1.1f)
            box(0f, 1.9f, -0.62f, 0.66f, 0.12f, 0.45f)
            color(RUST)
            box(0.38f, 1.62f, 0f, 0.05f, 0.35f, 1.3f)
            box(-0.38f, 1.62f, 0f, 0.05f, 0.35f, 1.3f)
            color(0x4fe3d2, 1f)
            cylinder(0f, 1.98f, -0.25f, 0.16f, 0.13f, 0.18f, 8)
            color(CABLE)
            box(0f, 1.45f, 0.6f, 0.3f, 0.3f, 0.4f)
            // tail antenna
            color(PANEL)
            push().trs(0f, 1.8f, -0.8f, 0f, -0.9f, 0f).cylinder(0f, 0f, 0f, 0.05f, 0.02f, 0.7f, 4).pop()
        }
        val head = part {
            color(METAL)
            push().trs(0f, 0f, 0f, 0f, 0.6f, 0f).box(0f, 0.3f, 0f, 0.22f, 0.7f, 0.22f).pop()
            color(PLATE)
            box(0f, 0.6f, 0.32f, 0.3f, 0.26f, 0.55f)
            color(0x3fd0ff, 1f)
            box(0f, 0.62f, 0.6f, 0.26f, 0.08f, 0.04f)
            color(PANEL)
            box(0.16f, 0.8f, 0.2f, 0.04f, 0.3f, 0.08f)
            box(-0.16f, 0.8f, 0.2f, 0.04f, 0.3f, 0.08f)
        }
        val leg = part {
            color(METAL)
            box(0f, -0.4f, 0f, 0.14f, 0.8f, 0.16f)
            color(CABLE)
            box(0f, -0.82f, -0.05f, 0.1f, 0.1f, 0.12f)
            color(PANEL)
            box(0f, -1.15f, -0.08f, 0.09f, 0.62f, 0.09f)
            color(PLATE)
            box(0f, -1.47f, 0.0f, 0.16f, 0.06f, 0.24f)
        }
        return CreatureModel(listOf(
            PartDef(body, 0f, 0f, 0f, PartKind.BODY),
            PartDef(head, 0f, 1.85f, 0.75f, PartKind.HEAD),
            PartDef(leg, 0.32f, 1.5f, 0.6f, PartKind.LEG, 0f, 0.55f),
            PartDef(leg, -0.32f, 1.5f, 0.6f, PartKind.LEG, 3.14f, 0.55f),
            PartDef(leg, 0.32f, 1.5f, -0.6f, PartKind.LEG, 3.14f, 0.55f),
            PartDef(leg, -0.32f, 1.5f, -0.6f, PartKind.LEG, 0f, 0.55f),
        ), 0.55f)
    }

    private fun scuttler(): CreatureModel {
        val body = part {
            color(METAL)
            sphere(0f, 0.55f, 0f, 0.55f, 0.22f, 0.62f, 3, 8, 0f, 0, flat = true)
            color(RUST)
            sphere(0f, 0.68f, -0.05f, 0.42f, 0.16f, 0.48f, 3, 7, 0f, 0, flat = true)
            color(0xff6a3a, 1f)
            box(0f, 0.8f, -0.2f, 0.18f, 0.08f, 0.18f)
            color(0xff4030, 1f)
            box(0.14f, 0.62f, 0.58f, 0.08f, 0.06f, 0.05f)
            box(-0.14f, 0.62f, 0.58f, 0.08f, 0.06f, 0.05f)
            color(PANEL)
            // pincers
            box(0.32f, 0.5f, 0.75f, 0.12f, 0.1f, 0.36f)
            box(-0.32f, 0.5f, 0.75f, 0.12f, 0.1f, 0.36f)
        }
        val leg = part {
            color(PANEL)
            push().trs(0f, 0f, 0f, 0f, 0f, -0.9f).box(0.3f, 0f, 0f, 0.6f, 0.07f, 0.07f).pop()
            color(METAL)
            box(0.5f, -0.3f, 0f, 0.06f, 0.5f, 0.06f)
        }
        val legR = part {
            color(PANEL)
            push().trs(0f, 0f, 0f, 0f, 0f, 0.9f).box(-0.3f, 0f, 0f, 0.6f, 0.07f, 0.07f).pop()
            color(METAL)
            box(-0.5f, -0.3f, 0f, 0.06f, 0.5f, 0.06f)
        }
        val parts = ArrayList<PartDef>()
        parts.add(PartDef(body, 0f, 0f, 0f, PartKind.BODY))
        for (i in 0 until 3) {
            val z = -0.35f + i * 0.35f
            parts.add(PartDef(leg, 0.4f, 0.55f, z, PartKind.LEG, i * 2.1f, 0.5f, sideways = true))
            parts.add(PartDef(legR, -0.4f, 0.55f, z, PartKind.LEG, i * 2.1f + 3.14f, 0.5f, sideways = true))
        }
        return CreatureModel(parts, 0.6f)
    }

    private fun prowler(): CreatureModel {
        val body = part {
            color(METAL)
            box(0f, 1.15f, 0f, 0.62f, 0.5f, 1.9f)
            color(0x2e3236)
            box(0f, 1.42f, -0.1f, 0.5f, 0.12f, 1.6f)
            color(0xff3a52, 1f)
            for (i in 0 until 4) box(0f, 1.49f, -0.6f + i * 0.4f, 0.52f, 0.03f, 0.08f)
            color(0xff3a52, 1f)
            cylinder(0f, 1.42f, -0.4f, 0.14f, 0.1f, 0.12f, 8)
            color(PANEL)
            box(0.33f, 1.05f, 0.2f, 0.06f, 0.3f, 1.1f)
            box(-0.33f, 1.05f, 0.2f, 0.06f, 0.3f, 1.1f)
        }
        val head = part {
            color(METAL)
            box(0f, 0f, 0.25f, 0.42f, 0.36f, 0.6f)
            color(PANEL)
            box(0f, -0.15f, 0.52f, 0.34f, 0.12f, 0.3f)
            color(0xff3a52, 1f)
            box(0.14f, 0.06f, 0.55f, 0.08f, 0.05f, 0.05f)
            box(-0.14f, 0.06f, 0.55f, 0.08f, 0.05f, 0.05f)
            color(PLATE)
            box(0.18f, 0.24f, 0.05f, 0.06f, 0.2f, 0.18f)
            box(-0.18f, 0.24f, 0.05f, 0.06f, 0.2f, 0.18f)
        }
        val tail = part {
            color(PANEL)
            for (i in 0 until 5) box(0f, i * 0.08f, -i * 0.28f, 0.12f - i * 0.015f, 0.12f - i * 0.015f, 0.3f)
            color(0xff3a52, 1f)
            box(0f, 0.42f, -1.45f, 0.1f, 0.1f, 0.12f)
        }
        val leg = part {
            color(METAL)
            box(0f, -0.3f, 0.05f, 0.16f, 0.6f, 0.2f)
            color(PANEL)
            box(0f, -0.8f, -0.05f, 0.12f, 0.5f, 0.12f)
            color(PLATE)
            box(0f, -1.03f, 0.06f, 0.2f, 0.06f, 0.26f)
        }
        return CreatureModel(listOf(
            PartDef(body, 0f, 0f, 0f, PartKind.BODY),
            PartDef(head, 0f, 1.3f, 1.0f, PartKind.HEAD),
            PartDef(tail, 0f, 1.25f, -0.95f, PartKind.TAIL),
            PartDef(leg, 0.3f, 1.05f, 0.75f, PartKind.LEG, 0f, 0.75f),
            PartDef(leg, -0.3f, 1.05f, 0.75f, PartKind.LEG, 3.14f, 0.75f),
            PartDef(leg, 0.3f, 1.05f, -0.75f, PartKind.LEG, 3.14f, 0.75f),
            PartDef(leg, -0.3f, 1.05f, -0.75f, PartKind.LEG, 0f, 0.75f),
        ), 0.6f)
    }

    private fun warden(s: Float): CreatureModel {
        val body = part {
            color(METAL)
            box(0f, 3.2f * s, 0f, 1.6f * s, 1.4f * s, 1.2f * s)
            color(PLATE)
            box(0f, 3.5f * s, 0.62f * s, 1.3f * s, 0.8f * s, 0.12f * s)
            color(RUST)
            box(0f, 2.55f * s, 0f, 1.2f * s, 0.25f * s, 0.9f * s)
            color(0xffb040, 1f)
            cylinder(0f, 3.25f * s, 0.62f * s, 0.22f * s, 0.22f * s, 0.1f * s, 10)
            color(PANEL)
            box(0f, 4.1f * s, -0.2f * s, 1.0f * s, 0.4f * s, 0.6f * s)
            // exhaust stacks
            color(CABLE)
            cylinder(0.45f * s, 3.6f * s, -0.55f * s, 0.12f * s, 0.12f * s, 1.0f * s, 6)
            cylinder(-0.45f * s, 3.6f * s, -0.55f * s, 0.12f * s, 0.12f * s, 1.0f * s, 6)
        }
        val head = part {
            color(PANEL)
            box(0f, 0.25f * s, 0.1f * s, 0.8f * s, 0.5f * s, 0.7f * s)
            color(0xffb040, 1f)
            box(0f, 0.28f * s, 0.46f * s, 0.6f * s, 0.12f * s, 0.04f * s)
        }
        val leg = part {
            color(METAL)
            box(0f, -0.6f * s, 0f, 0.5f * s, 1.2f * s, 0.6f * s)
            color(PANEL)
            box(0f, -1.6f * s, 0.1f * s, 0.4f * s, 1.0f * s, 0.45f * s)
            color(PLATE)
            box(0f, -2.25f * s, 0.2f * s, 0.7f * s, 0.25f * s, 1.0f * s)
        }
        val arm = part {
            color(PANEL)
            box(0f, -0.5f * s, 0f, 0.4f * s, 1.1f * s, 0.4f * s)
            color(METAL)
            push().trs(0f, -1.0f * s, 0f, 0f, 1.57f, 0f).cylinder(0f, -0.1f * s, 0f, 0.22f * s, 0.18f * s, 1.0f * s, 8).pop()
            color(0xffb040, 1f)
            box(0f, -1.0f * s, 0.92f * s, 0.18f * s, 0.18f * s, 0.05f * s)
        }
        return CreatureModel(listOf(
            PartDef(body, 0f, 0f, 0f, PartKind.BODY),
            PartDef(head, 0f, 4.3f * s, 0.3f * s, PartKind.HEAD),
            PartDef(leg, 0.6f * s, 2.45f * s, 0f, PartKind.LEG, 0f, 0.45f),
            PartDef(leg, -0.6f * s, 2.45f * s, 0f, PartKind.LEG, 3.14f, 0.45f),
            PartDef(arm, 1.05f * s, 3.7f * s, 0.1f * s, PartKind.ARM, 3.14f, 0.3f),
            PartDef(arm, -1.05f * s, 3.7f * s, 0.1f * s, PartKind.ARM, 0f, 0.3f),
        ), 0.7f)
    }

    private fun colossus(): CreatureModel {
        val body = part {
            color(METAL)
            box(0f, 8.5f, 0f, 5f, 3.4f, 7f)
            color(PLATE)
            box(0f, 10.4f, 0.5f, 4.4f, 0.6f, 5.6f)
            box(0f, 9f, 3.55f, 4f, 2.4f, 0.3f)
            color(0xff3355, 1f)
            sphere(0f, 8.2f, 3.6f, 0.9f, 0.9f, 0.5f, 4, 8, 0f, 0)
            color(RUST)
            for (i in 0 until 5) box(0f, 11f, -2.5f + i * 1.2f, 0.4f, 1.2f + i * 0.2f, 0.4f)
            color(0xff3355, 1f)
            for (i in 0 until 5) box(0f, 11.7f + i * 0.1f, -2.5f + i * 1.2f, 0.45f, 0.12f, 0.45f)
            color(CABLE)
            box(0f, 6.9f, 0f, 3.6f, 0.6f, 5.6f)
        }
        val head = part {
            color(PANEL)
            box(0f, 0.6f, 1.2f, 2.4f, 1.6f, 2.6f)
            color(PLATE)
            box(0f, 1.5f, 1.0f, 2.6f, 0.3f, 2.8f)
            color(0xff3355, 1f)
            box(0.6f, 0.75f, 2.52f, 0.5f, 0.3f, 0.05f)
            box(-0.6f, 0.75f, 2.52f, 0.5f, 0.3f, 0.05f)
            color(METAL)
            box(1.5f, 0.2f, 2.0f, 0.3f, 0.3f, 2.2f)
            box(-1.5f, 0.2f, 2.0f, 0.3f, 0.3f, 2.2f)
        }
        val leg = part {
            color(METAL)
            box(0f, -1.6f, 0f, 1.4f, 3.2f, 1.6f)
            color(PANEL)
            box(0f, -4.6f, 0.3f, 1.1f, 3.0f, 1.2f)
            color(PLATE)
            box(0f, -6.3f, 0.4f, 2.0f, 0.6f, 2.6f)
        }
        return CreatureModel(listOf(
            PartDef(body, 0f, 0f, 0f, PartKind.BODY),
            PartDef(head, 0f, 9.4f, 3.6f, PartKind.HEAD),
            PartDef(leg, 2.6f, 7.2f, 2.4f, PartKind.LEG, 0f, 0.3f),
            PartDef(leg, -2.6f, 7.2f, 2.4f, PartKind.LEG, 3.14f, 0.3f),
            PartDef(leg, 2.6f, 7.2f, -2.4f, PartKind.LEG, 3.14f, 0.3f),
            PartDef(leg, -2.6f, 7.2f, -2.4f, PartKind.LEG, 0f, 0.3f),
        ), 0.8f)
    }


    private fun quad4(body: MeshData, head: MeshData, leg: MeshData, hx: Float, hy: Float, hz: Float, lx: Float, ly: Float, lz: Float, amp: Float, tail: MeshData? = null, tz: Float = 0f, ty: Float = 0f): List<PartDef> {
        val l = ArrayList<PartDef>()
        l.add(PartDef(body, 0f, 0f, 0f, PartKind.BODY))
        l.add(PartDef(head, hx, hy, hz, PartKind.HEAD))
        l.add(PartDef(leg, lx, ly, lz, PartKind.LEG, 0f, amp))
        l.add(PartDef(leg, -lx, ly, lz, PartKind.LEG, 3.14f, amp))
        l.add(PartDef(leg, lx, ly, -lz, PartKind.LEG, 3.14f, amp))
        l.add(PartDef(leg, -lx, ly, -lz, PartKind.LEG, 0f, amp))
        if (tail != null) l.add(PartDef(tail, 0f, ty, tz, PartKind.TAIL))
        return l
    }

    private fun hare(): CreatureModel {
        val body = part {
            color(0x9c8468); sphere(0f, 0.28f, -0.02f, 0.17f, 0.17f, 0.25f, 6, 9, 0.02f, 1)
            color(0xe8e0d4); sphere(0f, 0.3f, -0.26f, 0.06f, 0.06f, 0.06f, 4, 6, 0f, 2)
        }
        val head = part {
            color(0x9c8468); sphere(0f, 0.02f, 0.06f, 0.1f, 0.09f, 0.12f, 5, 8, 0f, 3)
            push().trs(0.04f, 0.08f, 0.02f, 0f, -0.3f, -0.2f).sphere(0f, 0.12f, 0f, 0.025f, 0.11f, 0.04f, 4, 6, 0f, 4).pop()
            push().trs(-0.04f, 0.08f, 0.02f, 0f, -0.3f, 0.2f).sphere(0f, 0.12f, 0f, 0.025f, 0.11f, 0.04f, 4, 6, 0f, 5).pop()
            color(0x101010); sphere(0.07f, 0.04f, 0.1f, 0.015f, 0.015f, 0.015f, 2, 4, 0f, 6); sphere(-0.07f, 0.04f, 0.1f, 0.015f, 0.015f, 0.015f, 2, 4, 0f, 7)
        }
        val leg = legMesh(0.2f, 0.035f, 0.025f, 0x8a7458, 0x6a5a48)
        return CreatureModel(quad4(body, head, leg, 0f, 0.36f, 0.2f, 0.08f, 0.2f, 0.12f, 1.0f), 0.05f)
    }

    private fun fox(): CreatureModel {
        val body = part {
            color(0xc8642a); sphere(0f, 0.45f, 0f, 0.17f, 0.17f, 0.4f, 6, 9, 0.02f, 1)
            color(0xf0e8dc); sphere(0f, 0.38f, 0.22f, 0.11f, 0.11f, 0.15f, 4, 7, 0f, 2)
        }
        val head = part {
            color(0xc8642a); sphere(0f, 0.04f, 0.08f, 0.12f, 0.11f, 0.13f, 5, 8, 0f, 3)
            push().trs(0f, 0f, 0.2f, 0f, 1.5708f, 0f).cylinder(0f, 0f, 0f, 0.06f, 0.02f, 0.14f, 6).pop()
            color(0x1a1410); sphere(0f, 0f, 0.34f, 0.02f, 0.02f, 0.02f, 2, 4, 0f, 4)
            color(0xa04820)
            push().trs(0.06f, 0.12f, 0.04f, 0f, 0f, -0.2f).cylinder(0f, 0f, 0f, 0.045f, 0f, 0.1f, 4, flat = true).pop()
            push().trs(-0.06f, 0.12f, 0.04f, 0f, 0f, 0.2f).cylinder(0f, 0f, 0f, 0.045f, 0f, 0.1f, 4, flat = true).pop()
        }
        val leg = legMesh(0.32f, 0.035f, 0.025f, 0x3a2a20, 0x201812)
        val tail = part {
            color(0xc8642a); push().trs(0f, 0f, 0f, 0f, 0.9f, 0f).sphere(0f, 0f, -0.25f, 0.09f, 0.09f, 0.28f, 5, 8, 0f, 5).pop()
            color(0xf0e8dc); sphere(0f, -0.2f, -0.45f, 0.05f, 0.05f, 0.06f, 3, 6, 0f, 6)
        }
        return CreatureModel(quad4(body, head, leg, 0f, 0.55f, 0.38f, 0.1f, 0.33f, 0.24f, 0.9f, tail, -0.35f, 0.48f), 0.1f)
    }

    private fun goat(): CreatureModel {
        val body = part {
            color(0xd8d2c4); sphere(0f, 0.78f, 0f, 0.3f, 0.32f, 0.55f, 6, 10, 0.05f, 1)
            color(0xb8b0a0); sphere(0f, 0.66f, 0f, 0.25f, 0.2f, 0.45f, 4, 8, 0.05f, 2)
        }
        val head = part {
            color(0xd8d2c4); sphere(0f, 0.08f, 0.14f, 0.12f, 0.13f, 0.2f, 5, 8, 0f, 3)
            color(0x9a9080); sphere(0f, -0.1f, 0.24f, 0.05f, 0.1f, 0.05f, 3, 6, 0f, 4)
            color(0x5a5048)
            for (sd in listOf(-1f, 1f)) for (k in 0 until 4) {
                val a = k * 0.4f
                sphere(sd * (0.06f + k * 0.03f), 0.2f + kotlin.math.sin(a) * 0.08f, 0.06f - k * 0.06f, 0.035f - k * 0.005f, 0.035f, 0.04f, 3, 5, 0f, k)
            }
            color(0x201812); sphere(0.09f, 0.1f, 0.22f, 0.018f, 0.018f, 0.018f, 2, 4, 0f, 9); sphere(-0.09f, 0.1f, 0.22f, 0.018f, 0.018f, 0.018f, 2, 4, 0f, 10)
        }
        val leg = legMesh(0.55f, 0.055f, 0.04f, 0xc8c0b0, 0x403830)
        return CreatureModel(quad4(body, head, leg, 0f, 0.98f, 0.5f, 0.17f, 0.55f, 0.36f, 0.7f), 0.08f)
    }

    private fun wolf(): CreatureModel {
        val body = part {
            color(0x6a6a68); sphere(0f, 0.68f, 0f, 0.24f, 0.27f, 0.6f, 6, 10, 0.04f, 1)
            color(0x8a8a86); sphere(0f, 0.62f, 0.3f, 0.2f, 0.24f, 0.25f, 5, 8, 0.04f, 2)
            color(0x4a4a48); sphere(0f, 0.85f, -0.05f, 0.16f, 0.08f, 0.45f, 3, 7, 0.05f, 3)
        }
        val head = part {
            color(0x6a6a68); sphere(0f, 0.04f, 0.1f, 0.14f, 0.14f, 0.16f, 5, 8, 0f, 4)
            color(0x7a7a76); push().trs(0f, -0.02f, 0.22f, 0f, 1.5708f, 0f).cylinder(0f, 0f, 0f, 0.07f, 0.04f, 0.18f, 7).pop()
            color(0x101010); sphere(0f, -0.01f, 0.41f, 0.025f, 0.02f, 0.02f, 2, 4, 0f, 5)
            color(0xd8b040, 0.4f); sphere(0.07f, 0.07f, 0.2f, 0.018f, 0.014f, 0.01f, 2, 4, 0f, 6); sphere(-0.07f, 0.07f, 0.2f, 0.018f, 0.014f, 0.01f, 2, 4, 0f, 7)
            color(0x5a5a58)
            push().trs(0.07f, 0.14f, 0.04f, 0f, 0f, -0.15f).cylinder(0f, 0f, 0f, 0.045f, 0f, 0.11f, 4, flat = true).pop()
            push().trs(-0.07f, 0.14f, 0.04f, 0f, 0f, 0.15f).cylinder(0f, 0f, 0f, 0.045f, 0f, 0.11f, 4, flat = true).pop()
        }
        val leg = legMesh(0.5f, 0.05f, 0.035f, 0x5a5a58, 0x2a2a28)
        val tail = part { color(0x5a5a58); push().trs(0f, 0f, 0f, 0f, 0.7f, 0f).sphere(0f, 0f, -0.25f, 0.07f, 0.07f, 0.3f, 4, 7, 0f, 8).pop() }
        return CreatureModel(quad4(body, head, leg, 0f, 0.86f, 0.58f, 0.13f, 0.5f, 0.38f, 0.85f, tail, -0.55f, 0.75f), 0.1f)
    }

    private fun bear(): CreatureModel {
        val body = part {
            color(0x4a3426); sphere(0f, 1.05f, 0f, 0.6f, 0.62f, 0.95f, 7, 11, 0.05f, 1)
            sphere(0f, 1.3f, 0.35f, 0.5f, 0.45f, 0.4f, 5, 9, 0.05f, 2)
        }
        val head = part {
            color(0x4a3426); sphere(0f, 0.05f, 0.18f, 0.3f, 0.28f, 0.3f, 6, 9, 0f, 3)
            color(0x6a5040); sphere(0f, -0.04f, 0.45f, 0.13f, 0.11f, 0.15f, 4, 7, 0f, 4)
            color(0x101010); sphere(0f, -0.01f, 0.6f, 0.04f, 0.03f, 0.03f, 2, 5, 0f, 5)
            sphere(0.12f, 0.1f, 0.42f, 0.025f, 0.025f, 0.02f, 2, 4, 0f, 6); sphere(-0.12f, 0.1f, 0.42f, 0.025f, 0.025f, 0.02f, 2, 4, 0f, 7)
            color(0x3a2a20); sphere(0.2f, 0.26f, 0.1f, 0.07f, 0.07f, 0.04f, 3, 5, 0f, 8); sphere(-0.2f, 0.26f, 0.1f, 0.07f, 0.07f, 0.04f, 3, 5, 0f, 9)
        }
        val leg = legMesh(0.75f, 0.14f, 0.12f, 0x3e2c20, 0x2a2018)
        return CreatureModel(quad4(body, head, leg, 0f, 1.35f, 0.95f, 0.32f, 0.75f, 0.58f, 0.6f), 0.08f)
    }

    private fun bird(): CreatureModel {
        val body = part {
            color(0x5a4a3a); sphere(0f, 0f, 0f, 0.06f, 0.06f, 0.14f, 4, 7, 0f, 1)
            color(0xd8a050); sphere(0f, -0.02f, 0.05f, 0.05f, 0.04f, 0.07f, 3, 6, 0f, 2)
            color(0x5a4a3a); sphere(0f, 0.04f, 0.14f, 0.045f, 0.045f, 0.05f, 4, 6, 0f, 3)
            color(0xe0a020); push().trs(0f, 0.04f, 0.19f, 0f, 1.5708f, 0f).cylinder(0f, 0f, 0f, 0.012f, 0f, 0.04f, 4).pop()
            color(0x3a2e24); flatQuad(-0.03f, 0f, -0.12f, 0.03f, 0f, -0.12f, 0.05f, 0.01f, -0.24f, -0.05f, 0.01f, -0.24f)
            flatQuad(-0.05f, 0.01f, -0.24f, 0.05f, 0.01f, -0.24f, 0.03f, 0f, -0.12f, -0.03f, 0f, -0.12f)
        }
        val wingR = part { color(0x4a3c30); flatQuad(0f, 0f, 0.06f, 0.26f, 0.02f, 0.0f, 0.24f, 0.02f, -0.07f, 0f, 0f, -0.06f); flatQuad(0f, 0f, -0.06f, 0.24f, 0.02f, -0.07f, 0.26f, 0.02f, 0.0f, 0f, 0f, 0.06f) }
        val wingL = part { color(0x4a3c30); flatQuad(0f, 0f, -0.06f, -0.24f, 0.02f, -0.07f, -0.26f, 0.02f, 0.0f, 0f, 0f, 0.06f); flatQuad(0f, 0f, 0.06f, -0.26f, 0.02f, 0.0f, -0.24f, 0.02f, -0.07f, 0f, 0f, -0.06f) }
        return CreatureModel(listOf(
            PartDef(body, 0f, 0f, 0f, PartKind.BODY),
            PartDef(wingR, 0.04f, 0.02f, 0f, PartKind.WING, 0f, 0.9f),
            PartDef(wingL, -0.04f, 0.02f, 0f, PartKind.WING, 0f, 0.9f, sideways = true),
        ), 0.1f)
    }

    private fun fish(): CreatureModel {
        val body = part {
            color(0x8aa0a8); sphere(0f, 0f, 0.05f, 0.05f, 0.08f, 0.2f, 5, 8, 0f, 1)
            color(0xd8e0e0); sphere(0f, -0.025f, 0.05f, 0.04f, 0.05f, 0.17f, 4, 7, 0f, 2)
            color(0x101010); sphere(0.035f, 0.02f, 0.18f, 0.012f, 0.012f, 0.012f, 2, 4, 0f, 3); sphere(-0.035f, 0.02f, 0.18f, 0.012f, 0.012f, 0.012f, 2, 4, 0f, 4)
            color(0x6a8088); flatQuad(0f, 0.07f, 0.1f, 0f, 0.07f, -0.04f, 0f, 0.13f, -0.06f, 0f, 0.1f, 0.06f)
            flatQuad(0f, 0.1f, 0.06f, 0f, 0.13f, -0.06f, 0f, 0.07f, -0.04f, 0f, 0.07f, 0.1f)
        }
        val tail = part { color(0x7a9098)
            flatTri(0f, 0f, 0f, 0f, 0.07f, -0.12f, 0f, -0.07f, -0.12f); flatTri(0f, 0f, 0f, 0f, -0.07f, -0.12f, 0f, 0.07f, -0.12f) }
        return CreatureModel(listOf(PartDef(body, 0f, 0f, 0f, PartKind.BODY), PartDef(tail, 0f, 0f, -0.14f, PartKind.TAIL)), 0.6f)
    }

    private fun shark(): CreatureModel {
        val body = part {
            color(0x5a6670); sphere(0f, 0f, 0.2f, 0.32f, 0.36f, 1.4f, 7, 12, 0f, 1)
            color(0xd8dcdc); sphere(0f, -0.12f, 0.25f, 0.26f, 0.22f, 1.2f, 5, 10, 0f, 2)
            color(0x4a5660)
            flatQuad(0f, 0.3f, 0.5f, 0f, 0.3f, 0.0f, 0f, 0.85f, -0.15f, 0f, 0.4f, 0.4f); flatQuad(0f, 0.4f, 0.4f, 0f, 0.85f, -0.15f, 0f, 0.3f, 0.0f, 0f, 0.3f, 0.5f)
            flatQuad(0.25f, -0.1f, 0.6f, 0.7f, -0.35f, 0.2f, 0.65f, -0.3f, 0.05f, 0.25f, -0.1f, 0.3f)
            flatQuad(-0.25f, -0.1f, 0.3f, -0.65f, -0.3f, 0.05f, -0.7f, -0.35f, 0.2f, -0.25f, -0.1f, 0.6f)
            color(0x101010); sphere(0.2f, 0.05f, 1.2f, 0.04f, 0.04f, 0.04f, 2, 4, 0f, 3); sphere(-0.2f, 0.05f, 1.2f, 0.04f, 0.04f, 0.04f, 2, 4, 0f, 4)
        }
        val tail = part { color(0x4a5660)
            flatTri(0f, 0f, 0f, 0f, 0.8f, -0.7f, 0f, 0.1f, -0.45f); flatTri(0f, 0.1f, -0.45f, 0f, 0.8f, -0.7f, 0f, 0f, 0f)
            flatTri(0f, 0f, 0f, 0f, -0.1f, -0.45f, 0f, -0.5f, -0.6f); flatTri(0f, -0.5f, -0.6f, 0f, -0.1f, -0.45f, 0f, 0f, 0f)
            push().trs(0f, 0f, 0f, 0f, 0f, 0f).sphere(0f, 0f, -0.2f, 0.15f, 0.18f, 0.35f, 4, 7, 0f, 5).pop() }
        return CreatureModel(listOf(PartDef(body, 0f, 0f, 0f, PartKind.BODY), PartDef(tail, 0f, 0f, -1.1f, PartKind.TAIL)), 0.5f)
    }

    private fun skyray(): CreatureModel {
        val body = part {
            color(METAL); sphere(0f, 0f, 0f, 0.4f, 0.16f, 0.8f, 5, 9, 0f, 1)
            color(PLATE); sphere(0f, 0.08f, 0.1f, 0.3f, 0.08f, 0.55f, 4, 8, 0f, 2)
            color(0x40e0ff, 1f); sphere(0f, 0.02f, 0.7f, 0.1f, 0.06f, 0.08f, 3, 6, 0f, 3)
            color(0xff8a3c, 1f); cylinder(0f, 0.12f, -0.1f, 0.1f, 0.08f, 0.06f, 8)
            color(PANEL); push().trs(0f, 0f, -0.7f, 0f, 1.5708f, 0f).cylinder(0f, -1.2f, 0f, 0.03f, 0.05f, 1.2f, 5).pop()
        }
        val wingR = part { color(PANEL); flatQuad(0f, 0f, 0.5f, 1.4f, -0.05f, -0.1f, 1.2f, -0.05f, -0.35f, 0f, 0f, -0.4f); flatQuad(0f, 0f, -0.4f, 1.2f, -0.05f, -0.35f, 1.4f, -0.05f, -0.1f, 0f, 0f, 0.5f)
            color(0x40e0ff, 1f); box(1.1f, -0.03f, -0.15f, 0.25f, 0.02f, 0.05f) }
        val wingL = part { color(PANEL); flatQuad(0f, 0f, -0.4f, -1.2f, -0.05f, -0.35f, -1.4f, -0.05f, -0.1f, 0f, 0f, 0.5f); flatQuad(0f, 0f, 0.5f, -1.4f, -0.05f, -0.1f, -1.2f, -0.05f, -0.35f, 0f, 0f, -0.4f)
            color(0x40e0ff, 1f); box(-1.1f, -0.03f, -0.15f, 0.25f, 0.02f, 0.05f) }
        return CreatureModel(listOf(
            PartDef(body, 0f, 0f, 0f, PartKind.BODY),
            PartDef(wingR, 0.3f, 0f, 0f, PartKind.WING, 0f, 0.35f),
            PartDef(wingL, -0.3f, 0f, 0f, PartKind.WING, 0f, 0.35f, sideways = true),
        ), 0.7f)
    }

    private fun tidecrawler(): CreatureModel {
        val body = part {
            color(METAL); sphere(0f, 1.1f, 0f, 1.0f, 0.42f, 1.1f, 5, 10, 0f, 1)
            color(0x3a6a6a); sphere(0f, 1.32f, -0.1f, 0.8f, 0.28f, 0.85f, 4, 9, 0f, 2)
            color(0x40ffd0, 1f); cylinder(0f, 1.48f, -0.3f, 0.18f, 0.14f, 0.12f, 8)
            color(0xff4030, 1f); sphere(0.25f, 1.25f, 1.02f, 0.07f, 0.05f, 0.04f, 2, 5, 0f, 3); sphere(-0.25f, 1.25f, 1.02f, 0.07f, 0.05f, 0.04f, 2, 5, 0f, 4)
            color(PANEL)
            push().trs(0.75f, 1.0f, 1.0f, 0.3f, 0f, 0f).box(0f, 0f, 0.3f, 0.3f, 0.25f, 0.7f).box(0.12f, 0f, 0.75f, 0.12f, 0.2f, 0.35f).pop()
            push().trs(-0.75f, 1.0f, 1.0f, -0.3f, 0f, 0f).box(0f, 0f, 0.3f, 0.3f, 0.25f, 0.7f).box(-0.12f, 0f, 0.75f, 0.12f, 0.2f, 0.35f).pop()
        }
        val leg = part { color(PANEL); push().trs(0f, 0f, 0f, 0f, 0f, -0.8f).box(0.45f, 0f, 0f, 0.9f, 0.13f, 0.13f).pop(); color(METAL); box(0.75f, -0.5f, 0f, 0.1f, 0.9f, 0.1f) }
        val legR = part { color(PANEL); push().trs(0f, 0f, 0f, 0f, 0f, 0.8f).box(-0.45f, 0f, 0f, 0.9f, 0.13f, 0.13f).pop(); color(METAL); box(-0.75f, -0.5f, 0f, 0.1f, 0.9f, 0.1f) }
        val parts = ArrayList<PartDef>()
        parts.add(PartDef(body, 0f, 0f, 0f, PartKind.BODY))
        for (i in 0 until 3) {
            val z = -0.6f + i * 0.55f
            parts.add(PartDef(leg, 0.8f, 1.05f, z, PartKind.LEG, i * 2.1f, 0.45f, sideways = true))
            parts.add(PartDef(legR, -0.8f, 1.05f, z, PartKind.LEG, i * 2.1f + 3.14f, 0.45f, sideways = true))
        }
        return CreatureModel(parts, 0.65f)
    }

    private val world = FloatArray(16)
    private val body = FloatArray(16)

    /** Computes the model matrix for each part of a creature into [out] (16 floats per part). */
    fun pose(c: Creature, model: CreatureModel, out: Array<FloatArray>) {
        val moving = min(1f, c.speed / c.type.walkSpeed.coerceAtLeast(0.1f))
        val ph = c.walkPhase
        var pitch = 0f
        var roll = 0f
        var bob = sin(ph * 2f) * 0.035f * moving * c.type.height
        var drop = 0f
        if (c.state == CState.WINDUP) pitch = -0.18f * min(1f, c.stateTime * 3f)
        if (c.state == CState.RECOVER) pitch = 0.12f * (1f - min(1f, c.stateTime * 2f))
        if (c.type.loco == com.rustbloom.game.game.Loco.AIR || c.type.loco == com.rustbloom.game.game.Loco.WATER) bob = 0f
        if (c.state == CState.DEAD) {
            val t = min(1f, c.deadTime * 2.5f)
            roll = 1.45f * t
            drop = -c.type.height * 0.35f * t
            bob = 0f
        }
        if (c.stun > 0) roll = sin(c.stun * 30f) * 0.05f
        Mat4.fromTRS(world, c.pos.x, c.pos.y + drop, c.pos.z, c.yaw, pitch, roll, 1f, 1f, 1f)
        Mat4.mulTRS(body, world, 0f, bob, 0f, 0f, 0f, 0f, 1f, 1f, 1f)
        for (i in model.parts.indices) {
            val p = model.parts[i]
            val m = out[i]
            when (p.kind) {
                PartKind.BODY -> System.arraycopy(body, 0, m, 0, 16)
                PartKind.HEAD -> {
                    val idle = if (c.speed < 0.1f && c.alive) sin(ph * 0.8f) * 0.15f else 0f
                    val graze = if (c.state == CState.IDLE && !c.type.machine) 0.5f else 0f
                    Mat4.mulTRS(m, body, p.px, p.py, p.pz, c.headYaw + idle, graze, 0f, 1f, 1f, 1f)
                }
                PartKind.LEG -> {
                    val a = if (c.alive) sin(ph + p.phase) * p.amp * moving else 0.3f
                    if (p.sideways) Mat4.mulTRS(m, body, p.px, p.py, p.pz, a * 0.8f, 0f, 0f, 1f, 1f, 1f)
                    else Mat4.mulTRS(m, body, p.px, p.py, p.pz, 0f, a, 0f, 1f, 1f, 1f)
                }
                PartKind.ARM -> {
                    var a = sin(ph + p.phase) * p.amp * moving
                    if (c.state == CState.WINDUP) a = -0.9f
                    Mat4.mulTRS(m, body, p.px, p.py, p.pz, 0f, a, 0f, 1f, 1f, 1f)
                }
                PartKind.TAIL -> {
                    val swim = c.type.loco == com.rustbloom.game.game.Loco.WATER
                    val a = if (swim) sin(ph * 1.5f) * 0.45f else sin(ph * 0.7f) * 0.4f
                    Mat4.mulTRS(m, body, p.px, p.py, p.pz, a, if (swim) 0f else 0.1f, 0f, 1f, 1f, 1f)
                }
                PartKind.WING -> {
                    val flap = if (c.alive) sin(ph) * p.amp + (if (c.diving) -0.3f else 0.1f) else 0.6f
                    Mat4.mulTRS(m, body, p.px, p.py, p.pz, 0f, 0f, if (p.sideways) -flap else flap, 1f, 1f, 1f)
                }
            }
        }
    }
}
