package com.worldwake.game.render

import com.worldwake.game.engine.Mat
import com.worldwake.game.engine.MeshBuilder
import com.worldwake.game.engine.MeshData
import com.worldwake.game.engine.PI_F
import com.worldwake.game.engine.TWO_PI
import com.worldwake.game.engine.beam
import com.worldwake.game.engine.capsule
import com.worldwake.game.engine.dome
import com.worldwake.game.engine.softBox
import com.worldwake.game.engine.torus
import com.worldwake.game.engine.tube
import com.worldwake.game.game.BuildType
import com.worldwake.game.game.POD_CELL
import com.worldwake.game.game.POD_FLOOR
import com.worldwake.game.game.POD_HEIGHT
import com.worldwake.game.game.RAMP_LEN
import kotlin.math.cos
import kotlin.math.sin

/**
 * A buildable's geometry. [spin] turns around the vertical axis at the model origin, or around the local X axis
 * at height [spinY] when [spinAxisX] is set. [glass] is drawn blended after everything opaque.
 */
class ModelSet(
    val solid: MeshData,
    val glow: MeshData?,
    val spin: MeshData?,
    val glass: MeshData?,
    val spinY: Float = 0f,
    val spinSpeed: Float = 1f,
    val spinAxisX: Boolean = false,
)

object Models {
    private class B {
        val s = MeshBuilder(1024); val g = MeshBuilder(); val sp = MeshBuilder(); val gl = MeshBuilder()
        var spinY = 0f; var spinSpeed = 1f; var axisX = false
        fun set() = ModelSet(s.buildClean(), if (g.vertexCount > 0) g.buildClean() else null, if (sp.vertexCount > 0) sp.buildClean() else null,
            if (gl.vertexCount > 0) gl.build() else null, spinY, spinSpeed, axisX)
    }

    private fun hull(b: MeshBuilder) = b.color(0.9f, 0.91f, 0.95f).mat(Mat.HULL)
    private fun dark(b: MeshBuilder) = b.color(0.32f, 0.34f, 0.4f).mat(Mat.METAL)
    private fun steel(b: MeshBuilder) = b.color(0.62f, 0.65f, 0.72f).mat(Mat.METAL)
    private fun warm(b: MeshBuilder) = b.color(0.95f, 0.47f, 0.38f).mat(Mat.HULL)
    private fun blue(b: MeshBuilder) = b.color(0.4f, 0.52f, 0.86f).mat(Mat.HULL)
    private fun teal(b: MeshBuilder) = b.color(0.22f, 0.74f, 0.7f).mat(Mat.HULL)
    private fun rose(b: MeshBuilder) = b.color(0.9f, 0.32f, 0.58f).mat(Mat.HULL)
    private fun gold(b: MeshBuilder) = b.color(0.96f, 0.8f, 0.3f).mat(Mat.HULL)
    private fun glassTint(b: MeshBuilder) = b.color(0.6f, 0.9f, 0.95f)

    /** Round footing that sinks a little into the ground so machines sit well on slopes. */
    private fun footing(b: MeshBuilder, r: Float, h: Float = 0.3f, seg: Int = 14) {
        dark(b); b.cylinder(0f, -0.7f, 0f, r * 1.05f, r, 0.7f + h, seg, caps = true)
    }

    private fun legs(b: MeshBuilder, n: Int, rTop: Float, rFoot: Float, yTop: Float, w: Float = 0.1f) {
        for (i in 0 until n) {
            val a = i * TWO_PI / n + 0.4f
            dark(b)
            b.beam(cos(a) * rTop, yTop, sin(a) * rTop, cos(a) * rFoot, -0.3f, sin(a) * rFoot, w)
            b.cylinder(cos(a) * rFoot, -0.5f, sin(a) * rFoot, w * 2.2f, w * 1.8f, 0.6f, 8)
        }
    }

