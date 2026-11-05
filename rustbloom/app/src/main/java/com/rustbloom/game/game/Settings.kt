package com.rustbloom.game.game

import android.content.Context

enum class GraphicsPreset(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High"), ULTRA("Ultra"), CUSTOM("Custom") }
enum class ControlScheme(val label: String) { TOUCH("Touch"), CONTROLLER("Controller"), KEYBOARD_MOUSE("Keyboard & Mouse") }
enum class Difficulty(val label: String, val damageTaken: Float, val drain: Float, val damageDealt: Float) {
    STORY("Story", 0.5f, 0.6f, 1.4f),
    NORMAL("Normal", 1f, 1f, 1f),
    HARD("Hard", 1.5f, 1.3f, 0.85f),
}

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

    var controls = ControlScheme.TOUCH
    var lookSensitivity = 1f
    var invertY = false
    var vibration = true
    var autoSprint = true
    var touchOpacity = 0.6f
    var masterVolume = 0.8f
    var dayLengthMinutes = 30

    fun applyPreset(p: GraphicsPreset) {
        preset = p
        when (p) {
            GraphicsPreset.LOW -> { renderScale = 0.6f; viewDistance = 3; shadowQuality = 0; shadowRes = 1024; decorDensity = 0.3f; waterQuality = 0; terrainDetail = 0; clouds = false; weatherParticles = true; fpsCap = 60 }
            GraphicsPreset.MEDIUM -> { renderScale = 0.75f; viewDistance = 3; shadowQuality = 1; shadowRes = 1024; decorDensity = 0.55f; waterQuality = 1; terrainDetail = 0; clouds = true; weatherParticles = true; fpsCap = 60 }
            GraphicsPreset.HIGH -> { renderScale = 0.85f; viewDistance = 4; shadowQuality = 2; shadowRes = 2048; decorDensity = 0.8f; waterQuality = 1; terrainDetail = 1; clouds = true; weatherParticles = true; fpsCap = 60 }
            GraphicsPreset.ULTRA -> { renderScale = 1.0f; viewDistance = 5; shadowQuality = 2; shadowRes = 2048; decorDensity = 1f; waterQuality = 1; terrainDetail = 1; clouds = true; weatherParticles = true; fpsCap = 60 }
            GraphicsPreset.CUSTOM -> {}
        }
    }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!p.contains("preset")) { applyPreset(GraphicsPreset.HIGH); return }
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
        controls = ControlScheme.entries.getOrElse(p.getInt("controls", 0)) { ControlScheme.TOUCH }
        lookSensitivity = p.getFloat("lookSensitivity", lookSensitivity)
        invertY = p.getBoolean("invertY", invertY)
        vibration = p.getBoolean("vibration", vibration)
        autoSprint = p.getBoolean("autoSprint", autoSprint)
        touchOpacity = p.getFloat("touchOpacity", touchOpacity)
        masterVolume = p.getFloat("masterVolume", masterVolume)
        dayLengthMinutes = p.getInt("dayLength", dayLengthMinutes)
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
            .putInt("controls", controls.ordinal)
            .putFloat("lookSensitivity", lookSensitivity)
            .putBoolean("invertY", invertY)
            .putBoolean("vibration", vibration)
            .putBoolean("autoSprint", autoSprint)
            .putFloat("touchOpacity", touchOpacity)
            .putFloat("masterVolume", masterVolume)
            .putInt("dayLength", dayLengthMinutes)
            .apply()
    }
}
