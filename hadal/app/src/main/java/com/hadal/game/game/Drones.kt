package com.hadal.game.game

import com.hadal.game.engine.Rng
import com.hadal.game.engine.TWO_PI
import com.hadal.game.engine.Vec3
import com.hadal.game.engine.lerp
import com.hadal.game.world.SeaGen
import com.hadal.game.world.Structures
import com.hadal.game.world.Zone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Fleet-wide drone upgrades. Every drone benefits from them. */
enum class DroneUpgrade(val title: String, val desc: String) {
    SPEED("Thrusters", "How fast the drones swim."),
    CAPACITY("Cargo Pods", "How many materials each trip brings back."),
    RANGE("Range", "How far from the station they will search."),
}

object DroneStats {
    const val MAX_DRONES = 3
    const val MAX_LEVEL = 3
    const val WORK_SECONDS = 25f
    const val LOST_SECONDS = 90f

    val speed = floatArrayOf(5f, 7.5f, 11f, 15f)
    val capacity = intArrayOf(2, 4, 6, 9)
    val range = floatArrayOf(300f, 600f, 1000f, 1700f)

    fun buildCost(n: Int): Map<Item, Int> = when (n) {
        0 -> mapOf(Item.SCRAP to 12, Item.COPPER to 6)
        1 -> mapOf(Item.SCRAP to 16, Item.COPPER to 10, Item.BIOSAMPLE to 4)
        else -> mapOf(Item.NODULE to 12, Item.COPPER to 12, Item.CRYSTAL to 4)
    }

    fun upgradeCost(u: DroneUpgrade, lvl: Int): Map<Item, Int> = when (u) {
        DroneUpgrade.SPEED -> when (lvl) {
            0 -> mapOf(Item.SCRAP to 8, Item.COPPER to 4)
            1 -> mapOf(Item.COPPER to 8, Item.NODULE to 6)
            else -> mapOf(Item.CRYSTAL to 6, Item.NODULE to 8, Item.COPPER to 6)
        }
        DroneUpgrade.CAPACITY -> when (lvl) {
            0 -> mapOf(Item.SCRAP to 10, Item.BIOSAMPLE to 2)
            1 -> mapOf(Item.SCRAP to 12, Item.NODULE to 6, Item.BIOSAMPLE to 4)
            else -> mapOf(Item.NODULE to 12, Item.PEARL to 2, Item.CRYSTAL to 4)
        }
        DroneUpgrade.RANGE -> when (lvl) {
            0 -> mapOf(Item.COPPER to 6, Item.BIOSAMPLE to 3)
            1 -> mapOf(Item.COPPER to 8, Item.NODULE to 8, Item.BIOSAMPLE to 4)
            else -> mapOf(Item.CRYSTAL to 8, Item.PEARL to 3, Item.NODULE to 10)
        }
    }

    /** Chance that a drone is ambushed while working in this zone. */
    fun danger(z: Zone): Float = when (z) {
        Zone.SHELF -> 0f
        Zone.TWILIGHT -> 0.05f
        Zone.MIDNIGHT -> 0.12f
        Zone.VENTS -> 0.18f
        Zone.RIM, Zone.HADAL -> 0.25f
    }

    /** What a drone can find in each zone (weights add up to 1). */
    fun loot(z: Zone): List<Pair<Item, Float>> = when (z) {
        Zone.SHELF -> listOf(Item.SCRAP to 0.45f, Item.COPPER to 0.30f, Item.BIOSAMPLE to 0.20f, Item.PEARL to 0.05f)
        Zone.TWILIGHT -> listOf(Item.SCRAP to 0.30f, Item.COPPER to 0.30f, Item.BIOSAMPLE to 0.25f, Item.NODULE to 0.10f, Item.PEARL to 0.05f)
        Zone.MIDNIGHT -> listOf(Item.NODULE to 0.45f, Item.BIOSAMPLE to 0.25f, Item.SCRAP to 0.15f, Item.PEARL to 0.10f, Item.COPPER to 0.05f)
        Zone.VENTS -> listOf(Item.CRYSTAL to 0.50f, Item.NODULE to 0.40f, Item.BIOSAMPLE to 0.10f)
        Zone.RIM -> listOf(Item.ALLOY to 0.40f, Item.NODULE to 0.30f, Item.CRYSTAL to 0.20f, Item.PEARL to 0.10f)
        Zone.HADAL -> listOf(Item.ALLOY to 0.60f, Item.CRYSTAL to 0.25f, Item.NODULE to 0.15f)
    }
}

enum class DroneState { IDLE, OUT, WORKING, BACK, LOST }

class Drone(val index: Int) {
    var state = DroneState.IDLE
    var timer = 2f + index * 4f
    var travel = 1f
    val pos = Vec3()
    val home = Vec3()
    val target = Vec3()
    var yaw = 0f
    var zone = Zone.SHELF
    var distance = 0f
    val loot = IntArray(Item.entries.size)
    var trips = 0
}

/**
 * Up to three drones that fly out of the station on their own, search the sea and bring materials home into the
 * station storage. They keep working while you dive. Searching is abstract: a drone picks a spot inside its range,
 * swims there, collects what that zone offers and swims back, with a risk of being ambushed in the deep.
 */
class DroneFleet(private val g: GameState) {
    val drones = ArrayList<Drone>()
    val levels = IntArray(DroneUpgrade.entries.size)
    private val rng = Rng(System.nanoTime())

