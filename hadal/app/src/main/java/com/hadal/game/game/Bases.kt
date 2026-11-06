package com.hadal.game.game

import com.hadal.game.engine.MeshData
import com.hadal.game.world.Collider
import com.hadal.game.world.Fixture

/**
 * A place the Wren can dock: Lantern Station or an outpost placed by the player. All bases share one storage,
 * and each one has bays for [DroneStats.PER_BASE] drones that search around it.
 */
class Base(
    val index: Int, val name: String,
    /** Position of the base itself. */
    val x: Float, val y: Float, val z: Float,
    /** Where the docked sub rests. */
    val subX: Float, val subY: Float, val subZ: Float,
)

/** Geometry of an outpost, built when it is placed. */
class OutpostGeo(val base: Base, val mesh: MeshData, val colliders: List<Collider>, val fixtures: List<Fixture>)

object OutpostRules {
    const val MAX_OUTPOSTS = 2
    /** Outposts must be this far from every other base so each one covers new ground. */
    const val MIN_SPACING = 160f

    fun kitCost(built: Int): Map<Item, Int> = when (built) {
        0 -> mapOf(Item.SCRAP to 30, Item.COPPER to 20, Item.NODULE to 10, Item.BIOSAMPLE to 8)
        else -> mapOf(Item.SCRAP to 40, Item.COPPER to 28, Item.NODULE to 20, Item.CRYSTAL to 8, Item.PEARL to 3)
    }
}
