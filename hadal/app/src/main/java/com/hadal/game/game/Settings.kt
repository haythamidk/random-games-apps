package com.hadal.game.game

import android.content.Context

enum class GraphicsPreset(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High"), ULTRA("Ultra"), CUSTOM("Custom") }
enum class ControlScheme(val label: String) { TOUCH("Touch"), CONTROLLER("Controller"), KEYBOARD_MOUSE("Keyboard & Mouse") }
enum class Difficulty(val label: String, val damage: Float, val drain: Float, val predators: Float) {
    CALM("Calm", 0.5f, 0.7f, 0.6f),
    NORMAL("Normal", 1f, 1f, 1f),
    ABYSSAL("Abyssal", 1.5f, 1.25f, 1.4f),
}

/** User settings persisted in SharedPreferences. */
class Settings {
    var preset = GraphicsPreset.HIGH
    var renderScale = 0.8f
    var viewDistance = 3        // chunk radius (64 m chunks)
    var shadows = 1             // lamp shadows: 0 off, 1 on, 2 soft high res
    var volumetrics = 1         // light shafts and lamp beams: 0 off, 1 normal, 2 high
    var particles = 1           // marine snow: 0 off, 1 normal, 2 dense
    var floraDensity = 0.8f
    var texQuality = 2          // 0 flat, 1 textures, 2 textures + normal maps + triplanar
    var caustics = true
    var postFx = true
    var bloom = 0.7f
    var fxaa = false
    var dynamicRes = true
    var fpsCap = 60
    var showFps = false
    var fov = 72f

    var controls = ControlScheme.TOUCH
    var lookSensitivity = 1f
    var invertY = false
    var vibration = true
    var touchOpacity = 0.6f
    var cockpitCamera = false
    var difficulty = Difficulty.NORMAL
    var masterVolume = 0.85f
    var musicVolume = 0.6f

    fun applyPreset(p: GraphicsPreset) {
        preset = p
        when (p) {
            GraphicsPreset.LOW -> { renderScale = 0.6f; viewDistance = 2; shadows = 0; volumetrics = 0; particles = 1; floraDensity = 0.4f; texQuality = 0; caustics = false; postFx = false; bloom = 0.5f; fxaa = false; dynamicRes = false }
            GraphicsPreset.MEDIUM -> { renderScale = 0.7f; viewDistance = 2; shadows = 0; volumetrics = 1; particles = 1; floraDensity = 0.6f; texQuality = 1; caustics = true; postFx = true; bloom = 0.6f; fxaa = false; dynamicRes = true }
            GraphicsPreset.HIGH -> { renderScale = 0.8f; viewDistance = 3; shadows = 1; volumetrics = 1; particles = 1; floraDensity = 0.8f; texQuality = 2; caustics = true; postFx = true; bloom = 0.7f; fxaa = false; dynamicRes = true }
            GraphicsPreset.ULTRA -> { renderScale = 1f; viewDistance = 3; shadows = 2; volumetrics = 2; particles = 2; floraDensity = 1f; texQuality = 2; caustics = true; postFx = true; bloom = 0.75f; fxaa = true; dynamicRes = false }
            GraphicsPreset.CUSTOM -> {}
        }
    }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!p.contains("preset")) { applyPreset(GraphicsPreset.HIGH); return }
        preset = GraphicsPreset.entries.getOrElse(p.getInt("preset", 2)) { GraphicsPreset.HIGH }
        renderScale = p.getFloat("renderScale", renderScale)
        viewDistance = p.getInt("viewDistance", viewDistance)
        shadows = p.getInt("shadows", shadows)
        volumetrics = p.getInt("volumetrics", volumetrics)
        particles = p.getInt("particles", particles)
        floraDensity = p.getFloat("floraDensity", floraDensity)
        texQuality = p.getInt("texQuality", texQuality)
        caustics = p.getBoolean("caustics", caustics)
        postFx = p.getBoolean("postFx", postFx)
        bloom = p.getFloat("bloom", bloom)
        fxaa = p.getBoolean("fxaa", fxaa)
        dynamicRes = p.getBoolean("dynamicRes", dynamicRes)
        fpsCap = p.getInt("fpsCap", fpsCap)
        showFps = p.getBoolean("showFps", showFps)
        fov = p.getFloat("fov", fov)
        controls = ControlScheme.entries.getOrElse(p.getInt("controls", 0)) { ControlScheme.TOUCH }
        lookSensitivity = p.getFloat("lookSensitivity", lookSensitivity)
        invertY = p.getBoolean("invertY", invertY)
        vibration = p.getBoolean("vibration", vibration)
        touchOpacity = p.getFloat("touchOpacity", touchOpacity)
        cockpitCamera = p.getBoolean("cockpit", cockpitCamera)
        difficulty = Difficulty.entries.getOrElse(p.getInt("difficulty", 1)) { Difficulty.NORMAL }
        masterVolume = p.getFloat("masterVolume", masterVolume)
        musicVolume = p.getFloat("musicVolume", musicVolume)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putInt("preset", preset.ordinal).putFloat("renderScale", renderScale).putInt("viewDistance", viewDistance)
            .putInt("shadows", shadows).putInt("volumetrics", volumetrics).putInt("particles", particles)
            .putFloat("floraDensity", floraDensity).putInt("texQuality", texQuality).putBoolean("caustics", caustics)
            .putBoolean("postFx", postFx).putFloat("bloom", bloom).putBoolean("fxaa", fxaa).putBoolean("dynamicRes", dynamicRes)
            .putInt("fpsCap", fpsCap).putBoolean("showFps", showFps).putFloat("fov", fov)
            .putInt("controls", controls.ordinal).putFloat("lookSensitivity", lookSensitivity).putBoolean("invertY", invertY)
            .putBoolean("vibration", vibration).putFloat("touchOpacity", touchOpacity).putBoolean("cockpit", cockpitCamera)
            .putInt("difficulty", difficulty.ordinal).putFloat("masterVolume", masterVolume).putFloat("musicVolume", musicVolume)
            .apply()
    }
}
