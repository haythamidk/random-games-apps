package com.rustbloom.game.world

import com.rustbloom.game.engine.Rng
import com.rustbloom.game.engine.SimplexNoise
import com.rustbloom.game.engine.clamp01
import com.rustbloom.game.engine.lerp
import com.rustbloom.game.engine.smoothstep
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Biome(val displayName: String, val baseTemp: Float) {
    OCEAN("Open Water", 14f),
    BEACH("Shoreline", 22f),
    MEADOW("Greenreach Meadows", 19f),
    FOREST("Hollowwood", 16f),
    PINE("Greyneedle Taiga", 4f),
    SNOW("Whitecrown Peaks", -10f),
    DESERT("Rust Flats", 30f),
    MARSH("Sunken Fen", 21f),
}

enum class PoiKind { VAULT, RELAY, SPIRE_GREEN, SPIRE_DUST, SPIRE_FROST, SPIRE_TIDE, CROWN, RUIN, CACHE, WRECK, CAMP }

class Poi(
    val id: Int,
    val kind: PoiKind,
    val x: Float,
    val z: Float,
    val radius: Float,
    val name: String,
    val logIndex: Int = -1,
) {
    var flatHeight = 0f
    var y = 0f
}

/**
 * Deterministic world description: terrain height, biome weights, and points of interest.
 * Thread-safe for reads after construction (used by background chunk builders).
 */
object WorldGen {
    const val SEED = 20261105L
    const val HALF = 1536f
    const val WATER = 0f

    private val n1 = SimplexNoise(SEED)
    private val n2 = SimplexNoise(SEED * 31 + 7)
    private val n3 = SimplexNoise(SEED * 131 + 3)
    private val n4 = SimplexNoise(SEED * 17 + 11)

    val pois = ArrayList<Poi>()
    private const val GRID = 128f
    private val poiGrid = HashMap<Long, MutableList<Poi>>()

    val vaultPoi get() = pois.first { it.kind == PoiKind.VAULT }

    val RUIN_NAMES = listOf(
        "Sunken Overpass", "Glasshouse Ruin", "Old Transit Hub", "Broken Habitat", "Signal Hut",
        "Collapsed Archive", "Rusted Depot", "Silent Clinic", "Hydro Station", "Overgrown Plaza",
        "Survey Outpost", "Cooling Tower", "Fallen Skybridge", "Seed Bank", "Weather Mast",
        "Drone Hangar", "Worker Barracks", "Server Crypt", "Solar Field", "Pump House",
        "Watch Post", "Freight Yard", "Water Works", "Last Station",
    )

    init {
        generatePois()
    }

    // ----------------------------------------------------------------- regional masks

    fun coldness(x: Float, z: Float): Float = smoothstep(-420f, -880f, z + n2.noise(x * 0.002f, z * 0.002f) * 160f)
    fun dryness(x: Float, z: Float): Float = smoothstep(420f, 760f, x + n2.noise(x * 0.0021f + 40f, z * 0.0021f) * 170f) * (1f - coldness(x, z))
    fun marshness(x: Float, z: Float): Float {
        val dx = x + 600f; val dz = z - 660f
        val d = sqrt(dx * dx + dz * dz) + n3.noise(x * 0.003f, z * 0.003f) * 120f
        return smoothstep(420f, 260f, d)
    }

    fun forestness(x: Float, z: Float): Float {
        val f = n4.fbm(x * 0.0035f, z * 0.0035f, 3) + 0.08f
        // the west is heavily forested (Hollowwood)
        val west = smoothstep(-150f, -520f, x) * 0.35f
        return clamp01(f * 2.2f + west)
    }

    private fun continent(x: Float, z: Float): Float {
        val wx = x + n1.fbm(x * 0.0009f, z * 0.0009f, 3) * 260f
        val wz = z + n1.fbm(x * 0.0009f + 91f, z * 0.0009f - 33f, 3) * 260f
        val d = sqrt(wx * wx + wz * wz) / HALF
        return smoothstep(1.0f, 0.74f, d)
    }

