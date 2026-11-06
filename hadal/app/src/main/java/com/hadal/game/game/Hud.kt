package com.hadal.game.game

/** Snapshot of everything the HUD draws, copied once per frame under a lock. */
class HudData {
    var hull = 1f; var power = 1f; var oxygen = 1f
    var hullV = 0; var powerV = 0; var oxygenV = 0
    var depth = 0f; var rating = 0f
    var heading = 0f
    var speed = 0f
    var zone = ""
    var objective = ""
    var chapter = ""
    var useLabel = ""
    var scanLabel = ""
    var scanProgress = -1f
    var cargo = 0; var cargoCap = 0
    var outpostKits = 0
    var flares = 0; var kits = 0
    var hasFlares = false; var hasPulse = false
    var lampOn = true
    var noise = 0f
    var fear = 0f
    var damageFlash = 0f
    var lowPower = false
    var lowOxygen = false
    var pressure = false
    var docked = false
    var dead = false
    var fps = 0
    var message: Message? = null
    var messageAlpha = 0f
    var toast = ""
    var toastAlpha = 0f
    var sonarRange = 90f
    var pingAge = 99f
    /** Radar blips relative to the sub: dx, dz, dy, kind (0 resource, 1 creature, 2 hostile, 3 poi, 4 echo, 5 station). */
    var blips = FloatArray(0)
    var blipCount = 0
    var stationDx = 0f; var stationDz = 0f
    var goalHint = ""
    var goalOn = false; var goalDx = 0f; var goalDz = 0f; var goalWhere = ""
    var trackOn = false; var trackDx = 0f; var trackDz = 0f; var trackLabel = ""; var trackWhere = ""
    var loading = false
    var loadProgress = 0f

    fun copyFrom(o: HudData) {
        hull = o.hull; power = o.power; oxygen = o.oxygen; hullV = o.hullV; powerV = o.powerV; oxygenV = o.oxygenV
        depth = o.depth; rating = o.rating; heading = o.heading; speed = o.speed; zone = o.zone; objective = o.objective; chapter = o.chapter
        outpostKits = o.outpostKits; useLabel = o.useLabel; scanLabel = o.scanLabel; scanProgress = o.scanProgress; cargo = o.cargo; cargoCap = o.cargoCap
        flares = o.flares; kits = o.kits; hasFlares = o.hasFlares; hasPulse = o.hasPulse; lampOn = o.lampOn; noise = o.noise; fear = o.fear
        damageFlash = o.damageFlash; lowPower = o.lowPower; lowOxygen = o.lowOxygen; pressure = o.pressure; docked = o.docked; dead = o.dead
        fps = o.fps; message = o.message; messageAlpha = o.messageAlpha; toast = o.toast; toastAlpha = o.toastAlpha
        sonarRange = o.sonarRange; pingAge = o.pingAge
        if (blips.size < o.blipCount * 4) blips = FloatArray(o.blips.size)
        System.arraycopy(o.blips, 0, blips, 0, o.blipCount * 4)
        blipCount = o.blipCount; stationDx = o.stationDx; stationDz = o.stationDz
        goalHint = o.goalHint; goalOn = o.goalOn; goalDx = o.goalDx; goalDz = o.goalDz; goalWhere = o.goalWhere
        trackOn = o.trackOn; trackDx = o.trackDx; trackDz = o.trackDz; trackLabel = o.trackLabel; trackWhere = o.trackWhere
        loading = o.loading; loadProgress = o.loadProgress
    }
}
