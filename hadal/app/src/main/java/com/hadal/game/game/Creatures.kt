package com.hadal.game.game

import com.hadal.game.engine.Rng
import com.hadal.game.engine.TWO_PI
import com.hadal.game.engine.Vec3
import com.hadal.game.engine.angleDiff
import com.hadal.game.engine.clamp
import com.hadal.game.world.SeaGen
import com.hadal.game.world.Zone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class Species(
    val title: String,
    val desc: String,
    val zones: Set<Zone>,
    val speed: Float,
    val radius: Float,
    val scanSeconds: Float,
    val school: Int = 0,
    val segments: Int = 0,
) {
    GLASSFIN("Glassfin", "Translucent schooling fish. They flash silver when they turn, all at once, like one animal.", setOf(Zone.SHELF, Zone.TWILIGHT), 4.5f, 0.25f, 1.2f, school = 18),
    MANTA("Veil Manta", "Gentle filter feeder with a six meter wingspan. Curious about the sub's lights.", setOf(Zone.SHELF, Zone.TWILIGHT), 3.2f, 2.5f, 2f),
    MOON_JELLY("Moon Jelly", "Drifting bell trailing stinging threads. Brushing one shorts out electrics.", setOf(Zone.TWILIGHT, Zone.MIDNIGHT, Zone.RIM), 0.6f, 1f, 1.5f),
    RIBBON_EEL("Ribbon Eel", "Ambush hunter that lives in wreck holes. Strikes fast, retreats faster.", setOf(Zone.TWILIGHT, Zone.MIDNIGHT), 13f, 0.5f, 2f, segments = 10),
    SONGWHALE("Songwhale", "Twenty meters of patience. Its song can be heard for kilometers and it seems to answer the sonar.", setOf(Zone.TWILIGHT, Zone.MIDNIGHT), 2.6f, 5f, 3.5f),
    ANGLER("Black Angler", "A lure of cold light and a mouth full of needles. Waits. Then does not.", setOf(Zone.MIDNIGHT, Zone.VENTS, Zone.RIM), 9f, 1.2f, 2.5f),
    ISOPOD("Giant Isopod", "Armored scavenger the size of a dog. Harmless, unless you are dead.", setOf(Zone.MIDNIGHT, Zone.RIM, Zone.VENTS), 0.7f, 0.8f, 1.5f),
    LANTERN_SQUID("Lantern Squid", "Small squid that blink in patterns. They go dark when something big is near.", setOf(Zone.MIDNIGHT, Zone.VENTS, Zone.RIM), 6f, 0.35f, 1.5f, school = 10),
    VENT_CRAB("Ember Crab", "White crab farming bacteria on its claws beside the smokers.", setOf(Zone.VENTS), 1f, 0.6f, 1.5f),
    GULPER("Gulper", "Mostly mouth. Hunts by sound and charges in a straight line. Hates bright light.", setOf(Zone.VENTS, Zone.RIM, Zone.HADAL), 15f, 2.6f, 3f),
    PALE("The Pale", "Something forty meters long that follows you in the dark. The crew called it the Pale.", emptySet(), 11f, 2.4f, 5f, segments = 22),
    WISP("Choir Wisp", "Lights that drift in the trench and gather around the Choir. They hum.", setOf(Zone.HADAL), 2f, 0.6f, 2f, school = 6);

    val hostile get() = this == RIBBON_EEL || this == ANGLER || this == GULPER || this == PALE || this == MOON_JELLY
}

enum class CState { IDLE, WANDER, FLEE, STALK, CHARGE, ATTACK, RETREAT, LURK, STUNNED, DISTRACTED }

