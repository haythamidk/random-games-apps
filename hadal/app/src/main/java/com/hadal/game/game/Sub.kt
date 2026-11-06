package com.hadal.game.game

import com.hadal.game.engine.Vec3
import com.hadal.game.engine.clamp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** The submersible "Wren": state, derived stats and orientation helpers. Physics live in GameState. */
class Sub {
    val pos = Vec3()
    val vel = Vec3()
    var yaw = 0f
    /** Look pitch in radians, positive looks up. */
    var pitch = 0f
    /** Visual body orientation (smoothed toward the look direction). */
    var bodyYaw = 0f
    var bodyPitch = 0f
    var bodyRoll = 0f

    var hull = 100f
    var power = 100f
    var oxygen = 240f
    var lampOn = true
    var noise = 0f
    var speed = 0f
    var boosting = false
    var flares = 0
    var repairKits = 0
    var shake = 0f
    var flicker = 0f

    // stats from upgrades
    var maxHull = 100f
    var maxPower = 100f
    var maxOxygen = 240f
    var rating = 160f
    var thrust = 6.2f
    var lampRange = 34f
    var lampCos = 0.82f
    var sonarRange = 90f
    var cargoCap = 16
    var silent = false
    var stealth = 0
    var scanRange = 9f
    var scanSpeed = 1f

    fun applyUpgrades(levels: IntArray) {
        fun lvl(u: Upgrade) = levels[u.ordinal]
        rating = Costs.hullRating[lvl(Upgrade.HULL)].toFloat()
        maxHull = 100f + lvl(Upgrade.HULL) * 15f
        maxPower = Costs.batteryCap[lvl(Upgrade.BATTERY)]
        maxOxygen = Costs.oxygenCap[lvl(Upgrade.OXYGEN)]
        thrust = Costs.thrust[lvl(Upgrade.THRUSTERS)]
        lampRange = Costs.lampRange[lvl(Upgrade.LAMP)]
        lampCos = 0.82f - lvl(Upgrade.LAMP) * 0.035f
        sonarRange = Costs.sonarRange[lvl(Upgrade.SONAR)]
        cargoCap = Costs.cargoCap[lvl(Upgrade.CARGO)] + 8 * lvl(Upgrade.HOLD)
        stealth = lvl(Upgrade.STEALTH)
        silent = lvl(Upgrade.SILENT) > 0
        scanRange = 9f + lvl(Upgrade.SCANNER) * 4f
        scanSpeed = 1f + lvl(Upgrade.SCANNER) * 0.5f
        hull = hull.coerceAtMost(maxHull); power = power.coerceAtMost(maxPower); oxygen = oxygen.coerceAtMost(maxOxygen)
    }

    fun forward(out: Vec3): Vec3 = out.set(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))
    fun right(out: Vec3): Vec3 = out.set(-cos(yaw), 0f, sin(yaw))

    /** Nose position where the lamps and the arm sit. */
    fun nose(out: Vec3): Vec3 {
        out.set(pos.x + sin(bodyYaw) * cos(bodyPitch) * 1.9f, pos.y + sin(bodyPitch) * 1.9f - 0.2f, pos.z + cos(bodyYaw) * cos(bodyPitch) * 1.9f)
        return out
    }

    private val tmp = Vec3()

    /** True if [p] is in front of the sub within the cone given by cosine [minCos]. */
    fun facing(p: Vec3, minCos: Float): Boolean {
        forward(tmp)
        val dx = p.x - pos.x; val dy = p.y - pos.y; val dz = p.z - pos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-3f)
        return (dx * tmp.x + dy * tmp.y + dz * tmp.z) / d >= minCos
    }

    fun inLampBeam(p: Vec3, slack: Float = 1f): Boolean {
        if (!lampOn) return false
        val dx = p.x - pos.x; val dy = p.y - pos.y; val dz = p.z - pos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d > lampRange * slack) return false
        return facing(p, lampCos - (slack - 1f) * 0.1f)
    }

    /** Multiplier on how far predators notice the sub: light and noise make it louder in the dark. */
    fun detectability(): Float = clamp(0.55f + (if (lampOn) 0.35f else 0f) + noise * 0.6f, 0.4f, 1.6f) * (1f - 0.1f * stealth)

    fun addNoise(n: Float) { noise = max(noise, n) }
}
