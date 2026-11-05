package com.hadal.game.input

import kotlin.math.abs
import kotlin.math.sqrt

enum class Action { LAMP, SONAR, USE, FLARE, PULSE, REPAIR, MENU, CAMERA, SKIP }

/** Unified input from touch, gamepad and keyboard/mouse. Written by the UI thread, consumed by the game thread. */
class InputState {
    private val lock = Any()
    private var touchMoveX = 0f; private var touchMoveY = 0f
    private var padMoveX = 0f; private var padMoveY = 0f
    private var keyMoveX = 0f; private var keyMoveY = 0f
    private var padLookX = 0f; private var padLookY = 0f
    private var padVert = 0f
    private var lookAccX = 0f; private var lookAccY = 0f
    private val actions = ArrayList<Action>()

    @Volatile var touchUp = false
    @Volatile var touchDown = false
    @Volatile var keyUp = false
    @Volatile var keyDown = false
    @Volatile var boostHeld = false
    @Volatile var scanHeld = false
    @Volatile var padBoost = false
    @Volatile var padScan = false
    var kW = false; var kA = false; var kS = false; var kD = false

    fun setTouchMove(x: Float, y: Float) = synchronized(lock) { touchMoveX = x; touchMoveY = y }
    fun setPadMove(x: Float, y: Float) = synchronized(lock) { padMoveX = x; padMoveY = y }
    fun setPadLook(x: Float, y: Float) = synchronized(lock) { padLookX = x; padLookY = y }
    fun setPadVertical(v: Float) = synchronized(lock) { padVert = v }
    fun updateKeys() = synchronized(lock) {
        keyMoveX = (if (kD) 1f else 0f) - (if (kA) 1f else 0f)
        keyMoveY = (if (kW) 1f else 0f) - (if (kS) 1f else 0f)
    }
    fun addLook(dx: Float, dy: Float) = synchronized(lock) { lookAccX += dx; lookAccY += dy }
    fun push(a: Action) = synchronized(lock) { actions.add(a) }

    class Frame {
        var moveX = 0f; var moveY = 0f; var vertical = 0f
        var lookX = 0f; var lookY = 0f
        var padLookX = 0f; var padLookY = 0f
        var boost = false; var scan = false
        val actions = ArrayList<Action>()
    }

    fun poll(f: Frame) = synchronized(lock) {
        var mx = touchMoveX; var my = touchMoveY
        if (abs(padMoveX) + abs(padMoveY) > abs(mx) + abs(my)) { mx = padMoveX; my = padMoveY }
        if (abs(keyMoveX) + abs(keyMoveY) > abs(mx) + abs(my)) {
            mx = keyMoveX; my = keyMoveY
            val l = sqrt(mx * mx + my * my); if (l > 1f) { mx /= l; my /= l }
        }
        f.moveX = mx; f.moveY = my
        var v = (if (touchUp || keyUp) 1f else 0f) - (if (touchDown || keyDown) 1f else 0f)
        if (abs(padVert) > abs(v)) v = padVert
        f.vertical = v
        f.lookX = lookAccX; f.lookY = lookAccY; lookAccX = 0f; lookAccY = 0f
        f.padLookX = padLookX; f.padLookY = padLookY
        f.boost = boostHeld || padBoost
        f.scan = scanHeld || padScan
        f.actions.clear(); f.actions.addAll(actions); actions.clear()
    }

    fun reset() = synchronized(lock) {
        touchMoveX = 0f; touchMoveY = 0f; padMoveX = 0f; padMoveY = 0f; keyMoveX = 0f; keyMoveY = 0f
        padLookX = 0f; padLookY = 0f; padVert = 0f; lookAccX = 0f; lookAccY = 0f
        touchUp = false; touchDown = false; keyUp = false; keyDown = false
        boostHeld = false; scanHeld = false; padBoost = false; padScan = false
        kW = false; kA = false; kS = false; kD = false
        actions.clear()
    }
}
