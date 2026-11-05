package com.rustbloom.game.game

import com.rustbloom.game.engine.Rng
import com.rustbloom.game.engine.Vec3
import com.rustbloom.game.engine.angleDiff
import com.rustbloom.game.engine.clamp
import com.rustbloom.game.engine.TWO_PI
import com.rustbloom.game.world.WorldGen
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class Temperament { PASSIVE, SKITTISH, DEFENSIVE, HOSTILE, BOSS }

enum class CreatureType(
    val displayName: String,
    val maxHp: Float,
    val walkSpeed: Float,
    val runSpeed: Float,
    val damage: Float,
    val attackRange: Float,
    val perception: Float,
    val temperament: Temperament,
    val machine: Boolean,
    val radius: Float,
    val height: Float,
    val xp: Int,
) {
    GRAZEHORN("Grazehorn", 40f, 1.8f, 9f, 0f, 0f, 22f, Temperament.PASSIVE, false, 0.7f, 1.5f, 12),
    TUSKER("Tusker", 55f, 1.6f, 7.5f, 13f, 1.9f, 12f, Temperament.DEFENSIVE, false, 0.75f, 1.0f, 16),
    STRIDER("Strider", 95f, 2.2f, 10f, 14f, 2.4f, 26f, Temperament.SKITTISH, true, 0.9f, 2.2f, 30),
    SCUTTLER("Scuttler", 38f, 2.4f, 7f, 8f, 1.6f, 18f, Temperament.HOSTILE, true, 0.6f, 0.7f, 18),
    PROWLER("Prowler", 170f, 2.6f, 9.5f, 20f, 2.4f, 28f, Temperament.HOSTILE, true, 1.0f, 1.4f, 60),
    WARDEN("Warden", 360f, 1.8f, 4.2f, 26f, 3.6f, 40f, Temperament.HOSTILE, true, 1.4f, 4.2f, 140),
    COLOSSUS("The Colossus", 3200f, 1.5f, 2.6f, 34f, 9f, 90f, Temperament.BOSS, true, 4.5f, 12f, 1500),
}

enum class CState { IDLE, WANDER, ALERT, FLEE, CHASE, WINDUP, RECOVER, DEAD }

enum class Variant(val hpMul: Float, val dmgMul: Float, val tint: Int) {
    NORMAL(1f, 1f, 0),
    FROST(1.35f, 1.2f, 0x9fd8ff),
    DUST(1.2f, 1.15f, 0xe0a060),
    FEN(1.15f, 1.1f, 0x80c090),
    CORRUPT(1.6f, 1.4f, 0xff5a6a),
}

class Creature(val id: Int, val type: CreatureType, x: Float, z: Float, val variant: Variant = Variant.NORMAL) {
    val pos = Vec3(x, WorldGen.height(x, z), z)
    var yaw = 0f
    var maxHp = type.maxHp * variant.hpMul
    var hp = maxHp
    var state = CState.IDLE
    var stateTime = 0f
    var targetX = x
    var targetZ = z
    val homeX = x
    val homeZ = z
    var speed = 0f
    var walkPhase = 0f
    var attackCd = 0f
    var rangedCd = 3f
    var hitFlash = 0f
    var burn = 0f
    var stun = 0f
    var deadTime = 0f
    var looted = false
    var aggroTime = 0f
    var lastSeen = 0f
    var headYaw = 0f
    var guardPoi = -1
    var encounter = false
    var summonStage = 0
    var stompWarn = 0f
    var removed = false
    val rng = Rng(id.toLong() * 7919 + 17)

    val alive get() = state != CState.DEAD
    val hostileNow get() = type.temperament == Temperament.HOSTILE || type.temperament == Temperament.BOSS || aggroTime > 0f