    val speed get() = DroneStats.speed[levels[DroneUpgrade.SPEED.ordinal]]
    val capacity get() = DroneStats.capacity[levels[DroneUpgrade.CAPACITY.ordinal]]
    val range get() = DroneStats.range[levels[DroneUpgrade.RANGE.ordinal]]

    fun reset() { drones.clear(); levels.fill(0) }

    fun addDrone(): Drone {
        val d = Drone(drones.size)
        placeHome(d)
        d.pos.set(d.home)
        drones.add(d)
        return d
    }

    private fun placeHome(d: Drone) {
        // a little row of docking cradles beside the Wren
        d.home.set(Structures.dockX + (d.index - 1) * 3.2f, Structures.dockY - 4.2f, Structures.dockZ + 5.5f)
    }

    fun update(dt: Float) {
        for (d in drones) {
            placeHome(d)
            when (d.state) {
                DroneState.IDLE -> {
                    d.pos.set(d.home)
                    d.timer -= dt
                    if (d.timer <= 0f) launch(d)
                }
                DroneState.OUT -> {
                    d.timer -= dt
                    fly(d, d.home, d.target, 1f - d.timer / d.travel)
                    if (d.timer <= 0f) { d.state = DroneState.WORKING; d.timer = DroneStats.WORK_SECONDS; d.pos.set(d.target) }
                }
                DroneState.WORKING -> {
                    d.timer -= dt
                    d.pos.set(d.target.x, d.target.y + sin(g.time * 2f + d.index) * 0.3f, d.target.z)
                    if (d.timer <= 0f) finishWork(d)
                }
                DroneState.BACK -> {
                    d.timer -= dt
                    fly(d, d.target, d.home, 1f - d.timer / d.travel)
                    if (d.timer <= 0f) arrive(d)
                }
                DroneState.LOST -> {
                    d.timer -= dt
                    if (d.timer <= 0f) { d.state = DroneState.IDLE; d.timer = 4f; d.pos.set(d.home) }
                }
            }
        }
    }

    private fun launch(d: Drone) {
        val r = range
        // pick a spot, area weighted so far places are not over represented
        val dist = 60f + (r - 60f) * sqrt(rng.nextFloat())
        val a = rng.range(0f, TWO_PI)
        val x = Structures.dockX + sin(a) * dist
        val z = Structures.dockZ + cos(a) * dist
        val floor = SeaGen.height(x, z)
        d.target.set(x, floor + 2.5f, z)
        d.zone = SeaGen.zone(x, z, floor)
        d.distance = dist
        d.travel = max(4f, dist / speed)
        d.timer = d.travel
        d.state = DroneState.OUT
        g.sound.playAt(Sfx.UNDOCK, d.home.x, d.home.y, d.home.z, 0.25f, 1.8f)
    }

    private fun finishWork(d: Drone) {
        d.loot.fill(0)
        if (rng.nextFloat() < DroneStats.danger(d.zone)) {
            d.state = DroneState.LOST
            d.timer = DroneStats.LOST_SECONDS
            g.toast("Drone ${d.index + 1} was ambushed and went offline")
            return
        }
        val table = DroneStats.loot(d.zone)
        val n = capacity + if (rng.chance(0.3f)) 1 else 0
        for (i in 0 until n) {
            var roll = rng.nextFloat()
            var pick = table.last().first
            for ((item, w) in table) { roll -= w; if (roll <= 0f) { pick = item; break } }
            d.loot[pick.ordinal]++
        }
        d.state = DroneState.BACK
        d.timer = d.travel
    }

    private fun arrive(d: Drone) {
        val parts = ArrayList<String>()
        var total = 0
        for (i in Item.entries) {
            val n = d.loot[i.ordinal]
            if (n == 0) continue
            g.storage[i.ordinal] += n
            g.story.collected[i.ordinal] += n
            total += n
            parts.add("$n ${i.title}")
        }
        d.loot.fill(0)
        d.trips++
        d.state = DroneState.IDLE
        d.timer = 6f
        d.pos.set(d.home)
        if (total > 0) {
            g.toast("Drone ${d.index + 1} is back: ${parts.joinToString(", ")}")
            g.sound.play(Sfx.PICKUP, 0.5f, 0.9f)
        }
    }

    /** Moves along the straight line between two points, staying clear of the floor. */
    private fun fly(d: Drone, from: Vec3, to: Vec3, t: Float) {
        val k = t.coerceIn(0f, 1f)
        val x = lerp(from.x, to.x, k); val z = lerp(from.z, to.z, k)
        val y = lerp(from.y, to.y, k)
        d.pos.set(x, max(y, SeaGen.height(x, z) + 2.5f) + sin(g.time * 3f + d.index * 2f) * 0.15f, z)
        d.yaw = atan2(to.x - from.x, to.z - from.z)
    }

    fun statusText(i: Int): String {
        val d = drones.getOrNull(i) ?: return ""
        val where = "${d.zone.title}, ${d.distance.toInt()} m"
        return when (d.state) {
            DroneState.IDLE -> "Docked, preparing the next trip" + if (d.trips > 0) "  (${d.trips} trips)" else ""
            DroneState.OUT -> "Heading out to $where, ${d.timer.toInt()} s"
            DroneState.WORKING -> "Searching $where, ${d.timer.toInt()} s"
            DroneState.BACK -> "Returning with ${d.loot.sum()} materials, ${d.timer.toInt()} s"
            DroneState.LOST -> "Offline after an ambush, back in ${d.timer.toInt()} s"
        }
    }
}