    fun build(t: BuildType): ModelSet {
        val m = B()
        val s = m.s; val g = m.g; val sp = m.sp; val gl = m.gl
        when (t) {
            BuildType.POD -> { /* pods are assembled from PodParts */ s.box(0f, 0.1f, 0f, 0.1f, 0.1f, 0.1f) }

            BuildType.FABRICATOR -> {
                dark(s); s.box(0f, 0.45f, 0f, 1.5f, 0.9f, 0.8f)
                hull(s); s.box(0f, 0.95f, 0f, 1.6f, 0.1f, 0.9f)
                s.box(0f, 1.35f, -0.36f, 1.5f, 0.75f, 0.1f)
                teal(s); s.box(0f, 0.3f, 0.41f, 1.3f, 0.12f, 0.02f)
                steel(s); s.cylinder(0.45f, 1f, 0.05f, 0.24f, 0.24f, 0.05f, 12)
                g.color(0.35f, 1f, 0.95f, 1.2f); g.box(-0.3f, 1.4f, -0.3f, 0.7f, 0.42f, 0.02f)
                g.color(0.35f, 1f, 0.95f, 0.8f); g.torus(0.45f, 1.07f, 0.05f, 0.2f, 0.012f, 16, 4)
                // the print head hovers and turns over the plate
                m.spinSpeed = 1.6f
                sp.color(0.35f, 1f, 0.95f, 1.4f)
                sp.push().trs(0.45f, 0f, 0.05f); sp.torus(0f, 1.32f, 0f, 0.16f, 0.015f, 14, 4); sp.box(0.16f, 1.25f, 0f, 0.03f, 0.14f, 0.03f); sp.pop()
            }
            BuildType.LOCKER -> {
                hull(s); s.box(0f, 1f, 0f, 1.0f, 2f, 0.6f)
                dark(s); s.box(0f, 1f, 0.31f, 0.02f, 1.8f, 0.01f); s.box(0f, 0.04f, 0f, 1.04f, 0.08f, 0.64f); s.box(0f, 1.98f, 0f, 1.04f, 0.06f, 0.64f)
                steel(s); s.box(-0.12f, 1.05f, 0.32f, 0.04f, 0.3f, 0.03f); s.box(0.12f, 1.05f, 0.32f, 0.04f, 0.3f, 0.03f)
                gold(s); s.box(0f, 1.75f, 0.305f, 0.9f, 0.12f, 0.01f)
                g.color(0.35f, 1f, 0.95f, 1f); g.box(0.36f, 1.5f, 0.31f, 0.06f, 0.03f, 0.01f)
            }
            BuildType.PLANTER -> {
                hull(s); s.box(0f, 0.35f, 0f, 1.5f, 0.5f, 0.8f)
                dark(s); s.box(0f, 0.08f, 0f, 1.4f, 0.16f, 0.7f)
                s.color(0.3f, 0.2f, 0.26f).mat(Mat.SOIL); s.box(0f, 0.6f, 0f, 1.36f, 0.06f, 0.66f)
                steel(s); s.cylinder(-0.7f, 0.6f, -0.34f, 0.025f, 0.025f, 0.85f, 5); s.cylinder(0.7f, 0.6f, -0.34f, 0.025f, 0.025f, 0.85f, 5)
                hull(s); s.box(0f, 1.48f, -0.2f, 1.5f, 0.06f, 0.36f)
                g.color(1f, 0.6f, 0.9f, 1.1f); g.box(0f, 1.44f, -0.2f, 1.36f, 0.02f, 0.26f)
                for (i in 0 until 4) {
                    val x = -0.5f + i * 0.33f
                    s.color(0.5f, 0.24f, 0.36f); s.cylinder(x, 0.62f, 0.02f, 0.015f, 0.01f, 0.3f, 4, caps = false)
                    s.color(0.86f, 0.3f, 0.45f).mat(Mat.LEAVES); s.sphere(x, 0.95f, 0.02f, 0.17f, 0.12f, 0.17f, 4, 6, 0.2f, i)
                    g.color(1f, 0.5f, 0.6f, 0.9f); g.sphere(x + 0.06f, 0.78f, 0.12f, 0.07f, 0.085f, 0.07f, 4, 6)
                }
            }

            BuildType.WIND -> {
                footing(s, 0.5f); legs(s, 3, 0.12f, 0.75f, 1.3f, 0.07f)
                hull(s); s.cylinder(0f, 0.3f, 0f, 0.13f, 0.09f, 3.0f, 8)
                gold(s); s.cylinder(0f, 3.2f, 0f, 0.17f, 0.17f, 0.22f, 10)
                dark(s); s.cylinder(0f, 3.4f, 0f, 0.035f, 0.035f, 1.7f, 6)
                g.color(1f, 0.85f, 0.35f, 1.2f); g.sphere(0f, 5.14f, 0f, 0.06f, 0.06f, 0.06f, 4, 6)
                // three twisted vanes around the mast
                m.spinSpeed = 2.4f
                for (k in 0 until 3) {
                    val a0 = k * TWO_PI / 3f
                    val segs = 7
                    for (i in 0 until segs) {
                        val t0 = i.toFloat() / segs; val t1 = (i + 1f) / segs
                        val aA = a0 + t0 * 2.1f; val aB = a0 + t1 * 2.1f
                        val rA = 0.16f + sin(t0 * PI_F) * 0.5f; val rB = 0.16f + sin(t1 * PI_F) * 0.5f
                        val yA = 3.5f + t0 * 1.5f; val yB = 3.5f + t1 * 1.5f
                        sp.color(0.92f, 0.93f, 0.97f)
                        sp.flatQuad(cos(aA) * 0.05f, yA, sin(aA) * 0.05f, cos(aA) * rA, yA, sin(aA) * rA, cos(aB) * rB, yB, sin(aB) * rB, cos(aB) * 0.05f, yB, sin(aB) * 0.05f)
                        sp.flatQuad(cos(aB) * 0.05f, yB, sin(aB) * 0.05f, cos(aB) * rB, yB, sin(aB) * rB, cos(aA) * rA, yA, sin(aA) * rA, cos(aA) * 0.05f, yA, sin(aA) * 0.05f)
                    }
                }
            }
            BuildType.SOLAR -> {
                footing(s, 0.6f)
                hull(s); s.cylinder(0f, 0.3f, 0f, 0.18f, 0.12f, 1.5f, 8)
                gold(s); s.sphere(0f, 1.9f, 0f, 0.26f, 0.2f, 0.26f, 5, 10)
                g.color(1f, 0.85f, 0.35f, 1f); g.sphere(0f, 2.06f, 0f, 0.1f, 0.06f, 0.1f, 4, 8)
                for (i in 0 until 5) {
                    val a = i * TWO_PI / 5f
                    s.push().trs(0f, 1.9f, 0f, a, 0f, 0.55f)
                    dark(s); s.box(0.3f, 0f, 0f, 0.5f, 0.05f, 0.08f)
                    hull(s); s.box(1.25f, 0f, 0f, 1.5f, 0.05f, 1.0f)
                    s.color(0.1f, 0.14f, 0.45f).mat(Mat.METAL); s.box(1.25f, 0.035f, 0f, 1.38f, 0.02f, 0.88f)
                    s.pop()
                }
            }
            BuildType.THERMAL -> {
                footing(s, 1.5f, 0.4f, 18)
                hull(s); s.cylinder(0f, 0.4f, 0f, 1.25f, 0.85f, 1.3f, 16, caps = false)
                gold(s); s.cylinder(0f, 1.7f, 0f, 0.87f, 0.87f, 0.25f, 16, caps = false)
                hull(s); s.cylinder(0f, 1.95f, 0f, 0.8f, 0.55f, 1.2f, 14, caps = false)
                dark(s); for (i in 0 until 5) s.torus(0f, 2.1f + i * 0.22f, 0f, 0.78f - i * 0.05f, 0.05f, 14, 5)
                dark(s); s.cylinder(0f, 3.15f, 0f, 0.55f, 0.62f, 0.5f, 14, caps = false); s.disc(0f, 3.3f, 0f, 0.56f, 14, true)
                for (i in 0 until 3) {
                    val a = i * TWO_PI / 3f
                    steel(s); s.beam(cos(a) * 1.1f, 1.0f, sin(a) * 1.1f, cos(a) * 1.75f, 0.9f, sin(a) * 1.75f, 0.2f)
                    s.cylinder(cos(a) * 1.75f, -0.6f, sin(a) * 1.75f, 0.14f, 0.14f, 1.6f, 8)
                }
                g.color(1f, 0.5f, 0.3f, 1.3f); g.torus(0f, 1.55f, 0f, 1.0f, 0.04f, 18, 4); g.disc(0f, 3.32f, 0f, 0.4f, 12, true)
            }
            BuildType.FUSION -> {
                footing(s, 2f, 0.4f, 18)
                hull(s); s.cylinder(0f, 0.4f, 0f, 1.7f, 1.1f, 0.9f, 16, caps = true)
                for (i in 0 until 4) {
                    val a = i * TWO_PI / 4f + 0.785f
                    hull(s); s.beam(cos(a) * 1.9f, 0.2f, sin(a) * 1.9f, cos(a) * 1.25f, 3.0f, sin(a) * 1.25f, 0.28f)
                    gold(s); s.sphere(cos(a) * 1.25f, 3.05f, sin(a) * 1.25f, 0.2f, 0.2f, 0.2f, 5, 8)
                }
                dark(s); s.cylinder(0f, 1.3f, 0f, 0.5f, 0.3f, 0.6f, 10)
                g.color(1f, 0.75f, 0.9f, 2.2f); g.sphere(0f, 3f, 0f, 0.7f, 0.7f, 0.7f, 8, 12)
                gl.color(1f, 0.8f, 0.95f); gl.sphere(0f, 3f, 0f, 1.0f, 1.0f, 1.0f, 8, 14)
                m.spinSpeed = 1.3f
                steel(sp); sp.torus(0f, 3f, 0f, 1.35f, 0.07f, 22, 6)
                sp.push().trs(0f, 3f, 0f, 0f, PI_F * 0.5f, 0f); sp.torus(0f, 0f, 0f, 1.5f, 0.06f, 22, 6); sp.pop()
                sp.push().trs(0f, 3f, 0f, 0.78f, PI_F * 0.5f, 0f); sp.torus(0f, 0f, 0f, 1.65f, 0.05f, 22, 6); sp.pop()
            }

            BuildType.HEATER_1 -> {
                footing(s, 0.55f)
                hull(s); s.cylinder(0f, 0.3f, 0f, 0.5f, 0.38f, 0.45f, 12)
                warm(s); s.cylinder(0f, 0.75f, 0f, 0.4f, 0.4f, 0.1f, 12)
                for (i in 0 until 3) { val a = i * TWO_PI / 3f; dark(s); s.cylinder(cos(a) * 0.36f, 0.8f, sin(a) * 0.36f, 0.025f, 0.025f, 1.1f, 5) }
                hull(s); s.cylinder(0f, 1.9f, 0f, 0.42f, 0.3f, 0.16f, 12)
                g.color(1f, 0.45f, 0.2f, 1.6f)
                for (i in 0 until 6) g.torus(0f, 0.95f + i * 0.16f, 0f, 0.22f, 0.04f, 12, 5)
            }
            BuildType.HEATER_2 -> {
                footing(s, 0.95f)
                hull(s); s.cylinder(0f, 0.3f, 0f, 0.9f, 0.8f, 1.5f, 14, caps = false)
                warm(s); s.cylinder(0f, 1.8f, 0f, 0.82f, 0.82f, 0.18f, 14, caps = false)
                hull(s); s.cylinder(0f, 1.98f, 0f, 0.8f, 0.4f, 0.6f, 14, caps = false)
                dark(s); s.cylinder(0f, 2.58f, 0f, 0.4f, 0.32f, 1.5f, 10, caps = false); s.torus(0f, 4.05f, 0f, 0.34f, 0.06f, 10, 5)
                steel(s); s.capsule(1.05f, 0.2f, 0.2f, 0.26f, 1.5f, 8); s.beam(1.05f, 1.4f, 0.2f, 0.75f, 1.6f, 0.1f, 0.08f)
                g.color(1f, 0.5f, 0.2f, 1.5f)
                for (i in 0 until 6) { val a = i * TWO_PI / 6f; g.push().trs(0f, 0f, 0f, a); g.box(0f, 1.0f, 0.86f, 0.14f, 0.7f, 0.03f); g.pop() }
                g.disc(0f, 4.02f, 0f, 0.26f, 10, true)
            }
            BuildType.HEATER_3 -> {
                footing(s, 1.2f, 0.3f, 16)
                legs(s, 3, 0.3f, 1.5f, 4.2f, 0.14f)
                hull(s); s.cylinder(0f, 0.3f, 0f, 0.7f, 0.55f, 0.7f, 12)
                dark(s); for (i in 0 until 4) s.torus(0f, 1.3f + i * 1.0f, 0f, 0.34f, 0.07f, 12, 5)
                warm(s); s.cylinder(0f, 4.4f, 0f, 0.36f, 0.75f, 0.7f, 12, caps = false)
                hull(s); s.torus(0f, 5.1f, 0f, 0.75f, 0.07f, 14, 5)
                g.color(1f, 0.42f, 0.16f, 1.8f); g.cylinder(0f, 1.0f, 0f, 0.2f, 0.2f, 3.5f, 10, caps = false)
                g.color(1f, 0.6f, 0.25f, 1.6f); g.disc(0f, 4.95f, 0f, 0.66f, 14, true)
            }
            BuildType.HEATER_4 -> {
                footing(s, 1.7f, 0.3f, 18)
                hull(s); s.cylinder(0f, 0.3f, 0f, 1.3f, 0.9f, 0.9f, 14)
                dark(s); s.cylinder(0f, 1.2f, 0f, 0.4f, 0.3f, 1.6f, 10)
                steel(s); s.beam(-1.3f, 2.8f, 0f, 1.3f, 2.8f, 0f, 0.2f); s.beam(-1.3f, 2.8f, 0f, -1.3f, 4.2f, 0f, 0.16f); s.beam(1.3f, 2.8f, 0f, 1.3f, 4.2f, 0f, 0.16f)
                // the dish, tipped back to catch the sun
                s.push().trs(0f, 4.3f, 0f, 0f, -0.7f, 0f)
                hull(s); s.push().trs(0f, 0f, 0f, 0f, PI_F, 0f); s.dome(0f, -1.1f, 0f, 2.3f, 1.1f, 5, 18); s.pop()
                s.color(0.7f, 0.8f, 0.9f).mat(Mat.CRYSTAL); s.cylinder(0f, 0.05f, 0f, 2.25f, 0.2f, 1.0f, 18, caps = false)
                dark(s); for (i in 0 until 3) { val a = i * TWO_PI / 3f; s.beam(cos(a) * 2.1f, 0.1f, sin(a) * 2.1f, 0f, 2.0f, 0f, 0.06f) }
                warm(s); s.sphere(0f, 2.0f, 0f, 0.2f, 0.2f, 0.2f, 5, 8)
                s.pop()
                g.push().trs(0f, 4.3f, 0f, 0f, -0.7f, 0f); g.color(1f, 0.75f, 0.4f, 2f); g.sphere(0f, 1.9f, 0f, 0.13f, 0.13f, 0.13f, 5, 8); g.pop()
                g.color(1f, 0.5f, 0.3f, 1.2f); g.torus(0f, 1.1f, 0f, 0.95f, 0.04f, 16, 4)
            }
            BuildType.HEATER_5 -> {
                footing(s, 2.6f, 0.3f, 22)
                hull(s); s.cylinder(0f, 0.3f, 0f, 2.4f, 2.0f, 0.6f, 20, caps = false)
                dark(s); s.cylinder(0f, 0.9f, 0f, 2.0f, 1.4f, 0.01f, 20, caps = false); s.cylinder(0f, 0.35f, 0f, 1.4f, 1.4f, 0.55f, 20, caps = true)
                for (i in 0 until 6) {
                    val a = i * TWO_PI / 6f
                    hull(s); s.beam(cos(a) * 2.2f, 0.6f, sin(a) * 2.2f, cos(a) * 1.7f, 6.4f, sin(a) * 1.7f, 0.3f)
                    warm(s); s.beam(cos(a) * 1.7f, 6.4f, sin(a) * 1.7f, cos(a) * 0.9f, 7.8f, sin(a) * 0.9f, 0.2f)
                    g.color(1f, 0.6f, 0.3f, 1.5f); g.sphere(cos(a) * 0.9f, 7.85f, sin(a) * 0.9f, 0.12f, 0.12f, 0.12f, 4, 6)
                }
                g.color(1f, 0.72f, 0.4f, 2.6f); g.sphere(0f, 4.6f, 0f, 1.1f, 1.1f, 1.1f, 9, 14)
                gl.color(1f, 0.8f, 0.6f); gl.sphere(0f, 4.6f, 0f, 1.5f, 1.5f, 1.5f, 8, 14)
                m.spinSpeed = 0.7f
                steel(sp); sp.torus(0f, 4.6f, 0f, 2.0f, 0.09f, 26, 6)
                sp.push().trs(0f, 4.6f, 0f, 0f, 1.1f, 0f); sp.torus(0f, 0f, 0f, 2.2f, 0.07f, 26, 6); sp.pop()
            }

            BuildType.DRILL_1 -> {
                footing(s, 0.6f)
                legs(s, 3, 0.16f, 0.85f, 2.6f, 0.08f)
                blue(s); s.cylinder(0f, 2.5f, 0f, 0.28f, 0.22f, 0.4f, 10)
                hull(s); s.cylinder(0f, 0.3f, 0f, 0.36f, 0.3f, 0.35f, 10)
                steel(s); s.cylinder(0.3f, 2.6f, 0f, 0.07f, 0.09f, 0.7f, 6)
                g.color(0.6f, 0.8f, 1f, 1.2f); g.torus(0f, 2.72f, 0f, 0.25f, 0.025f, 12, 4)
                m.spinSpeed = 5f
                dark(sp); sp.cylinder(0f, -0.4f, 0f, 0.06f, 0.06f, 3f, 6)
                steel(sp); for (i in 0 until 7) sp.box(0f, 0.2f + i * 0.3f, 0f, 0.24f, 0.05f, 0.07f)
            }
            BuildType.DRILL_2 -> {
                footing(s, 0.9f)
                for (i in 0 until 4) {
                    val a = i * TWO_PI / 4f + 0.785f; val b = (i + 1) * TWO_PI / 4f + 0.785f
                    dark(s)
                    s.beam(cos(a) * 1.1f, -0.2f, sin(a) * 1.1f, cos(a) * 0.35f, 4.2f, sin(a) * 0.35f, 0.1f)
                    for (k in 0 until 3) {
                        val t0 = k / 3f; val t1 = (k + 1) / 3f
                        val r0 = 1.1f - 0.75f * t0; val r1 = 1.1f - 0.75f * t1
                        s.beam(cos(a) * r0, 4.4f * t0, sin(a) * r0, cos(b) * r1, 4.4f * t1, sin(b) * r1, 0.05f)
                    }
                }
                blue(s); s.cylinder(0f, 4.1f, 0f, 0.45f, 0.36f, 0.45f, 10)
                hull(s); s.cylinder(0f, 0.3f, 0f, 0.55f, 0.45f, 0.5f, 12)
                steel(s); s.cylinder(0.75f, 0f, 0.5f, 0.16f, 0.12f, 2.4f, 8); s.torus(0.75f, 2.4f, 0.5f, 0.14f, 0.04f, 8, 4)
                g.color(0.6f, 0.8f, 1f, 1.2f); g.torus(0f, 4.35f, 0f, 0.42f, 0.03f, 14, 4)
                m.spinSpeed = 4f
                dark(sp); sp.cylinder(0f, -0.4f, 0f, 0.09f, 0.09f, 4.6f, 6)
                steel(sp); for (i in 0 until 10) sp.box(0f, 0.5f + i * 0.33f, 0f, 0.36f, 0.06f, 0.09f)
            }
            BuildType.DRILL_3 -> {
                footing(s, 1.3f, 0.3f, 16)
                hull(s); s.cylinder(0f, 0.3f, 0f, 1.15f, 0.9f, 1.2f, 14, caps = false)
                blue(s); s.cylinder(0f, 1.5f, 0f, 0.92f, 0.92f, 0.2f, 14, caps = false)
                for (i in 0 until 7) { steel(s); s.torus(0f, 1.95f + i * 0.45f, 0f, 0.72f - i * 0.03f, 0.2f, 14, 6) }
                hull(s); s.cylinder(0f, 5.0f, 0f, 0.5f, 0.75f, 0.7f, 12, caps = false)
                dark(s); s.disc(0f, 5.3f, 0f, 0.6f, 12, true)
                for (i in 0 until 2) { val x = if (i == 0) 1.2f else -1.2f; steel(s); s.capsule(x, 0.1f, 0.3f, 0.3f, 1.6f, 8) }
                g.color(0.55f, 0.8f, 1f, 1.3f); g.torus(0f, 1.72f, 0f, 0.94f, 0.03f, 16, 4); g.torus(0f, 5.72f, 0f, 0.74f, 0.03f, 14, 4)
            }
            BuildType.DRILL_4 -> {
                footing(s, 1.9f, 0.3f, 18)
                for (i in 0 until 4) {
                    val a = i * TWO_PI / 4f + 0.785f
                    hull(s); s.beam(cos(a) * 2.0f, -0.2f, sin(a) * 2.0f, cos(a) * 0.8f, 6.6f, sin(a) * 0.8f, 0.3f)
                    dark(s); s.beam(cos(a) * 1.6f, 2.2f, sin(a) * 1.6f, cos(a + 1.57f) * 1.6f, 2.2f, sin(a + 1.57f) * 1.6f, 0.12f)
                    s.beam(cos(a) * 1.1f, 4.8f, sin(a) * 1.1f, cos(a + 1.57f) * 1.1f, 4.8f, sin(a + 1.57f) * 1.1f, 0.1f)
                }
                blue(s); s.cylinder(0f, 6.4f, 0f, 1.0f, 0.8f, 0.9f, 12)
                hull(s); s.cylinder(0f, 0.3f, 0f, 1.0f, 0.8f, 0.9f, 14)
                steel(s); s.cylinder(0f, 7.3f, 0f, 0.3f, 0.36f, 0.8f, 8)
                g.color(0.55f, 0.8f, 1f, 1.4f); g.torus(0f, 6.85f, 0f, 0.95f, 0.04f, 16, 4); g.torus(0f, 1.3f, 0f, 0.85f, 0.04f, 16, 4)
                m.spinSpeed = 3f
                dark(sp); sp.cylinder(0f, 0.5f, 0f, 0.22f, 0.22f, 6f, 8)
                steel(sp); for (i in 0 until 12) sp.box(0f, 1.3f + i * 0.4f, 0f, 0.8f, 0.1f, 0.14f)
            }
            BuildType.DRILL_5 -> {
                footing(s, 2.6f, 0.3f, 22)
                hull(s); s.cylinder(0f, 0.3f, 0f, 2.3f, 1.3f, 3.2f, 18, caps = false)
                blue(s); s.cylinder(0f, 3.5f, 0f, 1.32f, 1.32f, 0.3f, 18, caps = false)
                hull(s); s.cylinder(0f, 3.8f, 0f, 1.25f, 0.8f, 3.6f, 16, caps = false)
                dark(s); for (i in 0 until 4) s.torus(0f, 4.3f + i * 0.8f, 0f, 1.2f - i * 0.1f, 0.08f, 16, 5)
                for (i in 0 until 3) {
                    val a = i * TWO_PI / 3f
                    s.push().trs(0f, 7.3f, 0f, a, 0f, -0.6f)
                    steel(s); s.cylinder(0.3f, 0f, 0f, 0.3f, 0.6f, 2.4f, 10, caps = false)
                    s.pop()
                }
                g.color(0.6f, 0.82f, 1f, 1.5f); g.torus(0f, 3.66f, 0f, 1.35f, 0.04f, 20, 4); g.disc(0f, 7.42f, 0f, 0.7f, 14, true)
                m.spinSpeed = 1.2f
                steel(sp); sp.torus(0f, 8.6f, 0f, 2.1f, 0.08f, 24, 6)
                for (i in 0 until 3) { val a = i * TWO_PI / 3f + 1f; sp.beam(0f, 8.2f, 0f, cos(a) * 2.1f, 8.6f, sin(a) * 2.1f, 0.07f) }
            }

            BuildType.OXY_1 -> {
                footing(s, 0.65f)
                hull(s); s.cylinder(0f, 0.3f, 0f, 0.62f, 0.55f, 0.3f, 12)
                teal(s); s.cylinder(0f, 0.6f, 0f, 0.5f, 0.5f, 0.08f, 12)
                hull(s); s.cylinder(0f, 1.75f, 0f, 0.5f, 0.42f, 0.2f, 12)
                steel(s); s.cylinder(0f, 1.95f, 0f, 0.1f, 0.1f, 0.25f, 6); s.beam(0.5f, 0.5f, 0.3f, 0.5f, 1.8f, 0.3f, 0.05f)
                // red spores drifting in meltwater
                s.color(0.82f, 0.2f, 0.36f, 0.25f); s.cylinder(0f, 0.68f, 0f, 0.4f, 0.4f, 0.85f, 12)
                g.color(1f, 0.4f, 0.55f, 0.9f)
                for (i in 0 until 7) { val a = i * 2.4f; g.sphere(cos(a) * 0.22f, 0.8f + (i % 4) * 0.2f, sin(a) * 0.22f, 0.05f, 0.05f, 0.05f, 3, 5) }
                glassTint(gl); gl.cylinder(0f, 0.68f, 0f, 0.47f, 0.47f, 1.07f, 14, caps = false)
            }
            BuildType.OXY_2 -> {
                footing(s, 0.85f)
                hull(s); s.cylinder(0f, 0.3f, 0f, 0.8f, 0.6f, 0.5f, 12)
                teal(s); s.cylinder(0f, 0.8f, 0f, 0.56f, 0.56f, 0.1f, 12)
                hull(s); s.cylinder(0f, 3.9f, 0f, 0.56f, 0.5f, 0.25f, 12)
                dark(s); s.cylinder(0f, 4.15f, 0f, 0.5f, 0.5f, 0.2f, 12, caps = false)
                s.color(0.78f, 0.16f, 0.32f, 0.25f); s.cylinder(0f, 0.9f, 0f, 0.42f, 0.42f, 2.6f, 12)
                g.color(1f, 0.42f, 0.55f, 0.9f)
                for (i in 0 until 16) { val a = i * 0.9f; g.sphere(cos(a) * 0.26f, 1.0f + i * 0.16f, sin(a) * 0.26f, 0.055f, 0.055f, 0.055f, 3, 5) }
                glassTint(gl); gl.cylinder(0f, 0.9f, 0f, 0.5f, 0.5f, 3.0f, 14, caps = false)
                for (i in 0 until 3) { val a = i * TWO_PI / 3f; steel(s); s.beam(cos(a) * 0.56f, 0.9f, sin(a) * 0.56f, cos(a) * 0.56f, 3.9f, sin(a) * 0.56f, 0.05f) }
                m.spinSpeed = 3f
                steel(sp); for (i in 0 until 4) { sp.push().trs(0f, 4.3f, 0f, i * TWO_PI / 4f, 0f, 0.3f); sp.box(0.26f, 0f, 0f, 0.44f, 0.02f, 0.16f); sp.pop() }
            }
            BuildType.OXY_3 -> {
                footing(s, 1.4f, 0.3f, 16)
                hull(s); s.cylinder(0f, 0.3f, 0f, 1.3f, 0.9f, 0.7f, 14)
                teal(s); s.cylinder(0f, 1.0f, 0f, 0.6f, 0.4f, 2.6f, 10)
                gold(s); s.sphere(0f, 3.8f, 0f, 0.4f, 0.3f, 0.4f, 5, 10)
                g.color(0.5f, 1f, 0.95f, 1.8f); g.sphere(0f, 4.15f, 0f, 0.22f, 0.22f, 0.22f, 5, 8)
                for (i in 0 until 3) {
                    val a = i * TWO_PI / 3f
                    val x = cos(a) * 1.25f; val z = sin(a) * 1.25f
                    steel(s); s.beam(cos(a) * 0.5f, 1.6f, sin(a) * 0.5f, x, 1.5f, z, 0.14f)
                    hull(s); s.cylinder(x, 1.2f, z, 0.6f, 0.6f, 0.3f, 12)
                    s.color(0.3f, 0.2f, 0.26f).mat(Mat.SOIL); s.disc(x, 1.52f, z, 0.55f, 12, true)
                    s.color(0.88f, 0.26f, 0.42f).mat(Mat.LEAVES); s.sphere(x, 1.95f, z, 0.42f, 0.4f, 0.42f, 5, 8, 0.2f, i)
                    g.color(1f, 0.55f, 0.65f, 0.9f); g.sphere(x + 0.2f, 2.25f, z, 0.07f, 0.07f, 0.07f, 3, 5)
                    glassTint(gl); gl.dome(x, 1.5f, z, 0.62f, 1.05f, 5, 14)
                }
            }
            BuildType.OXY_4 -> {
                footing(s, 2.2f, 0.3f, 20)
                hull(s); s.cylinder(0f, 0.3f, 0f, 1.9f, 0.9f, 1.4f, 16, caps = false)
                teal(s); s.cylinder(0f, 1.7f, 0f, 0.92f, 0.92f, 0.25f, 16, caps = false)
                hull(s); s.cylinder(0f, 1.95f, 0f, 0.85f, 0.45f, 4.6f, 12, caps = false)
                gold(s); s.sphere(0f, 6.7f, 0f, 0.55f, 0.4f, 0.55f, 5, 10)
                for (i in 0 until 6) {
                    val a = i * TWO_PI / 6f + (i % 2) * 0.3f
                    val y = 3.6f + (i % 3) * 1.1f
                    val r = 2.3f - (i % 3) * 0.35f
                    val x = cos(a) * r; val z = sin(a) * r
                    steel(s); s.beam(cos(a) * 0.5f, y - 0.6f, sin(a) * 0.5f, x, y, z, 0.16f)
                    hull(s); s.cylinder(x, y - 0.1f, z, 0.5f, 0.4f, 0.2f, 10)
                    s.color(0.9f, 0.24f, 0.4f).mat(Mat.LEAVES); s.sphere(x, y + 0.5f, z, 0.5f, 0.45f, 0.5f, 5, 8, 0.22f, i)
                    glassTint(gl); gl.sphere(x, y + 0.5f, z, 0.72f, 0.72f, 0.72f, 6, 12)
                    g.color(1f, 0.55f, 0.65f, 1f); g.sphere(x, y + 1.05f, z, 0.08f, 0.08f, 0.08f, 3, 5)
                }
                g.color(0.5f, 1f, 0.95f, 1.6f); g.torus(0f, 1.82f, 0f, 0.95f, 0.04f, 18, 4); g.sphere(0f, 7.15f, 0f, 0.2f, 0.2f, 0.2f, 5, 8)
            }

            BuildType.BIO_1 -> {
                footing(s, 1.1f)
                hull(s); s.cylinder(0f, 0.3f, 0f, 1.0f, 1.1f, 1.0f, 14, caps = false)
                rose(s); s.cylinder(0f, 1.3f, 0f, 1.12f, 1.12f, 0.18f, 14, caps = false)
                hull(s); s.dome(0f, 1.48f, 0f, 1.1f, 0.6f, 4, 14)
                dark(s); s.cylinder(0f, 2.0f, 0f, 0.16f, 0.12f, 0.7f, 8)
                g.color(1f, 0.4f, 0.6f, 1.2f); g.torus(0f, 1.0f, 0f, 1.08f, 0.03f, 16, 4)
                m.spinSpeed = 1.5f
                steel(sp); sp.beam(-1.5f, 2.7f, 0f, 1.5f, 2.7f, 0f, 0.09f)
                rose(sp); sp.sphere(-1.5f, 2.62f, 0f, 0.16f, 0.2f, 0.16f, 4, 8); sp.sphere(1.5f, 2.62f, 0f, 0.16f, 0.2f, 0.16f, 4, 8)
                dark(sp); sp.cylinder(0f, 2.6f, 0f, 0.14f, 0.14f, 0.2f, 8)
            }
            BuildType.BIO_2 -> {
                footing(s, 1.4f, 0.3f, 16)
                hull(s); s.cylinder(0f, 0.3f, 0f, 1.3f, 1.0f, 0.8f, 14)
                rose(s); s.cylinder(0f, 1.1f, 0f, 0.5f, 0.36f, 2.4f, 10)
                hull(s); s.dome(0f, 3.5f, 0f, 0.7f, 0.5f, 4, 12)
                g.color(1f, 0.45f, 0.65f, 1.3f); g.torus(0f, 3.5f, 0f, 0.7f, 0.03f, 14, 4)
                m.spinSpeed = 0.9f
                for (i in 0 until 6) {
                    val a = i * TWO_PI / 6f
                    steel(sp); sp.beam(cos(a) * 0.4f, 2.6f, sin(a) * 0.4f, cos(a) * 1.45f, 2.2f, sin(a) * 1.45f, 0.06f)
                    sp.color(0.95f, 0.5f, 0.3f).mat(Mat.LEAVES); sp.sphere(cos(a) * 1.5f, 1.95f, sin(a) * 1.5f, 0.22f, 0.3f, 0.22f, 4, 7)
                    sp.color(0.4f, 0.85f, 1f, 1f); sp.sphere(cos(a) * 1.5f, 1.6f, sin(a) * 1.5f, 0.05f, 0.05f, 0.05f, 3, 5)
                }
            }
            BuildType.BIO_3 -> {
                footing(s, 1.8f, 0.3f, 18)
                hull(s); s.cylinder(0f, 0.3f, 0f, 1.7f, 1.2f, 1.0f, 16)
                rose(s); s.cylinder(0f, 1.3f, 0f, 0.9f, 0.9f, 0.2f, 14)
                for (i in 0 until 3) {
                    val a = i * TWO_PI / 3f
                    s.push().trs(cos(a) * 0.55f, 1.5f, sin(a) * 0.55f, -a + PI_F * 0.5f, 0.38f, 0f)
                    hull(s); s.cylinder(0f, 0f, 0f, 0.34f, 0.3f, 4.2f, 10, caps = false)
                    dark(s); s.torus(0f, 4.2f, 0f, 0.3f, 0.06f, 10, 5); s.torus(0f, 1.4f, 0f, 0.34f, 0.05f, 10, 5)
                    s.pop()
                }
                // a sapling under glass on the deck
                s.color(0.9f, 0.88f, 0.86f).mat(Mat.BARK); s.cylinder(1.25f, 1.3f, -0.9f, 0.05f, 0.03f, 0.7f, 5)
                s.color(0.9f, 0.22f, 0.3f).mat(Mat.LEAVES); s.sphere(1.25f, 2.15f, -0.9f, 0.34f, 0.3f, 0.34f, 5, 7, 0.2f, 3)
                glassTint(gl); gl.dome(1.25f, 1.3f, -0.9f, 0.5f, 1.3f, 5, 12)
                g.color(1f, 0.45f, 0.65f, 1.4f); g.torus(0f, 1.42f, 0f, 0.92f, 0.035f, 16, 4)
            }
            BuildType.BIO_4 -> {
                footing(s, 1.6f, 0.3f, 16)
                var k = 0
                for (lv in 0 until 4) {
                    val n = 6 - lv
                    for (i in 0 until n) {
                        val a = i * TWO_PI / n + lv * 0.5f
                        val r = (1.2f - lv * 0.3f)
                        val x = cos(a) * r; val z = sin(a) * r; val y = 0.2f + lv * 1.1f
                        s.color(0.92f, 0.8f, 0.62f).mat(Mat.HULL)
                        s.cylinder(x, y, z, 0.52f, 0.46f, 1.05f, 6, caps = true, flat = true)
                        if (k % 2 == 0) { g.color(1f, 0.78f, 0.45f, 1.4f); g.push().trs(x, y + 0.55f, z, -a + PI_F * 0.5f); g.box(0f, 0f, 0.47f, 0.3f, 0.4f, 0.02f); g.pop() }
                        k++
                    }
                }
                rose(s); s.cylinder(0f, 4.6f, 0f, 0.4f, 0.1f, 0.6f, 6, flat = true)
                g.color(1f, 0.85f, 0.5f, 1.8f); g.sphere(0f, 5.3f, 0f, 0.12f, 0.12f, 0.12f, 4, 6)
            }
            BuildType.BIO_5 -> {
                footing(s, 2.2f, 0.3f, 20)
                s.color(0.5f, 0.52f, 0.62f).mat(Mat.ROCK); s.cylinder(0f, 0.3f, 0f, 2.0f, 1.3f, 1.4f, 7, caps = true, flat = true)
                // two tall tines, like a tuning fork grown out of Watcher stone
                for (i in 0 until 2) {
                    val x = if (i == 0) -0.7f else 0.7f
                    hull(s); s.beam(x * 1.2f, 1.6f, 0f, x, 11.5f, 0f, 0.5f)
                    rose(s); s.box(x, 11.7f, 0f, 0.56f, 0.4f, 0.56f)
                    g.color(1f, 0.4f, 0.55f, 1.6f); g.box(x * 0.72f, 6.5f, 0f, 0.03f, 8.6f, 0.2f)
                }
                steel(s); s.beam(-0.8f, 2.6f, 0f, 0.8f, 2.6f, 0f, 0.4f)
                m.spinSpeed = 0.6f
                steel(sp); sp.torus(0f, 5f, 0f, 1.7f, 0.07f, 24, 6); sp.torus(0f, 7.5f, 0f, 1.45f, 0.06f, 24, 6); sp.torus(0f, 10f, 0f, 1.2f, 0.05f, 24, 6)
                sp.color(1f, 0.6f, 0.7f, 1.4f)
                for (i in 0 until 3) { val a = i * 2.1f; sp.sphere(cos(a) * 1.7f, 5f, sin(a) * 1.7f, 0.12f, 0.12f, 0.12f, 4, 6); sp.sphere(cos(a + 1f) * 1.45f, 7.5f, sin(a + 1f) * 1.45f, 0.1f, 0.1f, 0.1f, 4, 6) }
            }

            BuildType.EXTRACTOR -> {
                footing(s, 1.2f)
                hull(s); s.box(0f, 0.75f, 0f, 2.0f, 0.9f, 1.6f)
                gold(s); s.box(0f, 1.25f, 0f, 2.04f, 0.14f, 1.64f)
                dark(s); s.box(0.55f, 0.5f, 0.81f, 0.7f, 0.5f, 0.04f)
                steel(s); s.beam(-0.7f, 1.3f, 0f, -0.7f, 3.3f, 0f, 0.16f); s.beam(-0.7f, 3.3f, 0f, -1.5f, 3.0f, 0f, 0.12f)
                dark(s); s.cylinder(-1.5f, 0.5f, 0f, 0.22f, 0.22f, 2.5f, 8)
                hull(s); s.cylinder(0.5f, 1.3f, 0f, 0.5f, 0.36f, 0.6f, 10)
                g.color(1f, 0.85f, 0.35f, 1.3f); g.box(0.55f, 0.5f, 0.835f, 0.5f, 0.1f, 0.01f); g.sphere(-0.7f, 3.4f, 0f, 0.07f, 0.07f, 0.07f, 4, 6)
                m.spinSpeed = 6f
                steel(sp); sp.push().trs(-1.5f, 0f, 0f); sp.cylinder(0f, -0.6f, 0f, 0.05f, 0.3f, 1.1f, 6, caps = false); for (i in 0 until 4) sp.box(0f, -0.3f + i * 0.2f, 0f, 0.5f - i * 0.08f, 0.05f, 0.08f); sp.pop()
            }
            BuildType.COLLECTOR -> {
                footing(s, 0.8f)
                legs(s, 3, 0.2f, 1.0f, 2.0f, 0.07f)
                hull(s); s.cylinder(0f, 1.9f, 0f, 0.2f, 1.25f, 0.9f, 14, caps = false)
                teal(s); s.torus(0f, 2.8f, 0f, 1.25f, 0.05f, 16, 5)
                steel(s); s.cylinder(0f, 1.2f, 0f, 0.08f, 0.08f, 0.75f, 6)
                hull(s); s.cylinder(0f, 0.3f, 0f, 0.42f, 0.42f, 0.12f, 10); s.cylinder(0f, 1.15f, 0f, 0.42f, 0.42f, 0.1f, 10)
                s.color(0.3f, 0.7f, 0.95f, 0.2f); s.cylinder(0f, 0.42f, 0f, 0.32f, 0.32f, 0.5f, 10)
                glassTint(gl); gl.cylinder(0f, 0.42f, 0f, 0.38f, 0.38f, 0.73f, 12, caps = false)
                g.color(0.4f, 0.9f, 1f, 1f); g.torus(0f, 0.36f, 0f, 0.43f, 0.02f, 12, 4)
            }
            BuildType.CRATE -> {
                s.color(0.86f, 0.88f, 0.93f).mat(Mat.HULL); s.box(0f, 0.42f, 0f, 1.1f, 0.8f, 0.9f)
                dark(s); s.box(0f, 0.06f, 0f, 1.16f, 0.12f, 0.96f); s.box(0f, 0.8f, 0f, 1.16f, 0.1f, 0.96f)
                teal(s); s.box(-0.3f, 0.43f, 0f, 0.1f, 0.84f, 0.94f); s.box(0.3f, 0.43f, 0f, 0.1f, 0.84f, 0.94f)
                steel(s); s.box(0f, 0.6f, 0.46f, 0.2f, 0.12f, 0.04f)
                g.color(0.35f, 1f, 0.95f, 1f); g.box(0f, 0.6f, 0.485f, 0.1f, 0.03f, 0.01f)
            }
            BuildType.BEACON -> {
                footing(s, 0.3f, 0.2f, 8)
                hull(s); s.cylinder(0f, 0.2f, 0f, 0.1f, 0.05f, 3.4f, 8)
                teal(s); s.torus(0f, 1.2f, 0f, 0.14f, 0.03f, 10, 4); s.torus(0f, 2.4f, 0f, 0.11f, 0.025f, 10, 4)
                dark(s); s.cylinder(0f, 3.6f, 0f, 0.09f, 0.09f, 0.12f, 8)
                g.color(0.5f, 1f, 0.95f, 2.2f); g.sphere(0f, 3.9f, 0f, 0.15f, 0.2f, 0.15f, 5, 8)
            }
            else -> {}
        }
        if (t.spinsOnAxisX()) m.axisX = true
        return m.set()
    }