class Creature(val species: Species, val id: Int) {
    val pos = Vec3()
    val vel = Vec3()
    var yaw = 0f
    var pitch = 0f
    var state = CState.WANDER
    var timer = 0f
    var cool = 0f
    var anim = 0f
    var size = 1f
    var schoolId = -1
    val home = Vec3()
    val target = Vec3()
    var seenByPlayer = 0f
    var glow = 1f
    /** Body segment trail for long bodies (positions, 3 floats each). */
    val trail: FloatArray? = if (species.segments > 0) FloatArray(species.segments * 3) else null
    var dead = false

    fun initTrail() {
        val t = trail ?: return
        for (i in 0 until species.segments) { t[i * 3] = pos.x - sin(yaw) * i * segLen(); t[i * 3 + 1] = pos.y; t[i * 3 + 2] = pos.z - cos(yaw) * i * segLen() }
    }

    fun segLen() = when (species) { Species.PALE -> 2.1f * size; Species.RIBBON_EEL -> 0.42f * size; else -> 1f }

    /** Drags the body segments behind the head so they follow its path. */
    fun updateTrail() {
        val t = trail ?: return
        t[0] = pos.x; t[1] = pos.y; t[2] = pos.z
        val l = segLen()
        for (i in 1 until species.segments) {
            val o = i * 3; val p = o - 3
            var dx = t[o] - t[p]; var dy = t[o + 1] - t[p + 1]; var dz = t[o + 2] - t[p + 2]
            val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            dx /= d; dy /= d; dz /= d
            t[o] = t[p] + dx * l; t[o + 1] = t[p + 1] + dy * l; t[o + 2] = t[p + 2] + dz * l
        }
    }
}

/** Shared school state: a moving center that fish orbit and follow. */
class School(val id: Int, val species: Species) {
    val center = Vec3()
    val goal = Vec3()
    var scatter = 0f
    var members = 0
}

/** Spawns, despawns and drives every creature. The Pale has its own director inside. */
class Ecology(private val g: GameState) {
    val list = ArrayList<Creature>()
    val schools = HashMap<Int, School>()
    private var nextId = 1
    private var spawnTimer = 0f
    private val rng = Rng(System.nanoTime())
    val tmp = Vec3()

    var pale: Creature? = null
    /** 0..1, how hard the Pale is hunting. Rises with noise, light and depth. */
    var paleThreat = 0f
    var paleCooldown = 120f
    var paleEncounters = 0

    fun clear() { list.clear(); schools.clear(); pale = null }

    private fun spawn(sp: Species, x: Float, y: Float, z: Float): Creature {
        val c = Creature(sp, nextId++)
        c.pos.set(x, y, z); c.home.set(x, y, z); c.target.set(x, y, z)
        c.yaw = rng.range(0f, TWO_PI)
        c.size = when (sp) {
            Species.SONGWHALE -> rng.range(0.9f, 1.15f)
            Species.PALE -> 1f
            Species.GLASSFIN, Species.LANTERN_SQUID -> rng.range(0.8f, 1.2f)
            else -> rng.range(0.8f, 1.25f)
        }
        c.anim = rng.range(0f, 10f)
        c.initTrail()
        list.add(c)
        return c
    }

    private fun floorAt(x: Float, z: Float) = SeaGen.height(x, z)

    private fun countOf(sp: Species) = list.count { it.species == sp }

    fun update(dt: Float) {
        val sub = g.sub
        val p = sub.pos
        // despawn far creatures
        list.removeAll { c ->
            val far = c.pos.dist(p) > (if (c.species == Species.SONGWHALE || c.species == Species.PALE) 260f else 170f)
            if (far && c === pale) pale = null
            far || c.dead
        }
        schools.values.removeAll { s -> list.none { it.schoolId == s.id } }
        spawnTimer -= dt
        if (spawnTimer <= 0f) { spawnTimer = 1.2f; populate() }
        updatePale(dt)
        for (s in schools.values) updateSchool(s, dt)
        for (c in list) {
            c.anim += dt * (0.6f + c.vel.length() * 0.5f)
            c.cool = max(0f, c.cool - dt)
            if (c.species != Species.PALE) think(c, dt)
            integrate(c, dt)
            c.updateTrail()
        }
    }