    /** Weak spot in world space: machines have a glowing core on their back/chest. */
    fun weakSpot(out: Vec3): Vec3 {
        val (fx, fy) = when (type) {
            CreatureType.STRIDER -> 0f to 1.9f
            CreatureType.SCUTTLER -> -0.2f to 0.75f
            CreatureType.PROWLER -> -0.4f to 1.35f
            CreatureType.WARDEN -> 0.5f to 3.4f
            CreatureType.COLOSSUS -> 2.6f to 8.2f
            else -> 0.6f to type.height * 0.8f
        }
        return out.set(pos.x + sin(yaw) * fx, pos.y + fy, pos.z + cos(yaw) * fx)
    }

    fun centerY(): Float = pos.y + type.height * 0.55f
}

/** Behaviour callbacks the AI needs from the game. */
interface CreatureHost {
    val playerPos: Vec3
    val playerStealth: Float
    val isNight: Boolean
    fun creatureMelee(c: Creature, damage: Float)
    fun creatureFireBolt(c: Creature, fromX: Float, fromY: Float, fromZ: Float, dirX: Float, dirY: Float, dirZ: Float, damage: Float)
    fun creatureStomp(c: Creature, radius: Float, damage: Float)
    fun creatureSummon(c: Creature, count: Int)
    fun creatureAlerted(c: Creature)
    fun nearFire(x: Float, z: Float): Boolean
}

object CreatureAI {

    fun update(c: Creature, dt: Float, host: CreatureHost) {
        if (c.hitFlash > 0) c.hitFlash = max(0f, c.hitFlash - dt * 4f)
        if (c.state == CState.DEAD) { c.deadTime += dt; return }
        c.stateTime += dt
        if (c.attackCd > 0) c.attackCd -= dt
        if (c.rangedCd > 0) c.rangedCd -= dt
        if (c.aggroTime > 0) c.aggroTime -= dt
        if (c.burn > 0) {
            c.burn -= dt
            c.hp -= 5f * dt
            if (c.hp <= 0f) { kill(c); return }
        }
        if (c.stun > 0) {
            c.stun -= dt
            c.speed = 0f
            return
        }
        val p = host.playerPos
        val dx = p.x - c.pos.x
        val dz = p.z - c.pos.z
        val dist = sqrt(dx * dx + dz * dz)
        val t = c.type
        val nightMul = if (host.isNight && !t.machine) 0.7f else if (host.isNight) 1.15f else 1f
        val perceive = t.perception * host.playerStealth * nightMul
        val sees = dist < perceive && kotlin.math.abs(p.y - c.pos.y) < 25f

        when (t.temperament) {
            Temperament.PASSIVE -> passive(c, dt, sees, dx, dz, dist)
            Temperament.SKITTISH -> skittish(c, dt, sees, dx, dz, dist, host)
            Temperament.DEFENSIVE -> defensive(c, dt, sees, dx, dz, dist, host)
            Temperament.HOSTILE -> hostile(c, dt, sees, dx, dz, dist, host)
            Temperament.BOSS -> boss(c, dt, dx, dz, dist, host)
        }
        move(c, dt)
    }

    fun kill(c: Creature) {
        c.hp = 0f
        c.state = CState.DEAD
        c.stateTime = 0f
        c.speed = 0f
        c.deadTime = 0f
    }

    private fun setState(c: Creature, s: CState) { if (c.state != s) { c.state = s; c.stateTime = 0f } }

    private fun wander(c: Creature, dt: Float, radius: Float) {
        when (c.state) {
            CState.IDLE -> {
                c.speed = 0f
                if (c.stateTime > 2f + c.rng.nextFloat() * 5f) {
                    val a = c.rng.nextFloat() * TWO_PI
                    val r = c.rng.range(6f, radius)
                    c.targetX = c.homeX + cos(a) * r
                    c.targetZ = c.homeZ + sin(a) * r
                    if (WorldGen.height(c.targetX, c.targetZ) < 0.3f) { c.targetX = c.homeX; c.targetZ = c.homeZ }
                    setState(c, CState.WANDER)
                }
            }
            CState.WANDER -> {
                c.speed = c.type.walkSpeed
                if (steerTo(c, c.targetX, c.targetZ, dt) < 1.5f || c.stateTime > 25f) setState(c, CState.IDLE)
            }
            else -> setState(c, CState.IDLE)
        }
    }