    private fun BuildType.spinsOnAxisX() = false

    // ------------------------------------------------------------------ pods

    /** Floor, roof, corner posts and the legs under a pod. Centred on the cell, y = 0 is the underside of the floor. */
    fun podBase(): Pair<MeshData, MeshData> {
        val s = MeshBuilder(1024); val g = MeshBuilder()
        val h = POD_CELL * 0.5f
        val top = POD_HEIGHT
        dark(s); s.box(0f, POD_FLOOR * 0.5f, 0f, POD_CELL - 0.02f, POD_FLOOR, POD_CELL - 0.02f)
        s.color(0.5f, 0.52f, 0.58f).mat(Mat.METAL); s.box(0f, POD_FLOOR + 0.005f, 0f, POD_CELL - 0.5f, 0.01f, POD_CELL - 0.5f)
        // roof slab with a raised hump and a rounded rim
        hull(s); s.box(0f, top - 0.12f, 0f, POD_CELL - 0.05f, 0.24f, POD_CELL - 0.05f)
        s.box(0f, top + 0.12f, 0f, POD_CELL - 1.0f, 0.24f, POD_CELL - 1.0f)
        teal(s); s.box(0f, top - 0.3f, 0f, POD_CELL - 0.01f, 0.1f, POD_CELL - 0.01f)
        dark(s); s.box(1.3f, top + 0.36f, -1.2f, 1.0f, 0.3f, 0.8f); s.cylinder(-1.6f, top + 0.24f, 1.5f, 0.03f, 0.02f, 1.4f, 5)
        g.color(0.35f, 1f, 0.95f, 1.4f); g.sphere(-1.6f, top + 1.66f, 1.5f, 0.05f, 0.05f, 0.05f, 4, 6)
        // ceiling light panel
        g.color(1f, 0.96f, 0.86f, 1.2f); g.box(0f, top - 0.245f, 0f, 2.2f, 0.01f, 0.5f)
        for (i in 0 until 4) {
            val x = if (i % 2 == 0) -1f else 1f; val z = if (i < 2) -1f else 1f
            hull(s); s.box(x * (h - 0.23f), (POD_FLOOR + top - 0.3f) * 0.5f, z * (h - 0.23f), 0.4f, top - 0.3f - POD_FLOOR, 0.4f)
            dark(s); s.cylinder(x * (h - 0.35f), -3.2f, z * (h - 0.35f), 0.11f, 0.11f, 3.2f, 6)
            s.beam(x * (h - 0.35f), -0.1f, z * (h - 0.35f), x * (h - 1.5f), -0.9f, z * (h - 0.35f), 0.07f)
        }
        dark(s); s.box(0f, -0.13f, 0f, POD_CELL - 0.6f, 0.2f, POD_CELL - 0.6f)
        return Pair(s.buildClean(), g.buildClean())
    }

