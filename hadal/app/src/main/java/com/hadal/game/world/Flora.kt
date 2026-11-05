package com.hadal.game.world

import com.hadal.game.engine.Mat
import com.hadal.game.engine.MeshBuilder
import com.hadal.game.engine.MeshData
import com.hadal.game.engine.PI_F
import com.hadal.game.engine.Rng
import com.hadal.game.engine.TWO_PI
import com.hadal.game.game.Item
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything scattered on the sea floor. [sway] drives the current animation in the instancing shader,
 * [collide] is the collision radius at scale 1 (0 = pass through), [item] makes the object harvestable.
 */
enum class FloraType(
    val sway: Float,
    val collide: Float,
    val collideHeight: Float = 0f,
    val item: Item? = null,
    val amount: Int = 1,
    val respawnMin: Float = 0f,
    val glow: Boolean = false,
) {
    KELP(1.0f, 0f),
    SEAGRASS(0.6f, 0f),
    CORAL_FAN(0.25f, 0f),
    CORAL_BRAIN(0f, 0.9f, 1f),
    CORAL_BRANCH(0.05f, 0f),
    SPONGE(0.02f, 0.6f, 1.4f),
    ROCK(0f, 0.9f, 1.2f),
    BOULDER(0f, 2.6f, 3.5f),
    PILLAR(0f, 3.2f, 26f),
    TUBEWORMS(0.3f, 0f, glow = true),
    CHIMNEY(0f, 2.3f, 14f, glow = true),
    SEA_LILY(0.5f, 0f, glow = true),
    ANEMONE(0.35f, 0f, glow = true),
    BONES(0f, 0f),
    SCRAP(0f, 0f, item = Item.SCRAP, amount = 2, respawnMin = 25f),
    COPPER(0f, 0.8f, 1f, item = Item.COPPER, amount = 2, respawnMin = 30f),
    NODULE(0f, 0f, item = Item.NODULE, amount = 2, respawnMin = 30f),
    CRYSTAL(0f, 0f, item = Item.CRYSTAL, amount = 1, respawnMin = 40f, glow = true),
    CLAM(0f, 0f, item = Item.PEARL, amount = 1, respawnMin = 60f, glow = true),
    BIOPOD(0.4f, 0f, item = Item.BIOSAMPLE, amount = 1, respawnMin = 20f, glow = true),
    ALLOY(0f, 0f, item = Item.ALLOY, amount = 1, respawnMin = 50f, glow = true);

    val harvestable get() = item != null
}

object FloraMeshes {
    private val cache = HashMap<FloraType, MeshData>()

    @Synchronized
    fun get(t: FloraType): MeshData = cache.getOrPut(t) { build(t) }