    /** Raw procedural height before POI flattening. */
    fun baseHeight(x: Float, z: Float): Float {
        val c = continent(x, z)
        if (c <= 0f) return -32f
        val cold = coldness(x, z)
        val dry = dryness(x, z)
        val marsh = marshness(x, z)
        val broad = n1.fbm(x * 0.0011f, z * 0.0011f, 3) * 26f
        val hills = n2.fbm(x * 0.0045f, z * 0.0045f, 4) * 11f
        val detail = n3.noise(x * 0.02f, z * 0.02f) * 1.2f
        val mMask = clamp01(n4.fbm(x * 0.0007f - 13f, z * 0.0007f + 7f, 2) * 1.6f + cold * 0.9f + 0.05f)
        val ridge = n3.ridged(x * 0.0022f, z * 0.0022f, 5)
        var h = 14f + broad + hills + detail + ridge * ridge * 150f * mMask * mMask
        // rust flats: wind-carved dunes and mesas
        if (dry > 0f) {
            val dune = (1f - abs(n2.noise(x * 0.006f + z * 0.002f, z * 0.004f))) * 7f
            val mesa = smoothstep(0.35f, 0.42f, n4.noise(x * 0.004f, z * 0.004f)) * 22f
            h = lerp(h, 10f + broad * 0.4f + dune + mesa, dry * 0.85f)
        }
        // marsh: flat waterlogged lowland
        if (marsh > 0f) h = lerp(h, 1.2f + hills * 0.18f + detail, marsh)
        // rivers
        val rv = abs(n1.noise(x * 0.0013f + 300f, z * 0.0013f - 200f) + n2.noise(x * 0.004f, z * 0.004f) * 0.08f)
        val carve = smoothstep(0.055f, 0.0f, rv) * (1f - mMask * 0.6f) * (1f - dry * 0.8f)
        h = lerp(h, -2.6f, carve * smoothstep(-2f, 8f, h))
        return lerp(-32f, h, c)
    }

    /** Final terrain height including plateaus around points of interest. */
    fun height(x: Float, z: Float): Float {
        var h = baseHeight(x, z)
        val gx = Math.floorDiv(x.toInt(), GRID.toInt())
        val gz = Math.floorDiv(z.toInt(), GRID.toInt())
        for (ox in -1..1) for (oz in -1..1) {
            val list = poiGrid[key(gx + ox, gz + oz)] ?: continue
            for (p in list) {
                val dx = x - p.x; val dz = z - p.z
                val r = p.radius
                val d2 = dx * dx + dz * dz
                val outer = r * 1.8f
                if (d2 > outer * outer) continue
                val d = sqrt(d2)
                val w = smoothstep(outer, r, d)
                h = lerp(h, p.flatHeight, w)
            }
        }
        return h
    }

    fun normal(x: Float, z: Float, out: FloatArray, eps: Float = 1f) {
        val hl = height(x - eps, z); val hr = height(x + eps, z)
        val hd = height(x, z - eps); val hu = height(x, z + eps)
        var nx = hl - hr; var ny = 2f * eps; var nz = hd - hu
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= l; ny /= l; nz /= l
        out[0] = nx; out[1] = ny; out[2] = nz
    }

    fun biomeAt(x: Float, z: Float): Biome = biomeAt(x, z, height(x, z))

    fun biomeAt(x: Float, z: Float, h: Float): Biome {
        if (h < -1.2f) return Biome.OCEAN
        val marsh = marshness(x, z)
        if (marsh > 0.5f) return Biome.MARSH
        if (h < 2.2f && continent(x, z) < 0.6f) return Biome.BEACH
        val cold = coldness(x, z)
        if (h > 105f || (cold > 0.55f && h > 45f) || (cold > 0.82f && h > 6f)) return Biome.SNOW
        if (cold > 0.5f) return Biome.PINE
        if (dryness(x, z) > 0.5f) return Biome.DESERT
        return if (forestness(x, z) > 0.55f) Biome.FOREST else Biome.MEADOW
    }

    /** Approximate ambient temperature in Celsius before time-of-day and weather modifiers. */
    fun baseTemperature(x: Float, z: Float, h: Float): Float {
        val b = biomeAt(x, z, h)
        var t = b.baseTemp
        if (h > 40f) t -= (h - 40f) * 0.12f
        return t
    }

    /** sRGB terrain color for a point plus a rock tint parameter in alpha. */
    fun terrainColor(x: Float, z: Float, h: Float, out: FloatArray) {
        val cold = coldness(x, z)
        val dry = dryness(x, z)
        val marsh = marshness(x, z)
        val forest = forestness(x, z)
        val moist = n3.noise(x * 0.01f, z * 0.01f) * 0.5f + 0.5f
        // meadow grass
        var r = lerp(0.34f, 0.27f, moist); var g = lerp(0.47f, 0.43f, moist); var b = lerp(0.18f, 0.16f, moist)
        // forest floor: darker, mossy
        val f = smoothstep(0.35f, 0.75f, forest)
        r = lerp(r, 0.22f, f); g = lerp(g, 0.30f, f); b = lerp(b, 0.13f, f)
        // taiga: cool olive
        r = lerp(r, 0.30f, cold); g = lerp(g, 0.34f, cold); b = lerp(b, 0.24f, cold)
        // rust flats: oxidized red sand
        val sandVar = n4.noise(x * 0.02f, z * 0.02f) * 0.06f
        r = lerp(r, 0.62f + sandVar, dry); g = lerp(g, 0.36f + sandVar * 0.6f, dry); b = lerp(b, 0.22f, dry)
        // marsh: dark wet olive
        r = lerp(r, 0.22f, marsh); g = lerp(g, 0.27f, marsh); b = lerp(b, 0.15f, marsh)
        // beaches and river banks
        val beach = smoothstep(2.8f, 0.8f, h) * (1f - marsh)
        r = lerp(r, 0.64f, beach); g = lerp(g, 0.58f, beach); b = lerp(b, 0.43f, beach)
        // snow line
        val snow = clamp01(smoothstep(95f, 115f, h) + smoothstep(40f, 60f, h) * smoothstep(0.5f, 0.8f, cold) + smoothstep(0.78f, 0.9f, cold) * smoothstep(4f, 9f, h))
        r = lerp(r, 0.88f, snow); g = lerp(g, 0.9f, snow); b = lerp(b, 0.94f, snow)
        // underwater
        if (h < -0.5f) { r *= 0.8f; g *= 0.85f; b *= 0.8f }
        out[0] = r; out[1] = g; out[2] = b
        out[3] = dry
    }

