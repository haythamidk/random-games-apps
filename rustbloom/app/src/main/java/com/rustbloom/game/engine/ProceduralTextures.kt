package com.rustbloom.game.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generates every surface texture in code (no image assets): tileable albedo layers (RGB + height/alpha)
 * and matching normal layers (normal xy, roughness, ambient occlusion). Results are cached on disk.
 *
 * Terrain layers (0..7) are full color. All other layers are "detail" textures centered on mid grey:
 * shaders multiply them with the vertex color (times two).
 */
object ProceduralTextures {
    const val SIZE = 512
    const val NSIZE = 256
    private const val VERSION = 4

    class Result(val albedo: List<ByteArray>, val normals: List<ByteArray>)

    @Volatile var result: Result? = null
        private set

    fun load(cacheDir: File?, pool: ExecutorService?): Result {
        result?.let { return it }
        val f = cacheDir?.let { File(it, "textures_v$VERSION.bin") }
        if (f != null && f.exists()) {
            try {
                val r = read(f)
                result = r
                return r
            } catch (_: Throwable) { f.delete() }
        }
        val layers = (0 until Mat.COUNT).toList()
        val out: List<Pair<ByteArray, ByteArray>> = if (pool != null) {
            val futures = layers.map { l -> pool.submit(Callable { generate(l) }) }
            futures.map { it.get() }
        } else layers.map { generate(it) }
        val r = Result(out.map { it.first }, out.map { it.second })
        if (f != null) try { write(f, r) } catch (_: Throwable) {}
        result = r
        return r
    }

