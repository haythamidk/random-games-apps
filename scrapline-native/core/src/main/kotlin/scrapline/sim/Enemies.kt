package scrapline.sim

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

const val MAX_STRAYS = 24
const val STRAY_HP = 100
private const val STRAY_SPEED = 3.2f
private const val STRAY_HOVER = 1.25f
private const val STRAY_REACH = 1.7f
private const val STRAY_DAMAGE = 50
private const val STRAY_ATTACK_TICKS = 20
private const val SPAWN_EVERY = 100
private const val SPAWN_RING = 42f

const val TURRET_RANGE = 12f
private const val TURRET_DAMAGE = 25
private const val TURRET_PERIOD = 6

/** Hostile drones. Fixed pool, struct-of-arrays; `alive[i] == false` slots are free. */
class Strays {
    val alive = BooleanArray(MAX_STRAYS)
    val x = FloatArray(MAX_STRAYS)
    val y = FloatArray(MAX_STRAYS)
    val z = FloatArray(MAX_STRAYS)
    val hp = IntArray(MAX_STRAYS)
    val cooldown = IntArray(MAX_STRAYS)
    /** How many are alive right now. */
    var count = 0
    /** How many have been spawned during the current night. */
    var spawned = 0
}

/** How many strays may be alive at once on a given night (0-based). */
fun nightAtOnce(night: Int): Int = min(3 + night, 10)
/** How many strays a given night spawns in total. */
fun nightTotal(night: Int): Int = min(8 + night * 4, 40)

fun spawnStray(sim: Sim, x: Float, z: Float): Int {
    val s = sim.strays
    for (i in 0 until MAX_STRAYS) {
        if (s.alive[i]) continue
        s.alive[i] = true
        s.x[i] = x.coerceIn(4f, WORLD_SIZE - 4f)
        s.z[i] = z.coerceIn(4f, WORLD_SIZE - 4f)
        s.y[i] = sim.world.heightAt(s.x[i], s.z[i]) + STRAY_HOVER
        s.hp[i] = STRAY_HP
        s.cooldown[i] = STRAY_ATTACK_TICKS
        s.count++
        return i
    }
    return -1
}

/** Applies damage. Returns true if the stray was destroyed. */
fun damageStray(sim: Sim, i: Int, amount: Int): Boolean {
    val s = sim.strays
    if (!s.alive[i]) return false
    s.hp[i] -= amount
    if (s.hp[i] > 0) return false
    s.alive[i] = false
    s.count--
    return true
}

/** Index of the nearest living stray within `range` of a point, or -1. */
fun nearestStray(sim: Sim, x: Float, z: Float, range: Float): Int {
    val s = sim.strays
    var best = -1
    var bestD = range * range
    for (i in 0 until MAX_STRAYS) {
        if (!s.alive[i]) continue
        val dx = s.x[i] - x
        val dz = s.z[i] - z
        val d = dx * dx + dz * dz
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

/** One tick of the night threat: spawning, chasing, biting, and turrets shooting. */
fun tickStrays(sim: Sim, night: Boolean, nightIndex: Int) {
    val s = sim.strays
    val p = sim.player

    if (night) {
        if (sim.tick % SPAWN_EVERY == 0 && s.count < nightAtOnce(nightIndex) && s.spawned < nightTotal(nightIndex)) {
            val a = sim.rng.range(0f, Math.PI.toFloat() * 2f)
            if (spawnStray(sim, p.x + cos(a) * SPAWN_RING, p.z + sin(a) * SPAWN_RING) >= 0) s.spawned++
        }
    } else {
        // Daylight shuts them down.
        if (s.count > 0) for (i in 0 until MAX_STRAYS) {
            if (!s.alive[i]) continue
            s.alive[i] = false
            s.count--
            sim.strayDown(i, false)
        }
        s.spawned = 0
    }

    for (i in 0 until MAX_STRAYS) {
        if (!s.alive[i]) continue
        val dx = p.x - s.x[i]
        val dz = p.z - s.z[i]
        val d = hypot(dx, dz)
        if (d > STRAY_REACH * 0.8f) {
            val step = STRAY_SPEED / TICK_HZ
            s.x[i] += dx / d * step
            s.z[i] += dz / d * step
        }
        s.y[i] = sim.world.heightAt(s.x[i], s.z[i]) + STRAY_HOVER
        if (s.cooldown[i] > 0) s.cooldown[i]--
        if (d < STRAY_REACH && s.cooldown[i] == 0 && !sim.dead) {
            s.cooldown[i] = STRAY_ATTACK_TICKS
            sim.vitals.health = max(0, sim.vitals.health - STRAY_DAMAGE)
            sim.emit(Ev.BITE)
        }
    }

    // Turrets fire only while their `fire` input is high.
    val es = sim.entities
    for (id in 1 until es.high) {
        if (es.kind[id] != EntityKind.TURRET) continue
        var firing = 0
        if (es.enabled[id] != 0 && s.count > 0) {
            val t = nearestStray(sim, es.cx[id] + 0.5f, es.cz[id] + 0.5f, TURRET_RANGE)
            if (t >= 0) {
                firing = 1
                if (++es.progress[id] >= TURRET_PERIOD) {
                    es.progress[id] = 0
                    sim.emit(Ev.SHOT, id, t)
                    if (damageStray(sim, t, TURRET_DAMAGE)) sim.strayDown(t, true)
                }
            }
        }
        es.active[id] = firing
    }
}