    fun inWorld(x: Float, z: Float): Boolean = abs(x) < HALF && abs(z) < HALF

    // ----------------------------------------------------------------- POIs

    private fun key(gx: Int, gz: Int): Long = (gx.toLong() shl 32) xor (gz.toLong() and 0xffffffffL)

    private fun generatePois() {
        var id = 0
        fun add(kind: PoiKind, x: Float, z: Float, r: Float, name: String, log: Int = -1, minH: Float = 4f): Poi {
            val p = Poi(id++, kind, x, z, r, name, log)
            // plateau height: average base height around the POI, kept above the water line
            var sum = 0f
            for (i in 0 until 8) {
                val a = i * 0.785f
                sum += baseHeight(x + kotlin.math.cos(a) * r * 0.7f, z + kotlin.math.sin(a) * r * 0.7f)
            }
            sum += baseHeight(x, z) * 2f
            p.flatHeight = max(sum / 10f, minH)
            p.y = p.flatHeight
            pois.add(p)
            return p
        }

        add(PoiKind.VAULT, 40f, 160f, 18f, "Cradle-7 Vault", minH = 8f)
        add(PoiKind.RELAY, 330f, -60f, 16f, "Whisper Relay", minH = 12f)
        add(PoiKind.SPIRE_GREEN, -560f, 180f, 22f, "Verdant Spire", minH = 8f)
        add(PoiKind.SPIRE_DUST, 960f, 260f, 22f, "Dust Spire", minH = 10f)
        add(PoiKind.SPIRE_FROST, -60f, -1030f, 22f, "Frost Spire", minH = 30f)
        add(PoiKind.SPIRE_TIDE, -640f, 720f, 22f, "Tide Spire", minH = 3.5f)
        add(PoiKind.CROWN, 420f, -560f, 40f, "The Crown", minH = 20f)
        add(PoiKind.CAMP, 160f, 300f, 10f, "Abandoned Camp", minH = 5f)

        val rng = Rng(SEED xor 0x5DEECE66DL)
        fun scatter(kind: PoiKind, count: Int, r: Float, names: List<String>, withLog: Boolean, startLog: Int) {
            var placed = 0
            var tries = 0
            while (placed < count && tries < 6000) {
                tries++
                val x = rng.range(-1250f, 1250f)
                val z = rng.range(-1250f, 1250f)
                val h = baseHeight(x, z)
                if (h < 4f || h > 120f) continue
                if (pois.any { val dx = it.x - x; val dz = it.z - z; dx * dx + dz * dz < 150f * 150f }) continue
                val name = names[placed % names.size]
                add(kind, x, z, r, name, if (withLog) startLog + placed else -1)
                placed++
            }
        }
        scatter(PoiKind.RUIN, 24, 14f, RUIN_NAMES, true, 0)
        scatter(PoiKind.CACHE, 14, 6f, listOf("Supply Cache"), false, 0)
        scatter(PoiKind.WRECK, 10, 16f, listOf("Machine Graveyard"), false, 0)

        for (p in pois) {
            val gx = Math.floorDiv(p.x.toInt(), GRID.toInt())
            val gz = Math.floorDiv(p.z.toInt(), GRID.toInt())
            poiGrid.getOrPut(key(gx, gz)) { ArrayList() }.add(p)
        }
    }

    fun poisNear(x: Float, z: Float, range: Float): List<Poi> {
        val out = ArrayList<Poi>()
        for (p in pois) {
            val dx = p.x - x; val dz = p.z - z
            if (dx * dx + dz * dz < (range + p.radius) * (range + p.radius)) out.add(p)
        }
        return out
    }

    /** Distance to the nearest POI (used to keep vegetation out of ruins). */
    fun poiClearance(x: Float, z: Float): Float {
        var best = Float.MAX_VALUE
        val gx = Math.floorDiv(x.toInt(), GRID.toInt())
        val gz = Math.floorDiv(z.toInt(), GRID.toInt())
        for (ox in -1..1) for (oz in -1..1) {
            val list = poiGrid[key(gx + ox, gz + oz)] ?: continue
            for (p in list) {
                val dx = x - p.x; val dz = z - p.z
                best = min(best, sqrt(dx * dx + dz * dz) - p.radius)
            }
        }
        return best
    }

}
