package com.worldwake.game.engine

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
    private const val VERSION = 1

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
        val rng = Rng(7000L + layer * 131)
        val w = FloatArray(2)
        var strength = 6f
        var alphaMask = false
        val inv = 1f / n
        when (layer) {
            Mat.SOIL -> {
                strength = 6f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 5, 6, 11)
                    val grain = vnoise(u, v, 220, 220, 13)
                    worley(u, v, 9, 15, w)
                    val crack = smoothstep(0.05f, 0.0f, w[1]) * smoothstep(0.45f, 0.6f, fbm(u, v, 3, 3, 17))
                    val i = y * n + x
                    val s = (0.8f + grain * 0.3f) * (1f - crack * 0.5f)
                    img.set(i, (0.22f + f * 0.1f) * s, (0.16f + f * 0.07f) * s, (0.21f + f * 0.09f) * s)
                    img.h[i] = f * 0.6f + grain * 0.2f - crack * 0.5f
                    img.rough[i] = 0.92f
                }
                for (k in 0 until 260) {
                    val g = rng.range(0.16f, 0.34f)
                    img.ellipse(rng.range(0f, n.toFloat()), rng.range(0f, n.toFloat()), rng.range(1.5f, 5f), rng.range(1.2f, 3.5f), rng.range(0f, 6.28f), g * 0.95f, g * 0.92f, g * 1.08f, 0.8f)
                }
            }
            Mat.FROST -> {
                strength = 3f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 4, 5, 41)
                    val warp = fbm(u, v, 3, 3, 45)
                    val rip = 0.5f + 0.5f * sin((u * 0.6f + v + warp * 0.25f) * 6.2831f * 9f)
                    val sp = vnoise(u, v, 256, 256, 43)
                    worley(u, v, 14, 47, w)
                    val plate = smoothstep(0.0f, 0.12f, w[1])
                    val i = y * n + x
                    val c = 0.8f + f * 0.12f + rip * 0.04f
                    img.set(i, c * 0.95f * (0.93f + plate * 0.07f), c * 0.94f * (0.93f + plate * 0.07f), c * 1.06f)
                    img.h[i] = f * 0.7f + rip * 0.25f + plate * 0.1f
                    img.rough[i] = if (sp > 0.955f) 0.08f else 0.5f
                }
            }
            Mat.ROCK -> {
                strength = 10f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 6, 21)
                    worley(u, v, 6, 23, w)
                    val crack = smoothstep(0.05f, 0.0f, w[1])
                    val strata = 0.5f + 0.5f * sin((v * 14f + f * 2.2f) * 6.2831f)
                    val vein = smoothstep(0.035f, 0.0f, abs(fbm(u, v, 3, 4, 27) - 0.5f)) * smoothstep(0.5f, 0.7f, fbm(u, v, 5, 3, 29))
                    val i = y * n + x
                    var c = 0.2f + f * 0.2f + strata * 0.07f
                    c *= 1f - crack * 0.6f
                    img.set(i, lerp(c * 0.9f, 0.16f, vein), lerp(c, 0.62f, vein), lerp(c * 1.18f, 0.56f, vein))
                    img.h[i] = f * 0.7f + strata * 0.2f - crack * 0.45f + (1f - w[0]) * 0.15f
                    img.rough[i] = lerp(0.7f, 0.25f, vein)
                }
            }
            Mat.SILT -> {
                strength = 3.5f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val grain = vnoise(u, v, 256, 256, 31)
                    val warp = fbm(u, v, 4, 3, 33)
                    val rip = 0.5f + 0.5f * sin((v + warp * 0.2f) * 6.2831f * 18f)
                    val f = fbm(u, v, 3, 3, 35)
                    val i = y * n + x
                    val s = 0.86f + grain * 0.2f + rip * 0.07f
                    img.set(i, (0.5f + f * 0.08f) * s, (0.47f + f * 0.07f) * s, (0.53f + f * 0.08f) * s)
                    img.h[i] = rip * 0.5f + grain * 0.15f
                    img.rough[i] = 0.9f
                }
            }
            Mat.MOSS -> {
                strength = 7f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 5, 5, 51)
                    worley(u, v, 22, 53, w)
                    val cushion = 1f - clamp01(w[0] * 1.3f)
                    val fine = vnoise(u, v, 200, 200, 55)
                    val pale = smoothstep(0.6f, 0.75f, fbm(u, v, 4, 3, 57))
                    val i = y * n + x
                    val s = 0.55f + cushion * 0.6f + fine * 0.15f
                    var cr = (0.5f + f * 0.14f) * s; var cg = (0.1f + f * 0.05f) * s; var cb = (0.26f + f * 0.1f) * s
                    cr = lerp(cr, 0.78f * s, pale * 0.5f); cg = lerp(cg, 0.3f * s, pale * 0.5f); cb = lerp(cb, 0.42f * s, pale * 0.5f)
                    img.set(i, cr, cg, cb)
                    img.h[i] = cushion * 0.8f + fine * 0.15f
                    img.rough[i] = 0.95f
                }
            }
            Mat.GRASS -> {
                strength = 5f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val base = fbm(u, v, 4, 4, 61)
                    val blades = vnoise(u + fbm(u, v, 8, 2, 65) * 0.02f, v, 160, 28, 63)
                    val blades2 = vnoise(u, v + fbm(u, v, 8, 2, 66) * 0.02f, 34, 140, 64)
                    val bl = max(blades, blades2)
                    val rose = smoothstep(0.5f, 0.7f, fbm(u, v, 3, 3, 69))
                    val i = y * n + x
                    var cr = lerp(0.6f, 0.82f, base); var cg = lerp(0.42f, 0.58f, base); var cb = lerp(0.16f, 0.26f, base)
                    cr = lerp(cr, 0.8f, rose * 0.6f); cg = lerp(cg, 0.36f, rose * 0.6f); cb = lerp(cb, 0.34f, rose * 0.6f)
                    val s = 0.6f + bl * 0.6f
                    img.set(i, cr * s, cg * s, cb * s)
                    img.h[i] = bl * 0.7f + base * 0.3f
                    img.rough[i] = 0.9f
                }
            }
            Mat.SULFUR -> {
                strength = 7f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 5, 5, 71)
                    worley(u, v, 8, 73, w)
                    val crack = smoothstep(0.07f, 0.0f, w[1])
                    val crust = smoothstep(0.4f, 0.65f, fbm(u, v, 6, 4, 75))
                    val stain = smoothstep(0.6f, 0.72f, fbm(u, v, 3, 3, 77))
                    val i = y * n + x
                    var cr = lerp(0.38f, 0.74f, crust) + f * 0.08f; var cg = lerp(0.36f, 0.68f, crust) + f * 0.08f; var cb = lerp(0.2f, 0.2f, crust)
                    cr = lerp(cr, 0.72f, stain * 0.5f); cg = lerp(cg, 0.4f, stain * 0.5f); cb = lerp(cb, 0.14f, stain * 0.5f)
                    val s = 1f - crack * 0.7f
                    img.set(i, cr * s, cg * s, cb * s)
                    img.h[i] = crust * 0.5f + f * 0.3f - crack * 0.6f
                    img.rough[i] = lerp(0.9f, 0.6f, crust)
                }
            }
            Mat.GRAVEL -> {
                strength = 9f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 4, 81)
                    worley(u, v, 26, 83, w)
                    val stone = 1f - clamp01(w[0] * 1.5f)
                    val edge = smoothstep(0.0f, 0.1f, w[1])
                    val tone = hash((u * 26).toInt(), (v * 26).toInt(), 85)
                    val i = y * n + x
                    val c = (0.2f + tone * 0.16f + f * 0.08f) * (0.55f + edge * 0.45f)
                    img.set(i, c * 0.96f, c * 0.98f, c * 1.1f)
                    img.h[i] = stone * 0.8f * edge + f * 0.1f
                    img.rough[i] = 0.85f
                }
            }
            Mat.BARK -> {
                strength = 8f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val warp = fbm(u, v, 4, 3, 91) * 0.05f
                    val band = smoothstep(0.62f, 0.72f, vnoise(u * 0.5f, v + warp, 3, 26, 93))
                    val fine = fbm(u, v, 4, 3, 95, 24)
                    val peel = smoothstep(0.55f, 0.7f, fbm(u, v, 5, 4, 97))
                    val i = y * n + x
                    val c = (0.56f + fine * 0.1f - peel * 0.08f) * (1f - band * 0.6f)
                    img.set(i, c * 1.02f, c, c * 0.98f)
                    img.h[i] = fine * 0.3f - band * 0.6f + peel * 0.3f
                    img.rough[i] = 0.7f
                }
            }
            Mat.LEAVES -> {
                alphaMask = true
                strength = 6f
                for (i in 0 until n * n) { img.a[i] = 0f; img.set(i, 0.5f, 0.5f, 0.5f) }
                for (c in 0 until 76) {
                    val cx = rng.range(0f, n.toFloat()); val cy = rng.range(0f, n.toFloat())
                    val count = 10 + rng.nextInt(10)
                    for (k in 0 until count) {
                        val a = rng.range(0f, 6.28f); val d = rng.range(0f, 30f)
                        val s = rng.range(0.72f, 1.28f)
                        val warm = rng.range(-0.06f, 0.1f)
                        img.ellipse(cx + cos(a) * d, cy + sin(a) * d, rng.range(9f, 15f), rng.range(5f, 9f), rng.range(0f, 6.28f), (0.5f + warm) * s, (0.5f - warm * 0.3f) * s, (0.5f - warm) * s, rng.range(0.6f, 1f), 1f, vein = true)
                    }
                }
            }
            Mat.FROND -> {
                alphaMask = true
                strength = 5f
                for (i in 0 until n * n) { img.a[i] = 0f; img.set(i, 0.5f, 0.5f, 0.5f) }
                for (b in 0 until 54) {
                    val x0 = rng.range(0f, n.toFloat()); val y0 = rng.range(0f, n.toFloat())
                    val a = rng.range(-0.5f, 0.5f) + 1.5708f
                    val len = rng.range(60f, 120f)
                    val x1 = x0 + cos(a) * len; val y1 = y0 + sin(a) * len
                    img.line(x0, y0, x1, y1, 1.4f, 0.42f, 0.42f, 0.42f, 0.6f)
                    val steps = (len / 4).toInt()
                    for (s in 0 until steps) {
                        val t = s.toFloat() / steps
                        val px = x0 + (x1 - x0) * t; val py = y0 + (y1 - y0) * t
                        for (side in listOf(-1f, 1f)) {
                            val na = a + side * rng.range(0.9f, 1.3f)
                            val nl = rng.range(8f, 14f) * sin(t * 2.6f + 0.4f)
                            val sh = rng.range(0.8f, 1.25f)
                            img.ellipse(px + cos(na) * nl * 0.5f, py + sin(na) * nl * 0.5f, nl * 0.55f, 2.2f, na, 0.5f * sh, 0.5f * sh, 0.5f * sh, rng.range(0.5f, 1f), 1f)
                        }
                    }
                }
            }
            Mat.HULL -> {
                strength = 6f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 4, 4, 111)
                    val streak = fbm(u, v, 30, 3, 113, 3)
                    val seamX = min(x % 256, 256 - x % 256); val seamY = min(y % 128, 128 - y % 128)
                    val seam = if (seamX < 2 || seamY < 2) 1f else 0f
                    val bevel = if (seamX in 2..5 || seamY in 2..5) 1f else 0f
                    val rivet = if ((seamX in 9..12 && y % 32 in 14..17) || (seamY in 9..12 && x % 32 in 14..17)) 1f else 0f
                    val i = y * n + x
                    val c = (0.5f + f * 0.05f - streak * 0.05f) * (1f - seam * 0.55f) + bevel * 0.04f - rivet * 0.07f
                    img.set(i, c, c, c * 1.01f)
                    img.h[i] = 0.5f - seam * 0.5f + bevel * 0.1f + rivet * 0.25f
                    img.rough[i] = 0.42f + f * 0.15f
                }
            }
            Mat.METAL -> {
                strength = 6f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val brushed = vnoise(u, v, 4, 220, 121)
                    val f = fbm(u, v, 4, 4, 123)
                    val seamY = min(y % 170, 170 - y % 170)
                    val seam = if (seamY < 2) 1f else 0f
                    val i = y * n + x
                    val c = (0.47f + brushed * 0.09f + f * 0.07f) * (1f - seam * 0.5f)
                    img.set(i, c * 0.98f, c, c * 1.03f)
                    img.h[i] = 0.5f - seam * 0.5f + brushed * 0.06f
                    img.rough[i] = 0.3f + f * 0.2f
                }
                for (k in 0 until 120) {
                    val x0 = rng.range(0f, n.toFloat()); val y0 = rng.range(0f, n.toFloat()); val a = rng.range(0f, 6.28f); val l = rng.range(10f, 50f)
                    img.line(x0, y0, x0 + cos(a) * l, y0 + sin(a) * l, 0.6f, 0.6f, 0.6f, 0.62f, 0.45f)
                }
            }
            Mat.WORN -> {
                strength = 7f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 6, 131)
                    val chip = smoothstep(0.52f, 0.6f, fbm(u, v, 7, 5, 133))
                    val streak = fbm(u, v, 26, 3, 137, 2)
                    val seamX = min(x % 256, 256 - x % 256); val seamY = min(y % 128, 128 - y % 128)
                    val seam = if (seamX < 2 || seamY < 2) 1f else 0f
                    val i = y * n + x
                    val paint = 0.52f + f * 0.08f - streak * 0.12f
                    val bare = 0.3f + f * 0.12f
                    val c = lerp(paint, bare, chip) * (1f - seam * 0.5f)
                    img.set(i, c * lerp(1f, 1.06f, chip), c, c * lerp(1f, 1.04f, chip))
                    img.h[i] = 0.5f - chip * 0.25f - seam * 0.5f + f * 0.15f
                    img.rough[i] = lerp(0.6f, 0.4f, chip)
                }
                for (k in 0 until 200) {
                    val x0 = rng.range(0f, n.toFloat()); val y0 = rng.range(0f, n.toFloat()); val a = rng.range(0f, 6.28f); val l = rng.range(8f, 40f)
                    img.line(x0, y0, x0 + cos(a) * l, y0 + sin(a) * l, 0.7f, 0.33f, 0.33f, 0.34f, 0.3f)
                }
            }
            Mat.CRYSTAL -> {
                strength = 8f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    worley(u, v, 5, 141, w)
                    val facet = hash((u * 5 + w[0] * 3f).toInt(), (v * 5 + w[1] * 7f).toInt(), 143)
                    val streak = vnoise(u + fbm(u, v, 3, 2, 147) * 0.08f, v, 60, 4, 145)
                    val edge = smoothstep(0.04f, 0.0f, w[1])
                    val i = y * n + x
                    val c = 0.4f + facet * 0.18f + streak * 0.14f + edge * 0.25f
                    img.set(i, c, c, c)
                    img.h[i] = w[0] * 0.6f + streak * 0.2f
                    img.rough[i] = 0.12f + streak * 0.15f
                }
            }
            else -> { // GRIP
                strength = 5f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 5, 4, 151)
                    val wx = 0.5f + 0.5f * sin(u * 6.2831f * 48f); val wy = 0.5f + 0.5f * sin(v * 6.2831f * 48f)
                    val weave = max(wx, wy)
                    val i = y * n + x
                    val c = 0.42f + weave * 0.14f + f * 0.08f
                    img.set(i, c, c, c)
                    img.h[i] = weave * 0.6f + f * 0.2f
                    img.rough[i] = 0.85f
                }
            }
        }
        if (!alphaMask) {
            var lo = 9f; var hi = -9f
            for (v in img.h) { lo = min(lo, v); hi = max(hi, v) }
            val rng2 = max(1e-4f, hi - lo)
            for (i in img.h.indices) img.a[i] = (img.h[i] - lo) / rng2
        }
        return Pair(img.toAlbedo(), img.toNormal(strength, alphaMask))
    }
}