    private fun steerTo(c: Creature, tx: Float, tz: Float, dt: Float): Float {
        val dx = tx - c.pos.x
        val dz = tz - c.pos.z
        val d = sqrt(dx * dx + dz * dz)
        if (d > 0.01f) {
            val want = atan2(dx, dz)
            val turn = if (c.type == CreatureType.COLOSSUS) 0.8f else 4.5f
            c.yaw += clamp(angleDiff(c.yaw, want), -turn * dt, turn * dt)
        }
        return d
    }

    private fun fleeFrom(c: Creature, dx: Float, dz: Float, dt: Float) {
        c.speed = c.type.runSpeed
        val len = max(0.01f, sqrt(dx * dx + dz * dz))
        steerTo(c, c.pos.x - dx / len * 20f, c.pos.z - dz / len * 20f, dt)
    }

    private fun passive(c: Creature, dt: Float, sees: Boolean, dx: Float, dz: Float, dist: Float) {
        if ((sees && dist < c.type.perception * 0.7f) || c.aggroTime > 0) {
            setState(c, CState.FLEE)
            c.aggroTime = max(c.aggroTime, 0f)
        }
        if (c.state == CState.FLEE) {
            fleeFrom(c, dx, dz, dt)
            if (c.stateTime > 7f && dist > c.type.perception * 1.3f) setState(c, CState.IDLE)
            return
        }
        wander(c, dt, 30f)
    }

    private fun skittish(c: Creature, dt: Float, sees: Boolean, dx: Float, dz: Float, dist: Float, host: CreatureHost) {
        // Striders flee but will kick when cornered or recently hit at close range
        if (c.state == CState.WINDUP) { windup(c, dist, host); return }
        if (c.state == CState.RECOVER) { c.speed = 0f; if (c.stateTime > 0.6f) setState(c, CState.FLEE); return }
        if (c.aggroTime > 0 && dist < c.type.attackRange + 0.6f && c.attackCd <= 0f) {
            c.speed = 0f
            steerTo(c, host.playerPos.x, host.playerPos.z, dt * 3f)
            setState(c, CState.WINDUP)
            return
        }
        if (sees && c.state != CState.FLEE && c.state != CState.ALERT) { setState(c, CState.ALERT); host.creatureAlerted(c) }
        if (c.state == CState.ALERT) {
            c.speed = 0f
            steerTo(c, host.playerPos.x, host.playerPos.z, dt)
            if (c.stateTime > 1.0f) setState(c, CState.FLEE)
            return
        }
        if (c.state == CState.FLEE) {
            fleeFrom(c, dx, dz, dt)
            if (c.stateTime > 8f && dist > c.type.perception * 1.4f) setState(c, CState.IDLE)
            return
        }
        wander(c, dt, 40f)
    }

    private fun defensive(c: Creature, dt: Float, sees: Boolean, dx: Float, dz: Float, dist: Float, host: CreatureHost) {
        if (sees && dist < 4.5f && c.aggroTime <= 0f) c.aggroTime = 8f
        if (c.aggroTime > 0) { combat(c, dt, dist, host, false); return }
        wander(c, dt, 25f)
    }

    private fun hostile(c: Creature, dt: Float, sees: Boolean, dx: Float, dz: Float, dist: Float, host: CreatureHost) {
        if (c.state == CState.WINDUP || c.state == CState.RECOVER || c.state == CState.CHASE) {
            combat(c, dt, dist, host, c.type == CreatureType.WARDEN)
            return
        }
        if (c.state == CState.ALERT) {
            c.speed = 0f
            steerTo(c, host.playerPos.x, host.playerPos.z, dt)
            if (c.stateTime > 0.9f) setState(c, CState.CHASE)
            return
        }
        // machines keep some distance from campfires
        if ((sees || c.aggroTime > 0 || c.encounter) && !(host.nearFire(host.playerPos.x, host.playerPos.z) && c.type == CreatureType.SCUTTLER && !c.encounter)) {
            setState(c, CState.ALERT)
            host.creatureAlerted(c)
            c.lastSeen = 0f
            return
        }
        wander(c, dt, if (c.guardPoi >= 0) 18f else 35f)
    }