    /** One wall on the +x side. kind 0 = window wall, 1 = door wall, 2 = open frame to the next pod. */
    fun podSide(kind: Int): Triple<MeshData, MeshData?, MeshData?> {
        val s = MeshBuilder(512); val g = MeshBuilder(); val gl = MeshBuilder()
        val h = POD_CELL * 0.5f
        val x = h - 0.14f
        val t = 0.24f
        val y0 = POD_FLOOR; val y1 = POD_HEIGHT - 0.24f
        val span = POD_CELL - 0.8f
        when (kind) {
            0 -> {
                hull(s)
                s.box(x, y0 + 0.45f, 0f, t, 0.9f, span)                      // sill
                s.box(x, y1 - 0.25f, 0f, t, 0.5f, span)                      // header
                s.box(x, (y0 + y1) * 0.5f, -span * 0.5f + 0.5f, t, y1 - y0, 1.0f)
                s.box(x, (y0 + y1) * 0.5f, span * 0.5f - 0.5f, t, y1 - y0, 1.0f)
                dark(s); s.box(x + 0.02f, y0 + 0.93f, 0f, t + 0.06f, 0.06f, span - 1.9f); s.box(x + 0.02f, y1 - 0.53f, 0f, t + 0.06f, 0.06f, span - 1.9f)
                s.box(x, (y0 + y1) * 0.5f + 0.2f, 0f, 0.08f, y1 - y0 - 1.4f, 0.06f)
                teal(s); s.box(x + 0.125f, y0 + 0.25f, 0f, 0.01f, 0.14f, span)
                glassTint(gl); gl.box(x, y0 + 1.55f, 0f, 0.04f, 1.25f, span - 2.0f)
            }
            1 -> {
                hull(s)
                val pw = span * 0.5f - 0.86f
                s.box(x, (y0 + y1) * 0.5f, -(0.86f + pw * 0.5f), t, y1 - y0, pw)
                s.box(x, (y0 + y1) * 0.5f, 0.86f + pw * 0.5f, t, y1 - y0, pw)
                s.box(x, y1 - 0.2f, 0f, t, 0.4f, 1.9f)
                // door frame
                gold(s); s.box(x + 0.03f, y0 + 1.1f, -0.86f, t + 0.1f, 2.2f, 0.14f); s.box(x + 0.03f, y0 + 1.1f, 0.86f, t + 0.1f, 2.2f, 0.14f); s.box(x + 0.03f, y0 + 2.25f, 0f, t + 0.1f, 0.14f, 1.86f)
                g.color(0.35f, 1f, 0.95f, 1.4f); g.box(x + 0.16f, y0 + 2.42f, 0f, 0.02f, 0.06f, 1.2f)
                // the air curtain that keeps the pressure in
                gl.color(0.3f, 1f, 0.95f); gl.box(x, y0 + 1.1f, 0f, 0.02f, 2.2f, 1.58f)
            }
            else -> {
                hull(s)
                s.box(x, (y0 + y1) * 0.5f, -span * 0.5f + 0.22f, t + 0.3f, y1 - y0, 0.44f)
                s.box(x, (y0 + y1) * 0.5f, span * 0.5f - 0.22f, t + 0.3f, y1 - y0, 0.44f)
                s.box(x, y1 - 0.15f, 0f, t + 0.3f, 0.3f, span)
                dark(s); s.box(x + 0.07f, y0 + 0.01f, 0f, 0.42f, 0.02f, span - 0.9f)
            }
        }
        return Triple(s.buildClean(), if (g.vertexCount > 0) g.buildClean() else null, if (gl.vertexCount > 0) gl.build() else null)
    }

