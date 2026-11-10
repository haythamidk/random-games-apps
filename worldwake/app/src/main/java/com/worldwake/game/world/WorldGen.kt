package com.worldwake.game.world

import com.worldwake.game.engine.Rng
import com.worldwake.game.game.Item
import com.worldwake.game.engine.SimplexNoise
import com.worldwake.game.engine.TWO_PI
import com.worldwake.game.engine.clamp
import com.worldwake.game.engine.clamp01
import com.worldwake.game.engine.lerp
import com.worldwake.game.engine.smoothstep
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class Biome(val label: String) {
    FLATS("Frost Flats"), SPIRES("Slate Spires"), SHARDS("The Shards"), VENTS("Brimstone Vents"),
    HIGHLAND("North Terraces"), SINK("The Sink"), RIM("Rimwall"),
}

enum class PoiKind { LANDER, OUTPOST, SLATE, CARGO, WRECK, VAULT, WATCHER }

class Poi(val id: Int, val kind: PoiKind, var x: Float, var z: Float, val flatten: Float, val logIndex: Int = -1, val yaw: Float = 0f) {
    var y = 0f
}

/**
 * Deterministic terrain for Ysmere: one huge impact basin ringed by the Rimwall. Low ground becomes lakes once
 * the water rises to [WATER]. Thread safe after class init (pure functions over immutable noise tables).
 */
object WorldGen {
    const val WATER = 0f
    const val RIM_R = 1080f
    const val WORLD_R = 1220f

    private val nA = SimplexNoise(1101)
    private val nB = SimplexNoise(2203)
    private val nC = SimplexNoise(3307)
    private val nD = SimplexNoise(4409)
    private val nE = SimplexNoise(5501)

    // region centres
    const val SINK_X = 140f; const val SINK_Z = 220f
    private const val SPIRE_X = -640f; private const val SPIRE_Z = 120f
    private const val SHARD_X = 600f; private const val SHARD_Z = 620f
    private const val VENT_X = 640f; private const val VENT_Z = -480f
    private const val HIGH_X = -80f; private const val HIGH_Z = 860f
    const val START_X = -120f; const val START_Z = -430f

    private fun region(x: Float, z: Float, cx: Float, cz: Float, r: Float): Float {
        val dx = x - cx; val dz = z - cz
        val d = sqrt(dx * dx + dz * dz) + nD.noise(x * 0.004f, z * 0.004f) * 70f
        return smoothstep(r, r * 0.55f, d)
    }

    fun sinkW(x: Float, z: Float) = region(x, z, SINK_X, SINK_Z, 310f)
    fun spireW(x: Float, z: Float) = region(x, z, SPIRE_X, SPIRE_Z, 400f)
    fun shardW(x: Float, z: Float) = region(x, z, SHARD_X, SHARD_Z, 340f)
    fun ventW(x: Float, z: Float) = region(x, z, VENT_X, VENT_Z, 330f)
    fun highW(x: Float, z: Float) = region(x, z, HIGH_X, HIGH_Z, 430f)

    private fun terrace(v: Float, step: Float): Float {
        val k = floor(v / step)
        val f = v / step - k
        return (k + smoothstep(0.3f, 0.7f, f)) * step
    }