    private fun combat(c: Creature, dt: Float, dist: Float, host: CreatureHost, ranged: Boolean) {
        val p = host.playerPos
        when (c.state) {
            CState.WINDUP -> { windup(c, dist, host); return }
            CState.RECOVER -> {
                c.speed = 0f
                if (c.stateTime > 0.7f) setState(c, CState.CHASE)
                return
            }
            else -> {}
        }
        if (c.state != CState.CHASE) setState(c, CState.CHASE)
        val sees = dist < c.type.perception * 1.6f * host.playerStealth.coerceAtLeast(0.6f)
        if (sees) c.lastSeen = 0f else c.lastSeen += dt
        if ((c.lastSeen > 6f && !c.encounter) || dist > 160f) {
            c.aggroTime = 0f
            setState(c, CState.IDLE)
            return
        }
        if (ranged && dist > 7f && dist < 48f && c.rangedCd <= 0f) {
            c.speed = 0f
            steerTo(c, p.x, p.z, dt)
            val ox = c.pos.x + sin(c.yaw) * 1.2f
            val oy = c.pos.y + 3.6f
            val oz = c.pos.z + cos(c.yaw) * 1.2f
            val tx = p.x - ox; val ty = (p.y + 1.2f) - oy; val tz = p.z - oz
            val l = sqrt(tx * tx + ty * ty + tz * tz)
            host.creatureFireBolt(c, ox, oy, oz, tx / l, ty / l, tz / l, c.type.damage * 0.8f * c.variant.dmgMul)
            c.rangedCd = 2.8f + c.rng.nextFloat() * 1.5f
            return
        }
        if (dist <= c.type.attackRange && c.attackCd <= 0f) {
            c.speed = 0f
            setState(c, CState.WINDUP)
            return
        }
        c.speed = if (dist < c.type.attackRange * 0.8f) 0f else c.type.runSpeed
        if (ranged && dist < 20f && c.rangedCd > 0.5f) c.speed = c.type.runSpeed
        steerTo(c, p.x, p.z, dt)
    }

    private fun windup(c: Creature, dist: Float, host: CreatureHost) {
        c.speed = 0f
        val wind = if (c.type == CreatureType.WARDEN) 0.75f else 0.45f
        if (c.stateTime >= wind) {
            if (c.type == CreatureType.WARDEN) {
                host.creatureStomp(c, 5f, c.type.damage * c.variant.dmgMul)
            } else if (dist <= c.type.attackRange + 0.7f) {
                host.creatureMelee(c, c.type.damage * c.variant.dmgMul)
            }
            c.attackCd = 1.4f + c.rng.nextFloat() * 0.6f
            setState(c, CState.RECOVER)
        }
    }

    private fun boss(c: Creature, dt: Float, dx: Float, dz: Float, dist: Float, host: CreatureHost) {
        val p = host.playerPos
        if (dist > 140f) { c.speed = 0f; return }
        steerTo(c, p.x, p.z, dt)
        // summons
        val frac = c.hp / c.maxHp
        if (c.summonStage == 0 && frac < 0.66f) { c.summonStage = 1; host.creatureSummon(c, 4) }
        if (c.summonStage == 1 && frac < 0.33f) { c.summonStage = 2; host.creatureSummon(c, 6) }
        if (c.stompWarn > 0f) {
            c.stompWarn -= dt
            c.speed = 0f
            if (c.stompWarn <= 0f) {
                host.creatureStomp(c, 13f, c.type.damage * 1.2f)
                c.attackCd = 3f
            }
            return
        }
        if (dist < 12f && c.attackCd <= 0f) { c.stompWarn = 1.3f; return }
        if (c.rangedCd <= 0f && dist < 90f) {
            val volley = if (frac < 0.5f) 7 else 5
            for (i in 0 until volley) {
                val spread = (i - volley / 2) * 0.09f
                val ox = c.pos.x + sin(c.yaw) * 3f
                val oy = c.pos.y + 9f
                val oz = c.pos.z + cos(c.yaw) * 3f
                val tx = p.x - ox; val ty = (p.y + 1.2f) - oy; val tz = p.z - oz
                val l = sqrt(tx * tx + ty * ty + tz * tz)
                val ca = cos(spread); val sa = sin(spread)
                val rx = tx * ca - tz * sa; val rz = tx * sa + tz * ca
                host.creatureFireBolt(c, ox, oy, oz, rx / l, ty / l, rz / l, 14f)
            }
            c.rangedCd = if (frac < 0.5f) 3f else 4.2f
        }
        // slow advance
        c.speed = if (dist > 25f) c.type.runSpeed else if (dist < 14f) 0f else c.type.walkSpeed
        val maxR = 26f
        val hx = c.pos.x - c.homeX; val hz = c.pos.z - c.homeZ
        if (hx * hx + hz * hz > maxR * maxR) c.speed = 0f
    }