    /** Ramp outside a door on the +x side. It drops one unit over its length; scale Y by the real drop. */
    fun podRamp(): MeshData {
        val s = MeshBuilder(128)
        val x0 = POD_CELL * 0.5f; val x1 = x0 + RAMP_LEN
        val w = 0.95f
        val y = POD_FLOOR
        dark(s)
        // tread
        s.flatQuad(x0, y, w, x1, y - 1f, w, x1, y - 1f, -w, x0, y, -w)
        s.flatQuad(x0, y, -w, x1, y - 1f, -w, x1, y - 1f, w, x0, y, w)
        // side cheeks down to the ground
        s.flatQuad(x0, y, w, x0, y - 1.6f, w, x1, y - 1.6f, w, x1, y - 1f, w)
        s.flatQuad(x0, y, -w, x1, y - 1f, -w, x1, y - 1.6f, -w, x0, y - 1.6f, -w)
        s.flatQuad(x1, y - 1f, w, x1, y - 1.6f, w, x1, y - 1.6f, -w, x1, y - 1f, -w)
        gold(s)
        s.beam(x0, y + 0.02f, w, x1, y - 0.98f, w, 0.07f); s.beam(x0, y + 0.02f, -w, x1, y - 0.98f, -w, 0.07f)
        return s.buildClean()
    }

