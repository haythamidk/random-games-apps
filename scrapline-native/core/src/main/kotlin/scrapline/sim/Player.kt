package scrapline.sim

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

const val EYE_HEIGHT = 1.62f
private const val WALK_SPEED = 4.2f
private const val SPRINT_SPEED = 6.8f
private const val ACCEL = 28f
private const val GRAVITY = 22f
private const val JUMP_SPEED = 7.2f
/** Steepest slope (rise over run) the player can walk up. */
private const val MAX_CLIMB = 1.15f
private const val BORDER = 2f
private const val PITCH_LIMIT = 1.45f

class Player(w: World) {
    var x = WORLD_SIZE / 2f
    var z = WORLD_SIZE / 2f
    /** Feet height. */
    var y = w.heightAt(x, z)
    var vx = 0f
    var vy = 0f
    var vz = 0f
    /** Radians; 0 looks toward -Z, positive turns left. */
    var yaw = 0f
    var pitch = 0f
    var onGround = true
}

/** What the input layer asks the player to do this frame. */
class MoveIntent {
    var moveX = 0f
    var moveY = 0f
    var sprint = false
    var jump = false
    /** Look deltas in radians, already scaled by sensitivity. */
    var lookYaw = 0f
    var lookPitch = 0f
}

/**
 * Player kinematics. Runs once per rendered frame (not on the 20 Hz sim tick)
 * so that touch input has no added latency; `dt` is clamped by the caller.
 */
fun updatePlayer(p: Player, w: World, intent: MoveIntent, dt: Float) {
    p.yaw += intent.lookYaw
    p.pitch = (p.pitch + intent.lookPitch).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)

    var mx = intent.moveX
    var my = intent.moveY
    val len = hypot(mx, my)
    if (len > 1f) {
        mx /= len
        my /= len
    }
    val speed = if (intent.sprint) SPRINT_SPEED else WALK_SPEED
    val s = sin(p.yaw)
    val c = cos(p.yaw)
    // Forward is (-sin, -cos); right is (cos, -sin).
    val targetVx = (-s * my + c * mx) * speed
    val targetVz = (-c * my - s * mx) * speed

    val maxDelta = ACCEL * dt * (if (p.onGround) 1f else 0.35f)
    val dvx = targetVx - p.vx
    val dvz = targetVz - p.vz
    val dv = hypot(dvx, dvz)
    if (dv <= maxDelta || dv == 0f) {
        p.vx = targetVx
        p.vz = targetVz
    } else {
        p.vx += dvx / dv * maxDelta
        p.vz += dvz / dv * maxDelta
    }

    if (intent.jump && p.onGround) {
        p.vy = JUMP_SPEED
        p.onGround = false
    }

    // Horizontal move, one axis at a time so the player slides along steep ground.
    val lo = BORDER
    val hi = WORLD_SIZE - BORDER
    val stepX = p.vx * dt
    if (stepX != 0f) {
        val nx = (p.x + stepX).coerceIn(lo, hi)
        if (canStep(p, w, nx, p.z)) p.x = nx else p.vx = 0f
    }
    val stepZ = p.vz * dt
    if (stepZ != 0f) {
        val nz = (p.z + stepZ).coerceIn(lo, hi)
        if (canStep(p, w, p.x, nz)) p.z = nz else p.vz = 0f
    }

    val ground = w.heightAt(p.x, p.z)
    if (p.onGround) {
        // Stick to the ground when walking downhill instead of micro-falling.
        if (ground >= p.y - 0.45f) {
            p.y = ground
            p.vy = 0f
        } else {
            p.onGround = false
        }
    }
    if (!p.onGround) {
        p.vy -= GRAVITY * dt
        p.y += p.vy * dt
        if (p.y <= ground) {
            p.y = ground
            p.vy = 0f
            p.onGround = true
        }
    }
}

private fun canStep(p: Player, w: World, nx: Float, nz: Float): Boolean {
    val run = hypot(nx - p.x, nz - p.z)
    if (run < 1e-6f) return true
    val rise = w.heightAt(nx, nz) - p.y
    // Airborne players may always move where they clear the ground.
    if (!p.onGround) return rise <= 0.3f
    return rise / run <= MAX_CLIMB
}