    private fun move(c: Creature, dt: Float) {
        if (c.speed <= 0.01f) {
            c.walkPhase += dt * 0.5f
            return
        }
        val nx = c.pos.x + sin(c.yaw) * c.speed * dt
        val nz = c.pos.z + cos(c.yaw) * c.speed * dt
        val nh = WorldGen.height(nx, nz)
        val canSwim = c.type == CreatureType.COLOSSUS
        if ((nh < 0.2f && !canSwim) || !WorldGen.inWorld(nx, nz) || nh - c.pos.y > 2.5f) {
            // blocked: turn around and pick a new direction
            c.yaw += 2.2f + c.rng.nextFloat()
            if (c.state == CState.WANDER) c.state = CState.IDLE
            return
        }
        c.pos.x = nx; c.pos.z = nz
        c.pos.y = nh
        c.walkPhase += dt * c.speed * (if (c.type.machine) 2.2f else 2.6f) / max(0.6f, c.type.height * 0.6f)
    }

    /** Returns loot for a corpse. */
    fun loot(c: Creature, rng: Rng, bonus: Float): List<Pair<Item, Int>> {
        val out = ArrayList<Pair<Item, Int>>()
        fun add(i: Item, n: Int) { if (n > 0) out.add(i to n) }
        fun roll(p: Float) = rng.nextFloat() < min(1f, p * (1f + bonus))
        when (c.type) {
            CreatureType.GRAZEHORN -> { add(Item.RAW_MEAT, 2 + rng.nextInt(2)); add(Item.HIDE, 2) }
            CreatureType.TUSKER -> { add(Item.RAW_MEAT, 3 + rng.nextInt(2)); add(Item.HIDE, 1 + rng.nextInt(2)) }
            CreatureType.STRIDER -> {
                add(Item.WIRE, 2 + rng.nextInt(2)); add(Item.SCRAP, 2)
                if (roll(0.55f)) add(Item.ALLOY, 1)
                if (roll(0.2f)) add(Item.LENS, 1)
            }
            CreatureType.SCUTTLER -> {
                add(Item.SCRAP, 1 + rng.nextInt(2)); add(Item.WIRE, 1)
                if (roll(0.04f)) add(Item.CORE, 1)
            }
            CreatureType.PROWLER -> {
                add(Item.ALLOY, 2); add(Item.WIRE, 2)
                if (roll(0.3f)) add(Item.LENS, 1)
                if (roll(0.2f)) add(Item.CORE, 1)
            }
            CreatureType.WARDEN -> {
                add(Item.ALLOY, 4 + rng.nextInt(2)); add(Item.WIRE, 3); add(Item.CORE, 1)
                if (roll(0.55f)) add(Item.LENS, 1)
            }
            CreatureType.COLOSSUS -> { add(Item.CORE, 5); add(Item.ALLOY, 15); add(Item.LENS, 3) }
        }
        if (c.variant == Variant.FROST && c.type.machine) add(Item.CRYO_SHARD, 1 + rng.nextInt(2))
        return out
    }
}