    /** Terrain height without the flattening applied around points of interest. */
    fun baseHeight(x: Float, z: Float): Float {
        val r = sqrt(x * x + z * z)
        var h = 7f + nA.fbm(x * 0.0016f, z * 0.0016f, 4) * 17f
        h += nB.fbm(x * 0.007f, z * 0.007f, 3) * 3.5f
        h += nC.noise(x * 0.03f, z * 0.03f) * 0.45f

        // small lake basins scattered over the flats
        val lake = smoothstep(-0.28f, -0.56f, nE.fbm(x * 0.0034f, z * 0.0034f, 2))
        val wSp = spireW(x, z)
        val wHi = highW(x, z)
        val wSink = sinkW(x, z)
        val wSh = shardW(x, z)
        val wVe = ventW(x, z)
        if (lake > 0f) h = lerp(h, min(h, -3.2f + nB.noise(x * 0.01f, z * 0.01f) * 1.2f), lake * (1f - wSp) * (1f - wHi))

        if (wSp > 0f) {
            val rg = nB.ridged(x * 0.0055f + 3f, z * 0.0055f, 3)
            val up = terrace(rg * 34f, 7f)
            h += wSp * (4f + up)
        }
        if (wHi > 0f) {
            val rg = nC.ridged(x * 0.0038f, z * 0.0038f + 5f, 4)
            h += wHi * (14f + terrace(rg * 46f, 9f))
        }
        if (wSh > 0f) {
            val flat = 3.5f + nB.noise(x * 0.012f, z * 0.012f) * 1.6f
            h = lerp(h, flat, wSh * 0.85f)
            // shallow impact pits
            val pit = smoothstep(0.62f, 0.8f, nE.noise(x * 0.011f + 9f, z * 0.011f))
            h -= pit * wSh * 3f
        }
        if (wVe > 0f) {
            h = lerp(h, 6f + nD.fbm(x * 0.009f, z * 0.009f, 3) * 6f, wVe * 0.8f)
            val mound = smoothstep(0.55f, 0.85f, nE.noise(x * 0.016f, z * 0.016f + 4f))
            h += mound * wVe * 5f
        }
        if (wSink > 0f) {
            val bed = -6.5f + nB.fbm(x * 0.006f, z * 0.006f, 2) * 1.6f
            h = lerp(h, bed, wSink)
            // the vault island at the centre of the Sink
            val dx = x - SINK_X; val dz = z - SINK_Z
            val d = sqrt(dx * dx + dz * dz)
            h += smoothstep(62f, 20f, d) * 10.5f
        }
        if (r > RIM_R) {
            val t = (r - RIM_R) / 170f
            h += t * t * 150f + nA.ridged(x * 0.004f, z * 0.004f, 4) * 70f * min(t, 1f)
        } else if (r > RIM_R - 160f) {
            h += smoothstep(RIM_R - 160f, RIM_R, r) * 10f
        }
        return h
    }

