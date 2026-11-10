package com.worldwake.game.world

import com.worldwake.game.engine.Mat
import com.worldwake.game.engine.MeshBuilder
import com.worldwake.game.engine.MeshData
import com.worldwake.game.engine.PI_F
import com.worldwake.game.engine.Rng
import com.worldwake.game.engine.TWO_PI
import com.worldwake.game.engine.beam
import com.worldwake.game.engine.capsule
import com.worldwake.game.engine.dome
import com.worldwake.game.engine.torus
import kotlin.math.cos
import kotlin.math.sin

class StructGeo(val poi: Poi, val solid: MeshData, val glow: MeshData?, val boundsRadius: Float)

/** A solid cylinder the player cannot walk through, up to height [top]. */
class Collider(val x: Float, val z: Float, val r: Float, val top: Float)

/**
 * Fixed world structures: the lander, what Lantern Nine left behind, and the vault. Geometry is built in world
 * space once. The Watchers share one model drawn per stone, because they turn.
 */
class Structures {
    val geos = ArrayList<StructGeo>()
    val colliders = ArrayList<Collider>()
    val watcherSolid: MeshData
    val watcherGlow: MeshData
    val watcherBloom: MeshData

    init {
        for (p in WorldGen.pois) {
            when (p.kind) {
                PoiKind.LANDER -> lander(p)
                PoiKind.OUTPOST -> outpost(p)
                PoiKind.SLATE -> slate(p)
                PoiKind.CARGO -> cargo(p)
                PoiKind.WRECK -> wreck(p)
                PoiKind.VAULT -> vault(p)
                PoiKind.WATCHER -> colliders.add(Collider(p.x, p.z, 0.7f, p.y + 6f))
            }
        }
        val s = MeshBuilder(); val g = MeshBuilder(); val bl = MeshBuilder()
        watcher(s, g, bl)
        watcherSolid = s.build(); watcherGlow = g.build(); watcherBloom = bl.build()
    }

    private fun add(p: Poi, s: MeshBuilder, g: MeshBuilder, r: Float) {
        geos.add(StructGeo(p, s.build(), if (g.vertexCount > 0) g.build() else null, r))
    }

    private fun hull(b: MeshBuilder) = b.color(0.9f, 0.91f, 0.95f).mat(Mat.HULL)
    private fun dark(b: MeshBuilder) = b.color(0.34f, 0.36f, 0.42f).mat(Mat.METAL)
    private fun worn(b: MeshBuilder) = b.color(0.78f, 0.76f, 0.74f).mat(Mat.WORN)

    private fun lander(p: Poi) {
        val s = MeshBuilder(2048); val g = MeshBuilder()
        s.trs(p.x, p.y, p.z, p.yaw); g.trs(p.x, p.y, p.z, p.yaw)
        // heat shield and body
        dark(s); s.cylinder(0f, 0.9f, 0f, 1.5f, 2.3f, 0.5f, 16, caps = true)
        hull(s); s.cylinder(0f, 1.4f, 0f, 2.3f, 2.1f, 2.0f, 16, caps = false)
        s.color(0.2f, 0.72f, 0.72f).mat(Mat.HULL); s.cylinder(0f, 2.2f, 0f, 2.24f, 2.2f, 0.28f, 16, caps = false)
        hull(s); s.cylinder(0f, 3.4f, 0f, 2.1f, 1.2f, 0.9f, 16, caps = false)
        s.dome(0f, 4.3f, 0f, 1.2f, 0.55f, 4, 16)
        // legs
        for (i in 0 until 4) {
            val a = i * TWO_PI / 4f + 0.785f
            val cx = cos(a); val sz = sin(a)
            dark(s)
            s.beam(cx * 2.0f, 1.9f, sz * 2.0f, cx * 3.4f, 0.15f, sz * 3.4f, 0.16f)
            s.beam(cx * 1.6f, 1.0f, sz * 1.6f, cx * 3.4f, 0.15f, sz * 3.4f, 0.1f)
            s.cylinder(cx * 3.4f, 0f, sz * 3.4f, 0.42f, 0.36f, 0.14f, 10)
        }
        // side tanks
        for (i in 0 until 2) {
            val a = 1.9f + i * 2.5f
            s.color(0.8f, 0.82f, 0.88f).mat(Mat.METAL)
            s.capsule(cos(a) * 2.45f, 1.5f, sin(a) * 2.45f, 0.36f, 1.7f, 10)
        }
        // hatch with a short ramp, facing +z
        dark(s); s.box(0f, 2.25f, 2.18f, 1.2f, 1.7f, 0.12f)
        s.color(0.5f, 0.52f, 0.58f).mat(Mat.METAL)
        s.push().trs(0f, 0.75f, 3.25f, 0f, 0.42f, 0f); s.box(0f, 0f, 0f, 1.3f, 0.08f, 2.5f); s.pop()
        // dish and mast
        dark(s); s.cylinder(0.5f, 4.7f, -0.3f, 0.04f, 0.03f, 1.5f, 6)
        hull(s); s.push().trs(-0.45f, 4.95f, 0.2f, 0.6f, 0.9f, 0f); s.dome(0f, 0f, 0f, 0.6f, 0.2f, 3, 12); s.pop()
        // solar petals
        for (i in 0 until 2) {
            val sx = if (i == 0) 1f else -1f
            dark(s); s.beam(sx * 2.1f, 3.2f, -0.6f, sx * 3.3f, 3.6f, -0.6f, 0.07f)
            s.color(0.12f, 0.16f, 0.42f).mat(Mat.METAL)
            s.push().trs(sx * 4.3f, 3.75f, -0.6f, 0f, 0f, sx * 0.25f); s.box(0f, 0f, 0f, 2.2f, 0.05f, 1.3f); s.pop()
        }
        // lights
        g.color(0.35f, 1f, 0.95f, 1.2f)
        g.box(0f, 3.2f, 2.14f, 1.0f, 0.07f, 0.06f)
        for (i in 0 until 8) { val a = i * TWO_PI / 8f; g.sphere(cos(a) * 2.27f, 1.55f, sin(a) * 2.27f, 0.05f, 0.05f, 0.05f, 3, 5) }
        g.color(1f, 0.35f, 0.5f, 1.6f); g.sphere(0.5f, 6.22f, -0.3f, 0.07f, 0.07f, 0.07f, 4, 6)
        colliders.add(Collider(p.x, p.z, 2.5f, p.y + 5f))
        add(p, s, g, 7f)
    }

