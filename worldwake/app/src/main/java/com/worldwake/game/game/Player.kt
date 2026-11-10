package com.worldwake.game.game

import com.worldwake.game.engine.Vec3

class Player {
    val pos = Vec3()
    val vel = Vec3()
    var yaw = 0f
    var pitch = 0f
    var onGround = false
    val eyeHeight = 1.62f
    var headBob = 0f
    var bobAmount = 0f

    var o2 = 150f
    var water = 100f
    var food = 100f
    var health = 100f
    var dead = false

    val inv = Inventory(24)
    val upgrades = BooleanArray(Item.entries.size)

    var jetFuel = 1f
    var jetting = false
    var underwater = false
    var inWater = false
    /** True while inside a pod or next to the lander: free air. */
    var sheltered = false
    var indoors = false
    var sprinting = false
    var stepTimer = 0f
    var airTime = 0f

    fun has(i: Item) = upgrades[i.ordinal]

    val o2Max: Float get() = when {
        has(Item.TANK_4) -> 700f
        has(Item.TANK_3) -> 420f
        has(Item.TANK_2) -> 260f
        else -> 150f
    }

    val packSize: Int get() = when {
        has(Item.PACK_4) -> 100
        has(Item.PACK_3) -> 64
        has(Item.PACK_2) -> 40
        else -> 24
    }

    fun reset() {
        vel.set(0f, 0f, 0f)
        o2 = 150f; water = 100f; food = 100f; health = 100f; dead = false
        inv.clear(); inv.capacity = 24
        upgrades.fill(false)
        jetFuel = 1f; jetting = false; underwater = false; sheltered = false; indoors = false
        pitch = 0f
    }
}