    private fun write(f: File, r: Result) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { o ->
            o.writeInt(VERSION); o.writeInt(r.albedo.size)
            for (a in r.albedo) o.write(a)
            for (n in r.normals) o.write(n)
        }
        tmp.renameTo(f)
    }

    private fun read(f: File): Result {
        DataInputStream(f.inputStream().buffered(1 shl 16)).use { i ->
            if (i.readInt() != VERSION) throw IllegalStateException("version")
            val n = i.readInt()
            if (n != Mat.COUNT) throw IllegalStateException("count")
            val a = List(n) { ByteArray(SIZE * SIZE * 4).also { b -> i.readFully(b) } }
            val nm = List(n) { ByteArray(NSIZE * NSIZE * 4).also { b -> i.readFully(b) } }
            return Result(a, nm)
        }
    }

    // ------------------------------------------------------------------ tileable noise

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xffffff) / 16777215f
    }

    /** Periodic value noise in [0,1]; px/py are integer periods across the unit tile. */
    fun vnoise(u: Float, v: Float, px: Int, py: Int, seed: Int): Float {
        val x = u * px; val y = v * py
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        val sx = fx * fx * fx * (fx * (fx * 6 - 15) + 10)
        val sy = fy * fy * fy * (fy * (fy * 6 - 15) + 10)
        val x0 = Math.floorMod(xi, px); val x1 = Math.floorMod(xi + 1, px)
        val y0 = Math.floorMod(yi, py); val y1 = Math.floorMod(yi + 1, py)
        val a = hash(x0, y0, seed); val b = hash(x1, y0, seed)
        val c = hash(x0, y1, seed); val d = hash(x1, y1, seed)
        return lerp(lerp(a, b, sx), lerp(c, d, sx), sy)
    }

    fun fbm(u: Float, v: Float, p: Int, oct: Int, seed: Int, py: Int = p): Float {
        var s = 0f; var a = 1f; var n = 0f; var fx = p; var fy = py
        for (o in 0 until oct) {
            s += vnoise(u, v, fx, fy, seed + o * 97) * a
            n += a; a *= 0.5f; fx *= 2; fy *= 2
        }
        return s / n
    }

    /** Periodic Worley noise: returns F1 and F2-F1 packed (f1 in [0], edge in [1]). */
    fun worley(u: Float, v: Float, p: Int, seed: Int, out: FloatArray) {
        val x = u * p; val y = v * p
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        var f1 = 9f; var f2 = 9f
        for (dy in -1..1) for (dx in -1..1) {
            val cx = xi + dx; val cy = yi + dy
            val wx = Math.floorMod(cx, p); val wy = Math.floorMod(cy, p)
            val px = cx + hash(wx, wy, seed); val py = cy + hash(wx, wy, seed + 7)
            val d = sqrt((px - x) * (px - x) + (py - y) * (py - y))
            if (d < f1) { f2 = f1; f1 = d } else if (d < f2) f2 = d
        }
        out[0] = f1; out[1] = f2 - f1
    }

    // ------------------------------------------------------------------ painting helpers

    private class Img(val n: Int) {
        val r = FloatArray(n * n); val g = FloatArray(n * n); val b = FloatArray(n * n)
        val a = FloatArray(n * n) { 1f }
        val h = FloatArray(n * n)
        val rough = FloatArray(n * n) { 0.8f }

        fun set(i: Int, cr: Float, cg: Float, cb: Float) { r[i] = cr; g[i] = cg; b[i] = cb }

        /** Draws a rotated ellipse with wrap-around (tileable). */
        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, ang: Float, cr: Float, cg: Float, cb: Float, height: Float, alpha: Float = 1f, vein: Boolean = false) {
            val ca = cos(ang); val sa = sin(ang)
            val ext = max(rx, ry).toInt() + 2
            for (dy in -ext..ext) for (dx in -ext..ext) {
                val lx = (dx * ca + dy * sa) / rx
                val ly = (-dx * sa + dy * ca) / ry
                val d = lx * lx + ly * ly
                if (d > 1f) continue
                val x = Math.floorMod((cx + dx).toInt(), n); val y = Math.floorMod((cy + dy).toInt(), n)
                val i = y * n + x
                val dome = sqrt(1f - d)
                var shade = 0.85f + 0.15f * dome
                if (vein && abs(ly) < 0.08f) shade *= 0.8f
                r[i] = cr * shade; g[i] = cg * shade; b[i] = cb * shade
                a[i] = max(a[i], alpha)
                h[i] = max(h[i], height * dome)
            }
        }

        fun line(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, cr: Float, cg: Float, cb: Float, height: Float, alpha: Float = 1f) {
            val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            val steps = max(2, (len * 1.5f).toInt())
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val px = x0 + (x1 - x0) * t; val py = y0 + (y1 - y0) * t
                val e = w.toInt() + 1
                for (dy in -e..e) for (dx in -e..e) {
                    if (dx * dx + dy * dy > w * w) continue
                    val x = Math.floorMod((px + dx).toInt(), n); val y = Math.floorMod((py + dy).toInt(), n)
                    val i = y * n + x
                    r[i] = cr; g[i] = cg; b[i] = cb
                    a[i] = max(a[i], alpha); h[i] = max(h[i], height)
                }
            }
        }

        fun toAlbedo(): ByteArray {
            val out = ByteArray(n * n * 4)
            for (i in 0 until n * n) {
                out[i * 4] = (clamp01(r[i]) * 255).toInt().toByte()
                out[i * 4 + 1] = (clamp01(g[i]) * 255).toInt().toByte()
                out[i * 4 + 2] = (clamp01(b[i]) * 255).toInt().toByte()
                out[i * 4 + 3] = (clamp01(a[i]) * 255).toInt().toByte()
            }
            return out
        }

        /** Normal map at half resolution from the height field. */
        fun toNormal(strength: Float, alphaMask: Boolean): ByteArray {
            val m = NSIZE
            val k = n / m
            val hs = FloatArray(m * m)
            val rs = FloatArray(m * m)
            for (y in 0 until m) for (x in 0 until m) {
                var s = 0f; var rr = 0f
                for (yy in 0 until k) for (xx in 0 until k) { val i = (y * k + yy) * n + x * k + xx; s += h[i]; rr += rough[i] }
                hs[y * m + x] = s / (k * k); rs[y * m + x] = rr / (k * k)
            }
            val out = ByteArray(m * m * 4)
            for (y in 0 until m) for (x in 0 until m) {
                fun H(xx: Int, yy: Int) = hs[Math.floorMod(yy, m) * m + Math.floorMod(xx, m)]
                val dx = (H(x + 1, y) - H(x - 1, y)) * strength
                val dy = (H(x, y + 1) - H(x, y - 1)) * strength
                var nx = -dx; var ny = -dy; val nz = 1f
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                nx /= l; ny /= l
                // cavity based ambient occlusion
                val avg = (H(x - 2, y) + H(x + 2, y) + H(x, y - 2) + H(x, y + 2)) * 0.25f
                val ao = clamp01(1f - max(0f, avg - H(x, y)) * 4f)
                val o = (y * m + x) * 4
                out[o] = ((nx * 0.5f + 0.5f) * 255).toInt().toByte()
                out[o + 1] = ((ny * 0.5f + 0.5f) * 255).toInt().toByte()
                out[o + 2] = (clamp01(rs[y * m + x]) * 255).toInt().toByte()
                out[o + 3] = (clamp01(if (alphaMask) 1f else ao) * 255).toInt().toByte()
            }
            return out
        }
    }

    private fun generate(layer: Int): Pair<ByteArray, ByteArray> {
        val n = SIZE
        val img = Img(n)
        val rng = Rng(9000L + layer * 131)
        val w = FloatArray(2)
        var strength = 6f
        var alphaMask = false
        val inv = 1f / n
        when (layer) {
            Mat.GRASS -> {
                strength = 5f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val base = fbm(u, v, 4, 4, 1)
                    val blades = vnoise(u + fbm(u, v, 8, 2, 5) * 0.02f, v, 160, 28, 3)
                    val blades2 = vnoise(u, v + fbm(u, v, 8, 2, 6) * 0.02f, 34, 140, 4)
                    val bl = max(blades, blades2)
                    val dry = smoothstep(0.6f, 0.75f, fbm(u, v, 3, 3, 9))
                    val i = y * n + x
                    var cr = lerp(0.13f, 0.24f, base); var cg = lerp(0.24f, 0.36f, base); var cb = lerp(0.06f, 0.1f, base)
                    cr = lerp(cr, 0.42f, dry * 0.6f); cg = lerp(cg, 0.4f, dry * 0.5f); cb = lerp(cb, 0.14f, dry * 0.5f)
                    val s = 0.65f + bl * 0.6f
                    img.set(i, cr * s, cg * s, cb * s)
                    img.h[i] = bl * 0.7f + base * 0.3f
                    img.rough[i] = 0.9f
                }
            }
            Mat.FOREST -> {
                strength = 7f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 5, 11)
                    val i = y * n + x
                    img.set(i, 0.17f + f * 0.12f, 0.12f + f * 0.08f, 0.07f + f * 0.04f)
                    img.h[i] = f * 0.4f
                    img.rough[i] = 0.85f
                }
                val leafCols = arrayOf(floatArrayOf(0.34f, 0.24f, 0.12f), floatArrayOf(0.28f, 0.2f, 0.11f), floatArrayOf(0.24f, 0.25f, 0.12f), floatArrayOf(0.36f, 0.29f, 0.15f), floatArrayOf(0.2f, 0.15f, 0.09f))
                for (k in 0 until 700) {
                    val c = leafCols[rng.nextInt(leafCols.size)]
                    val s = rng.range(0.7f, 1.1f)
                    img.ellipse(rng.range(0f, n.toFloat()), rng.range(0f, n.toFloat()), rng.range(6f, 12f), rng.range(3f, 6f), rng.range(0f, 6.28f), c[0] * s, c[1] * s, c[2] * s, rng.range(0.5f, 0.8f), vein = true)
                }
                for (k in 0 until 60) {
                    val x0 = rng.range(0f, n.toFloat()); val y0 = rng.range(0f, n.toFloat()); val a = rng.range(0f, 6.28f); val l = rng.range(20f, 60f)
                    img.line(x0, y0, x0 + cos(a) * l, y0 + sin(a) * l, 1.6f, 0.2f, 0.13f, 0.08f, 0.9f)
                }
            }
            Mat.ROCK -> {
                strength = 9f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 6, 21)
                    worley(u, v, 7, 23, w)
                    val crack = smoothstep(0.06f, 0.0f, w[1])
                    val strata = 0.5f + 0.5f * sin((v * 10f + f * 2.5f) * 6.2831f)
                    val lichen = smoothstep(0.66f, 0.72f, fbm(u, v, 5, 4, 29))
                    val i = y * n + x
                    var c = 0.32f + f * 0.32f + strata * 0.05f
                    c *= 1f - crack * 0.55f
                    img.set(i, c * 1.02f, c, c * 0.95f)
                    if (lichen > 0f) { img.r[i] = lerp(img.r[i], 0.5f, lichen * 0.6f); img.g[i] = lerp(img.g[i], 0.52f, lichen * 0.6f); img.b[i] = lerp(img.b[i], 0.28f, lichen * 0.6f) }
                    img.h[i] = f * 0.8f + strata * 0.1f - crack * 0.4f + (1f - w[0]) * 0.15f
                    img.rough[i] = 0.75f
                }
            }
            Mat.SAND -> {
                strength = 3.5f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val grain = vnoise(u, v, 256, 256, 31)
                    val warp = fbm(u, v, 4, 3, 33)
                    val rip = 0.5f + 0.5f * sin((v + warp * 0.18f) * 6.2831f * 22f)
                    val f = fbm(u, v, 3, 3, 35)
                    val i = y * n + x
                    val s = 0.88f + grain * 0.18f + rip * 0.06f
                    img.set(i, (0.72f + f * 0.08f) * s, (0.64f + f * 0.06f) * s, (0.47f + f * 0.05f) * s)
                    img.h[i] = rip * 0.5f + grain * 0.15f
                    img.rough[i] = 0.92f
                }
            }
            Mat.SNOW -> {
                strength = 2.5f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 4, 5, 41)
                    val sp = vnoise(u, v, 256, 256, 43)
                    val i = y * n + x
                    val c = 0.86f + f * 0.1f
                    img.set(i, c * 0.97f, c * 0.98f, c * 1.03f)
                    img.h[i] = f
                    img.rough[i] = if (sp > 0.96f) 0.1f else 0.55f
                }
            }
            Mat.RUST_SAND -> {
                strength = 4f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 5, 5, 51)
                    val grain = vnoise(u, v, 200, 200, 53)
                    val dark = smoothstep(0.62f, 0.7f, fbm(u, v, 4, 3, 55))
                    val i = y * n + x
                    val s = 0.85f + grain * 0.25f
                    img.set(i, (0.58f + f * 0.12f) * s * (1f - dark * 0.3f), (0.32f + f * 0.06f) * s * (1f - dark * 0.35f), (0.18f + f * 0.04f) * s)
                    img.h[i] = f * 0.5f + grain * 0.2f
                    img.rough[i] = 0.9f
                }
                for (k in 0 until 220) {
                    val g = rng.range(0.15f, 0.3f)
                    img.ellipse(rng.range(0f, n.toFloat()), rng.range(0f, n.toFloat()), rng.range(1.5f, 4f), rng.range(1f, 3f), rng.range(0f, 6.28f), g, g * 0.9f, g * 0.85f, 0.7f)
                }
            }
            Mat.MUD -> {
                strength = 5f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 5, 5, 61)
                    val puddle = smoothstep(0.6f, 0.66f, fbm(u, v, 3, 4, 63))
                    val i = y * n + x
                    val c = 0.16f + f * 0.1f
                    img.set(i, c * 1.1f * (1f - puddle * 0.35f), c * 0.95f * (1f - puddle * 0.3f), c * 0.7f * (1f - puddle * 0.2f))
                    img.h[i] = lerp(f * 0.6f, 0.2f, puddle)
                    img.rough[i] = lerp(0.65f, 0.08f, puddle)
                }
            }
            Mat.NEEDLE_GROUND -> {
                strength = 6f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 4, 71)
                    val i = y * n + x
                    img.set(i, 0.16f + f * 0.08f, 0.12f + f * 0.06f, 0.08f + f * 0.04f)
                    img.h[i] = f * 0.3f
                    img.rough[i] = 0.88f
                }
                for (k in 0 until 2600) {
                    val x0 = rng.range(0f, n.toFloat()); val y0 = rng.range(0f, n.toFloat()); val a = rng.range(0f, 6.28f); val l = rng.range(8f, 16f)
                    val s = rng.range(0.7f, 1.2f)
                    img.line(x0, y0, x0 + cos(a) * l, y0 + sin(a) * l, 0.8f, 0.42f * s, 0.27f * s, 0.12f * s, rng.range(0.4f, 0.9f))
                }
            }
            Mat.BARK -> {
                strength = 10f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val warp = fbm(u, v, 4, 3, 81) * 0.06f
                    val ridges = 1f - abs(vnoise(u + warp, v, 14, 3, 83) * 2f - 1f)
                    val fine = fbm(u, v, 16, 3, 85, 4)
                    val moss = smoothstep(0.64f, 0.74f, fbm(u, v, 3, 4, 87))
                    val i = y * n + x
                    val c = 0.28f + ridges * 0.32f + fine * 0.12f
                    img.set(i, c * 1.05f, c, c * 0.92f)
                    if (moss > 0f) { img.r[i] = lerp(img.r[i], 0.42f, moss); img.g[i] = lerp(img.g[i], 0.6f, moss); img.b[i] = lerp(img.b[i], 0.3f, moss) }
                    img.h[i] = ridges * 0.8f + fine * 0.2f
                    img.rough[i] = 0.92f
                }
            }
            Mat.LEAVES -> {
                alphaMask = true
                strength = 6f
                for (i in 0 until n * n) { img.a[i] = 0f; img.set(i, 0.45f, 0.5f, 0.4f) }
                // leaves grouped in clumps so the canopy shows gaps
                for (c in 0 until 70) {
                    val cx = rng.range(0f, n.toFloat()); val cy = rng.range(0f, n.toFloat())
                    val count = 10 + rng.nextInt(10)
                    for (k in 0 until count) {
                        val a = rng.range(0f, 6.28f); val d = rng.range(0f, 30f)
                        val s = rng.range(0.75f, 1.25f)
                        val yellow = rng.range(0f, 0.15f)
                        img.ellipse(cx + cos(a) * d, cy + sin(a) * d, rng.range(10f, 16f), rng.range(5f, 8f), rng.range(0f, 6.28f), (0.44f + yellow * 0.5f) * s, 0.5f * s, (0.4f - yellow * 0.5f) * s, rng.range(0.6f, 1f), 1f, vein = true)
                    }
                }
            }
            Mat.PINE -> {
                alphaMask = true
                strength = 5f
                for (i in 0 until n * n) { img.a[i] = 0f; img.set(i, 0.4f, 0.5f, 0.42f) }
                for (b in 0 until 46) {
                    val x0 = rng.range(0f, n.toFloat()); val y0 = rng.range(0f, n.toFloat())
                    val a = rng.range(-0.6f, 0.6f) + 1.5708f
                    val len = rng.range(50f, 110f)
                    val x1 = x0 + cos(a) * len; val y1 = y0 + sin(a) * len
                    img.line(x0, y0, x1, y1, 1.6f, 0.35f, 0.3f, 0.25f, 0.6f)
                    val steps = (len / 3).toInt()
                    for (s in 0 until steps) {
                        val t = s.toFloat() / steps
                        val px = x0 + (x1 - x0) * t; val py = y0 + (y1 - y0) * t
                        for (side in listOf(-1f, 1f)) {
                            val na = a + side * rng.range(0.6f, 1.1f)
                            val nl = rng.range(9f, 16f) * (1f - t * 0.4f)
                            val sh = rng.range(0.8f, 1.2f)
                            img.line(px, py, px + cos(na) * nl, py + sin(na) * nl, 0.9f, 0.38f * sh, 0.55f * sh, 0.42f * sh, rng.range(0.5f, 1f))
                        }
                    }
                }
            }
            Mat.CONCRETE -> {
                strength = 5f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 5, 111)
                    val streak = fbm(u, v, 24, 3, 113, 3)
                    val pore = if (vnoise(u, v, 180, 180, 115) > 0.88f) 0.75f else 1f
                    worley(u, v, 4, 117, w)
                    val crack = smoothstep(0.03f, 0.0f, w[1]) * smoothstep(0.55f, 0.6f, fbm(u, v, 3, 2, 119))
                    val i = y * n + x
                    val c = (0.44f + f * 0.14f - streak * 0.08f) * pore * (1f - crack * 0.5f)
                    img.set(i, c, c * 0.99f, c * 0.96f)
                    img.h[i] = f * 0.4f - (1f - pore) * 0.5f - crack * 0.5f
                    img.rough[i] = 0.9f
                }
            }
            Mat.METAL -> {
                strength = 6f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val brushed = vnoise(u, v, 4, 220, 121)
                    val f = fbm(u, v, 4, 4, 123)
                    val seamX = min(x % 128, 128 - x % 128); val seamY = min(y % 170, 170 - y % 170)
                    val seam = if (seamX < 2 || seamY < 2) 1f else 0f
                    val rivet = if ((seamX in 6..9 || seamY in 6..9) && ((x / 8 + y / 8) % 4 == 0)) 1f else 0f
                    val i = y * n + x
                    val c = (0.46f + brushed * 0.08f + f * 0.08f) * (1f - seam * 0.5f) + rivet * 0.12f
                    img.set(i, c * 0.98f, c, c * 1.03f)
                    img.h[i] = 0.5f - seam * 0.5f + rivet * 0.4f + brushed * 0.05f
                    img.rough[i] = 0.35f + f * 0.2f
                }
                for (k in 0 until 140) {
                    val x0 = rng.range(0f, n.toFloat()); val y0 = rng.range(0f, n.toFloat()); val a = rng.range(0f, 6.28f); val l = rng.range(10f, 50f)
                    img.line(x0, y0, x0 + cos(a) * l, y0 + sin(a) * l, 0.6f, 0.62f, 0.62f, 0.62f, 0.45f)
                }
            }
            Mat.RUST -> {
                strength = 7f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 6, 131)
                    val blot = smoothstep(0.45f, 0.6f, fbm(u, v, 4, 5, 133))
                    val pit = vnoise(u, v, 160, 160, 135)
                    val i = y * n + x
                    val c = 0.4f + f * 0.2f
                    img.set(i, lerp(c, c * 1.35f, blot), lerp(c, c * 0.85f, blot), lerp(c, c * 0.55f, blot))
                    img.h[i] = f * 0.5f + blot * 0.3f - (if (pit > 0.9f) 0.3f else 0f)
                    img.rough[i] = lerp(0.45f, 0.9f, blot)
                }
            }
            Mat.WOOD -> {
                strength = 6f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val plank = y / 64
                    val off = hash(plank, 0, 141)
                    val grain = 0.5f + 0.5f * sin(((v * 8f + off) * 6.2831f * 3f) + fbm(u + off, v, 4, 4, 143, 16) * 8f)
                    val seam = if (y % 64 < 2) 1f else 0f
                    val endSeam = if (Math.floorMod(x + (off * 512).toInt(), 256) < 2) 1f else 0f
                    val fine = vnoise(u, v, 256, 16, 145)
                    val i = y * n + x
                    val c = (0.42f + grain * 0.14f + fine * 0.06f + (off - 0.5f) * 0.08f) * (1f - max(seam, endSeam) * 0.6f)
                    img.set(i, c * 1.12f, c * 0.98f, c * 0.82f)
                    img.h[i] = grain * 0.3f - max(seam, endSeam) * 0.6f
                    img.rough[i] = 0.75f
                }
            }
            else -> { // HIDE
                strength = 4f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 5, 5, 151)
                    val hair = vnoise(u + fbm(u, v, 6, 2, 153) * 0.05f, v, 220, 30, 155)
                    val i = y * n + x
                    val c = 0.42f + f * 0.12f + hair * 0.1f
                    img.set(i, c * 1.05f, c, c * 0.92f)
                    img.h[i] = hair * 0.6f + f * 0.2f
                    img.rough[i] = 0.85f
                }
            }
        }
        if (!alphaMask) {
            // height lives in alpha for opaque layers (used for height-based layer blending)
            var lo = 9f; var hi = -9f
            for (v in img.h) { lo = min(lo, v); hi = max(hi, v) }
            val rng2 = max(1e-4f, hi - lo)
            for (i in img.h.indices) img.a[i] = (img.h[i] - lo) / rng2
        }
        return Pair(img.toAlbedo(), img.toNormal(strength, alphaMask))
    }

    @Suppress("unused")
    private fun gauss(x: Float) = exp(-x * x)
}
