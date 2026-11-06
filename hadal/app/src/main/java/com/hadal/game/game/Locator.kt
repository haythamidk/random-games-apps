package com.hadal.game.game

import com.hadal.game.engine.Rng
import com.hadal.game.world.InteractKind
import com.hadal.game.world.PoiType
import com.hadal.game.world.SeaGen
import com.hadal.game.world.Structures
import com.hadal.game.world.Zone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A search area shown on the map and compass: a circle around the place, never the exact spot. The real thing is
 * always somewhere inside the circle.
 */
class Track(val label: String, val x: Float, val z: Float, val radius: Float, val note: String)

/** Works out where materials and story items can be found. Everything is derived from the world generator. */
object Locator {
    private const val STEP = 100f
    private val points = HashMap<Zone, ArrayList<FloatArray>>()
    @Volatile private var ready = false

    /** Samples the ocean once so zone lookups are instant. Called on a background thread while the game loads. */
    @Synchronized
    fun init() {
        if (ready) return
        var x = -3000f
        while (x <= 3000f) {
            var z = -2600f
            while (z <= 3000f) {
                val h = SeaGen.height(x, z)
                points.getOrPut(SeaGen.zone(x, z, h)) { ArrayList() }.add(floatArrayOf(x, z))
                z += STEP
            }
            x += STEP
        }
        ready = true
    }

    /** Sample points of a zone (empty until [init] has run). */
    fun zonePoints(z: Zone): List<FloatArray> { if (!ready) init(); return points[z] ?: emptyList() }

    /** Zones in which a material can be found by the player or by drones. */
    fun zonesFor(item: Item): Set<Zone> = when (item) {
        Item.SCRAP -> setOf(Zone.SHELF, Zone.TWILIGHT, Zone.MIDNIGHT)
        Item.COPPER -> setOf(Zone.SHELF, Zone.TWILIGHT)
        Item.NODULE -> setOf(Zone.MIDNIGHT, Zone.VENTS, Zone.RIM, Zone.HADAL)
        Item.CRYSTAL -> setOf(Zone.VENTS)
        Item.PEARL -> setOf(Zone.SHELF, Zone.TWILIGHT, Zone.MIDNIGHT)
        Item.BIOSAMPLE -> setOf(Zone.SHELF, Zone.TWILIGHT, Zone.MIDNIGHT)
        Item.ALLOY -> setOf(Zone.RIM, Zone.HADAL)
        else -> emptySet()
    }

    private fun jitter(salt: Int, x: Float, z: Float, radius: Float, spread: Float): FloatArray {
        val r = Rng(Rng.hash(salt, floor(x / 200f).toInt(), floor(z / 200f).toInt()))
        val a = r.range(0f, 6.2831f)
        val d = radius * spread * sqrt(r.nextFloat())
        return floatArrayOf(x + cos(a) * d, z + sin(a) * d)
    }

    private fun nearest(list: List<FloatArray>, px: Float, pz: Float): FloatArray? {
        var best: FloatArray? = null
        var bd = Float.MAX_VALUE
        for (p in list) {
            val dx = p[0] - px; val dz = p[1] - pz
            val d = dx * dx + dz * dz
            if (d < bd) { bd = d; best = p }
        }
        return best
    }

    /** The nearest area where [item] can be found. */
    fun material(item: Item, px: Float, pz: Float): Track? {
        val zones = zonesFor(item)
        if (zones.isEmpty()) return null
        val cands = ArrayList<FloatArray>()
        for (zn in zones) cands.addAll(zonePoints(zn))
        var spot = nearest(cands, px, pz) ?: return null
        var radius = 160f
        var where = "open sea floor"
        // wrecks are littered with scrap
        if (item == Item.SCRAP) {
            val sites = SeaGen.pois.filter { it.type == PoiType.MERIDIAN || it.type == PoiType.WRECK || it.type == PoiType.CINDER }
            val w = sites.minByOrNull { (it.x - px) * (it.x - px) + (it.z - pz) * (it.z - pz) }
            if (w != null) {
                val dw = sqrt((w.x - px) * (w.x - px) + (w.z - pz) * (w.z - pz))
                val ds = sqrt((spot[0] - px) * (spot[0] - px) + (spot[1] - pz) * (spot[1] - pz))
                if (dw < ds + 500f) { spot = floatArrayOf(w.x, w.z); radius = 110f; where = "around ${w.name}" }
            }
        }
        val c = jitter(item.ordinal + 7, spot[0], spot[1], radius, 0.35f)
        val zn = SeaGen.zone(spot[0], spot[1])
        if (where == "open sea floor") where = "in the ${zn.title}"
        return Track(item.title, c[0], c[1], radius, "$where, search inside the circle")
    }

    /** The nearest unrecovered crew data core, optionally limited to some log numbers. */
    fun cores(g: GameState, px: Float, pz: Float, only: IntRange? = null): Track? {
        var best: com.hadal.game.world.Interact? = null
        var bd = Float.MAX_VALUE
        for (it in Structures.interacts) {
            if (it.kind != InteractKind.LOG) continue
            val li = g.logIndex(it)
            if (only != null && li !in only) continue
            if (g.story.logsFound.contains(li)) continue
            val d = (it.x - px) * (it.x - px) + (it.z - pz) * (it.z - pz)
            if (d < bd) { bd = d; best = it }
        }
        val b = best ?: return null
        val c = jitter(31, b.x, b.z, 80f, 0.4f)
        return Track("Data core", c[0], c[1], 80f, "near ${b.poi.name}, ping sonar for pink squares")
    }

    fun echoStone(g: GameState, px: Float, pz: Float): Track? {
        var best: com.hadal.game.world.Poi? = null
        var bd = Float.MAX_VALUE
        for ((i, e) in SeaGen.echoStones.withIndex()) {
            if (g.story.echoes.contains(i)) continue
            val d = (e.x - px) * (e.x - px) + (e.z - pz) * (e.z - pz)
            if (d < bd) { bd = d; best = e }
        }
        val b = best ?: return null
        val c = jitter(41, b.x, b.z, 180f, 0.45f)
        return Track("Echo Stone", c[0], c[1], 180f, "glowing stones, ping sonar for cyan squares")
    }

    fun beacon(g: GameState, px: Float, pz: Float): Track? {
        val b = Structures.interacts.filter { it.kind == InteractKind.BEACON && !g.story.beacons.contains(it.index) }
            .minByOrNull { (it.x - px) * (it.x - px) + (it.z - pz) * (it.z - pz) } ?: return null
        val c = jitter(53, b.x, b.z, 90f, 0.4f)
        return Track("Descent beacon", c[0], c[1], 90f, "on the wall of the trench")
    }

    fun poi(type: PoiType, label: String, radius: Float, exact: Boolean = false): Track? {
        val p = SeaGen.poi(type)
        if (exact) return Track(label, p.x, p.z, radius, "")
        val c = jitter(61 + type.ordinal, p.x, p.z, radius, 0.4f)
        return Track(label, c[0], c[1], radius, "")
    }

    fun station(): Track = Track("Lantern Station", SeaGen.poi(PoiType.STATION).x, SeaGen.poi(PoiType.STATION).z, 55f, "")

    /** Compass direction word for a bearing from the sub to a point (north is +z, east is -x). */
    fun direction(dx: Float, dz: Float): String {
        val a = Math.toDegrees(atan2(-dx, dz).toDouble()).let { if (it < 0) it + 360.0 else it }
        return listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((a + 22.5) / 45.0).toInt()) % 8]
    }
}