    private fun outpost(p: Poi) {
        val rng = Rng(900L + p.id * 13)
        val s = MeshBuilder(2048); val g = MeshBuilder()
        s.trs(p.x, p.y, p.z, p.yaw, 0f, 0.03f); g.trs(p.x, p.y, p.z, p.yaw, 0f, 0.03f)
        // a Lantern Nine habitat: rounded box on short legs, one panel torn away
        worn(s)
        s.box(0f, 0.45f, 0f, 5.2f, 0.3f, 4.2f)
        for (i in 0 until 4) { val x = if (i % 2 == 0) -2.2f else 2.2f; val z = if (i < 2) -1.7f else 1.7f; dark(s); s.cylinder(x, -0.6f, z, 0.14f, 0.14f, 1.0f, 6) }
        worn(s)
        s.box(0f, 1.9f, -2.0f, 5.2f, 2.6f, 0.2f)       // back wall
        s.box(-2.5f, 1.9f, 0f, 0.2f, 2.6f, 4.2f)      // left wall
        s.box(2.5f, 1.9f, -1.1f, 0.2f, 2.6f, 2.0f)    // right wall, front half missing
        s.box(-1.7f, 1.9f, 2.0f, 1.8f, 2.6f, 0.2f)    // front left
        s.box(1.9f, 2.9f, 2.0f, 1.4f, 0.6f, 0.2f)     // lintel fragment
        // roof: a low barrel
        s.push().trs(0f, 3.2f, 0f, 0f, 0f, PI_F * 0.5f)
        s.pop()
        s.box(0f, 3.3f, -0.2f, 5.4f, 0.22f, 3.9f)
        s.color(0.66f, 0.64f, 0.63f).mat(Mat.WORN)
        s.push().trs(0.9f, 3.45f, 1.9f, 0f, 0.5f, 0.1f); s.box(0f, 0f, 0f, 2.8f, 0.14f, 1.4f); s.pop() // fallen roof panel
        // faded stripe in the Lantern colours
        s.color(0.85f, 0.62f, 0.25f).mat(Mat.WORN); s.box(0f, 2.75f, -2.11f, 5.22f, 0.3f, 0.02f)
        // inside: bunk frame and a dead console
        dark(s); s.box(-1.6f, 0.95f, -1.2f, 1.9f, 0.14f, 0.9f); s.box(-1.6f, 0.75f, -1.2f, 1.8f, 0.3f, 0.8f)
        // the log terminal stands outside under a small awning
        dark(s); s.box(3.6f, 0.55f, 1.3f, 0.7f, 1.1f, 0.5f)
        s.push().trs(3.6f, 1.25f, 1.3f, 0f, -0.5f, 0f); s.box(0f, 0f, 0f, 0.74f, 0.1f, 0.6f); s.pop()
        dark(s); s.cylinder(3.2f, 0f, 1.0f, 0.04f, 0.04f, 2.3f, 5); s.cylinder(4.0f, 0f, 1.0f, 0.04f, 0.04f, 2.3f, 5)
        worn(s); s.push().trs(3.6f, 2.3f, 1.25f, 0f, 0.2f, 0f); s.box(0f, 0f, 0f, 1.2f, 0.05f, 1.0f); s.pop()
        g.color(1f, 0.8f, 0.4f, 1.3f)
        g.push().trs(3.6f, 1.33f, 1.3f, 0f, -0.5f, 0f); g.box(0f, 0f, 0f, 0.56f, 0.02f, 0.4f); g.pop()
        g.color(1f, 0.8f, 0.4f, 0.9f); g.sphere(3.6f, 2.2f, 1.25f, 0.07f, 0.07f, 0.07f, 4, 6)
        // supply crates
        for (i in 0 until 3) {
            val x = -3.5f + rng.range(-0.4f, 0.4f); val z = -0.6f + i * 0.95f
            s.color(0.82f, 0.6f, 0.26f).mat(Mat.WORN)
            s.push().trs(x, 0.33f, z, rng.range(0f, 1f), 0f, 0f); s.box(0f, 0f, 0f, 0.8f, 0.66f, 0.7f)
            dark(s); s.box(0f, 0.2f, 0f, 0.84f, 0.06f, 0.74f); s.pop()
        }
        // a bent wind spindle
        dark(s); s.push().trs(-3.6f, 0f, -2.6f, 0f, 0.25f, 0.1f); s.cylinder(0f, 0f, 0f, 0.09f, 0.05f, 4.2f, 6)
        worn(s); s.push().trs(0f, 4.2f, 0f, 0f, 0f, 0.6f); s.box(0f, 0f, 0f, 2.6f, 0.05f, 0.24f); s.pop(); s.pop()
        colliders.add(Collider(p.x, p.z, 3.3f, p.y + 3.6f))
        add(p, s, g, 8f)
    }

