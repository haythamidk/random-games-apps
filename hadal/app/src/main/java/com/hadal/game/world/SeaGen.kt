package com.hadal.game.world

import com.hadal.game.engine.Rng
import com.hadal.game.engine.SimplexNoise
import com.hadal.game.engine.clamp01
import com.hadal.game.engine.lerp
import com.hadal.game.engine.smoothstep
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class Zone(val title: String) {
    SHELF("Kelp Shelf"), TWILIGHT("Twilight Slope"), MIDNIGHT("Midnight Plains"), VENTS("Cinder Vents"),
    RIM("Trench Rim"), HADAL("The Hadal Deep"),
}

enum class PoiType { STATION, RELAY, MERIDIAN, WRECK, CINDER, BEACON, CHOIR, ECHO, WHALEFALL }

class Poi(val id: Int, val type: PoiType, val name: String, val x: Float, val z: Float, val radius: Float) {
    val y: Float get() = SeaGen.height(x, z)
}

/**
 * Deterministic ocean floor. The station sits on a sunlit kelp shelf; the floor falls away through the twilight
 * slope to the midnight plains, a volcanic vent basin lies to the west (+x) and the hadal trench cuts across the north (+z).
 * Surface is y = 0, so -y is the depth in meters.
 */
object SeaGen {
    private val nBase = SimplexNoise(7101)
    private val nDetail = SimplexNoise(7102)
    private val nRidge = SimplexNoise(7103)
    private val nWarp = SimplexNoise(7104)
    private val nMat = SimplexNoise(7105)

    const val VENT_X = 1500f
    const val VENT_Z = 650f
    const val VENT_R = 420f

    /** Center line of the trench along x. */
    fun trenchZ(x: Float): Float = 2250f + 260f * sin(x / 760f) + 90f * sin(x / 230f + 1.3f)
    const val TRENCH_HALF = 230f

    val pois = ArrayList<Poi>()
    val echoStones = ArrayList<Poi>()

