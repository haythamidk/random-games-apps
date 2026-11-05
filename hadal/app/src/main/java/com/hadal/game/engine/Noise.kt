package com.hadal.game.engine

import kotlin.math.abs
import kotlin.math.floor

/** Seeded 2D simplex noise (based on Stefan Gustavson's public domain reference). Output range roughly [-1, 1]. */
class SimplexNoise(seed: Long) {
    private val perm = IntArray(512)
    private val permMod12 = IntArray(512)

    init {
        val p = IntArray(256) { it }
        val rng = Rng(seed)
        for (i in 255 downTo 1) {
            val j = rng.nextInt(i + 1)
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        for (i in 0 until 512) {
            perm[i] = p[i and 255]
            permMod12[i] = perm[i] % 12
        }
    }

    fun noise(xin: Float, yin: Float): Float {
        val s = (xin + yin) * F2
        val i = fastFloor(xin + s)
        val j = fastFloor(yin + s)
        val t = (i + j) * G2
        val x0 = xin - (i - t)
        val y0 = yin - (j - t)
        val i1: Int; val j1: Int
        if (x0 > y0) { i1 = 1; j1 = 0 } else { i1 = 0; j1 = 1 }
        val x1 = x0 - i1 + G2
        val y1 = y0 - j1 + G2
        val x2 = x0 - 1f + 2f * G2
        val y2 = y0 - 1f + 2f * G2
        val ii = i and 255
        val jj = j and 255
        var n0 = 0f; var n1 = 0f; var n2 = 0f
        var t0 = 0.5f - x0 * x0 - y0 * y0
        if (t0 >= 0) {
            val gi = permMod12[ii + perm[jj]] * 3
            t0 *= t0
            n0 = t0 * t0 * (GRAD[gi] * x0 + GRAD[gi + 1] * y0)
        }
        var t1 = 0.5f - x1 * x1 - y1 * y1
        if (t1 >= 0) {
            val gi = permMod12[ii + i1 + perm[jj + j1]] * 3
            t1 *= t1
            n1 = t1 * t1 * (GRAD[gi] * x1 + GRAD[gi + 1] * y1)
        }
        var t2 = 0.5f - x2 * x2 - y2 * y2
        if (t2 >= 0) {
            val gi = permMod12[ii + 1 + perm[jj + 1]] * 3
            t2 *= t2
            n2 = t2 * t2 * (GRAD[gi] * x2 + GRAD[gi + 1] * y2)
        }
        return 70f * (n0 + n1 + n2)
    }

    fun fbm(x: Float, y: Float, octaves: Int, lacunarity: Float = 2f, gain: Float = 0.5f): Float {
        var sum = 0f
        var amp = 1f
        var freq = 1f
        var norm = 0f
        for (o in 0 until octaves) {
            sum += noise(x * freq + o * 17.31f, y * freq - o * 9.17f) * amp
            norm += amp
            amp *= gain
            freq *= lacunarity
        }
        return sum / norm
    }

    /** Ridged multifractal in [0,1]: sharp mountain crests. */
    fun ridged(x: Float, y: Float, octaves: Int): Float {
        var sum = 0f
        var amp = 0.5f
        var freq = 1f
        var weight = 1f
        var norm = 0f
        for (o in 0 until octaves) {
            var n = 1f - abs(noise(x * freq + o * 31.7f, y * freq + o * 11.3f))
            n *= n
            n *= weight
            weight = clamp01(n * 2f)
            sum += n * amp
            norm += amp
            amp *= 0.5f
            freq *= 2.03f
        }
        return sum / norm
    }

    companion object {
        private const val F2 = 0.36602540378f
        private const val G2 = 0.2113248654f
        private val GRAD = floatArrayOf(
            1f, 1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, -1f, -1f, 0f,
            1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, -1f, 0f, -1f,
            0f, 1f, 1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, -1f, -1f
        )

        private fun fastFloor(x: Float): Int {
            val xi = x.toInt()
            return if (x < xi) xi - 1 else xi
        }
    }
}

/** Generates a tileable RGBA noise image used by shaders for surface detail, water, and clouds. */
object NoiseImage {
    fun generate(size: Int, seed: Long): ByteArray {
        val out = ByteArray(size * size * 4)
        val layers = arrayOf(intArrayOf(8, 4), intArrayOf(16, 4), intArrayOf(4, 5), intArrayOf(32, 3))
        val lattices = Array(4) { l ->
            val rng = Rng(seed + l * 7919L)
            FloatArray(64 * 64) { rng.nextFloat() }
        }
        for (y in 0 until size) {
            for (x in 0 until size) {
                for (c in 0 until 4) {
                    val baseCells = layers[c][0]
                    val octaves = layers[c][1]
                    var sum = 0f
                    var amp = 1f
                    var norm = 0f
                    var cells = baseCells
                    for (o in 0 until octaves) {
                        if (cells > 64) break
                        sum += periodicValue(lattices[c], x.toFloat() / size * cells, y.toFloat() / size * cells, cells, o) * amp
                        norm += amp
                        amp *= 0.5f
                        cells *= 2
                    }
                    val v = clamp01(sum / norm)
                    out[(y * size + x) * 4 + c] = (v * 255f).toInt().toByte()
                }
            }
        }
        return out
    }

    private fun periodicValue(lat: FloatArray, x: Float, y: Float, period: Int, octave: Int): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val fx = x - xi
        val fy = y - yi
        val ux = fx * fx * (3 - 2 * fx)
        val uy = fy * fy * (3 - 2 * fy)
        fun v(ix: Int, iy: Int): Float {
            val px = ((ix % period) + period) % period
            val py = ((iy % period) + period) % period
            val idx = ((px * 7 + octave * 13) and 63) * 64 + ((py * 11 + octave * 5) and 63)
            return lat[idx]
        }
        val a = v(xi, yi); val b = v(xi + 1, yi); val c = v(xi, yi + 1); val d = v(xi + 1, yi + 1)
        return lerp(lerp(a, b, ux), lerp(c, d, ux), uy)
    }
}