    private fun slate(p: Poi) {
        val s = MeshBuilder(); val g = MeshBuilder()
        s.trs(p.x, p.y, p.z, p.id * 1.7f); g.trs(p.x, p.y, p.z, p.id * 1.7f)
        s.color(0.46f, 0.48f, 0.55f).mat(Mat.ROCK)
        s.sphere(0f, 0.2f, 0f, 0.6f, 0.36f, 0.5f, 5, 8, 0.2f, p.id, flat = true)
        dark(s); s.push().trs(0f, 0.62f, 0.05f, 0f, -0.9f, 0f); s.box(0f, 0f, 0f, 0.36f, 0.03f, 0.5f); s.pop()
        g.color(1f, 0.8f, 0.4f, 1.4f); g.push().trs(0f, 0.64f, 0.06f, 0f, -0.9f, 0f); g.box(0f, 0f, 0f, 0.3f, 0.01f, 0.42f); g.pop()
        dark(s); s.cylinder(0.5f, 0f, -0.3f, 0.025f, 0.02f, 1.8f, 5)
        g.color(1f, 0.8f, 0.4f, 1.6f); g.sphere(0.5f, 1.82f, -0.3f, 0.06f, 0.06f, 0.06f, 4, 6)
        add(p, s, g, 2f)
    }

