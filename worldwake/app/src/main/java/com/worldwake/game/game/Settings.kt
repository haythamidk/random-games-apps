package com.worldwake.game.game

import android.content.Context

enum class GraphicsPreset(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High"), ULTRA("Ultra"), CUSTOM("Custom") }
enum class ControlScheme(val label: String) { TOUCH("Touch"), CONTROLLER("Controller"), KEYBOARD_MOUSE("Keyboard & Mouse") }
/** User settings persisted in SharedPreferences. */
class Settings {
    var preset = GraphicsPreset.HIGH
    var renderScale = 0.85f
    var viewDistance = 4            // chunk radius (64m chunks)
    var shadowQuality = 2           // 0 off, 1 hard, 2 soft
    var shadowRes = 2048
    var decorDensity = 0.8f
    var waterQuality = 1
    var terrainDetail = 1           // 1 = 1m near terrain
    var clouds = true
    var weatherParticles = true
    var fpsCap = 60
    var showFps = false
    var fov = 70f
    var postFx = true          // HDR + bloom + filmic tonemapping
    var fxaa = false
    var dynamicRes = true      // scales resolution to hold the frame rate (post FX path only)
    var texQuality = 2         // 0 flat colors, 1 textures, 2 textures + normal maps + triplanar
    var foliage = 1            // 0 solid canopies, 1 leaf cutouts, 2 leaf cutouts in shadows too
    var grassDistance = 50f
    var bloom = 0.6f

    var controls = ControlScheme.TOUCH
    var lookSensitivity = 1f
    var invertY = false
    var vibration = true
    var autoSprint = true
    var touchOpacity = 0.6f
    var masterVolume = 0.8f
    var dayLengthMinutes = 24
    var musicVolume = 0.7f
    var subtitles = true
    var wrenVoice = true
    var padLookSpeed = 1f
    /** 0 off, 1 FXAA, 2 MSAA 4x with FXAA. */
    var antiAlias = 2

    fun applyPreset(p: GraphicsPreset) {
        preset = p
        when (p) {
            GraphicsPreset.LOW -> { renderScale = 0.6f; viewDistance = 3; shadowQuality = 0; shadowRes = 1024; decorDensity = 0.3f; waterQuality = 0; terrainDetail = 0; clouds = false; weatherParticles = true; fpsCap = 60
                postFx = false; fxaa = false; dynamicRes = false; texQuality = 0; foliage = 0; grassDistance = 28f; bloom = 0.5f }
            GraphicsPreset.MEDIUM -> { renderScale = 0.75f; viewDistance = 3; shadowQuality = 1; shadowRes = 1024; decorDensity = 0.5f; waterQuality = 1; terrainDetail = 0; clouds = true; weatherParticles = true; fpsCap = 60
                postFx = false; fxaa = false; dynamicRes = false; texQuality = 1; foliage = 1; grassDistance = 38f; bloom = 0.5f }
            GraphicsPreset.HIGH -> { renderScale = 0.85f; viewDistance = 4; shadowQuality = 2; shadowRes = 2048; decorDensity = 0.75f; waterQuality = 1; terrainDetail = 1; clouds = true; weatherParticles = true; fpsCap = 60
                postFx = true; fxaa = false; dynamicRes = true; texQuality = 2; foliage = 1; grassDistance = 50f; bloom = 0.6f }
            GraphicsPreset.ULTRA -> { renderScale = 1.0f; viewDistance = 6; shadowQuality = 3; shadowRes = 2048; decorDensity = 1f; waterQuality = 1; terrainDetail = 1; clouds = true; weatherParticles = true; fpsCap = 60
                postFx = true; fxaa = true; dynamicRes = false; texQuality = 2; foliage = 2; grassDistance = 72f; bloom = 0.7f }
            GraphicsPreset.CUSTOM -> {}
        }
    }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!p.contains("preset") || !p.contains("postFx")) { applyPreset(GraphicsPreset.HIGH); return }
        preset = GraphicsPreset.entries.getOrElse(p.getInt("preset", 2)) { GraphicsPreset.HIGH }
        renderScale = p.getFloat("renderScale", renderScale)
        viewDistance = p.getInt("viewDistance", viewDistance)
        shadowQuality = p.getInt("shadowQuality", shadowQuality)
        shadowRes = p.getInt("shadowRes", shadowRes)
        decorDensity = p.getFloat("decorDensity", decorDensity)
        waterQuality = p.getInt("waterQuality", waterQuality)
        terrainDetail = p.getInt("terrainDetail", terrainDetail)
        clouds = p.getBoolean("clouds", clouds)
        weatherParticles = p.getBoolean("weatherParticles", weatherParticles)
        fpsCap = p.getInt("fpsCap", fpsCap)
        showFps = p.getBoolean("showFps", showFps)
        fov = p.getFloat("fov", fov)
        postFx = p.getBoolean("postFx", postFx)
        fxaa = p.getBoolean("fxaa", fxaa)
        dynamicRes = p.getBoolean("dynamicRes", dynamicRes)
        texQuality = p.getInt("texQuality", texQuality)
        foliage = p.getInt("foliage", foliage)
        grassDistance = p.getFloat("grassDistance", grassDistance)
        bloom = p.getFloat("bloom", bloom)
        controls = ControlScheme.entries.getOrElse(p.getInt("controls", 0)) { ControlScheme.TOUCH }
        lookSensitivity = p.getFloat("lookSensitivity", lookSensitivity)
        invertY = p.getBoolean("invertY", invertY)
        vibration = p.getBoolean("vibration", vibration)
        autoSprint = p.getBoolean("autoSprint", autoSprint)
        touchOpacity = p.getFloat("touchOpacity", touchOpacity)
        masterVolume = p.getFloat("masterVolume", masterVolume)
        dayLengthMinutes = p.getInt("dayLength", dayLengthMinutes)
        musicVolume = p.getFloat("musicVolume", musicVolume)
        subtitles = p.getBoolean("subtitles", subtitles)
        wrenVoice = p.getBoolean("wrenVoice", wrenVoice)
        padLookSpeed = p.getFloat("padLookSpeed", padLookSpeed)
        antiAlias = p.getInt("antiAlias", antiAlias)
        fxaa = antiAlias >= 1
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putInt("preset", preset.ordinal)
            .putFloat("renderScale", renderScale)
            .putInt("viewDistance", viewDistance)
            .putInt("shadowQuality", shadowQuality)
            .putInt("shadowRes", shadowRes)
            .putFloat("decorDensity", decorDensity)
            .putInt("waterQuality", waterQuality)
            .putInt("terrainDetail", terrainDetail)
            .putBoolean("clouds", clouds)
            .putBoolean("weatherParticles", weatherParticles)
            .putInt("fpsCap", fpsCap)
            .putBoolean("showFps", showFps)
            .putFloat("fov", fov)
            .putBoolean("postFx", postFx)
            .putBoolean("fxaa", fxaa)
            .putBoolean("dynamicRes", dynamicRes)
            .putInt("texQuality", texQuality)
            .putInt("foliage", foliage)
            .putFloat("grassDistance", grassDistance)
            .putFloat("bloom", bloom)
            .putInt("controls", controls.ordinal)
            .putFloat("lookSensitivity", lookSensitivity)
            .putBoolean("invertY", invertY)
            .putBoolean("vibration", vibration)
            .putBoolean("autoSprint", autoSprint)
            .putFloat("touchOpacity", touchOpacity)
            .putFloat("masterVolume", masterVolume)
            .putInt("dayLength", dayLengthMinutes)
            .putFloat("musicVolume", musicVolume)
            .putBoolean("subtitles", subtitles)
            .putBoolean("wrenVoice", wrenVoice)
            .putFloat("padLookSpeed", padLookSpeed)
            .putInt("antiAlias", antiAlias)
            .apply()
    }
}
