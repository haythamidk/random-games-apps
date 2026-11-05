package com.hadal.game.engine

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
 * Seafloor layers (0..4) are full color. All other layers are "detail" textures centered on mid grey:
 * shaders multiply them with the vertex color (times two).
 */
object ProceduralTextures {
    const val SIZE = 512
    const val NSIZE = 256
    private const val VERSION = 2

    class Result(val albedo: List<ByteArray>, val normals: List<ByteArray>)

    @Volatile var result: Result? = null
        private set

    fun load(cacheDir: File?, pool: ExecutorService?): Result {
        result?.let { return it }
        val f = cacheDir?.let { File(it, "hadal_textures_v$VERSION.bin") }
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

    /** Tileable caustics pattern (single channel in red), animated in shaders by overlapping two scrolling copies. */
    fun caustics(n: Int): ByteArray {
        val out = ByteArray(n * n * 4)
        val w = FloatArray(2)
        val inv = 1f / n
        for (y in 0 until n) for (x in 0 until n) {
            val u = x * inv; val v = y * inv
            val wu = u + (fbm(u, v, 3, 2, 71) - 0.5f) * 0.08f
            val wv = v + (fbm(u, v, 3, 2, 73) - 0.5f) * 0.08f
            worley(wu, wv, 6, 75, w)
            val a = exp(-w[1] * 9f)
            worley(wu * 1f, wv * 1f, 11, 77, w)
            val b = exp(-w[1] * 12f)
            val c = clamp01(a * 0.8f + b * 0.45f)
            val o = (y * n + x) * 4
            val vb = (c * c * 255).toInt().toByte()
            out[o] = vb; out[o + 1] = vb; out[o + 2] = vb; out[o + 3] = -1
        }
        return out
    }

    private fun generate(layer: Int): Pair<ByteArray, ByteArray> {
        val n = SIZE
        val img = Img(n)
        val rng = Rng(7000L + layer * 131)
        val w = FloatArray(2)
        var strength = 6f
        val inv = 1f / n
        when (layer) {
            Mat.SAND -> {
                strength = 4f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val grain = vnoise(u, v, 256, 256, 31)
                    val warp = fbm(u, v, 4, 3, 33)
                    val rip = 0.5f + 0.5f * sin((v + warp * 0.22f + u * 0.1f) * 6.2831f * 14f)
                    val ripSharp = rip * rip
                    val f = fbm(u, v, 3, 4, 35)
                    val i = y * n + x
                    val s = 0.86f + grain * 0.2f + ripSharp * 0.08f
                    img.set(i, (0.66f + f * 0.08f) * s, (0.62f + f * 0.07f) * s, (0.52f + f * 0.05f) * s)
                    img.h[i] = ripSharp * 0.55f + grain * 0.12f + f * 0.1f
                    img.rough[i] = 0.95f
                }
                // shell fragments
                for (k in 0 until 140) {
                    val s = rng.range(0.85f, 1.15f)
                    img.ellipse(rng.range(0f, n.toFloat()), rng.range(0f, n.toFloat()), rng.range(1.5f, 4f), rng.range(1f, 2.5f), rng.range(0f, 6.28f), 0.85f * s, 0.8f * s, 0.72f * s, rng.range(0.6f, 0.9f))
                }
            }
            Mat.SILT -> {
                strength = 5f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 5, 5, 41)
                    val clump = fbm(u, v, 16, 3, 43)
                    val i = y * n + x
                    val c = 0.3f + f * 0.14f + clump * 0.06f
                    img.set(i, c * 1.05f, c * 0.98f, c * 0.88f)
                    img.h[i] = f * 0.4f + clump * 0.3f
                    img.rough[i] = 0.97f
                }
                // worm tracks
                for (k in 0 until 26) {
                    var px = rng.range(0f, n.toFloat()); var py = rng.range(0f, n.toFloat()); var a = rng.range(0f, 6.28f)
                    for (s in 0 until 50) {
                        val nx = px + cos(a) * 3f; val ny = py + sin(a) * 3f
                        img.line(px, py, nx, ny, 1.1f, 0.22f, 0.2f, 0.17f, 0f)
                        px = nx; py = ny; a += rng.range(-0.35f, 0.35f)
                    }
                }
                // manganese pebbles
                for (k in 0 until 90) {
                    val s = rng.range(0.6f, 1f)
                    img.ellipse(rng.range(0f, n.toFloat()), rng.range(0f, n.toFloat()), rng.range(3f, 7f), rng.range(3f, 6f), rng.range(0f, 6.28f), 0.13f * s, 0.11f * s, 0.1f * s, rng.range(0.7f, 1f))
                }
            }
            Mat.ROCK -> {
                strength = 10f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 6, 21)
                    worley(u, v, 7, 23, w)
                    val crack = smoothstep(0.07f, 0.0f, w[1])
                    val strata = 0.5f + 0.5f * sin((v * 9f + f * 3f) * 6.2831f)
                    val crust = smoothstep(0.6f, 0.7f, fbm(u, v, 5, 4, 29))
                    val i = y * n + x
                    var c = 0.27f + f * 0.3f + strata * 0.05f
                    c *= 1f - crack * 0.6f
                    img.set(i, c * 0.98f, c, c * 1.02f)
                    if (crust > 0f) { img.r[i] = lerp(img.r[i], 0.52f, crust * 0.5f); img.g[i] = lerp(img.g[i], 0.42f, crust * 0.5f); img.b[i] = lerp(img.b[i], 0.33f, crust * 0.5f) }
                    img.h[i] = f * 0.8f + strata * 0.1f - crack * 0.45f + (1f - w[0]) * 0.15f + crust * 0.1f
                    img.rough[i] = 0.7f
                }
            }
            Mat.BASALT -> {
                strength = 11f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 8, 5, 51)
                    worley(u, v, 10, 53, w)
                    val cell = smoothstep(0.0f, 0.09f, w[1])
                    val pore = smoothstep(0.78f, 0.86f, vnoise(u, v, 96, 96, 55))
                    val mineral = smoothstep(0.62f, 0.75f, fbm(u, v, 4, 4, 57))
                    val i = y * n + x
                    var c = 0.09f + f * 0.1f
                    c *= 0.55f + cell * 0.45f
                    c *= 1f - pore * 0.6f
                    img.set(i, c, c * 0.98f, c * 1.03f)
                    if (mineral > 0f) { img.r[i] = lerp(img.r[i], 0.55f, mineral * 0.55f); img.g[i] = lerp(img.g[i], 0.3f, mineral * 0.55f); img.b[i] = lerp(img.b[i], 0.12f, mineral * 0.55f) }
                    img.h[i] = f * 0.4f + cell * 0.5f - pore * 0.4f + mineral * 0.15f
                    img.rough[i] = 0.6f
                }
            }
            Mat.CORAL -> {
                strength = 9f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 5, 61)
                    val i = y * n + x
                    val c = 0.25f + f * 0.15f
                    img.set(i, c, c * 0.95f, c * 0.85f)
                    img.h[i] = f * 0.3f
                    img.rough[i] = 0.8f
                }
                val cols = arrayOf(floatArrayOf(0.78f, 0.42f, 0.38f), floatArrayOf(0.85f, 0.62f, 0.32f), floatArrayOf(0.55f, 0.36f, 0.62f), floatArrayOf(0.4f, 0.62f, 0.55f), floatArrayOf(0.88f, 0.8f, 0.62f), floatArrayOf(0.62f, 0.25f, 0.3f))
                for (k in 0 until 900) {
                    val c = cols[rng.nextInt(cols.size)]
                    val s = rng.range(0.7f, 1.1f)
                    val r0 = rng.range(2f, 7f)
                    img.ellipse(rng.range(0f, n.toFloat()), rng.range(0f, n.toFloat()), r0, r0 * rng.range(0.7f, 1f), rng.range(0f, 6.28f), c[0] * s, c[1] * s, c[2] * s, rng.range(0.5f, 1f))
                }
                // polyp dots
                for (y in 0 until n) for (x in 0 until n) {
                    val i = y * n + x
                    val d = vnoise(x * inv, y * inv, 128, 128, 67)
                    if (d > 0.72f) { val k = (d - 0.72f) * 3f; img.r[i] *= 1f - k * 0.5f; img.g[i] *= 1f - k * 0.5f; img.b[i] *= 1f - k * 0.4f; img.h[i] -= k * 0.3f }
                }
            }
            Mat.RUST -> {
                strength = 8f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 6, 81)
                    val streak = fbm(u * 0.5f, v * 6f, 8, 3, 83, 2)
                    val rust = smoothstep(0.35f, 0.65f, f + streak * 0.3f)
                    val plate = if ((y % 128) < 3 || (x % 170) < 3) 1f else 0f
                    val rivet = if (((y % 128) in 8..12 && (x % 24) in 10..14) || ((x % 170) in 8..12 && (y % 24) in 10..14)) 1f else 0f
                    val i = y * n + x
                    val g0 = 0.5f + f * 0.12f
                    var cr = lerp(g0 * 0.95f, 0.62f, rust); var cg = lerp(g0 * 0.95f, 0.38f, rust); var cb = lerp(g0, 0.25f, rust)
                    cr *= 1f - plate * 0.4f; cg *= 1f - plate * 0.4f; cb *= 1f - plate * 0.4f
                    img.set(i, cr, cg, cb)
                    img.h[i] = f * 0.3f + rust * 0.25f - plate * 0.5f + rivet * 0.7f
                    img.rough[i] = lerp(0.45f, 0.9f, rust)
                }
                // barnacles
                for (k in 0 until 180) {
                    val r0 = rng.range(2f, 5f)
                    img.ellipse(rng.range(0f, n.toFloat()), rng.range(0f, n.toFloat()), r0, r0, 0f, 0.62f, 0.6f, 0.55f, 0.9f)
                }
            }
            Mat.PAINT -> {
                strength = 5f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 5, 5, 91)
                    val scratch = smoothstep(0.82f, 0.9f, vnoise(u, v + fbm(u, v, 4, 2, 93) * 0.1f, 4, 160, 95))
                    val grime = smoothstep(0.45f, 0.8f, fbm(u * 0.5f, v * 3f, 3, 4, 97, 4))
                    val seam = if ((y % 256) < 3 || (x % 128) < 3) 1f else 0f
                    val bolt = if ((y % 256) in 9..13 && (x % 32) in 14..18) 1f else 0f
                    val i = y * n + x
                    var c = 0.56f + f * 0.08f
                    c *= 1f - grime * 0.25f
                    c = lerp(c, 0.35f, scratch * 0.6f)
                    c *= 1f - seam * 0.2f
                    img.set(i, c, c, c * 0.98f)
                    img.h[i] = f * 0.15f - seam * 0.6f + bolt * 0.8f - scratch * 0.15f
                    img.rough[i] = lerp(0.35f, 0.7f, grime + scratch * 0.5f)
                }
            }
            Mat.SKIN -> {
                strength = 7f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val f = fbm(u, v, 6, 5, 101)
                    val wr = vnoise(u + fbm(u, v, 5, 2, 103) * 0.06f, v, 70, 12, 105)
                    worley(u, v, 18, 107, w)
                    val scale = smoothstep(0.0f, 0.08f, w[1])
                    val i = y * n + x
                    val c = 0.42f + f * 0.12f + wr * 0.08f
                    val k = 0.85f + scale * 0.15f
                    img.set(i, c * k, c * k * 0.98f, c * k * 0.97f)
                    img.h[i] = wr * 0.5f + scale * 0.3f + f * 0.2f
                    img.rough[i] = 0.35f
                }
            }
            Mat.KELP -> {
                strength = 4f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val fiber = vnoise(u + fbm(u, v, 6, 2, 113) * 0.03f, v, 200, 6, 111)
                    val f = fbm(u, v, 4, 4, 115)
                    val spot = smoothstep(0.7f, 0.8f, fbm(u, v, 10, 3, 117))
                    val i = y * n + x
                    val c = 0.45f + fiber * 0.12f + f * 0.08f
                    img.set(i, c * (1f + spot * 0.2f), c, c * (0.95f - spot * 0.2f))
                    img.h[i] = fiber * 0.4f + f * 0.2f
                    img.rough[i] = 0.5f
                }
            }
            else -> { // METAL
                strength = 4f
                for (y in 0 until n) for (x in 0 until n) {
                    val u = x * inv; val v = y * inv
                    val brush = vnoise(u, v, 256, 6, 121)
                    val f = fbm(u, v, 5, 4, 123)
                    val i = y * n + x
                    val c = 0.5f + brush * 0.06f + f * 0.08f
                    img.set(i, c, c, c * 1.01f)
                    img.h[i] = brush * 0.15f + f * 0.1f
                    img.rough[i] = 0.3f + f * 0.2f
                }
            }
        }
        var lo = 9f; var hi = -9f
        for (v in img.h) { lo = min(lo, v); hi = max(hi, v) }
        val rng2 = max(1e-4f, hi - lo)
        for (i in img.h.indices) img.a[i] = (img.h[i] - lo) / rng2
        return Pair(img.toAlbedo(), img.toNormal(strength, false))
    }
}
