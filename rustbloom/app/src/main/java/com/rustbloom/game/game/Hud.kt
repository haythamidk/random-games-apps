package com.rustbloom.game.game

/** Snapshot of everything the HUD needs, written by the game thread and read by the UI thread. */
class HudData {
    var hp = 100f; var maxHp = 100f
    var hunger = 100f; var thirst = 100f
    var stamina = 100f; var maxStamina = 100f
    var temp = 18f
    var tempState = 0 // -2 freezing, -1 cold, 0 ok, 1 hot, 2 burning
    var time = 8f; var day = 1
    var weather = ""
    var biome = ""
    var fps = 0
    var questTitle = ""
    val objectives = arrayOfNulls<String>(4)
    val objectiveDone = BooleanArray(4)
    var objectiveCount = 0
    val hotbarName = arrayOfNulls<String>(6)
    val hotbarShort = arrayOfNulls<String>(6)
    val hotbarCount = IntArray(6)
    val hotbarDur = FloatArray(6)
    val hotbarColor = IntArray(6)
    val hotbarItem = IntArray(6) { -1 }
    var fishing = 0
    var selectedConsumable = ""
    var promptIcon = -1
    var selected = 0
    var ammoName = ""
    var ammoCount = 0
    var showAmmo = false
    var prompt = ""
    var promptSub = ""
    var targetName = ""
    var targetHp = 0f
    var targetHostile = false
    var bowDraw = 0f
    var aiming = false
    var hurt = 0f
    var heal = 0f
    var compassYaw = 0f
    val markerAngle = FloatArray(8)
    val markerLabel = arrayOfNulls<String>(8)
    val markerColor = IntArray(8)
    val markerDist = FloatArray(8)
    var markerCount = 0
    var bossName = ""
    var bossHp = 0f
    var encounterTime = -1f
    var encounterLabel = ""
    var dead = false
    var fade = 0f
    var level = 1
    var xpFrac = 0f
    var skillPoints = 0
    var dialog = ""
    var dialogAlpha = 0f
    val notes = arrayOfNulls<String>(5)
    val noteAge = FloatArray(5)
    val noteColor = IntArray(5)
    var noteCount = 0
    var buildMode = false
    var buildName = ""
    var buildValid = false
    var underwater = false
    var loading = true
    var hint = ""
    var crouching = false
    var sprinting = false
    var hasTool = false
    var flashLevelUp = 0f
    var stealthy = false
}
