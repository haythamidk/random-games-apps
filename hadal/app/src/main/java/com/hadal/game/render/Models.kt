package com.hadal.game.render

import com.hadal.game.engine.Mat
import com.hadal.game.engine.MeshBuilder
import com.hadal.game.engine.MeshData
import com.hadal.game.engine.PI_F
import com.hadal.game.engine.Rng
import com.hadal.game.engine.TWO_PI
import com.hadal.game.game.Species
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural meshes for the sub and every creature. Local space: +z forward, +y up, origin at the body center.
 * Long creatures are split into a head and a repeatable segment that the renderer chains along their trail.
 */
object Models {
    fun sub(): MeshData {
        val b = MeshBuilder(4096)
        // main hull: a stubby yellow capsule
        b.color(0.95f, 0.72f, 0.12f).mat(Mat.PAINT)
        b.sphere(0f, 0f, -0.2f, 1.25f, 1.15f, 2.3f, 12, 20)
        // dark belly band and skids
        b.color(0.25f, 0.25f, 0.27f).mat(Mat.METAL)
        b.box(0f, -1.05f, -0.2f, 1.6f, 0.25f, 3.2f)
        for (s in listOf(-1f, 1f)) {
            b.push().trs(s * 0.85f, -1.35f, -0.2f, 0f, PI_F / 2f).cylinder(0f, -1.7f, 0f, 0.09f, 0.09f, 3.4f, 6).pop()
            b.box(s * 0.85f, -1.2f, 0.8f, 0.08f, 0.35f, 0.08f)
            b.box(s * 0.85f, -1.2f, -1.2f, 0.08f, 0.35f, 0.08f)
        }
        // front viewport dome
        b.color(0.08f, 0.16f, 0.2f, 0.12f)
        b.sphere(0f, 0.15f, 1.65f, 0.9f, 0.85f, 0.75f, 10, 16)
        b.color(0.25f, 0.25f, 0.27f).mat(Mat.METAL)
        b.push().trs(0f, 0.15f, 1.5f, 0f, PI_F / 2f).cylinder(0f, -0.1f, 0f, 0.98f, 0.98f, 0.2f, 18, caps = false).pop()
        // lamp housings
        for (s in listOf(-1f, 1f)) {
            b.color(0.3f, 0.3f, 0.32f).mat(Mat.METAL)
            b.push().trs(s * 1.05f, 0.55f, 1.2f, 0f, PI_F / 2f).cylinder(0f, -0.4f, 0f, 0.2f, 0.24f, 0.6f, 10).pop()
            b.color(1f, 0.95f, 0.85f, 3f)
            b.push().trs(s * 1.05f, 0.55f, 1.51f, 0f, PI_F / 2f).cylinder(0f, 0f, 0f, 0.2f, 0.2f, 0.02f, 10).pop()
            // side thruster pods
            b.color(0.3f, 0.3f, 0.32f).mat(Mat.METAL)
            b.push().trs(s * 1.45f, -0.1f, -0.6f, 0f, PI_F / 2f).cylinder(0f, -0.5f, 0f, 0.28f, 0.28f, 1f, 10).pop()
            b.color(0.08f, 0.08f, 0.09f)
            b.push().trs(s * 1.45f, -0.1f, -1.11f, 0f, PI_F / 2f).cylinder(0f, 0f, 0f, 0.22f, 0.22f, 0.02f, 10).pop()
            // nav lights
            b.color(if (s < 0) 1f else 0.1f, if (s < 0) 0.1f else 1f, 0.1f, 2.5f)
            b.sphere(s * 1.22f, 0.2f, -0.2f, 0.07f, 0.07f, 0.07f, 4, 6)
        }
        // conning tower, fin and antenna
        b.color(0.95f, 0.72f, 0.12f).mat(Mat.PAINT)
        b.box(0f, 1.2f, -0.4f, 0.7f, 0.5f, 1.3f)
        b.color(0.9f, 0.9f, 0.88f).mat(Mat.PAINT)
        b.push().trs(0f, 0.9f, -2.2f, 0f, 0.35f).box(0f, 0.3f, 0f, 0.08f, 0.9f, 0.8f).pop()
        b.color(0.4f, 0.4f, 0.4f).mat(Mat.METAL)
        b.cylinder(0.2f, 1.45f, -0.6f, 0.025f, 0.015f, 0.9f, 4)
        b.color(1f, 0.2f, 0.1f, 2f)
        b.sphere(0.2f, 2.37f, -0.6f, 0.05f, 0.05f, 0.05f, 3, 5)
        // stern shroud
        b.color(0.3f, 0.3f, 0.32f).mat(Mat.METAL)
        b.push().trs(0f, 0f, -2.55f, 0f, PI_F / 2f).cylinder(0f, -0.3f, 0f, 0.62f, 0.62f, 0.55f, 16, caps = false).pop()
        b.push().trs(0f, 0f, -2.55f, 0f, PI_F / 2f).cylinder(0f, -0.3f, 0f, 0.56f, 0.56f, 0.55f, 16, caps = false).pop()
        // manipulator arm folded under the nose
        b.color(0.35f, 0.35f, 0.37f).mat(Mat.METAL)
        b.push().trs(0.45f, -0.95f, 1.3f, 0.2f, -0.5f).box(0f, 0f, 0.35f, 0.14f, 0.14f, 0.8f).pop()
        b.box(0.55f, -1.1f, 1.85f, 0.1f, 0.25f, 0.1f)
        b.box(0.42f, -1.1f, 1.85f, 0.1f, 0.25f, 0.1f)
        // decals: a few hull stripes
        b.color(0.12f, 0.12f, 0.12f)
        for (s in listOf(-1f, 1f)) b.push().trs(s * 1.2f, 0.1f, -0.6f, s * 0.05f).box(0f, 0f, 0f, 0.05f, 0.18f, 1.6f).pop()
        return b.build()
    }

