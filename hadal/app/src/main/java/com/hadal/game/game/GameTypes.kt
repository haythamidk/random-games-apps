package com.hadal.game.game

enum class Sfx {
    UI, UI_OPEN, PING, ECHO, HULL_HIT, CREAK, GROAN, CLANG, GRAB, PICKUP, SCAN_TICK, SCAN_DONE, LAMP, ALARM,
    DOCK, UNDOCK, FLARE, PULSE, ZAP, BITE, SNAP, HISS, ROAR, WHALE, PALE_CALL, PALE_CLICK, CRAFT, QUEST,
    BUBBLES, HEARTBEAT, DATA, IMPLODE, REPAIR, BEACON, CHOIR,
}

enum class DamageKind { COLLISION, PRESSURE, BITE, STING, PALE }

/** Continuous sound state, written by the game every frame. */
class Ambience {
    var depth = 0f          // 0 shallow .. 1 hadal
    var thrust = 0f         // engine load 0..1
    var boost = false
    var docked = false
    var vents = 0f          // closeness to black smokers 0..1
    var station = 0f        // closeness to the station hum 0..1
    var fear = 0f           // Pale proximity 0..1
    var stress = 0f         // hull beyond rating 0..1
    var surface = 0f        // near the surface 0..1
    var menu = false
    var paused = false
    var lowPower = false

    fun copyFrom(o: Ambience) {
        depth = o.depth; thrust = o.thrust; boost = o.boost; docked = o.docked; vents = o.vents; station = o.station
        fear = o.fear; stress = o.stress; surface = o.surface; menu = o.menu; paused = o.paused; lowPower = o.lowPower
    }
}

interface SoundSink {
    fun play(s: Sfx, volume: Float = 1f, pitch: Float = 1f)
    fun playAt(s: Sfx, x: Float, y: Float, z: Float, volume: Float = 1f, pitch: Float = 1f)
    fun setListener(x: Float, y: Float, z: Float, yaw: Float)
    fun setAmbience(a: Ambience)
}

object NoSound : SoundSink {
    override fun play(s: Sfx, volume: Float, pitch: Float) {}
    override fun playAt(s: Sfx, x: Float, y: Float, z: Float, volume: Float, pitch: Float) {}
    override fun setListener(x: Float, y: Float, z: Float, yaw: Float) {}
    override fun setAmbience(a: Ambience) {}
}

/** A line of dialogue or a system message shown on the HUD. */
class Message(val speaker: String, val text: String, val seconds: Float)

/** A deployed flare drifting slowly down. */
class Flare(x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float) {
    val pos = com.hadal.game.engine.Vec3(x, y, z)
    val vel = com.hadal.game.engine.Vec3(vx, vy, vz)
    var life = 60f
}
