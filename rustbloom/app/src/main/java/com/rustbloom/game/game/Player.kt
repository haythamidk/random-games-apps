package com.rustbloom.game.game

import com.rustbloom.game.engine.Vec3
import com.rustbloom.game.engine.clamp
import com.rustbloom.game.world.Aabb
import com.rustbloom.game.world.ChunkManager
import com.rustbloom.game.world.WorldGen
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class Player {
    val pos = Vec3()
    val vel = Vec3()
    var yaw = 0f
    var pitch = 0f
    var onGround = false
    var crouching = false
    var sprinting = false
    var swimming = false
    var underwater = false
    var headBob = 0f
    var bobAmount = 0f
    var fallSpeed = 0f

    var hp = 100f
    var hunger = 100f
    var thirst = 100f
    var stamina = 100f
    var staminaDelay = 0f
    var feltTemp = 18f
    var xp = 0
    var level = 1
    var skillPoints = 0
    val skills = IntArray(Skill.entries.size)
    var selected = 0
    var ammo: Item = Item.ARROW

    var swingTime = -1f
    var swingHit = false
    var swingDuration = 0.45f
    var drawAmount = 0f
    var drawing = false
    var hurtFlash = 0f
    var healFlash = 0f
    var dead = false
    var spawnX = 0f
    var spawnZ = 0f
    var bedId = -1

    // spire perks
    var bonusHp = 0f
    var bonusWarmth = 0f
    var thirstPerk = 1f
    var hungerPerk = 1f

    val inv = Inventory()

    fun skill(s: Skill) = skills[s.ordinal]

    val maxHp get() = 100f + bonusHp + skill(Skill.THICK_SKIN) * 15f
    val maxStamina get() = 100f * (1f + skill(Skill.ENDURANCE) * 0.2f)
    val eyeHeight get() = if (crouching) 1.05f else 1.62f

    fun xpForNext(): Int = (80.0 * level.toDouble().pow(1.3)).toInt()

    /** Adds xp; returns number of levels gained. */
    fun addXp(n: Int): Int {
        xp += n
        var gained = 0
        while (xp >= xpForNext()) {
            xp -= xpForNext()
            level++
            skillPoints++
            gained++
        }
        return gained
    }

    fun selectedStack(): ItemStack? = inv.slots[selected]
    fun selectedTool(): ToolKind = selectedStack()?.item?.tool ?: ToolKind.NONE
}

/** Static and dynamic collision for the player and projectiles. */
class Collision(private val chunks: ChunkManager) {
    private val cell = 16f
    private val staticGrid = HashMap<Long, MutableList<Aabb>>()
    private val buildGrid = HashMap<Long, MutableList<Aabb>>()
    private val tmp = ArrayList<Aabb>()

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)

    private fun insert(grid: HashMap<Long, MutableList<Aabb>>, b: Aabb) {
        val x0 = floor(b.minX / cell).toInt(); val x1 = floor(b.maxX / cell).toInt()
        val z0 = floor(b.minZ / cell).toInt(); val z1 = floor(b.maxZ / cell).toInt()
        for (x in x0..x1) for (z in z0..z1) grid.getOrPut(key(x, z)) { ArrayList() }.add(b)
    }

    fun setStatic(list: List<Aabb>) {
        staticGrid.clear()
        for (b in list) insert(staticGrid, b)
    }

    fun setBuilds(list: List<Aabb>) {
        buildGrid.clear()
        for (b in list) insert(buildGrid, b)
    }

    fun boxesNear(x: Float, z: Float, r: Float, out: ArrayList<Aabb>) {
        out.clear()
        val x0 = floor((x - r) / cell).toInt(); val x1 = floor((x + r) / cell).toInt()
        val z0 = floor((z - r) / cell).toInt(); val z1 = floor((z + r) / cell).toInt()
        for (gx in x0..x1) for (gz in z0..z1) {
            staticGrid[key(gx, gz)]?.let { for (b in it) if (!out.contains(b)) out.add(b) }
            buildGrid[key(gx, gz)]?.let { for (b in it) if (!out.contains(b)) out.add(b) }
        }
    }

    /** Highest walkable surface under (x,z) that is not above feetY + step. */
    fun groundHeight(x: Float, z: Float, feetY: Float, step: Float = 0.6f): Float {
        var g = WorldGen.height(x, z)
        boxesNear(x, z, 0.5f, tmp)
        for (b in tmp) {
            if (x >= b.minX - 0.2f && x <= b.maxX + 0.2f && z >= b.minZ - 0.2f && z <= b.maxZ + 0.2f) {
                if (b.maxY <= feetY + step && b.maxY > g) g = b.maxY
            }
        }
        return g
    }

    /** Pushes a vertical cylinder out of trees and boxes. */
    fun resolve(p: Vec3, r: Float, h: Float) {
        chunks.forEachNodeNear(p.x, p.z, 3f) { n ->
            val cr = n.type.collide * n.scale
            if (cr > 0f) {
                val dx = p.x - n.x; val dz = p.z - n.z
                val d = sqrt(dx * dx + dz * dz)
                val min = r + cr
                if (d < min && d > 1e-4f && p.y < n.y + 6f) {
                    p.x = n.x + dx / d * min
                    p.z = n.z + dz / d * min
                }
            }
        }
        boxesNear(p.x, p.z, r + 1f, tmp)
        for (b in tmp) {
            if (p.y + h <= b.minY || p.y + 0.55f >= b.maxY) continue
            val cx = clamp(p.x, b.minX, b.maxX)
            val cz = clamp(p.z, b.minZ, b.maxZ)
            val dx = p.x - cx; val dz = p.z - cz
            val d2 = dx * dx + dz * dz
            if (d2 < r * r) {
                if (d2 > 1e-8f) {
                    val d = sqrt(d2)
                    p.x = cx + dx / d * r
                    p.z = cz + dz / d * r
                } else {
                    // center is inside the box: push out along the shallowest axis
                    val pl = p.x - b.minX; val pr = b.maxX - p.x
                    val pb = p.z - b.minZ; val pf = b.maxZ - p.z
                    val m = min(min(pl, pr), min(pb, pf))
                    when (m) {
                        pl -> p.x = b.minX - r
                        pr -> p.x = b.maxX + r
                        pb -> p.z = b.minZ - r
                        else -> p.z = b.maxZ + r
                    }
                }
            }
        }
    }

    /** True if the given box intersects a world structure (not player builds). */
    fun overlapsStatic(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float): Boolean {
        val x0 = floor(minX / cell).toInt(); val x1 = floor(maxX / cell).toInt()
        val z0 = floor(minZ / cell).toInt(); val z1 = floor(maxZ / cell).toInt()
        for (gx in x0..x1) for (gz in z0..z1) {
            val list = staticGrid[key(gx, gz)] ?: continue
            for (b in list) {
                if (b.maxX > minX && b.minX < maxX && b.maxY > minY && b.minY < maxY && b.maxZ > minZ && b.minZ < maxZ) return true
            }
        }
        return false
    }

    /** True if a point is inside any solid box (for projectiles). */
    fun solidAt(x: Float, y: Float, z: Float): Boolean {
        boxesNear(x, z, 0.1f, tmp)
        for (b in tmp) if (b.contains(x, y, z)) return true
        return false
    }

    /** Is there a roof-type surface above this point within [range] meters? */
    fun covered(x: Float, y: Float, z: Float, range: Float = 6f): Boolean {
        boxesNear(x, z, 0.1f, tmp)
        for (b in tmp) {
            if (x in b.minX..b.maxX && z in b.minZ..b.maxZ && b.minY > y + 1.5f && b.minY < y + range) return true
        }
        return false
    }
}

