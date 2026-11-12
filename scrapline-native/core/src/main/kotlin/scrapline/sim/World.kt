package scrapline.sim

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** World is a square of WORLD_SIZE metres on X and Z, origin at one corner. */
const val WORLD_SIZE = 256
/** Terrain lattice spacing in metres. Collision and rendering share the same triangles. */
const val CELL = 2
/** Lattice points per side. */
const val GRID_N = WORLD_SIZE / CELL + 1

class World(val seed: Int) {
    /** Lattice heights, row-major: index = iz * GRID_N + ix. */
    val heights = FloatArray(GRID_N * GRID_N)
    /** 0 = open sand, 1 = solid rock (the mesa rim), per lattice point. Used for colouring. */
    val rock = FloatArray(GRID_N * GRID_N)

    init {
        val half = WORLD_SIZE / 2f
        for (iz in 0 until GRID_N) for (ix in 0 until GRID_N) {
            val x = (ix * CELL).toFloat()
            val z = (iz * CELL).toFloat()
            // Long dunes: ridged noise stretched along one axis, plus broad swells.
            val swell = (fbm(x / 70f, z / 70f, seed) - 0.47f) * 9f
            val ridge = 1f - abs(valueNoise(x / 26f + z / 90f, z / 60f, seed + 311) * 2f - 1f)
            val dunes = ridge * ridge * 3.2f
            val ripple = (valueNoise(x / 7f, z / 7f, seed + 977) - 0.5f) * 0.35f
            // Distance from centre as a 0..1 "squircle" so the rim follows the square map.
            val dx = abs(x - half) / half
            val dz = abs(z - half) / half
            val edge = (dx.pow(4) + dz.pow(4)).pow(0.25f)
            // The rim is a wall of stepped mesas rather than a smooth bowl.
            val rimT = smooth(((edge - 0.74f) / 0.12f).coerceIn(0f, 1f))
            val steps = floor(valueNoise(x / 22f, z / 22f, seed + 55) * 3f) * 3.5f
            val mesa = rimT * (24f + steps)
            // Flatten a landing pad around the spawn point so the first minute is easy to read.
            val dc = hypot(x - half, z - half)
            val pad = smooth(1f - ((dc - 6f) / 16f).coerceIn(0f, 1f))
            val i = iz * GRID_N + ix
            heights[i] = (swell + dunes + ripple) * (1f - 0.9f * pad) * (1f - rimT) + mesa
            rock[i] = rimT
        }
    }

    /**
     * Terrain height at a point, interpolated across the same two triangles per cell
     * that the renderer draws, so the player's feet never sink or float.
     */
    fun heightAt(x: Float, z: Float): Float {
        val maxC = WORLD_SIZE - 1e-3f
        val gx = min(max(x, 0f), maxC) / CELL
        val gz = min(max(z, 0f), maxC) / CELL
        val ix = gx.toInt()
        val iz = gz.toInt()
        val fx = gx - ix
        val fz = gz - iz
        val i = iz * GRID_N + ix
        val h00 = heights[i]
        val h10 = heights[i + 1]
        val h01 = heights[i + GRID_N]
        val h11 = heights[i + GRID_N + 1]
        return if (fx + fz <= 1f) h00 + (h10 - h00) * fx + (h01 - h00) * fz
        else h11 + (h01 - h11) * (1f - fx) + (h10 - h11) * (1f - fz)
    }

    /** Steepness at a point as rise over run, sampled over half a metre. */
    fun slopeAt(x: Float, z: Float): Float {
        val e = 0.5f
        val dx = heightAt(x + e, z) - heightAt(x - e, z)
        val dz = heightAt(x, z + e) - heightAt(x, z - e)
        return hypot(dx, dz) / (2 * e)
    }
}

fun smooth(t: Float): Float = t * t * (3 - 2 * t)

fun valueNoise(x: Float, z: Float, seed: Int): Float {
    val xi = floor(x).toInt()
    val zi = floor(z).toInt()
    val fx = smooth(x - xi)
    val fz = smooth(z - zi)
    val a = hash2(xi, zi, seed)
    val b = hash2(xi + 1, zi, seed)
    val c = hash2(xi, zi + 1, seed)
    val d = hash2(xi + 1, zi + 1, seed)
    return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz
}

fun fbm(x: Float, z: Float, seed: Int): Float {
    var sum = 0f
    var amp = 0.5f
    var freq = 1f
    for (o in 0 until 4) {
        sum += amp * valueNoise(x * freq, z * freq, seed + o * 101)
        amp *= 0.5f
        freq *= 2f
    }
    return sum
}