    private fun populate() {
        val p = g.sub.pos
        val zone = g.zone
        if (g.docked) return
        // pick a spawn point out of sight, 60-110 m away
        val a = rng.range(0f, TWO_PI)
        val d = rng.range(60f, 110f)
        val sx = p.x + sin(a) * d; val sz = p.z + cos(a) * d
        val fl = floorAt(sx, sz)
        val szone = SeaGen.zone(sx, sz, fl)
        val nearStation = sqrt(sx * sx + sz * sz) < 120f
        val candidates = Species.entries.filter { it.zones.contains(szone) && it != Species.PALE }
        if (candidates.isEmpty()) return
        val sp = candidates[rng.nextInt(candidates.size)]
        val cap = when (sp) {
            Species.GLASSFIN, Species.LANTERN_SQUID -> 3 * sp.school
            Species.WISP -> 2 * sp.school
            Species.SONGWHALE -> 1
            Species.MANTA -> 2
            Species.MOON_JELLY -> 9
            Species.ISOPOD, Species.VENT_CRAB -> 5
            Species.ANGLER -> if (g.story.chapter >= 3) 2 else 0
            Species.GULPER -> if (g.story.chapter >= 4) 1 else 0
            Species.RIBBON_EEL -> 2
            else -> 2
        }
        if (countOf(sp) >= cap) return
        if (sp.hostile && nearStation) return
        val y = when (sp) {
            Species.ISOPOD, Species.VENT_CRAB -> fl + 0.4f
            Species.SONGWHALE -> fl + rng.range(25f, 45f)
            Species.MOON_JELLY -> fl + rng.range(6f, 40f)
            else -> fl + rng.range(3f, 18f)
        }.coerceAtMost(-4f)
        if (sp.school > 0) {
            val s = School(nextId++, sp)
            s.center.set(sx, y, sz); s.goal.set(sx, y, sz)
            schools[s.id] = s
            for (i in 0 until sp.school) {
                val c = spawn(sp, sx + rng.range(-3f, 3f), y + rng.range(-2f, 2f), sz + rng.range(-3f, 3f))
                c.schoolId = s.id
                s.members++
            }
        } else {
            val c = spawn(sp, sx, y, sz)
            if (sp == Species.RIBBON_EEL || sp == Species.ANGLER) c.state = CState.LURK
        }
        if (zone == Zone.HADAL && countOf(Species.WISP) < 12) {
            val s = School(nextId++, Species.WISP)
            val wx = p.x + rng.range(-40f, 40f); val wz = p.z + rng.range(-40f, 40f)
            val wy = floorAt(wx, wz) + 10f
            s.center.set(wx, wy, wz); s.goal.set(wx, wy, wz); schools[s.id] = s
            for (i in 0 until 6) { val c = spawn(Species.WISP, wx + rng.range(-4f, 4f), wy, wz + rng.range(-4f, 4f)); c.schoolId = s.id }
        }
    }