object PlayerPhysics {
    const val GRAVITY = 19f
    const val RADIUS = 0.35f
    const val HEIGHT = 1.8f

    /**
     * Moves the player. [moveX]/[moveY] are local intents in -1..1. Returns impact speed when landing (for fall damage).
     */
    fun step(p: Player, col: Collision, dt: Float, moveX: Float, moveY: Float, jump: Boolean, speedMul: Float): Float {
        val sy = kotlin.math.sin(p.yaw); val cy = kotlin.math.cos(p.yaw)
        // forward = (sin(yaw), cos(yaw)) ; right = (-cos(yaw), sin(yaw))
        var wx = sy * moveY - cy * moveX
        var wz = cy * moveY + sy * moveX
        val wl = sqrt(wx * wx + wz * wz)
        if (wl > 1f) { wx /= wl; wz /= wl }

        val terrain = WorldGen.height(p.pos.x, p.pos.z)
        val waterDepth = WorldGen.WATER - terrain
        p.swimming = waterDepth > 1.25f && p.pos.y < WorldGen.WATER - 0.9f
        val base = when {
            p.swimming -> 2.7f
            p.crouching -> 2.0f
            p.sprinting -> 7.2f
            else -> 4.3f
        } * speedMul * (if (!p.swimming && waterDepth > 0.4f) 0.7f else 1f)

        val accel = if (p.onGround || p.swimming) 28f else 5f
        p.vel.x += (wx * base - p.vel.x) * min(1f, accel * dt)
        p.vel.z += (wz * base - p.vel.z) * min(1f, accel * dt)

        if (p.swimming) {
            val targetY = WorldGen.WATER - 1.3f
            p.vel.y += (targetY - p.pos.y) * 6f * dt - p.vel.y * 4f * dt
            if (jump) p.vel.y = 2.5f
        } else {
            p.vel.y -= GRAVITY * dt
            if (jump && p.onGround) { p.vel.y = 6.4f; p.onGround = false }
        }

        val oldY = p.pos.y
        p.pos.x += p.vel.x * dt
        p.pos.z += p.vel.z * dt
        p.pos.y += p.vel.y * dt
        val lim = WorldGen.HALF - 30f
        p.pos.x = clamp(p.pos.x, -lim, lim)
        p.pos.z = clamp(p.pos.z, -lim, lim)

        col.resolve(p.pos, RADIUS, HEIGHT)

        val ground = col.groundHeight(p.pos.x, p.pos.z, max(oldY, p.pos.y))
        var impact = 0f
        if (p.pos.y <= ground) {
            if (!p.onGround && p.vel.y < 0f) impact = -p.vel.y
            p.pos.y = ground
            if (p.vel.y < 0f) p.vel.y = 0f
            p.onGround = true
        } else {
            // snap down small steps when walking down slopes
            if (p.onGround && p.pos.y - ground < 0.45f && p.vel.y <= 0f) {
                p.pos.y = ground
                p.vel.y = 0f
            } else {
                p.onGround = false
            }
        }
        if (p.swimming) p.onGround = false
        p.underwater = p.pos.y + p.eyeHeight < WorldGen.WATER - 0.05f

        val hs = sqrt(p.vel.x * p.vel.x + p.vel.z * p.vel.z)
        if (p.onGround && hs > 0.5f) {
            p.headBob += dt * hs * 1.9f
            p.bobAmount = min(1f, p.bobAmount + dt * 4f)
        } else {
            p.bobAmount = max(0f, p.bobAmount - dt * 4f)
        }
        return if (abs(impact) > 0f) impact else 0f
    }
}