    // ------------------------------------------------------------------ first person

    /**
     * The Waker's right arm reaching into view, holding the builder out in front: a long hard-edged tool pointed
     * away from you, gripped from underneath, with a square screen lying on top near the wrist.
     * Local space: x right, y up, -z is the way the tool points. The grip sits at the origin.
     */
    fun viewTool(): Pair<MeshData, MeshData> {
        val s = MeshBuilder(8192); val g = MeshBuilder()
        fun glove() = s.color(0.15f, 0.16f, 0.2f).mat(Mat.GRIP)
        fun tip() = s.color(0.9f, 0.34f, 0.4f).mat(Mat.GRIP)
        fun accent() = s.color(0.9f, 0.3f, 0.36f).mat(Mat.HULL)
        fun shell() = s.color(0.9f, 0.91f, 0.95f).mat(Mat.HULL)
        fun gun() = s.color(0.17f, 0.18f, 0.22f).mat(Mat.METAL)
        fun mid() = s.color(0.4f, 0.43f, 0.5f).mat(Mat.METAL)
        fun teal() = s.color(0.22f, 0.74f, 0.7f).mat(Mat.HULL)
        val by = 0.062f      // body centre height
        val bz = -0.105f     // body centre along the barrel

        // ---- body: a long dark core dressed in plates, open on the left to show its insides
        gun(); s.box(0f, by, bz, 0.062f, 0.074f, 0.23f)
        // top plate, split in two with a groove
        shell(); s.box(0f, by + 0.039f, bz - 0.062f, 0.066f, 0.006f, 0.1f); s.box(0f, by + 0.039f, bz + 0.054f, 0.066f, 0.006f, 0.118f)
        // right plate
        shell(); s.box(0.0325f, by, bz, 0.005f, 0.066f, 0.22f)
        // left side: a frame round an open bay
        shell(); s.box(-0.0325f, by + 0.029f, bz, 0.005f, 0.012f, 0.22f); s.box(-0.0325f, by - 0.029f, bz, 0.005f, 0.012f, 0.22f)
        s.box(-0.0325f, by, bz + 0.1f, 0.005f, 0.046f, 0.02f); s.box(-0.0325f, by, bz - 0.1f, 0.005f, 0.046f, 0.02f)
        // inside the bay: rails, a cell and coloured conduits
        mid(); s.box(-0.029f, by + 0.012f, bz, 0.004f, 0.006f, 0.17f); s.box(-0.029f, by - 0.012f, bz, 0.004f, 0.006f, 0.17f)
        accent(); s.box(-0.03f, by, bz - 0.03f, 0.005f, 0.012f, 0.07f)
        teal(); s.box(-0.03f, by, bz + 0.05f, 0.005f, 0.012f, 0.04f)
        s.color(0.96f, 0.8f, 0.3f).mat(Mat.HULL); s.tube(floatArrayOf(-0.03f, by, bz + 0.012f, -0.03f, by, bz + 0.026f), floatArrayOf(0.006f, 0.006f), 8)
        mid(); for (k in 0 until 4) s.box(-0.0345f, by + 0.029f, bz - 0.07f + k * 0.045f, 0.002f, 0.004f, 0.004f)
        // nose: stepped block and a lens
        mid(); s.box(0f, by, bz - 0.121f, 0.052f, 0.06f, 0.014f)
        gun(); s.box(0f, by, bz - 0.132f, 0.036f, 0.042f, 0.01f)
        s.push().trs(0f, by, bz - 0.136f, 0f, -PI_F * 0.5f, 0f)
        mid(); s.cylinder(0f, 0f, 0f, 0.015f, 0.013f, 0.014f, 16); gun(); s.torus(0f, 0.014f, 0f, 0.012f, 0.0035f, 16, 8)
        s.pop()
        g.color(0.35f, 1f, 0.95f, 1.6f)
        g.push().trs(0f, by, bz - 0.151f, 0f, -PI_F * 0.5f, 0f); g.disc(0f, 0f, 0f, 0.0095f, 14, true); g.pop()
        // belly rail and rear cap
        mid(); s.box(0f, by - 0.04f, bz - 0.04f, 0.04f, 0.006f, 0.13f)
        gun(); s.box(0f, by, bz + 0.119f, 0.05f, 0.058f, 0.01f)
        g.color(1f, 0.45f, 0.5f, 1.2f); g.box(-0.012f, by + 0.012f, bz + 0.1245f, 0.008f, 0.005f, 0.002f)
        g.color(0.35f, 1f, 0.95f, 1.2f); g.box(0.004f, by + 0.012f, bz + 0.1245f, 0.008f, 0.005f, 0.002f)

        // ---- grip under the back of the body, raked like a pistol
        val rake = 0.3f
        s.push().trs(0f, -0.022f, 0.004f, 0f, rake, 0f)
        gun(); s.softBox(0f, 0f, 0f, 0.036f, 0.1f, 0.048f, 0.4f)
        s.pop()
        gun(); s.tube(floatArrayOf(0f, 0.022f, -0.022f, 0f, 0.004f, -0.034f, 0f, 0.004f, -0.058f, 0f, 0.022f, -0.066f), floatArrayOf(0.004f, 0.004f, 0.004f, 0.004f), 6)

        // ---- the screen: a square slate lying on top near the wrist, tipped up toward your eyes
        mid(); s.box(0f, by + 0.05f, bz + 0.07f, 0.02f, 0.02f, 0.02f)
        s.push().trs(0f, by + 0.064f, bz + 0.118f, -0.22f, 0.85f, 0f)
        gun(); s.box(0f, 0.005f, -0.056f, 0.112f, 0.01f, 0.112f)
        shell(); s.box(0f, 0f, -0.056f, 0.118f, 0.005f, 0.118f)
        accent(); s.box(0f, 0.0105f, -0.004f, 0.04f, 0.003f, 0.006f)
        s.pop()
        g.push().trs(0f, by + 0.064f, bz + 0.118f, -0.22f, 0.85f, 0f)
        g.color(0.04f, 0.16f, 0.18f, 0.25f); g.box(0f, 0.0106f, -0.058f, 0.1f, 0.001f, 0.094f)
        g.color(0.18f, 0.62f, 0.6f, 0.26f)
        for (k in -3..3) { g.box(k * 0.014f, 0.0114f, -0.058f, 0.0008f, 0.001f, 0.092f); g.box(0f, 0.0114f, -0.058f + k * 0.0132f, 0.098f, 0.001f, 0.0008f) }
        // glyph: a pod under a roof
        g.color(0.8f, 1f, 0.97f, 0.7f)
        g.box(0f, 0.0122f, -0.05f, 0.032f, 0.001f, 0.02f)
        g.box(0f, 0.0122f, -0.069f, 0.048f, 0.001f, 0.0065f)
        g.box(-0.013f, 0.0122f, -0.034f, 0.006f, 0.001f, 0.011f); g.box(0.013f, 0.0122f, -0.034f, 0.006f, 0.001f, 0.011f)
        g.pop()

        // ---- the hand, in the grip's raked frame: a fist round the grip
        s.push().trs(0f, -0.022f, 0.004f, 0f, rake, 0f)
        glove(); s.softBox(0.012f, 0.0f, 0.034f, 0.074f, 0.094f, 0.046f, 0.55f)        // palm behind the grip
        s.softBox(0.04f, 0.0f, 0.012f, 0.03f, 0.09f, 0.06f, 0.6f)                       // side of the hand
        for (i in 0 until 4) {
            val y = 0.032f - i * 0.0225f
            val r = if (i == 3) 0.0092f else 0.0104f
            val k = if (i == 0 || i == 3) 0.9f else 1f
            glove()
            s.tube(floatArrayOf(
                0.042f, y, 0.02f,
                0.04f, y, -0.012f,
                0.022f * k, y, -0.034f,
                -0.006f, y - 0.001f, -0.038f * k,
                -0.026f * k, y - 0.002f, -0.022f,
            ), floatArrayOf(r * 1.1f, r * 1.08f, r, r, r * 0.94f), 10)
            // coloured fingertip, the part you see curled round the left side
            tip(); s.tube(floatArrayOf(-0.027f * k, y - 0.002f, -0.02f, -0.029f * k, y - 0.003f, -0.002f), floatArrayOf(r * 0.96f, r * 0.84f), 10)
        }
        // thumb: short, tucked along the left side just under the body
        glove(); s.tube(floatArrayOf(0.0f, 0.03f, 0.044f, -0.022f, 0.046f, 0.026f, -0.028f, 0.05f, 0.004f), floatArrayOf(0.015f, 0.013f, 0.0112f), 10)
        tip(); s.tube(floatArrayOf(-0.028f, 0.05f, 0.002f, -0.027f, 0.05f, -0.012f), floatArrayOf(0.0108f, 0.0092f), 10)
        s.pop()

        // ---- wrist, cuff and a long sleeve running back toward your shoulder
        val wx = 0.02f; val wy = -0.04f; val wz = 0.05f
        val ax = 0.2f; val ay = -0.26f; val az = 0.94f
        fun seg(t0: Float, t1: Float, r0: Float, r1: Float, n: Int = 18) =
            s.tube(floatArrayOf(wx + ax * t0, wy + ay * t0, wz + az * t0, wx + ax * t1, wy + ay * t1, wz + az * t1), floatArrayOf(r0, r1), n, caps = false)
        glove(); seg(-0.01f, 0.06f, 0.036f, 0.04f)
        // moulded forearm: narrow at the wrist, swelling toward the elbow
        s.color(0.2f, 0.215f, 0.28f)
        s.tube(floatArrayOf(wx + ax * 0.05f, wy + ay * 0.05f, wz + az * 0.05f, wx + ax * 0.16f, wy + ay * 0.16f, wz + az * 0.16f, wx + ax * 0.3f, wy + ay * 0.3f, wz + az * 0.3f),
            floatArrayOf(0.041f, 0.05f, 0.056f), 18, caps = false)
        shell(); seg(0.3f, 0.34f, 0.062f, 0.064f)
        teal(); seg(0.34f, 0.352f, 0.066f, 0.066f)
        s.color(0.17f, 0.19f, 0.27f); seg(0.352f, 0.9f, 0.064f, 0.085f)
        // a tab on the top of the forearm
        accent()
        s.softBox(wx + ax * 0.2f - 0.012f, wy + ay * 0.2f + 0.05f, wz + az * 0.2f, 0.022f, 0.012f, 0.034f, 0.4f)
        return Pair(s.buildClean(), g.buildClean())
    }

    fun cube(): MeshData { val b = MeshBuilder(24); b.color(1f, 1f, 1f); b.box(0f, 0f, 0f, 1f, 1f, 1f); return b.build() }

    fun skyTriangle(): MeshData {
        val b = MeshBuilder(3)
        val a = b.rawVertex(-1f, -1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f)
        val c = b.rawVertex(3f, -1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f)
        val d = b.rawVertex(-1f, 3f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f)
        b.tri(a, c, d)
        return b.build()
    }

    /** Random seeds for the weather particle shader: line pairs when [lines], points otherwise. */
    fun particles(n: Int): MeshData {
        val b = MeshBuilder(n * 2)
        val rng = com.worldwake.game.engine.Rng(5151)
        for (i in 0 until n) {
            val x = rng.nextFloat(); val y = rng.nextFloat(); val z = rng.nextFloat()
            b.rawVertex(x, y, z, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
            b.rawVertex(x, y, z, 1f, 0f, 0f, 1f, 1f, 1f, 0.2f)
        }
        return b.build()
    }
}
