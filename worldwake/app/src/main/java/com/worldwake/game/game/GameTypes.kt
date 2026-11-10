package com.worldwake.game.game

enum class Sfx {
    STEP_FROST, STEP_ROCK, STEP_SOIL, STEP_GRASS, STEP_METAL, STEP_WATER, LAND, JET,
    PICKUP, BUILD, DECON, CRAFT, UI, UI_OPEN, DENY, DRINK, EAT, O2_REFILL, AIRLOCK, ALARM,
    HURT, DEATH, STAGE, LOG, WREN, WHISPER, STONE, THUNDER, CRATE, MACHINE_ON, SPLASH, UNLOCK, ENDING,
}

/** What the world sounds like right now; the audio thread turns this into beds, one-shots and music. */
class Ambience {
    var wind = 0f
    var rain = 0f
    /** 0 = near vacuum (outside sound is thin and muffled), 1 = full air. */
    var atmo = 0f
    var indoors = false
    var underwater = false
    var night = 0f
    /** 0..1 how alive the world is: insects, rustling, birds of a sort. */
    var life = 0f
    /** 0..1 machines running close by. */
    var machines = 0f
    var water = 0f
    /** 0..1 closeness to a Watcher. */
    var watcher = 0f
    /** 0..1 the low hum of the planet itself. */
    var hum = 0f
    /** 0 calm .. 1 gasping. */
    var breath = 0f
    var helmet = true
    var stage = 0f
    var paused = false
    var menu = false

    fun copyFrom(a: Ambience) {
        wind = a.wind; rain = a.rain; atmo = a.atmo; indoors = a.indoors; underwater = a.underwater; night = a.night
        life = a.life; machines = a.machines; water = a.water; watcher = a.watcher; hum = a.hum; breath = a.breath
        helmet = a.helmet; stage = a.stage; paused = a.paused; menu = a.menu
    }
}

interface SoundSink {
    fun play(s: Sfx, volume: Float = 1f, pitch: Float = 1f)
    fun playAt(s: Sfx, x: Float, y: Float, z: Float, volume: Float = 1f, pitch: Float = 1f)
    fun setListener(x: Float, y: Float, z: Float, yaw: Float)
    fun setAmbience(a: Ambience)
    /** WREN speaks: a short run of chirps whose length follows the text. */
    fun speak(chars: Int)
}

/** Calls from the simulation into the Android UI. They arrive on the GL thread. */
interface GameUi {
    fun onPlayerDied()
    fun showLog(title: String, body: String)
    fun openStorage(title: String, inv: Inventory)
    fun openFabricator()
    fun refreshMenus()
    fun toast(text: String)
    fun vibrate(ms: Long)
    fun onEnding()
}