    private fun build(t: FloraType): MeshData {
        val b = MeshBuilder(512)
        val r = Rng(400L + t.ordinal * 17)
        when (t) {
            FloraType.KELP -> kelp(b, r)
            FloraType.SEAGRASS -> {
                b.color(0.36f, 0.42f, 0.2f)
                for (i in 0 until 18) {
                    val a = r.range(0f, TWO_PI); val d = r.range(0f, 0.6f)
                    b.blade(cos(a) * d, sin(a) * d, 0.07f, r.range(0.5f, 1.1f), r.range(0f, TWO_PI), r.range(0.05f, 0.25f))
                }
            }
            FloraType.CORAL_FAN -> {
                b.color(0.62f, 0.32f, 0.5f)
                fan(b, r, 0f, 0f, 0f, PI_F / 2f, 1.5f, 4)
            }
            FloraType.CORAL_BRAIN -> {
                b.color(0.6f, 0.52f, 0.36f).mat(Mat.CORAL)
                b.sphere(0f, 0.35f, 0f, 0.9f, 0.62f, 0.9f, 7, 12, 0.12f, 3)
            }
            FloraType.CORAL_BRANCH -> {
                b.color(0.85f, 0.55f, 0.4f)
                for (i in 0 until 5) branch(b, r, r.range(-0.3f, 0.3f), 0f, r.range(-0.3f, 0.3f), r.range(0f, TWO_PI), r.range(0.2f, 0.6f), 0.07f, 0.7f, 3)
            }
            FloraType.SPONGE -> {
                b.color(0.55f, 0.42f, 0.3f).mat(Mat.CORAL)
                b.cylinder(0f, 0f, 0f, 0.45f, 0.75f, 1.5f, 12, caps = false)
                b.color(0.2f, 0.14f, 0.1f)
                b.disc(0f, 1.2f, 0f, 0.6f, 12, true)
                b.color(0.55f, 0.42f, 0.3f).mat(Mat.CORAL)
                b.cylinder(0f, 1.5f, 0f, 0.75f, 0.6f, 0.08f, 12, caps = false)
            }
            FloraType.ROCK -> {
                b.color(0.5f, 0.5f, 0.5f).mat(Mat.ROCK)
                b.sphere(0f, 0.3f, 0f, 1f, 0.75f, 0.85f, 6, 9, 0.25f, 5, flat = true)
            }
            FloraType.BOULDER -> {
                b.color(0.48f, 0.48f, 0.5f).mat(Mat.ROCK)
                b.sphere(0f, 1f, 0f, 3f, 2.4f, 2.6f, 8, 12, 0.22f, 9, flat = true)
                b.sphere(1.8f, 0.4f, 1.2f, 1.4f, 1.1f, 1.2f, 5, 8, 0.2f, 11, flat = true)
            }
            FloraType.PILLAR -> {
                b.color(0.46f, 0.46f, 0.48f).mat(Mat.ROCK)
                var y = 0f; var rad = 3.6f
                var ox = 0f; var oz = 0f
                for (i in 0 until 6) {
                    val h = r.range(3.5f, 5.5f)
                    b.sphere(ox, y + h * 0.5f, oz, rad, h * 0.75f, rad * r.range(0.8f, 1.1f), 6, 10, 0.25f, 20 + i, flat = true)
                    y += h * 0.8f; rad *= r.range(0.72f, 0.88f)
                    ox += r.range(-0.6f, 0.6f); oz += r.range(-0.6f, 0.6f)
                }
            }
            FloraType.TUBEWORMS -> {
                for (i in 0 until 16) {
                    val a = r.range(0f, TWO_PI); val d = r.range(0f, 0.7f)
                    val x = cos(a) * d; val z = sin(a) * d
                    val h = r.range(0.6f, 1.8f)
                    b.color(0.82f, 0.8f, 0.72f).mat(Mat.SKIN)
                    b.cylinder(x, 0f, z, 0.07f, 0.06f, h, 6, caps = false)
                    b.color(0.9f, 0.12f, 0.1f, 0.35f)
                    b.sphere(x, h + 0.08f, z, 0.11f, 0.14f, 0.11f, 4, 6)
                }
            }
            FloraType.CHIMNEY -> {
                b.color(0.3f, 0.28f, 0.27f).mat(Mat.BASALT)
                var y = 0f; var rad = 2.2f
                for (i in 0 until 5) {
                    val h = r.range(2.4f, 3.4f)
                    b.cylinder(r.range(-0.2f, 0.2f), y, r.range(-0.2f, 0.2f), rad, rad * 0.82f, h, 9, caps = false, flat = true)
                    y += h; rad *= 0.82f
                }
                b.color(1f, 0.45f, 0.12f, 2.2f)
                b.cylinder(0f, y - 0.1f, 0f, rad * 1.02f, rad * 0.8f, 0.35f, 9, caps = false)
                b.color(1f, 0.55f, 0.2f, 3f)
                b.disc(0f, y + 0.2f, 0f, rad * 0.8f, 9, true)
                // glowing mineral veins
                for (k in 0 until 6) {
                    val a = r.range(0f, TWO_PI); val vy = r.range(1f, y - 1f)
                    val rr = 2.2f * (1f - vy / (y + 2f))
                    b.color(1f, 0.4f, 0.1f, 1.2f)
                    b.box(cos(a) * rr, vy, sin(a) * rr, 0.12f, r.range(0.6f, 1.6f), 0.12f)
                }
            }
            FloraType.SEA_LILY -> {
                b.color(0.7f, 0.6f, 0.75f).mat(Mat.SKIN)
                b.cylinder(0f, 0f, 0f, 0.05f, 0.035f, 2.4f, 5, caps = false)
                b.color(0.35f, 0.9f, 1f, 1.6f)
                for (i in 0 until 10) {
                    val a = i * TWO_PI / 10
                    b.push().trs(0f, 2.4f, 0f, a, 0.9f).box(0f, 0.35f, 0f, 0.05f, 0.7f, 0.03f).pop()
                }
                b.sphere(0f, 2.4f, 0f, 0.12f, 0.12f, 0.12f, 4, 6)
            }
            FloraType.ANEMONE -> {
                b.color(0.45f, 0.25f, 0.4f).mat(Mat.SKIN)
                b.cylinder(0f, 0f, 0f, 0.35f, 0.3f, 0.45f, 8)
                for (i in 0 until 22) {
                    val a = r.range(0f, TWO_PI); val d = r.range(0f, 0.28f)
                    b.color(0.95f, 0.35f, 0.85f, 1.1f)
                    b.push().trs(cos(a) * d, 0.45f, sin(a) * d, a, r.range(0.1f, 0.8f)).cylinder(0f, 0f, 0f, 0.035f, 0.005f, r.range(0.35f, 0.7f), 4, caps = false).pop()
                }
            }
            FloraType.BONES -> {
                b.color(0.78f, 0.75f, 0.66f).mat(Mat.SKIN)
                // spine
                b.push().trs(0f, 0.6f, 0f, 0f, PI_F / 2f).cylinder(0f, -7f, 0f, 0.4f, 0.3f, 14f, 6, flat = true).pop()
                for (i in 0 until 9) {
                    val z = -5.5f + i * 1.3f
                    for (side in listOf(-1f, 1f)) {
                        var px = 0f; var py = 0.6f
                        for (s in 0 until 5) {
                            val a = s * 0.33f
                            val nx = px + side * cos(a) * 0.9f; val ny = py + sin(a) * 0.9f + 0.2f
                            b.push().trs(px, py, z, 0f, 0f, -kotlin.math.atan2(nx - px, ny - py)).cylinder(0f, 0f, 0f, 0.12f, 0.1f, 1f, 5, caps = false).pop()
                            px = nx; py = ny
                        }
                    }
                }
            }
            FloraType.SCRAP -> {
                b.color(0.5f, 0.5f, 0.5f).mat(Mat.RUST)
                for (i in 0 until 6) {
                    b.push().trs(r.range(-0.6f, 0.6f), r.range(0.1f, 0.4f), r.range(-0.6f, 0.6f), r.range(0f, TWO_PI), r.range(-0.5f, 0.5f), r.range(-0.5f, 0.5f))
                    if (i % 2 == 0) b.box(0f, 0f, 0f, r.range(0.4f, 1.1f), 0.06f, r.range(0.3f, 0.8f))
                    else b.cylinder(0f, -0.3f, 0f, 0.12f, 0.12f, r.range(0.5f, 1f), 7)
                    b.pop()
                }
                b.color(0.8f, 0.62f, 0.12f).mat(Mat.PAINT)
                b.push().trs(0.2f, 0.25f, 0.1f, 0.5f, 0.3f, 0f).box(0f, 0f, 0f, 0.5f, 0.35f, 0.4f).pop()
            }
            FloraType.COPPER -> {
                b.color(0.45f, 0.45f, 0.46f).mat(Mat.ROCK)
                b.sphere(0f, 0.3f, 0f, 1f, 0.8f, 0.9f, 6, 9, 0.25f, 31, flat = true)
                for (i in 0 until 9) {
                    val a = r.range(0f, TWO_PI); val e = r.range(0.1f, 1.1f)
                    b.color(0.2f, 0.62f, 0.5f, 0.12f)
                    b.push().trs(cos(a) * 0.75f, 0.3f + sin(e) * 0.5f, sin(a) * 0.7f, a, e).box(0f, 0f, 0f, 0.28f, 0.16f, 0.22f).pop()
                }
            }
            FloraType.NODULE -> {
                b.color(0.13f, 0.12f, 0.11f).mat(Mat.BASALT)
                for (i in 0 until 7) {
                    val s = r.range(0.15f, 0.32f)
                    b.sphere(r.range(-0.6f, 0.6f), s * 0.5f, r.range(-0.6f, 0.6f), s, s * 0.75f, s, 5, 7, 0.3f, 40 + i, flat = true)
                }
            }
            FloraType.CRYSTAL -> {
                b.color(0.25f, 0.24f, 0.24f).mat(Mat.BASALT)
                b.sphere(0f, 0.1f, 0f, 0.7f, 0.35f, 0.7f, 4, 7, 0.2f, 50, flat = true)
                for (i in 0 until 7) {
                    val a = r.range(0f, TWO_PI)
                    b.color(1f, 0.62f, 0.2f, 2.4f)
                    b.push().trs(cos(a) * 0.25f, 0.2f, sin(a) * 0.25f, a, r.range(0.1f, 0.6f), r.range(-0.4f, 0.4f))
                        .cylinder(0f, 0f, 0f, 0.1f, 0.0f, r.range(0.6f, 1.3f), 6, caps = false, flat = true).pop()
                }
            }
            FloraType.CLAM -> {
                b.color(0.5f, 0.48f, 0.45f).mat(Mat.CORAL)
                b.push().trs(0f, 0.2f, 0f, 0f, -0.25f).sphere(0f, 0f, 0f, 0.8f, 0.25f, 0.6f, 5, 10, 0.08f, 60).pop()
                b.push().trs(0f, 0.32f, -0.1f, 0f, 0.45f).sphere(0f, 0f, 0f, 0.8f, 0.22f, 0.6f, 5, 10, 0.08f, 61).pop()
                b.color(0.3f, 0.6f, 0.75f, 0.6f)
                b.box(0f, 0.28f, 0.25f, 1.2f, 0.06f, 0.08f)
                b.color(0.95f, 0.95f, 1f, 1.4f)
                b.sphere(0f, 0.3f, 0.05f, 0.12f, 0.12f, 0.12f, 5, 7)
            }
            FloraType.BIOPOD -> {
                b.color(0.3f, 0.4f, 0.35f).mat(Mat.KELP)
                for (i in 0 until 5) {
                    val a = i * TWO_PI / 5 + r.range(0f, 0.4f)
                    b.push().trs(0f, 0f, 0f, a, 0.5f).cylinder(0f, 0f, 0f, 0.05f, 0.02f, 0.9f, 4, caps = false).pop()
                }
                b.color(0.3f, 1f, 0.8f, 2f)
                b.sphere(0f, 0.75f, 0f, 0.26f, 0.38f, 0.26f, 6, 8)
                b.color(0.2f, 0.8f, 0.9f, 1.2f)
                for (i in 0 until 4) { val a = i * 1.57f + 0.4f; b.sphere(cos(a) * 0.45f, 0.35f, sin(a) * 0.45f, 0.1f, 0.14f, 0.1f, 4, 6) }
            }
            FloraType.ALLOY -> {
                b.color(0.85f, 0.85f, 0.82f).mat(Mat.METAL)
                b.push().trs(0f, 0.4f, 0f, 0.3f, 0.2f, 0.35f).box(0f, 0f, 0f, 0.8f, 1.1f, 0.35f).pop()
                b.color(0.4f, 0.75f, 1f, 2.2f)
                b.push().trs(0f, 0.4f, 0f, 0.3f, 0.2f, 0.35f).box(0f, 0f, 0.18f, 0.06f, 0.9f, 0.02f).box(0f, 0.2f, 0.18f, 0.6f, 0.05f, 0.02f).pop()
            }
        }
        return b.build()
    }