    init {
        var id = 0
        pois.add(Poi(id++, PoiType.STATION, "Lantern Station", 0f, 0f, 30f))
        pois.add(Poi(id++, PoiType.RELAY, "Shelf Relay", 250f, -150f, 14f))
        pois.add(Poi(id++, PoiType.MERIDIAN, "Wreck of the Meridian", -560f, -420f, 60f))
        pois.add(Poi(id++, PoiType.WRECK, "Supply Barge", 380f, 520f, 30f))
        pois.add(Poi(id++, PoiType.WRECK, "Lost Trawler", -900f, 380f, 30f))
        pois.add(Poi(id++, PoiType.WHALEFALL, "Whale Fall", 760f, -820f, 25f))
        pois.add(Poi(id++, PoiType.WHALEFALL, "Old Bones", -1180f, 1050f, 25f))
        pois.add(Poi(id++, PoiType.CINDER, "Cinder Outpost", VENT_X - 260f, VENT_Z - 140f, 36f))
        val bx = floatArrayOf(-520f, 120f, 760f)
        for ((i, x) in bx.withIndex()) pois.add(Poi(id++, PoiType.BEACON, "Descent Beacon ${i + 1}", x, trenchZ(x) - TRENCH_HALF * (1.25f - i * 0.22f), 10f))
        pois.add(Poi(id++, PoiType.CHOIR, "The Choir", 300f, trenchZ(300f), 70f))
        // echo stones: late-game collectibles spread across every zone
        val r = Rng(55511)
        var tries = 0
        while (echoStones.size < 24 && tries < 2000) {
            tries++
            val x = r.range(-2600f, 2600f); val z = r.range(-2400f, 2700f)
            if (sqrt(x * x + z * z) < 260f) continue
            if (echoStones.any { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) < 400f * 400f }) continue
            echoStones.add(Poi(1000 + echoStones.size, PoiType.ECHO, "Echo Stone ${echoStones.size + 1}", x, z, 4f))
        }
    }

    fun poi(type: PoiType): Poi = pois.first { it.type == type }

    private fun fbm(n: SimplexNoise, x: Float, z: Float, oct: Int) = n.fbm(x, z, oct)

    /** Distance from the trench center line (approximate, vertical). */
    fun trenchDist(x: Float, z: Float): Float = abs(z - trenchZ(x))

    fun height(x: Float, z: Float): Float {
        val wx = x + nWarp.fbm(x * 0.0012f, z * 0.0012f, 2) * 160f
        val wz = z + nWarp.fbm(x * 0.0012f + 31f, z * 0.0012f, 2) * 160f
        val r = sqrt(wx * wx + wz * wz)
        // continental profile
        var h = -52f
        h -= smoothstep(300f, 950f, r) * 330f
        h -= smoothstep(950f, 1650f, r) * 170f
        // the plains tilt down toward the trench in the north
        h -= smoothstep(1200f, 2000f, z) * 420f
        // vent basin
        val vdx = x - VENT_X; val vdz = z - VENT_Z
        val vd = sqrt(vdx * vdx + vdz * vdz)
        val bowl = smoothstep(VENT_R * 1.25f, VENT_R * 0.3f, vd)
        h -= bowl * 330f
        // rolling detail, stronger and rougher with depth
        val depthK = clamp01(-h / 600f)
        h += fbm(nBase, x * 0.004f, z * 0.004f, 4) * (9f + depthK * 16f)
        h += fbm(nDetail, x * 0.025f, z * 0.025f, 3) * (1.6f + depthK * 1.4f)
        // rocky ridges on the slope
        val ridge = nRidge.ridged(x * 0.006f, z * 0.006f, 3)
        h += max(0f, ridge - 0.55f) * (22f + 40f * smoothstep(200f, 900f, r)) * (1f - bowl * 0.5f)
        // vent cones
        if (bowl > 0.05f) {
            val cone = nRidge.ridged(x * 0.02f + 7f, z * 0.02f, 2)
            h += max(0f, cone - 0.6f) * 60f * bowl
        }
        // the trench
        val td = trenchDist(x, z)
        if (td < TRENCH_HALF * 1.6f) {
            val wall = smoothstep(TRENCH_HALF * 1.05f, TRENCH_HALF * 0.35f, td + fbm(nDetail, x * 0.01f, z * 0.01f, 3) * 40f)
            val terrace = smoothstep(0.35f, 0.4f, wall) * 0.15f + smoothstep(0.62f, 0.68f, wall) * 0.2f
            h -= (wall * 0.65f + terrace) * 640f
            h += nRidge.ridged(x * 0.03f, z * 0.03f, 2) * 14f * wall
        }
        // flat pad for the station
        val sd = sqrt(x * x + z * z)
        h = lerp(-58f, h, smoothstep(40f, 95f, sd))
        // pads for structures
        for (p in pois) {
            if (p.type == PoiType.STATION || p.type == PoiType.BEACON || p.type == PoiType.ECHO) continue
            val dx = x - p.x; val dz = z - p.z
            val d2 = dx * dx + dz * dz
            val rr = p.radius * 1.8f
            if (d2 < rr * rr * 4f) {
                val ph = padHeight(p)
                h = lerp(ph, h, smoothstep(p.radius * 0.9f, rr * 2f, sqrt(d2)))
            }
        }
        return min(h, -6f)
    }

    private val padCache = HashMap<Int, Float>()
    private fun padHeight(p: Poi): Float = synchronized(padCache) {
        padCache.getOrPut(p.id) {
            // average of the unpadded floor around the site
            var s = 0f
            for (i in 0 until 8) {
                val a = i * 0.785f
                s += rawHeightNoPads(p.x + kotlin.math.cos(a) * p.radius, p.z + kotlin.math.sin(a) * p.radius)
            }
            s / 8f
        }
    }

    private fun rawHeightNoPads(x: Float, z: Float): Float {
        val wx = x + nWarp.fbm(x * 0.0012f, z * 0.0012f, 2) * 160f
        val wz = z + nWarp.fbm(x * 0.0012f + 31f, z * 0.0012f, 2) * 160f
        val r = sqrt(wx * wx + wz * wz)
        var h = -52f - smoothstep(300f, 950f, r) * 330f - smoothstep(950f, 1650f, r) * 170f - smoothstep(1200f, 2000f, z) * 420f
        val vdx = x - VENT_X; val vdz = z - VENT_Z
        h -= smoothstep(VENT_R * 1.25f, VENT_R * 0.3f, sqrt(vdx * vdx + vdz * vdz)) * 330f
        val td = trenchDist(x, z)
        if (td < TRENCH_HALF * 1.6f) h -= smoothstep(TRENCH_HALF * 1.05f, TRENCH_HALF * 0.35f, td) * 0.8f * 640f
        return h
    }

    fun normal(x: Float, z: Float, out: FloatArray) {
        val e = 1f
        val hx = height(x + e, z) - height(x - e, z)
        val hz = height(x, z + e) - height(x, z - e)
        var nx = -hx; var ny = 2f * e; var nz = -hz
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= l; ny /= l; nz /= l
        out[0] = nx; out[1] = ny; out[2] = nz
    }

    fun zone(x: Float, z: Float, h: Float = height(x, z)): Zone {
        val vdx = x - VENT_X; val vdz = z - VENT_Z
        if (vdx * vdx + vdz * vdz < (VENT_R * 1.15f) * (VENT_R * 1.15f) && h < -500f) return Zone.VENTS
        val td = trenchDist(x, z)
        if (td < TRENCH_HALF * 0.75f && h < -1050f) return Zone.HADAL
        if (td < TRENCH_HALF * 2.2f && h < -650f) return Zone.RIM
        return when {
            h > -130f -> Zone.SHELF
            h > -420f -> Zone.TWILIGHT
            else -> Zone.MIDNIGHT
        }
    }

    /** Material weights for terrain vertices: sand, silt, basalt, coral (rock comes from slope in the shader). */
    fun splat(x: Float, z: Float, h: Float, out: FloatArray) {
        val m = nMat.fbm(x * 0.02f, z * 0.02f, 3)
        val deep = smoothstep(-110f, -300f, h)
        var sand = (1f - deep) * (0.8f + m * 0.3f)
        var silt = deep
        val vdx = x - VENT_X; val vdz = z - VENT_Z
        var basalt = smoothstep(VENT_R * 1.2f, VENT_R * 0.6f, sqrt(vdx * vdx + vdz * vdz))
        basalt = max(basalt, smoothstep(TRENCH_HALF * 1.2f, TRENCH_HALF * 0.6f, trenchDist(x, z)) * 0.85f)
        var coral = (1f - deep) * smoothstep(0.15f, 0.45f, m) * smoothstep(-35f, -95f, h).let { 1f - it * 0.4f } * smoothstep(-140f, -70f, h)
        silt *= 1f - basalt; sand *= 1f - basalt
        coral *= 1f - basalt
        val s = sand + silt + basalt + coral + 1e-4f
        out[0] = sand / s; out[1] = silt / s; out[2] = basalt / s; out[3] = coral / s
    }

    /** Scalar 0..1 noise for scattering decisions. */
    fun density(x: Float, z: Float, salt: Float): Float = clamp01(0.5f + 0.5f * nMat.noise(x * 0.012f + salt, z * 0.012f - salt * 0.7f))

    /** Sunlight reaching depth d (0..1), used by gameplay (visibility to predators) and lighting. */
    fun sunAt(depth: Float): Float = kotlin.math.exp(-max(0f, depth) / 55f)
}
