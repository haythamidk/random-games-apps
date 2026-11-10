package com.worldwake.game.input

import kotlin.math.abs
import kotlin.math.sqrt

enum class Action {
    JUMP, INTERACT, PLACE, ROTATE, ROTATE_BACK, CANCEL, NEXT_BUILD, PREV_BUILD, DECON_TOGGLE, SKIP_DIALOG,
}

/**
 * Unified input from touch, gamepad and keyboard/mouse. Written by the UI thread, consumed by the game thread.
 */
class InputState {
    private val lock = Any()

    // per-source movement vectors (x = strafe right, y = forward)
    private var touchMoveX = 0f; private var touchMoveY = 0f
    private var padMoveX = 0f; private var padMoveY = 0f
    private var keyMoveX = 0f; private var keyMoveY = 0f
    private var padLookX = 0f; private var padLookY = 0f

    private var lookAccX = 0f
    private var lookAccY = 0f

    @Volatile var attackHeld = false
    @Volatile var aimHeld = false
    @Volatile var sprintHeld = false
    @Volatile var crouchHeld = false
    @Volatile var padAttack = false
    @Volatile var padAim = false
    @Volatile var touchSprint = false
    @Volatile var touchJump = false
    @Volatile var padJump = false
    @Volatile var keyJump = false

    private val actions = ArrayList<Action>()

    // keyboard key state
    var kW = false; var kA = false; var kS = false; var kD = false

    fun setTouchMove(x: Float, y: Float) = synchronized(lock) { touchMoveX = x; touchMoveY = y }
    fun setPadMove(x: Float, y: Float) = synchronized(lock) { padMoveX = x; padMoveY = y }
    fun setPadLook(x: Float, y: Float) = synchronized(lock) { padLookX = x; padLookY = y }
    fun updateKeys() = synchronized(lock) {
        keyMoveX = (if (kD) 1f else 0f) - (if (kA) 1f else 0f)
        keyMoveY = (if (kW) 1f else 0f) - (if (kS) 1f else 0f)
    }

    /** Adds a relative look delta in degrees. */
    fun addLook(dx: Float, dy: Float) = synchronized(lock) { lookAccX += dx; lookAccY += dy }

    fun push(a: Action) = synchronized(lock) { actions.add(a) }

    class Frame {
        var moveX = 0f
        var moveY = 0f
        var lookX = 0f
        var lookY = 0f
        var padLookX = 0f
        var padLookY = 0f
        var attack = false
        var aim = false
        var sprint = false
        var crouch = false
        var jump = false
        val actions = ArrayList<Action>()
    }

    /** Consumes accumulated input into [f]. */
    fun poll(f: Frame) = synchronized(lock) {
        var mx = touchMoveX; var my = touchMoveY
        if (abs(padMoveX) + abs(padMoveY) > abs(mx) + abs(my)) { mx = padMoveX; my = padMoveY }
        if (abs(keyMoveX) + abs(keyMoveY) > abs(mx) + abs(my)) {
            mx = keyMoveX; my = keyMoveY
            val l = sqrt(mx * mx + my * my)
            if (l > 1f) { mx /= l; my /= l }
        }
        f.moveX = mx; f.moveY = my
        f.lookX = lookAccX; f.lookY = lookAccY
        lookAccX = 0f; lookAccY = 0f
        f.padLookX = padLookX; f.padLookY = padLookY
        f.attack = attackHeld || padAttack
        f.aim = aimHeld || padAim
        f.sprint = sprintHeld || touchSprint
        f.crouch = crouchHeld
        f.jump = touchJump || padJump || keyJump
        f.actions.clear()
        f.actions.addAll(actions)
        actions.clear()
    }

    fun reset() = synchronized(lock) {
        touchMoveX = 0f; touchMoveY = 0f; padMoveX = 0f; padMoveY = 0f; keyMoveX = 0f; keyMoveY = 0f
        padLookX = 0f; padLookY = 0f; lookAccX = 0f; lookAccY = 0f
        attackHeld = false; aimHeld = false; sprintHeld = false; padAttack = false; padAim = false
        touchJump = false; padJump = false; keyJump = false; touchSprint = false
        kW = false; kA = false; kS = false; kD = false
        actions.clear()
    }
}