    private fun updateSchool(s: School, dt: Float) {
        val p = g.sub.pos
        s.scatter = max(0f, s.scatter - dt * 0.3f)
        val d = s.center.dist(p)
        if (d < 9f + g.sub.speed * 0.8f && s.species != Species.WISP) s.scatter = 1f
        if (s.center.dist(s.goal) < 4f || rng.chance(dt * 0.05f)) {
            val a = rng.range(0f, TWO_PI); val r = rng.range(10f, 35f)
            val gx = s.center.x + sin(a) * r; val gz = s.center.z + cos(a) * r
            s.goal.set(gx, (floorAt(gx, gz) + rng.range(4f, 16f)).coerceAtMost(-4f), gz)
        }
        // wisps drift toward the player and the Choir
        if (s.species == Species.WISP && d > 18f) s.goal.set(p.x + sin(g.time * 0.2f) * 12f, p.y + 3f, p.z + cos(g.time * 0.2f) * 12f)
        var dx = s.goal.x - s.center.x; var dy = s.goal.y - s.center.y; var dz = s.goal.z - s.center.z
        val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01f)
        val sp = s.species.speed * 0.35f
        dx = dx / l * sp; dy = dy / l * sp; dz = dz / l * sp
        if (s.scatter > 0.3f && s.species != Species.WISP) {
            // dart away from the sub
            val ax = s.center.x - p.x; val ay = s.center.y - p.y; val az = s.center.z - p.z
            val al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(0.01f)
            dx += ax / al * s.species.speed * s.scatter; dy += ay / al * s.species.speed * 0.3f * s.scatter; dz += az / al * s.species.speed * s.scatter
        }
        s.center.add(dx * dt, dy * dt, dz * dt)
        val fl = floorAt(s.center.x, s.center.z)
        if (s.center.y < fl + 2.5f) s.center.y = fl + 2.5f
        if (s.center.y > -3f) s.center.y = -3f
    }

    private fun steerTo(c: Creature, tx: Float, ty: Float, tz: Float, speed: Float, dt: Float, agility: Float = 1.5f) {
        var dx = tx - c.pos.x; var dy = ty - c.pos.y; var dz = tz - c.pos.z
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 0.01f) return
        dx /= l; dy /= l; dz /= l
        val k = min(1f, agility * dt)
        c.vel.x += (dx * speed - c.vel.x) * k
        c.vel.y += (dy * speed - c.vel.y) * k
        c.vel.z += (dz * speed - c.vel.z) * k
    }

    private fun think(c: Creature, dt: Float) {
        val sub = g.sub
        val p = sub.pos
        val sp = c.species
        val dist = c.pos.dist(p)
        c.timer -= dt
        // flares distract predators
        val flare = g.nearestFlare(c.pos, 40f)
        if (sp.hostile && flare != null && c.state != CState.STUNNED && sp != Species.MOON_JELLY) {
            c.state = CState.DISTRACTED
            steerTo(c, flare.pos.x, flare.pos.y, flare.pos.z, sp.speed * 0.6f, dt)
            return
        } else if (c.state == CState.DISTRACTED) c.state = CState.WANDER

        if (c.schoolId >= 0) {
            val s = schools[c.schoolId] ?: return
            // orbit the school center with a personal offset
            val ph = c.id * 1.7f + g.time * (0.6f + (c.id % 5) * 0.08f)
            val r = 2.2f + (c.id % 4) * 0.7f
            val tx = s.center.x + sin(ph) * r; val tz = s.center.z + cos(ph) * r
            val ty = s.center.y + sin(ph * 1.3f + c.id) * 1.2f
            steerTo(c, tx, ty, tz, sp.speed * (0.6f + s.scatter * 0.9f), dt, 2.5f + s.scatter * 3f)
            if (sp == Species.LANTERN_SQUID) c.glow = if ((pale?.pos?.dist(c.pos) ?: 999f) < 60f) 0.05f else 0.6f + 0.4f * sin(g.time * 3f + c.id)
            return
        }
        when (sp) {
            Species.MANTA, Species.SONGWHALE -> {
                if (c.timer <= 0f || c.pos.dist(c.target) < 6f) {
                    c.timer = rng.range(8f, 20f)
                    val a = rng.range(0f, TWO_PI); val r = if (sp == Species.SONGWHALE) rng.range(40f, 80f) else rng.range(15f, 40f)
                    val tx = p.x + sin(a) * r; val tz = p.z + cos(a) * r
                    c.target.set(tx, (floorAt(tx, tz) + (if (sp == Species.SONGWHALE) 30f else 8f)).coerceAtMost(-6f), tz)
                }
                steerTo(c, c.target.x, c.target.y, c.target.z, sp.speed, dt, 0.4f)
                if (sp == Species.SONGWHALE && c.cool <= 0f) {
                    c.cool = rng.range(14f, 30f)
                    g.sound.playAt(Sfx.WHALE, c.pos.x, c.pos.y, c.pos.z, 1f, rng.range(0.85f, 1.1f))
                }
            }
            Species.MOON_JELLY -> {
                c.vel.x += sin(g.time * 0.1f + c.id) * 0.02f * dt
                c.vel.y = sin(c.anim * 1.4f) * 0.35f
                c.vel.x *= 0.98f; c.vel.z *= 0.98f
                if (dist < 2.4f * c.size && c.cool <= 0f) {
                    c.cool = 2.5f
                    g.damageSub(4f, DamageKind.STING)
                    sub.power = max(0f, sub.power - 8f)
                    g.sound.playAt(Sfx.ZAP, c.pos.x, c.pos.y, c.pos.z, 1f)
                }
            }
            Species.RIBBON_EEL -> eel(c, dist, dt)
            Species.ANGLER -> angler(c, dist, dt)
            Species.GULPER -> gulper(c, dist, dt)
            Species.ISOPOD, Species.VENT_CRAB -> {
                if (c.timer <= 0f) { c.timer = rng.range(3f, 9f); val a = rng.range(0f, TWO_PI); c.target.set(c.pos.x + sin(a) * 6f, 0f, c.pos.z + cos(a) * 6f) }
                val fl = floorAt(c.pos.x, c.pos.z)
                steerTo(c, c.target.x, fl + 0.35f, c.target.z, sp.speed, dt, 1f)
                c.pos.y = fl + 0.35f * c.size
                c.vel.y = 0f
            }
            else -> {}
        }
    }

    private fun eel(c: Creature, dist: Float, dt: Float) {
        val p = g.sub.pos
        when (c.state) {
            CState.LURK -> {
                c.vel.scale(0.9f)
                c.pos.y += sin(c.anim) * 0.002f
                val detect = 15f * g.sub.detectability()
                if (dist < detect && c.cool <= 0f) { c.state = CState.CHARGE; c.timer = 2f; g.sound.playAt(Sfx.HISS, c.pos.x, c.pos.y, c.pos.z, 1f) }
            }
            CState.CHARGE -> {
                steerTo(c, p.x, p.y, p.z, c.species.speed, dt, 4f)
                if (dist < 2.2f) {
                    g.damageSub(11f, DamageKind.BITE)
                    g.sound.playAt(Sfx.BITE, c.pos.x, c.pos.y, c.pos.z, 1f)
                    c.state = CState.RETREAT; c.timer = 4f; c.cool = 8f
                }
                if (c.timer <= 0f) { c.state = CState.RETREAT; c.timer = 4f }
            }
            CState.RETREAT, CState.STUNNED -> {
                steerTo(c, c.home.x, c.home.y, c.home.z, c.species.speed * 0.7f, dt, 3f)
                if (c.pos.dist(c.home) < 1.5f || c.timer < -6f) { c.state = CState.LURK; c.cool = 6f }
            }
            else -> c.state = CState.LURK
        }
    }

    private fun angler(c: Creature, dist: Float, dt: Float) {
        val p = g.sub.pos
        when (c.state) {
            CState.LURK, CState.WANDER, CState.IDLE -> {
                c.vel.scale(0.95f)
                c.vel.y += sin(c.anim * 0.7f) * 0.02f
                // the lure draws you in; it only strikes up close
                if (dist < 22f * g.sub.detectability() && c.cool <= 0f) { c.state = CState.STALK; c.timer = 10f }
            }
            CState.STALK -> {
                steerTo(c, p.x, p.y, p.z, 1.6f, dt, 1f)
                if (dist < 7f) { c.state = CState.CHARGE; c.timer = 1.4f; g.sound.playAt(Sfx.SNAP, c.pos.x, c.pos.y, c.pos.z, 1f) }
                if (c.timer <= 0f || dist > 35f) c.state = CState.LURK
            }
            CState.CHARGE -> {
                steerTo(c, p.x, p.y, p.z, c.species.speed, dt, 5f)
                if (dist < 2.6f) {
                    g.damageSub(15f, DamageKind.BITE)
                    g.sound.playAt(Sfx.BITE, c.pos.x, c.pos.y, c.pos.z, 1f, 0.8f)
                    c.state = CState.RETREAT; c.timer = 5f; c.cool = 10f
                }
                if (c.timer <= 0f) { c.state = CState.RETREAT; c.timer = 3f }
            }
            CState.RETREAT, CState.STUNNED -> {
                val ax = c.pos.x - p.x; val az = c.pos.z - p.z; val l = sqrt(ax * ax + az * az).coerceAtLeast(0.1f)
                steerTo(c, c.pos.x + ax / l * 10f, c.pos.y, c.pos.z + az / l * 10f, 4f, dt, 2f)
                if (c.timer <= 0f) c.state = CState.LURK
            }
            else -> c.state = CState.LURK
        }
        c.glow = if (c.state == CState.CHARGE) 0.3f else 0.8f + 0.2f * sin(g.time * 2.3f + c.id)
    }

    private fun gulper(c: Creature, dist: Float, dt: Float) {
        val sub = g.sub
        val p = sub.pos
        // light in its face drives it off
        val lit = sub.lampOn && sub.inLampBeam(c.pos, 1.2f)
        if (lit && dist < sub.lampRange) c.seenByPlayer += dt else c.seenByPlayer = max(0f, c.seenByPlayer - dt)
        when (c.state) {
            CState.CHARGE -> {
                // straight line charge: steering is weak
                steerTo(c, c.target.x, c.target.y, c.target.z, c.species.speed, dt, 0.8f)
                if (dist < 3.2f * c.size) {
                    g.damageSub(24f, DamageKind.BITE)
                    g.sound.playAt(Sfx.ROAR, c.pos.x, c.pos.y, c.pos.z, 1f, 1.3f)
                    c.state = CState.RETREAT; c.timer = 6f; c.cool = 14f
                }
                if (c.seenByPlayer > 1.1f) { c.state = CState.RETREAT; c.timer = 6f; c.cool = 10f; g.sound.playAt(Sfx.ROAR, c.pos.x, c.pos.y, c.pos.z, 0.8f, 1.6f) }
                if (c.pos.dist(c.target) < 3f || c.timer <= 0f) { c.state = CState.WANDER; c.cool = 6f }
            }
            CState.RETREAT, CState.STUNNED -> {
                val ax = c.pos.x - p.x; val az = c.pos.z - p.z; val l = sqrt(ax * ax + az * az).coerceAtLeast(0.1f)
                steerTo(c, c.pos.x + ax / l * 20f, c.pos.y + 3f, c.pos.z + az / l * 20f, c.species.speed * 0.6f, dt, 1.5f)
                if (c.timer <= 0f) c.state = CState.WANDER
            }
            else -> {
                if (c.timer <= 0f || c.pos.dist(c.target) < 5f) {
                    c.timer = rng.range(6f, 12f)
                    val a = rng.range(0f, TWO_PI)
                    c.target.set(p.x + sin(a) * 50f, p.y + rng.range(-8f, 8f), p.z + cos(a) * 50f)
                }
                steerTo(c, c.target.x, c.target.y, c.target.z, 3f, dt, 0.8f)
                val hear = 30f + 60f * sub.noise
                if (dist < hear && c.cool <= 0f && rng.chance(dt * 0.6f)) {
                    c.state = CState.CHARGE; c.timer = 5f
                    // aim where the sub will be
                    c.target.set(p.x + sub.vel.x * 1.2f, p.y + sub.vel.y * 1.2f, p.z + sub.vel.z * 1.2f)
                    g.sound.playAt(Sfx.ROAR, c.pos.x, c.pos.y, c.pos.z, 1f, 1.4f)
                }
            }
        }
    }

    // ------------------------------------------------------------------ the Pale

    private fun paleAllowed(): Boolean {
        val p = g.sub.pos
        return g.story.chapter >= 3 && g.depth > 300f && sqrt(p.x * p.x + p.z * p.z) > 280f && !g.docked && !g.ending
    }

    private fun updatePale(dt: Float) {
        val sub = g.sub
        val p = sub.pos
        // threat builds from noise, lights and depth; fades in silence
        val depthK = clamp((g.depth - 250f) / 900f, 0f, 1f)
        val gain = (sub.noise * 0.05f + (if (sub.lampOn) 0.012f else 0f) + depthK * 0.006f) * (if (paleAllowed()) 1f else 0f)
        paleThreat = clamp(paleThreat + gain * dt - 0.012f * dt * (1f - sub.noise), 0f, 1f)
        paleCooldown -= dt
        var c = pale
        if (c == null) {
            if (paleAllowed() && (paleCooldown <= 0f && paleThreat > 0.25f || g.story.forcePale)) {
                g.story.forcePale = false
                // appear behind the sub, far beyond the lamp
                val a = sub.yaw + Math.PI.toFloat() + rng.range(-0.8f, 0.8f)
                val x = p.x + sin(a) * 75f; val z = p.z + cos(a) * 75f
                val y = max(floorAt(x, z) + 10f, p.y - 10f).coerceAtMost(-6f)
                c = spawn(Species.PALE, x, y, z)
                c.state = CState.STALK; c.timer = rng.range(30f, 50f)
                pale = c
                paleEncounters++
                g.sound.playAt(Sfx.PALE_CALL, x, y, z, 1f)
            }
            return
        }
        c.timer -= dt
        c.cool = max(0f, c.cool - dt)
        val dist = c.pos.dist(p)
        if (!paleAllowed() && c.state != CState.RETREAT) { c.state = CState.RETREAT; c.timer = 12f }
        val flare = g.nearestFlare(c.pos, 70f)
        if (flare != null && c.state != CState.RETREAT) {
            steerTo(c, flare.pos.x, flare.pos.y, flare.pos.z, 6f, dt, 1f)
            c.state = CState.DISTRACTED
            return
        }
        when (c.state) {
            CState.STALK, CState.DISTRACTED -> {
                c.state = CState.STALK
                // circle just beyond the edge of the lamp
                val orbit = 48f - paleThreat * 22f
                val ang = atan2(c.pos.x - p.x, c.pos.z - p.z) + dt * 0.12f
                val tx = p.x + sin(ang) * orbit; val tz = p.z + cos(ang) * orbit
                val ty = max(floorAt(tx, tz) + 5f, p.y + sin(g.time * 0.15f) * 8f).coerceAtMost(-6f)
                steerTo(c, tx, ty, tz, 7f, dt, 0.8f)
                if (c.cool <= 0f) { c.cool = rng.range(9f, 18f); g.sound.playAt(if (rng.chance(0.5f)) Sfx.PALE_CALL else Sfx.PALE_CLICK, c.pos.x, c.pos.y, c.pos.z, 1f, rng.range(0.85f, 1.05f)) }
                if (paleThreat > 0.7f && rng.chance(dt * 0.25f)) { c.state = CState.CHARGE; c.timer = 8f; g.sound.playAt(Sfx.ROAR, c.pos.x, c.pos.y, c.pos.z, 1.2f, 0.6f) }
                if (c.timer <= 0f) { c.state = CState.RETREAT; c.timer = 15f }
            }
            CState.CHARGE -> {
                steerTo(c, p.x, p.y, p.z, c.species.speed, dt, 1.3f)
                if (dist < 5f) {
                    c.state = CState.ATTACK; c.timer = 2.2f
                    g.paleGrab()
                }
                if (c.timer <= 0f) { c.state = CState.STALK; c.timer = 20f }
            }
            CState.ATTACK -> {
                // coil around the sub and shake it
                val ang = g.time * 2.4f
                steerTo(c, p.x + sin(ang) * 4f, p.y + cos(ang * 0.7f) * 2f, p.z + cos(ang) * 4f, 12f, dt, 4f)
                if (c.timer <= 0f) {
                    g.damageSub(28f, DamageKind.PALE)
                    g.sound.play(Sfx.HULL_HIT, 1f, 0.7f)
                    c.state = CState.RETREAT; c.timer = 18f
                    paleThreat = 0.1f
                }
            }
            CState.STUNNED, CState.RETREAT -> {
                val ax = c.pos.x - p.x; val az = c.pos.z - p.z; val l = sqrt(ax * ax + az * az).coerceAtLeast(0.1f)
                steerTo(c, c.pos.x + ax / l * 40f, c.pos.y - 6f, c.pos.z + az / l * 40f, 13f, dt, 1f)
                if (c.timer <= 0f || dist > 150f) {
                    list.remove(c); pale = null
                    paleCooldown = rng.range(100f, 220f) * (1.2f - depthK * 0.5f)
                }
            }
            else -> c.state = CState.STALK
        }
    }

    /** Shock pulse: stuns everything hostile nearby and sends it away. */
    fun pulse(radius: Float) {
        val p = g.sub.pos
        for (c in list) {
            if (!c.species.hostile) {
                if (c.pos.dist(p) < radius) schools[c.schoolId]?.scatter = 1f
                continue
            }
            if (c.pos.dist(p) < radius) {
                c.state = if (c === pale) CState.RETREAT else CState.STUNNED
                c.timer = if (c === pale) 20f else 5f
                c.cool = 12f
                if (c === pale) paleThreat = 0f
            }
        }
    }

    private fun integrate(c: Creature, dt: Float) {
        c.pos.addScaled(c.vel, dt)
        // stay off the floor and below the surface
        val fl = floorAt(c.pos.x, c.pos.z)
        val minH = when (c.species) { Species.ISOPOD, Species.VENT_CRAB -> 0.3f; Species.PALE -> 4f; Species.SONGWHALE -> 8f; else -> 1.2f }
        if (c.pos.y < fl + minH) { c.pos.y = fl + minH; if (c.vel.y < 0f) c.vel.y *= -0.3f }
        if (c.pos.y > -2.5f) { c.pos.y = -2.5f; c.vel.y = min(0f, c.vel.y) }
        val hs = c.vel.lengthXZ()
        if (hs > 0.05f) {
            val ty = atan2(c.vel.x, c.vel.z)
            c.yaw += angleDiff(c.yaw, ty) * min(1f, dt * 4f)
        }
        val tp = atan2(c.vel.y, max(0.1f, hs)).coerceIn(-0.9f, 0.9f)
        c.pitch += (tp - c.pitch) * min(1f, dt * 3f)
        // sub collision push
        val p = g.sub.pos
        val r = c.species.radius * c.size + 1.4f
        val dx = c.pos.x - p.x; val dy = c.pos.y - p.y; val dz = c.pos.z - p.z
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 < r * r && d2 > 1e-4f && c.species != Species.PALE) {
            val d = sqrt(d2)
            val push = (r - d)
            c.pos.add(dx / d * push * 0.7f, dy / d * push * 0.7f, dz / d * push * 0.7f)
            g.sub.pos.add(-dx / d * push * 0.3f, -dy / d * push * 0.3f, -dz / d * push * 0.3f)
        }
    }

    /** Creature in front of the sub that can be scanned, or null. */
    fun scanTarget(range: Float): Creature? {
        val s = g.sub
        var best: Creature? = null
        var bestD = range
        for (c in list) {
            val d = c.pos.dist(s.pos)
            if (d > bestD + c.species.radius * c.size) continue
            if (!s.facing(c.pos, 0.82f)) continue
            best = c; bestD = d
        }
        return best
    }
}