    private fun kelp(b: MeshBuilder, r: Rng) {
        val h = 14f
        val segs = 10
        b.color(0.42f, 0.36f, 0.16f).mat(Mat.KELP)
        b.cylinder(0f, 0f, 0f, 0.06f, 0.04f, h, 5, caps = false)
        // holdfast
        b.color(0.3f, 0.26f, 0.12f).mat(Mat.KELP)
        b.sphere(0f, 0.1f, 0f, 0.35f, 0.2f, 0.35f, 4, 7, 0.3f, 5)
        for (i in 1 until segs * 2) {
            val y = i * h / (segs * 2) + r.range(-0.2f, 0.2f)
            val a = i * 2.3f + r.range(-0.4f, 0.4f)
            val len = r.range(1.2f, 2.2f) * (0.5f + 0.5f * (1f - y / h * 0.5f))
            val w = r.range(0.3f, 0.5f)
            b.color(0.52f + r.range(-0.05f, 0.05f), 0.44f, 0.18f).mat(Mat.KELP)
            // bladder
            b.sphere(cos(a) * 0.12f, y, sin(a) * 0.12f, 0.07f, 0.09f, 0.07f, 3, 5)
            // a gently curved double sided blade hanging out from the stipe
            b.push().trs(0f, y, 0f, a, 0f, 0f)
            val n = 4
            var px = 0.1f; var py = 0f
            for (s in 0 until n) {
                val t0 = s.toFloat() / n; val t1 = (s + 1).toFloat() / n
                val nx = 0.1f + len * t1 * 0.7f; val ny = len * t1 * 0.75f - len * t1 * t1 * 0.1f
                val w0 = w * sin((t0 * 0.9f + 0.1f) * PI_F); val w1 = w * sin((t1 * 0.9f + 0.1f) * PI_F)
                // blades are vertical ribbons (they stream upward in the water), with a slight twist
                val tw0 = s * 0.25f; val tw1 = (s + 1) * 0.25f
                val a0 = b.vertex(px, py - w0 * 0.5f, -tw0 * w0, 0f, 0f, 1f); val a1 = b.vertex(nx, ny - w1 * 0.5f, -tw1 * w1, 0f, 0f, 1f)
                val a2 = b.vertex(nx, ny + w1 * 0.5f, tw1 * w1, 0f, 0f, 1f); val a3 = b.vertex(px, py + w0 * 0.5f, tw0 * w0, 0f, 0f, 1f)
                b.quad(a0, a1, a2, a3)
                val c0 = b.vertex(px, py - w0 * 0.5f, -tw0 * w0, 0f, 0f, -1f); val c1 = b.vertex(nx, ny - w1 * 0.5f, -tw1 * w1, 0f, 0f, -1f)
                val c2 = b.vertex(nx, ny + w1 * 0.5f, tw1 * w1, 0f, 0f, -1f); val c3 = b.vertex(px, py + w0 * 0.5f, tw0 * w0, 0f, 0f, -1f)
                b.quad(c0, c3, c2, c1)
                px = nx; py = ny
            }
            b.pop()
        }
    }