    fun propeller(): MeshData {
        val b = MeshBuilder(256)
        b.color(0.5f, 0.45f, 0.35f).mat(Mat.METAL)
        b.sphere(0f, 0f, 0f, 0.14f, 0.14f, 0.2f, 4, 8)
        for (i in 0 until 4) {
            val a = i * TWO_PI / 4
            b.push().trs(0f, 0f, 0f, 0f, 0f, a).push().trs(0f, 0.27f, 0f, 0.5f).box(0f, 0f, 0f, 0.16f, 0.42f, 0.03f).pop().pop()
        }
        return b.build()
    }

    /** Head (or whole body) mesh for each species. */
    fun creature(s: Species): MeshData {
        val b = MeshBuilder(2048)
        val r = Rng(77L + s.ordinal)
        when (s) {
            Species.GLASSFIN -> {
                b.color(0.7f, 0.78f, 0.82f, 0.15f)
                b.sphere(0f, 0f, 0f, 0.07f, 0.11f, 0.26f, 5, 8)
                b.color(0.55f, 0.65f, 0.7f, 0.1f)
                b.flatTri(0f, 0f, -0.22f, 0f, 0.11f, -0.4f, 0f, -0.11f, -0.4f)
                b.flatTri(0f, 0f, -0.22f, 0f, -0.11f, -0.4f, 0f, 0.11f, -0.4f)
                b.flatTri(0f, 0.09f, 0.02f, 0f, 0.17f, -0.1f, 0f, 0.09f, -0.14f)
                b.color(0.02f, 0.02f, 0.02f)
                b.sphere(0.055f, 0.03f, 0.17f, 0.025f, 0.025f, 0.025f, 3, 4)
                b.sphere(-0.055f, 0.03f, 0.17f, 0.025f, 0.025f, 0.025f, 3, 4)
            }
            Species.MANTA -> {
                b.color(0.18f, 0.2f, 0.24f).mat(Mat.SKIN)
                // body and wings as a flattened diamond with smooth shading
                b.sphere(0f, 0f, 0f, 1f, 0.35f, 1.4f, 6, 10)
                for (side in listOf(-1f, 1f)) {
                    val base = b.vertexCount
                    val pts = arrayOf(floatArrayOf(0.6f, 0f, 1f), floatArrayOf(3.2f, 0.15f, -0.4f), floatArrayOf(0.7f, 0f, -1.1f))
                    for (p in pts) b.vertex(side * p[0], p[1] + 0.04f, p[2], 0f, 1f, 0f)
                    for (p in pts) b.vertex(side * p[0], p[1] - 0.04f, p[2], 0f, -1f, 0f)
                    if (side > 0) { b.tri(base, base + 2, base + 1); b.tri(base + 3, base + 4, base + 5) } else { b.tri(base, base + 1, base + 2); b.tri(base + 3, base + 5, base + 4) }
                }
                b.color(0.85f, 0.85f, 0.82f).mat(Mat.SKIN)
                b.sphere(0f, -0.12f, 0.1f, 0.85f, 0.2f, 1.2f, 5, 8)
                b.color(0.18f, 0.2f, 0.24f).mat(Mat.SKIN)
                for (side in listOf(-1f, 1f)) b.push().trs(side * 0.45f, 0f, 1.35f, side * 0.3f).box(0f, 0f, 0.25f, 0.12f, 0.08f, 0.6f).pop()
                b.push().trs(0f, 0f, -1.3f, 0f, PI_F / 2f).cylinder(0f, 0f, 0f, 0.05f, 0.01f, 2.4f, 4).pop()
            }
            Species.MOON_JELLY -> {
                b.color(0.55f, 0.75f, 0.95f, 0.9f)
                // bell
                b.sphere(0f, 0f, 0f, 1f, 0.6f, 1f, 6, 14)
                b.color(0.9f, 0.6f, 0.95f, 1.6f)
                for (i in 0 until 4) { val a = i * PI_F / 2f; b.sphere(cos(a) * 0.35f, 0.05f, sin(a) * 0.35f, 0.2f, 0.06f, 0.12f, 3, 6) }
                b.color(0.6f, 0.8f, 1f, 1.1f)
                for (i in 0 until 16) {
                    val a = i * TWO_PI / 16
                    b.cylinder(cos(a) * 0.9f, -r.range(2.5f, 3.6f), sin(a) * 0.9f, 0.012f, 0.02f, r.range(2.5f, 3.6f), 3, caps = false)
                }
                for (i in 0 until 4) {
                    val a = i * PI_F / 2f + 0.4f
                    b.push().trs(cos(a) * 0.2f, -0.2f, sin(a) * 0.2f, a).box(0f, -0.7f, 0f, 0.16f, 1.4f, 0.03f).pop()
                }
            }
            Species.RIBBON_EEL -> {
                b.color(0.12f, 0.18f, 0.35f).mat(Mat.SKIN)
                b.sphere(0f, 0f, 0f, 0.2f, 0.18f, 0.4f, 5, 8)
                b.color(0.95f, 0.8f, 0.2f, 0.4f)
                b.sphere(0f, 0.05f, 0.35f, 0.08f, 0.04f, 0.15f, 3, 6)
                b.color(1f, 0.9f, 0.6f, 1f)
                b.sphere(0.12f, 0.08f, 0.2f, 0.04f, 0.04f, 0.04f, 3, 4)
                b.sphere(-0.12f, 0.08f, 0.2f, 0.04f, 0.04f, 0.04f, 3, 4)
            }
            Species.SONGWHALE -> {
                b.color(0.32f, 0.38f, 0.44f).mat(Mat.SKIN)
                b.sphere(0f, 0f, 0f, 2.2f, 2f, 9f, 10, 18)
                b.sphere(0f, -0.3f, 4f, 1.9f, 1.7f, 4f, 8, 16)
                b.color(0.7f, 0.72f, 0.7f).mat(Mat.SKIN)
                b.sphere(0f, -1f, 2f, 1.7f, 1f, 6f, 8, 14)
                // grooves
                b.color(0.25f, 0.28f, 0.3f)
                for (i in -3..3) b.box(i * 0.35f, -1.85f, 2.5f, 0.06f, 0.05f, 6f)
                b.color(0.32f, 0.38f, 0.44f).mat(Mat.SKIN)
                for (side in listOf(-1f, 1f)) b.push().trs(side * 2f, -0.8f, 2.5f, side * 0.5f, 0f, side * 0.6f).box(side * 1.6f, 0f, 0f, 3.6f, 0.2f, 1.1f).pop()
                // flukes
                b.push().trs(0f, 0f, -9.5f).box(0f, 0f, 0f, 5.5f, 0.25f, 1.8f).pop()
                b.push().trs(0f, 1.6f, -3f).box(0f, 0f, 0f, 0.2f, 0.9f, 1.2f).pop()
                b.color(0.02f, 0.02f, 0.03f, 0.2f)
                b.sphere(1.6f, -0.2f, 5.4f, 0.15f, 0.15f, 0.15f, 3, 5)
                b.sphere(-1.6f, -0.2f, 5.4f, 0.15f, 0.15f, 0.15f, 3, 5)
            }
            Species.ANGLER -> {
                b.color(0.09f, 0.08f, 0.09f).mat(Mat.SKIN)
                b.sphere(0f, 0f, 0f, 0.9f, 0.85f, 1.05f, 8, 12, 0.08f, 3)
                // jaw
                b.sphere(0f, -0.35f, 0.55f, 0.85f, 0.35f, 0.6f, 5, 10)
                b.color(0.85f, 0.85f, 0.8f)
                for (i in 0 until 14) {
                    val a = -1.2f + i * (2.4f / 13f)
                    b.push().trs(sin(a) * 0.75f, -0.15f, 0.65f + cos(a) * 0.45f, 0f, PI_F).cylinder(0f, 0f, 0f, 0.04f, 0f, r.range(0.25f, 0.45f), 4, caps = false).pop()
                    b.push().trs(sin(a) * 0.7f, -0.5f, 0.6f + cos(a) * 0.4f).cylinder(0f, 0f, 0f, 0.035f, 0f, r.range(0.2f, 0.35f), 4, caps = false).pop()
                }
                // lure
                b.color(0.15f, 0.12f, 0.12f).mat(Mat.SKIN)
                b.push().trs(0f, 0.8f, 0.2f, 0f, -0.9f).cylinder(0f, 0f, 0f, 0.03f, 0.02f, 1.4f, 4, caps = false).pop()
                b.color(0.6f, 0.95f, 1f, 4f)
                b.sphere(0f, 1.6f, 1.25f, 0.12f, 0.14f, 0.12f, 4, 6)
                b.color(0.09f, 0.08f, 0.09f).mat(Mat.SKIN)
                b.flatTri(0f, 0.2f, -0.9f, 0f, 0.7f, -1.6f, 0f, -0.5f, -1.6f)
                b.flatTri(0f, 0.2f, -0.9f, 0f, -0.5f, -1.6f, 0f, 0.7f, -1.6f)
                b.color(0.5f, 0.55f, 0.6f, 0.6f)
                b.sphere(0.5f, 0.35f, 0.6f, 0.07f, 0.07f, 0.07f, 3, 5)
                b.sphere(-0.5f, 0.35f, 0.6f, 0.07f, 0.07f, 0.07f, 3, 5)
            }
            Species.ISOPOD -> {
                b.color(0.62f, 0.58f, 0.62f).mat(Mat.SKIN)
                for (i in 0 until 7) {
                    val z = 0.55f - i * 0.17f
                    val w = 0.42f - kotlin.math.abs(i - 2.5f) * 0.03f
                    b.sphere(0f, 0.12f, z, w, 0.2f, 0.12f, 4, 10)
                }
                b.color(0.5f, 0.46f, 0.5f)
                for (i in 0 until 7) for (s in listOf(-1f, 1f)) b.push().trs(s * 0.38f, -0.02f, 0.5f - i * 0.15f, 0f, 0f, s * 0.7f).box(0f, -0.08f, 0f, 0.03f, 0.2f, 0.03f).pop()
                b.color(0.1f, 0.1f, 0.1f, 0.3f)
                b.sphere(0.18f, 0.18f, 0.66f, 0.05f, 0.04f, 0.03f, 3, 4)
                b.sphere(-0.18f, 0.18f, 0.66f, 0.05f, 0.04f, 0.03f, 3, 4)
            }
            Species.LANTERN_SQUID -> {
                b.color(0.55f, 0.25f, 0.3f).mat(Mat.SKIN)
                b.push().trs(0f, 0f, 0f, 0f, -PI_F / 2f).cylinder(0f, -0.1f, 0f, 0.1f, 0.0f, 0.55f, 7).pop()
                b.sphere(0f, 0f, -0.1f, 0.11f, 0.11f, 0.14f, 4, 7)
                for (i in 0 until 8) { val a = i * TWO_PI / 8; b.push().trs(cos(a) * 0.05f, sin(a) * 0.05f, -0.2f, 0f, PI_F / 2f).cylinder(0f, -0.4f, 0f, 0.012f, 0.02f, 0.4f, 3, caps = false).pop() }
                b.color(0.3f, 0.7f, 1f, 3f)
                for (i in 0 until 6) b.sphere(if (i % 2 == 0) 0.07f else -0.07f, -0.04f, 0.15f - i * 0.06f, 0.025f, 0.025f, 0.025f, 3, 4)
            }
            Species.VENT_CRAB -> {
                b.color(0.88f, 0.86f, 0.8f).mat(Mat.SKIN)
                b.sphere(0f, 0.15f, 0f, 0.35f, 0.18f, 0.28f, 5, 8)
                for (s in listOf(-1f, 1f)) {
                    for (i in 0 until 3) b.push().trs(s * 0.3f, 0.1f, -0.1f + i * 0.1f, 0f, 0f, s * 0.9f).box(0f, -0.2f, 0f, 0.04f, 0.4f, 0.04f).pop()
                    b.push().trs(s * 0.3f, 0.15f, 0.3f, s * -0.5f).box(0f, 0f, 0.15f, 0.06f, 0.06f, 0.3f).pop()
                    b.sphere(s * 0.4f, 0.15f, 0.48f, 0.1f, 0.07f, 0.13f, 4, 6)
                }
            }
            Species.GULPER -> {
                b.color(0.06f, 0.05f, 0.06f).mat(Mat.SKIN)
                b.sphere(0f, 0f, 0.6f, 1.6f, 1.4f, 1.9f, 8, 12, 0.05f, 9)
                b.color(0.2f, 0.05f, 0.06f)
                b.sphere(0f, -0.1f, 1.95f, 1.1f, 0.9f, 0.5f, 6, 10)
                b.color(0.06f, 0.05f, 0.06f).mat(Mat.SKIN)
                b.push().trs(0f, 0f, -1f, 0f, PI_F / 2f).cylinder(0f, -6f, 0f, 0.05f, 0.5f, 6f, 6, caps = false).pop()
                b.color(1f, 0.3f, 0.5f, 2.5f)
                b.sphere(0f, 0f, -7.1f, 0.12f, 0.12f, 0.2f, 3, 5)
                b.color(0.4f, 0.5f, 0.5f, 0.4f)
                b.sphere(0.6f, 0.9f, 1.6f, 0.06f, 0.06f, 0.06f, 3, 4)
                b.sphere(-0.6f, 0.9f, 1.6f, 0.06f, 0.06f, 0.06f, 3, 4)
            }
            Species.PALE -> {
                // long skull with rows of eyes
                b.color(0.8f, 0.79f, 0.74f).mat(Mat.SKIN)
                b.sphere(0f, 0f, 0.8f, 1.5f, 1.2f, 3.2f, 8, 14, 0.06f, 5)
                b.sphere(0f, -0.6f, 2f, 1.2f, 0.6f, 2.2f, 6, 12, 0.08f, 6)
                b.color(0.6f, 0.58f, 0.55f).mat(Mat.SKIN)
                for (i in 0 until 6) for (s in listOf(-1f, 1f)) b.push().trs(s * 1.2f, 0.4f, -0.8f + i * 0.5f, s * 0.6f, 0.3f).cylinder(0f, 0f, 0f, 0.14f, 0f, 1.6f - i * 0.15f, 4, caps = false).pop()
                b.color(0.75f, 0.9f, 1f, 5f)
                for (i in 0 until 4) for (s in listOf(-1f, 1f)) b.sphere(s * (0.9f - i * 0.12f), 0.45f + i * 0.1f, 2.6f - i * 0.45f, 0.11f, 0.08f, 0.11f, 3, 5)
                b.color(0.05f, 0.03f, 0.03f)
                b.box(0f, -0.65f, 3.6f, 1.6f, 0.08f, 1.2f)
            }
            Species.WISP -> {
                b.color(0.6f, 0.85f, 1f, 4f)
                b.sphere(0f, 0f, 0f, 0.3f, 0.3f, 0.3f, 5, 8)
                b.color(0.4f, 0.6f, 1f, 1.5f)
                for (i in 0 until 3) b.push().trs(0f, 0f, 0f, i * 2.1f, 0.4f).box(0f, 0f, -0.8f, 0.04f, 0.3f, 1.4f).pop()
            }
        }
        return b.build()
    }

