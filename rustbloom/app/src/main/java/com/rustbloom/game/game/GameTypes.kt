package com.rustbloom.game.game

import com.rustbloom.game.engine.Vec3
import com.rustbloom.game.world.Aabb
import com.rustbloom.game.world.Interactable
import com.rustbloom.game.world.Node

enum class Sfx {
    CHOP, MINE, SWING, HIT_FLESH, HIT_METAL, BOW_DRAW, BOW_RELEASE, ARROW_HIT, PICKUP, CRAFT, EAT, DRINK, HURT,
    MACHINE_ALERT, MACHINE_DIE, BOLT, STOMP, BUILD, UI, LEVEL_UP, QUEST, THUNDER, SPIRE, STEP, SPLASH, ZAP, DEATH,
    STEP_STONE, STEP_SAND, STEP_SNOW, STEP_WOOD, STEP_WATER, ANIMAL_HURT, GROWL, SCREECH, CAST, UI_OPEN,
}

/** Continuous sound environment around the listener; the engine turns this into beds and one-shot ambience. */
class Ambience {
    var wind = 0f        // 0..1 wind strength at the listener (exposure already applied)
    var rain = 0f
    var snow = false
    var fire = 0f        // closeness to a fire 0..1
    var ocean = 0f       // closeness to open water 0..1
    var night = 0f
    var forest = 0f      // tree density 0..1
    var marsh = 0f
    var cold = 0f
    var desert = 0f
    var sheltered = false
    var underwater = false
    var combat = false
    var paused = false
}

interface SoundSink {
    fun play(s: Sfx, volume: Float = 1f, pitch: Float = 1f)
    fun playAt(s: Sfx, x: Float, y: Float, z: Float, volume: Float = 1f, pitch: Float = 1f)
    fun setListener(x: Float, y: Float, z: Float, yaw: Float)
    fun setAmbience(a: Ambience)
}

interface GameUi {
    fun onPlayerDied()
    fun showLog(title: String, body: String)
    fun openChest(p: Placed)
    fun openBounties()
    fun openCrafting(station: Station)
    fun vibrate(ms: Long)
    fun onWorldReady()
    fun onVictory()
    fun refreshMenus()
    fun toast(text: String)
}

class Placed(val id: Int, val type: BuildType, val x: Float, val y: Float, val z: Float, val rot: Int) {
    var storage: Inventory? = if (type == BuildType.CHEST) Inventory(24).also { it.size = 24 } else null
    var water = 0
    var timer = 0f

    /** Half extents after 90 degree rotation. */
    val hx get() = if (rot % 2 == 0) type.sx / 2 else type.sz / 2
    val hz get() = if (rot % 2 == 0) type.sz / 2 else type.sx / 2
    val yaw get() = rot * 1.5707964f

    fun colliders(out: MutableList<Aabb>) {
        when (type) {
            BuildType.DOORWAY -> {
                // two side posts and a lintel, leaving a walkable gap
                val along = rot % 2 == 0
                val w = type.sx / 2
                if (along) {
                    out.add(Aabb(x - w, y, z - 0.13f, x - 0.6f, y + 3f, z + 0.13f))
                    out.add(Aabb(x + 0.6f, y, z - 0.13f, x + w, y + 3f, z + 0.13f))
                    out.add(Aabb(x - w, y + 2.3f, z - 0.13f, x + w, y + 3f, z + 0.13f))
                } else {
                    out.add(Aabb(x - 0.13f, y, z - w, x + 0.13f, y + 3f, z - 0.6f))
                    out.add(Aabb(x - 0.13f, y, z + 0.6f, x + 0.13f, y + 3f, z + w))
                    out.add(Aabb(x - 0.13f, y + 2.3f, z - w, x + 0.13f, y + 3f, z + w))
                }
            }
            BuildType.CAMPFIRE, BuildType.TORCH_POST -> {}
            else -> if (type.solid) out.add(Aabb(x - hx, y, z - hz, x + hx, y + type.sy, z + hz))
        }
    }
}

enum class ProjKind { ARROW, FIRE_ARROW, SHOCK_ARROW, BOLT }

class Projectile(val kind: ProjKind, val pos: Vec3, val vel: Vec3, val damage: Float, val fromPlayer: Boolean) {
    var life = 6f
    var alive = true
    val prev = Vec3().set(pos)
}

class Bounty(val title: String, val kill: CreatureType?, val deliver: Item?, val count: Int, val xp: Int, val reward: List<Pair<Item, Int>>) {
    var progress = 0
    var claimed = false
    val complete get() = if (kill != null) progress >= count else false
}

class Encounter(val poiId: Int, val spireKind: String, var time: Float) {
    var waveTimer = 2f
    var wave = 0
    var guardianId = -1
}

class Note(val text: String, val color: Int) { var age = 0f }

class Beam(val from: Vec3, val to: Vec3, var ttl: Float, val color: Int)

enum class TargetKind { NONE, NODE, CORPSE, INTERACT, PLACED, WATER }

class Target {
    var kind = TargetKind.NONE
    var node: Node? = null
    var creature: Creature? = null
    var inter: Interactable? = null
    var placed: Placed? = null
    var label = ""
    var sub = ""
    var dist = 0f
    fun clear() { kind = TargetKind.NONE; node = null; creature = null; inter = null; placed = null; label = ""; sub = "" }
}

/** State of the fishing line while a rod is cast. */
class Fishing {
    var active = false
    var x = 0f; var y = 0f; var z = 0f
    var wait = 0f
    var bite = false
    var biteWindow = 0f
}