    private fun fan(b: MeshBuilder, r: Rng, x: Float, y: Float, z: Float, ang: Float, len: Float, depth: Int) {
        if (depth == 0 || len < 0.12f) return
        val nx = x + cos(ang) * len * 0.5f
        val ny = y + sin(ang) * len * 0.5f
        b.push().trs((x + nx) / 2f, (y + ny) / 2f, z, 0f, 0f, ang - PI_F / 2f).box(0f, 0f, 0f, 0.05f * depth * 0.6f, len * 0.5f, 0.03f).pop()
        // lattice webbing between branches
        b.push().trs((x + nx) / 2f, (y + ny) / 2f, z, 0f, 0f, ang - PI_F / 2f).box(0f, 0f, 0f, len * 0.35f, len * 0.45f, 0.01f).pop()
        val n = 2 + r.nextInt(2)
        for (i in 0 until n) fan(b, r, nx, ny, z, ang + r.range(-0.7f, 0.7f), len * r.range(0.55f, 0.75f), depth - 1)
    }

    private fun branch(b: MeshBuilder, r: Rng, x: Float, y: Float, z: Float, yaw: Float, pitch: Float, rad: Float, len: Float, depth: Int) {
        if (depth == 0) return
        b.push().trs(x, y, z, yaw, pitch).cylinder(0f, 0f, 0f, rad, rad * 0.7f, len, 5, caps = false).pop()
        val dy = cos(pitch) * len; val dh = sin(pitch) * len
        val ex = x + sin(yaw) * dh; val ez = z + cos(yaw) * dh
        if (depth == 1) {
            b.push().trs(ex, y + dy, ez).sphere(0f, 0f, 0f, rad * 0.8f, rad * 0.8f, rad * 0.8f, 3, 5).pop()
        }
        for (i in 0 until 2) branch(b, r, ex, y + dy, ez, yaw + r.range(-1.2f, 1.2f), pitch + r.range(-0.3f, 0.5f), rad * 0.7f, len * 0.75f, depth - 1)
    }
}