    /** Body segment for chained creatures. Centered at origin, pointing +z, length 1. */
    fun segment(s: Species): MeshData {
        val b = MeshBuilder(512)
        when (s) {
            Species.PALE -> {
                b.color(0.78f, 0.77f, 0.72f).mat(Mat.SKIN)
                b.push().trs(0f, 0f, 0f, 0f, PI_F / 2f).cylinder(0f, -0.65f, 0f, 1f, 1f, 1.3f, 12, caps = false).pop()
                b.color(0.62f, 0.6f, 0.56f).mat(Mat.SKIN)
                b.push().trs(0f, 0f, 0f, 0f, PI_F / 2f).cylinder(0f, -0.1f, 0f, 1.08f, 1.08f, 0.2f, 12, caps = false).pop()
                b.push().trs(0f, 0.95f, 0f, 0f, -0.6f).cylinder(0f, 0f, 0f, 0.12f, 0f, 1.2f, 4, caps = false).pop()
                b.color(0.5f, 0.8f, 1f, 1.2f)
                b.sphere(0.95f, -0.2f, 0f, 0.06f, 0.06f, 0.06f, 3, 4)
                b.sphere(-0.95f, -0.2f, 0f, 0.06f, 0.06f, 0.06f, 3, 4)
            }
            else -> {
                b.color(0.12f, 0.18f, 0.35f).mat(Mat.SKIN)
                b.push().trs(0f, 0f, 0f, 0f, PI_F / 2f).cylinder(0f, -0.6f, 0f, 1f, 1f, 1.2f, 8, caps = false).pop()
                b.color(0.95f, 0.8f, 0.2f, 0.3f)
                b.box(0f, 1f, 0f, 0.3f, 0.12f, 1.2f)
            }
        }
        return b.build()
    }

    fun flareMesh(): MeshData {
        val b = MeshBuilder(64)
        b.color(0.3f, 0.3f, 0.3f).mat(Mat.METAL)
        b.push().trs(0f, 0f, 0f, 0f, PI_F / 2f).cylinder(0f, -0.2f, 0f, 0.06f, 0.06f, 0.4f, 6).pop()
        b.color(1f, 0.3f, 0.15f, 4f)
        b.sphere(0f, 0f, 0.22f, 0.07f, 0.07f, 0.07f, 3, 5)
        return b.build()
    }
}