    private fun cargo(p: Poi) {
        val s = MeshBuilder(); val g = MeshBuilder()
        s.trs(p.x, p.y, p.z, p.yaw, 0f, 0.35f); g.trs(p.x, p.y, p.z, p.yaw, 0f, 0.35f)
        worn(s)
        s.push().trs(0f, 0.35f, 0f, 0f, PI_F * 0.5f, 0f)
        s.cylinder(0f, -1.1f, 0f, 0.62f, 0.62f, 2.2f, 12, caps = false)
        s.dome(0f, 1.1f, 0f, 0.62f, 0.4f, 3, 12)
        dark(s); s.cylinder(0f, -1.3f, 0f, 0.5f, 0.62f, 0.2f, 12)
        s.color(0.85f, 0.62f, 0.25f).mat(Mat.WORN); s.cylinder(0f, -0.2f, 0f, 0.64f, 0.64f, 0.3f, 12, caps = false)
        dark(s); s.torus(0f, 0.55f, 0f, 0.64f, 0.035f, 12, 5); s.torus(0f, -0.75f, 0f, 0.64f, 0.035f, 12, 5)
        s.pop()
        // torn chute cloth on the ground
        s.color(0.9f, 0.86f, 0.8f).mat(Mat.GRIP)
        for (i in 0 until 5) {
            val a = 2.2f + i * 0.35f
            s.flatQuad(0.2f, 0.9f, 0f, 2.6f * cos(a), 0.06f + (i % 2) * 0.25f, 2.6f * sin(a), 2.6f * cos(a + 0.35f), 0.08f + ((i + 1) % 2) * 0.25f, 2.6f * sin(a + 0.35f), 0.2f, 0.9f, 0.01f)
            s.flatQuad(0.2f, 0.9f, 0.01f, 2.6f * cos(a + 0.35f), 0.08f + ((i + 1) % 2) * 0.25f, 2.6f * sin(a + 0.35f), 2.6f * cos(a), 0.06f + (i % 2) * 0.25f, 2.6f * sin(a), 0.2f, 0.9f, 0f)
        }
        g.color(1f, 0.8f, 0.4f, 1.5f); g.sphere(0f, 1.05f, 0f, 0.07f, 0.07f, 0.07f, 4, 6)
        colliders.add(Collider(p.x, p.z, 0.9f, p.y + 1.4f))
        add(p, s, g, 3f)
    }

    private fun wreck(p: Poi) {
        val s = MeshBuilder(1024); val g = MeshBuilder()
        s.trs(p.x, p.y + 0.1f, p.z, p.yaw, 0.06f, 0.14f); g.trs(p.x, p.y + 0.1f, p.z, p.yaw, 0.06f, 0.14f)
        worn(s)
        s.box(0f, 1.0f, 0f, 2.2f, 0.7f, 4.4f)
        s.box(0f, 1.75f, 0.9f, 2.0f, 0.9f, 2.0f)
        dark(s); s.box(0f, 1.85f, 1.92f, 1.6f, 0.55f, 0.06f); s.box(1.02f, 1.85f, 0.9f, 0.06f, 0.5f, 1.4f); s.box(-1.02f, 1.85f, 0.9f, 0.06f, 0.5f, 1.4f)
        s.color(0.85f, 0.62f, 0.25f).mat(Mat.WORN); s.box(0f, 1.38f, -1.2f, 2.22f, 0.12f, 1.6f)
        for (i in 0 until 6) {
            if (i == 4) continue
            val x = if (i % 2 == 0) -1.3f else 1.3f; val z = -1.6f + (i / 2) * 1.6f
            s.color(0.18f, 0.18f, 0.2f).mat(Mat.GRIP)
            s.push().trs(x, 0.55f, z, 0f, 0f, PI_F * 0.5f); s.cylinder(0f, -0.2f, 0f, 0.56f, 0.56f, 0.4f, 12); s.pop()
            dark(s); s.push().trs(x, 0.55f, z, 0f, 0f, PI_F * 0.5f); s.cylinder(0f, -0.22f, 0f, 0.25f, 0.25f, 0.44f, 8); s.pop()
        }
        // the lost wheel lies a few metres off
        s.color(0.18f, 0.18f, 0.2f).mat(Mat.GRIP); s.cylinder(3.2f, -0.1f, -2.4f, 0.56f, 0.56f, 0.4f, 12)
        // roof rack and mast
        dark(s); s.cylinder(-0.7f, 2.2f, -0.6f, 0.04f, 0.03f, 1.6f, 5)
        hull(s); s.box(0.3f, 2.3f, 0.9f, 1.2f, 0.2f, 1.0f)
        g.color(1f, 0.8f, 0.4f, 1.4f); g.sphere(-0.7f, 3.84f, -0.6f, 0.06f, 0.06f, 0.06f, 4, 6)
        g.color(1f, 0.8f, 0.4f, 0.5f); g.box(0f, 1.15f, 2.21f, 1.4f, 0.12f, 0.02f)
        colliders.add(Collider(p.x, p.z, 2.6f, p.y + 2.6f))
        add(p, s, g, 6f)
    }