    fun height(x: Float, z: Float): Float {
        var h = baseHeight(x, z)
        for (p in flatPois) {
            val dx = x - p.x; val dz = z - p.z
            val d2 = dx * dx + dz * dz
            val r = p.flatten
            if (d2 < r * r * 4f) {
                val t = smoothstep(r * 2f, r, sqrt(d2))
                h = lerp(h, p.y, t)
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

    fun biomeAt(x: Float, z: Float): Biome {
        val r = sqrt(x * x + z * z)
        if (r > RIM_R - 40f) return Biome.RIM
        var best = Biome.FLATS; var bw = 0.42f
        val a = sinkW(x, z); if (a > bw) { bw = a; best = Biome.SINK }
        val b = spireW(x, z); if (b > bw) { bw = b; best = Biome.SPIRES }
        val c = shardW(x, z); if (c > bw) { bw = c; best = Biome.SHARDS }
        val d = ventW(x, z); if (d > bw) { bw = d; best = Biome.VENTS }
        val e = highW(x, z); if (e > bw) { best = Biome.HIGHLAND }
        return best
    }

    /** Fertility 0..1: how readily native life takes hold here. */
    fun fertility(x: Float, z: Float, h: Float): Float {
        var f = 1f
        f -= shardW(x, z) * 0.7f
        f -= ventW(x, z) * 0.8f
        f -= highW(x, z) * 0.45f
        f -= spireW(x, z) * 0.35f
        f -= smoothstep(40f, 90f, h) * 0.8f
        f += smoothstep(6f, 0.5f, h) * 0.25f
        return clamp(f, 0f, 1f)
    }

    /** Terrain vertex attributes: A = (soil, silt, sulfur, gravel), B = (frost bias, fertility, rock bias, 0). */
    fun splat(x: Float, z: Float, h: Float, out: FloatArray) {
        val r = sqrt(x * x + z * z)
        val wSh = shardW(x, z); val wVe = ventW(x, z); val wHi = highW(x, z); val wSp = spireW(x, z)
        val rim = smoothstep(RIM_R - 120f, RIM_R + 40f, r)
        var silt = max(smoothstep(1.2f, -1.2f, h), wSh * 0.75f)
        var sulfur = wVe * smoothstep(-0.3f, 0.35f, nE.noise(x * 0.02f, z * 0.02f))
        var gravel = max(max(wHi * 0.7f, wSp * 0.6f), rim) * (1f - silt)
        sulfur *= 1f - silt
        gravel *= 1f - sulfur
        silt = clamp01(silt)
        val soil = clamp01(1f - silt - sulfur - gravel)
        val sum = soil + silt + sulfur + gravel
        out[0] = soil / sum; out[1] = silt / sum; out[2] = sulfur / sum; out[3] = gravel / sum
        out[4] = clamp((h - 30f) / 38f, -0.08f, 1.3f) - wVe * 0.8f + rim * 0.6f
        out[5] = fertility(x, z, h)
        out[6] = clamp01(wSp * 0.55f + rim * 0.5f)
        out[7] = 0f
    }

    // ------------------------------------------------------------------ points of interest

    val pois: List<Poi>
    private val flatPois: List<Poi>
    val lander: Poi
    val vault: Poi
    const val LOG_COUNT = 12

    init {
        val list = ArrayList<Poi>()
        var id = 0
        fun add(kind: PoiKind, x: Float, z: Float, flatten: Float, log: Int = -1, yaw: Float = 0f): Poi {
            val p = Poi(id++, kind, x, z, flatten, log, yaw)
            list.add(p)
            return p
        }
        lander = add(PoiKind.LANDER, START_X, START_Z, 12f, yaw = 0.6f)
        vault = add(PoiKind.VAULT, SINK_X, SINK_Z, 16f)
        // Lantern Nine outposts, each holds one of the crew logs
        add(PoiKind.OUTPOST, -330f, -250f, 10f, 0, 1.2f)
        add(PoiKind.OUTPOST, -70f, -40f, 10f, 2, 2.6f)
        add(PoiKind.OUTPOST, -560f, -60f, 10f, 4, 0.3f)
        add(PoiKind.OUTPOST, 560f, -400f, 10f, 6, 4.1f)
        add(PoiKind.OUTPOST, 500f, 520f, 10f, 8, 5.2f)
        add(PoiKind.OUTPOST, -170f, 740f, 10f, 10, 3.3f)
        // loose data slates
        add(PoiKind.SLATE, 60f, -520f, 0f, 1)
        add(PoiKind.SLATE, 250f, -250f, 0f, 3)
        add(PoiKind.SLATE, -760f, 300f, 0f, 5)
        add(PoiKind.SLATE, 780f, -230f, 0f, 7)
        add(PoiKind.SLATE, 330f, 470f, 0f, 9)
        add(PoiKind.SLATE, SINK_X + 9f, SINK_Z - 12f, 0f, 11)
        add(PoiKind.WRECK, 262f, -262f, 9f, yaw = 0.9f)
        // supply drops from the old expedition
        val rng = Rng(77123)
        var placed = 0
        var guard = 0
        while (placed < 16 && guard++ < 4000) {
            val a = rng.nextFloat() * TWO_PI
            val d = 120f + rng.nextFloat() * 880f
            val x = cos(a) * d; val z = sin(a) * d
            if (baseHeight(x, z) < 1.5f) continue
            if (list.any { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) < 110f * 110f }) continue
            add(PoiKind.CARGO, x, z, 0f, yaw = rng.nextFloat() * TWO_PI)
            placed++
        }
        // the Watchers: a ring around the Sink, and others scattered across the basin
        for (i in 0 until 12) {
            val a = i * TWO_PI / 12f + 0.13f
            add(PoiKind.WATCHER, SINK_X + cos(a) * 232f, SINK_Z + sin(a) * 232f, 0f)
        }
        placed = 0; guard = 0
        while (placed < 26 && guard++ < 4000) {
            val a = rng.nextFloat() * TWO_PI
            val d = 90f + rng.nextFloat() * 930f
            val x = cos(a) * d; val z = sin(a) * d
            if (baseHeight(x, z) < 1f) continue
            if (list.any { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) < 60f * 60f }) continue
            add(PoiKind.WATCHER, x, z, 0f)
            placed++
        }
        // settle every site on dry, gentle ground near where it was authored
        for (p in list) if (p.kind != PoiKind.VAULT) settle(p)
        flatPois = list.filter { it.flatten > 0f }
        for (p in list) p.y = baseHeight(p.x, p.z)
        pois = list
        // structures stand on the flattened pad height
        for (p in list) p.y = height(p.x, p.z)
    }

    private fun settle(p: Poi) {
        fun ok(x: Float, z: Float): Boolean {
            val h = baseHeight(x, z)
            if (h < 1.4f) return false
            val e = 4f
            val sx = baseHeight(x + e, z) - baseHeight(x - e, z)
            val sz = baseHeight(x, z + e) - baseHeight(x, z - e)
            val limit = if (p.flatten > 0f) 0.22f else 0.3f
            val slope = sqrt(sx * sx + sz * sz) / (2f * e)
            return slope < limit
        }
        if (ok(p.x, p.z)) return
        for (ring in 1..40) {
            val d = ring * 6f
            for (k in 0 until 12) {
                val a = k * TWO_PI / 12f + ring * 0.37f
                val x = p.x + cos(a) * d; val z = p.z + sin(a) * d
                if (ok(x, z)) { p.x = x; p.z = z; return }
            }
        }
    }

    /** Distance to the nearest built site, minus its footprint (negative inside). */
    fun poiClearance(x: Float, z: Float): Float {
        var best = 9999f
        for (p in pois) {
            val r = when (p.kind) {
                PoiKind.LANDER -> 9f; PoiKind.OUTPOST -> 9f; PoiKind.VAULT -> 15f; PoiKind.WRECK -> 7f
                PoiKind.CARGO -> 2.5f; PoiKind.WATCHER -> 2f; PoiKind.SLATE -> 1.2f
            }
            val dx = x - p.x; val dz = z - p.z
            val d = sqrt(dx * dx + dz * dz) - r
            if (d < best) best = d
        }
        return best
    }

    /** Ore an extractor pulls from the ground at a spot. */
    fun veinAt(x: Float, z: Float): Item {
        val n = nE.noise(x * 0.006f + 31f, z * 0.006f - 17f)
        val n2 = nD.noise(x * 0.008f - 11f, z * 0.008f + 23f)
        return when (biomeAt(x, z)) {
            Biome.FLATS -> if (n > 0.25f) Item.FERRITE else if (n < -0.3f) Item.MAGNESIUM else if (n2 > 0.2f) Item.SILICA else Item.COBALT
            Biome.SPIRES -> if (n > 0.1f) Item.ALUMINIUM else if (n2 > 0f) Item.TITANIUM else Item.FERRITE
            Biome.SHARDS -> if (n > 0f) Item.LUMEN else if (n2 > 0.1f) Item.SILICA else Item.COBALT
            Biome.VENTS -> if (n > 0.15f) Item.PYRE else Item.SULFUR
            Biome.HIGHLAND -> if (n > 0.1f) Item.OSMIUM else if (n2 > 0f) Item.TITANIUM else Item.MAGNESIUM
            Biome.SINK -> Item.ICE
            Biome.RIM -> Item.FERRITE
        }
    }
}