    private fun vault(p: Poi) {
        val s = MeshBuilder(2048); val g = MeshBuilder()
        s.trs(p.x, p.y, p.z); g.trs(p.x, p.y, p.z)
        // a low mound of the same stone as the Watchers, with a round door set into its south face
        s.color(0.4f, 0.42f, 0.5f).mat(Mat.ROCK)
        s.sphere(0f, -0.6f, 0f, 9f, 4.6f, 8f, 8, 14, 0.12f, 5)
        s.color(0.3f, 0.32f, 0.4f).mat(Mat.ROCK)
        s.push().trs(0f, 1.9f, 7.2f, 0f, -0.35f, 0f)
        s.push().trs(0f, 0f, 0f, 0f, PI_F * 0.5f, 0f)
        s.torus(0f, 0f, 0f, 2.5f, 0.5f, 20, 8)
        s.color(0.22f, 0.23f, 0.3f).mat(Mat.ROCK); s.cylinder(0f, -0.15f, 0f, 2.3f, 2.3f, 0.3f, 20)
        s.pop()
        g.push().trs(0f, 0f, 0f, 0f, PI_F * 0.5f, 0f)
        // spiral seam that lights up when the world is awake
        g.color(1f, 0.4f, 0.55f, 1.4f)
        var pa = 0f; var pr = 0.2f
        for (i in 0 until 46) {
            val a = pa + 0.32f; val r = pr + 0.044f
            g.beam(cos(pa) * pr, 0.17f, sin(pa) * pr, cos(a) * r, 0.17f, sin(a) * r, 0.05f)
            pa = a; pr = r
        }
        g.pop()
        s.pop()
        // nine small markers, one for each sleeper
        for (i in 0 until 9) {
            val a = -0.9f + i * (PI_F + 1.8f) / 8f
            val x = cos(a) * 11.5f; val z = sin(a) * 11.5f
            s.color(0.36f, 0.38f, 0.46f).mat(Mat.ROCK)
            s.cylinder(x, -0.5f, z, 0.34f, 0.2f, 2.3f, 5, flat = true)
            g.color(1f, 0.4f, 0.55f, 1.2f); g.sphere(x, 1.9f, z, 0.09f, 0.09f, 0.09f, 4, 6)
            colliders.add(Collider(p.x + x, p.z + z, 0.45f, p.y + 2f))
        }
        colliders.add(Collider(p.x, p.z, 6.6f, p.y + 5f))
        add(p, s, g, 16f)
    }

    private fun watcher(s: MeshBuilder, g: MeshBuilder, bl: MeshBuilder) {
        // a tall, thin, patient stone: twisted five-sided column, narrow shoulders, a smooth head with one slit
        var y = -1.5f
        val seg = 5
        var r = 0.62f
        for (i in 0 until 6) {
            val h = if (i == 0) 2.2f else 0.95f
            val r2 = when (i) { 0 -> 0.5f; 4 -> 0.52f; 5 -> 0.24f; else -> r * 0.93f }
            val sh = 0.9f + (i % 2) * 0.1f
            s.color(0.36f * sh, 0.38f * sh, 0.47f * sh).mat(Mat.ROCK)
            s.push().trs(0f, y, 0f, i * 0.22f, 0f, 0f)
            s.cylinder(0f, 0f, 0f, r, r2, h, seg, caps = false, flat = true)
            s.pop()
            y += h; r = r2
        }
        // head, tilted a little forward (+z is the way it looks)
        s.color(0.42f, 0.44f, 0.54f).mat(Mat.ROCK)
        s.sphere(0f, y + 0.5f, 0.06f, 0.36f, 0.56f, 0.4f, 8, 10)
        s.color(0.05f, 0.05f, 0.08f)
        s.box(0f, y + 0.52f, 0.41f, 0.05f, 0.5f, 0.08f)
        g.color(1f, 0.4f, 0.55f, 1.5f)
        g.box(0f, y + 0.52f, 0.455f, 0.022f, 0.44f, 0.01f)
        // the bloom: a crown of petals and hanging lights, only seen once the world is awake
        for (i in 0 until 9) {
            val a = i * TWO_PI / 9f
            bl.color(0.98f, 0.32f, 0.42f).mat(Mat.LEAVES)
            bl.push().trs(0f, y + 0.95f, 0f, a, 0f, 0.5f)
            bl.sphere(0.75f, 0.1f, 0f, 0.8f, 0.07f, 0.3f, 4, 8)
            bl.pop()
            bl.color(1f, 0.7f, 0.5f, 1.3f)
            bl.sphere(cos(a) * 1.25f, y + 1.3f, sin(a) * 1.25f, 0.07f, 0.07f, 0.07f, 4, 6)
        }
        bl.color(1f, 0.85f, 0.6f, 1.6f)
        bl.sphere(0f, y + 1.25f, 0f, 0.16f, 0.16f, 0.16f, 5, 8)
    }
}
