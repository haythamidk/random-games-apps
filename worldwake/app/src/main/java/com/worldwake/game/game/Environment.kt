package com.worldwake.game.game

import com.worldwake.game.engine.TWO_PI
import com.worldwake.game.engine.clamp01
import com.worldwake.game.engine.lerp
import com.worldwake.game.engine.smoothstep
import com.worldwake.game.world.Planet
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sky, light and weather. Everything follows the planet stage: the dead world has a black sky, hard light and no
 * weather; clouds, rain and a coloured sky arrive as the Wake Index climbs.
 */
class Environment {
    /** 0..1, 0 = midnight, 0.5 = noon. */
    var timeOfDay = 0.34f
    val v = Planet.Visuals()

    val sunDir = FloatArray(3)
    val lightDir = FloatArray(3)
    val sunColor = FloatArray(3)
    val skyAmb = FloatArray(3)
    val groundAmb = FloatArray(3)
    val zenith = FloatArray(3)
    val horizon = FloatArray(3)
    val sunset = FloatArray(3)
    val fogColor = FloatArray(3)
    val moonDir get() = Planet.giantDir
    var fogDensity = 0.0003f
    var exposure = 1f
    var flash = 0f
    var wetness = 0f
    var rain = 0f
    var wind = 0.3f
    var clouds = 0f
    var nightFactor = 0f
    var dayFactor = 1f
    var golden = 0f
    var starVis = 1f
    var giantVis = 1f
    var aurora = 0f
    /** Blowing ice crystals over the frozen world. */
    var drift = 0f
    var thunderPending = false

    private var weatherTimer = 240f
    private var raining = false
    private var storm = false
    private var rainTarget = 0f
    private var cloudTarget = 0f
    private var lightningTimer = 8f
    private var t = 0f
    private var seed = 99173
    private val tmp = FloatArray(3)
    private val tmp2 = FloatArray(3)

    private fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xffff) / 65535f }

    fun update(dt: Float, stage: Float, dayLengthSec: Float, advance: Boolean) {
        Planet.visuals(stage, v)
        t += dt
        if (advance) {
            timeOfDay += dt / dayLengthSec
            if (timeOfDay >= 1f) timeOfDay -= 1f
        }
        val a = (timeOfDay - 0.25f) * TWO_PI
        val elev = sin(a)
        var sx = -cos(a) * 0.86f; var sy = elev; var sz = 0.34f * (0.4f + 0.6f * cos(a) * cos(a)) + 0.12f
        var l = sqrt(sx * sx + sy * sy + sz * sz)
        sx /= l; sy /= l; sz /= l
        sunDir[0] = sx; sunDir[1] = sy; sunDir[2] = sz
        dayFactor = smoothstep(-0.08f, 0.16f, sy)
        nightFactor = 1f - dayFactor
        golden = smoothstep(0.42f, 0.02f, kotlin.math.abs(sy)) * smoothstep(-0.2f, 0f, sy)

        // weather
        updateWeather(dt)

        // sky colours
        Planet.dayZenith(stage, tmp)
        Planet.dayHorizon(stage, tmp2)
        val atmo = v.atmo
        val dim = 1f - clouds * 0.45f
        for (c in 0 until 3) {
            val nz = NIGHT_Z[c] + atmo * NIGHT_ZA[c]
            val nh = NIGHT_H[c] + atmo * NIGHT_HA[c]
            zenith[c] = lerp(nz, tmp[c] * dim, dayFactor)
            horizon[c] = lerp(nh, tmp2[c] * dim, dayFactor)
        }
        // overcast greys the sky a little
        if (clouds > 0.5f) {
            val g = (clouds - 0.5f) * 1.2f
            val zl = (zenith[0] + zenith[1] + zenith[2]) / 3f
            val hl = (horizon[0] + horizon[1] + horizon[2]) / 3f
            for (c in 0 until 3) { zenith[c] = lerp(zenith[c], zl * 1.1f, g * 0.6f); horizon[c] = lerp(horizon[c], hl, g * 0.6f) }
        }
        Planet.sunsetColor(stage, tmp)
        val glow = golden * sqrt(atmo) * (1f - clouds * 0.6f)
        for (c in 0 until 3) sunset[c] = lerp(horizon[c], tmp[c], glow)

        // light: the sun by day, giant-shine at night
        Planet.sunColor(stage, tmp)
        if (sy > 0.02f) {
            lightDir[0] = sx; lightDir[1] = sy; lightDir[2] = sz
            val k = smoothstep(0.02f, 0.2f, sy) * (1f - clouds * 0.7f)
            val warm = golden * sqrt(atmo)
            sunColor[0] = tmp[0] * k * lerp(1f, 1.1f, warm)
            sunColor[1] = tmp[1] * k * lerp(1f, 0.72f, warm)
            sunColor[2] = tmp[2] * k * lerp(1f, 0.5f, warm)
        } else {
            val g = Planet.giantDir
            lightDir[0] = g[0]; lightDir[1] = g[1]; lightDir[2] = g[2]
            // the giant shows a fuller face when the sun is opposite it
            val phase = clamp01(0.5f - 0.5f * (sx * g[0] + sy * g[1] + sz * g[2]) + 0.25f)
            val k = smoothstep(0.02f, -0.12f, sy) * (0.45f + 0.55f * phase) * (1f - clouds * 0.6f)
            sunColor[0] = 0.2f * k; sunColor[1] = 0.17f * k; sunColor[2] = 0.27f * k
        }
        // ambient: sky light plus light bounced off the bright ground, so vacuum shadows are dark but readable
        val bounce = dayFactor * (1f - clouds * 0.5f)
        for (c in 0 until 3) {
            skyAmb[c] = zenith[c] * 0.95f + horizon[c] * 0.4f + tmp[c] * bounce * 0.016f + GIANT_AMB[c] * (0.35f + 0.65f * nightFactor)
            groundAmb[c] = horizon[c] * 0.22f + tmp[c] * bounce * BOUNCE[c] * lerp(0.05f, 0.03f, atmo) + GIANT_AMB[c] * 0.5f
            fogColor[c] = horizon[c] * 0.9f
        }
        fogDensity = lerp(0.00022f, 0.0015f, atmo) + rain * 0.0045f + clouds * 0.0005f
        exposure = lerp(1.05f, 1.9f, nightFactor)
        starVis = max(nightFactor, v.starDay) * (1f - clouds * 0.9f)
        giantVis = lerp(1f, 0.6f, atmo * dayFactor) * (1f - clouds * 0.75f)
        aurora = v.aurora * smoothstep(0.55f, 0.95f, nightFactor)
        wind = clamp01(0.22f + 0.14f * sin(t * 0.05f) + 0.1f * sin(t * 0.13f + 1.7f) + rain * 0.4f + (if (storm) 0.25f else 0f))
        drift = v.frost * (0.25f + wind * 0.9f) * (1f - rain)
        flash = max(0f, flash - dt * 3.5f)
    }

    private fun updateWeather(dt: Float) {
        val base = v.cloudBase
        if (base <= 0.001f) {
            clouds = max(0f, clouds - dt * 0.05f); rain = max(0f, rain - dt * 0.1f); wetness = max(0f, wetness - dt * 0.01f)
            raining = false; storm = false
            return
        }
        weatherTimer -= dt
        if (weatherTimer <= 0f) {
            if (raining) {
                raining = false; storm = false
                rainTarget = 0f
                cloudTarget = base * (0.5f + rnd() * 1.1f)
                weatherTimer = 300f + rnd() * 520f
            } else if (v.rainAllowed && rnd() < 0.62f) {
                raining = true
                storm = rnd() < 0.3f
                rainTarget = if (storm) 0.8f + rnd() * 0.2f else 0.35f + rnd() * 0.4f
                cloudTarget = 0.85f + rnd() * 0.15f
                weatherTimer = 110f + rnd() * 170f
            } else {
                cloudTarget = base * (0.4f + rnd() * 1.5f)
                weatherTimer = 200f + rnd() * 300f
            }
        }
        if (cloudTarget <= 0f) cloudTarget = base
        clouds += (clamp01(cloudTarget) - clouds) * clamp01(dt * 0.03f)
        val rt = if (clouds > 0.6f) rainTarget else 0f
        rain += (rt - rain) * clamp01(dt * 0.12f)
        if (rain > 0.05f) wetness = clamp01(wetness + dt * 0.03f * rain) else wetness = max(0f, wetness - dt * 0.004f)
        if (storm && rain > 0.5f) {
            lightningTimer -= dt
            if (lightningTimer <= 0f) {
                lightningTimer = 6f + rnd() * 16f
                flash = 1f
                thunderPending = true
            }
        }
    }

    /** Forces a weather roll soon (used when a stage first allows rain, so the player sees it). */
    fun nudgeWeather(rainNow: Boolean) {
        if (rainNow) {
            raining = true; storm = false
            rainTarget = 0.55f; cloudTarget = 0.92f
            weatherTimer = 150f
        } else weatherTimer = minOf(weatherTimer, 20f)
    }

    companion object {
        private val NIGHT_Z = floatArrayOf(0.0012f, 0.0015f, 0.005f)
        private val NIGHT_ZA = floatArrayOf(0.002f, 0.004f, 0.01f)
        private val NIGHT_H = floatArrayOf(0.003f, 0.0035f, 0.01f)
        private val NIGHT_HA = floatArrayOf(0.008f, 0.012f, 0.028f)
        private val GIANT_AMB = floatArrayOf(0.007f, 0.006f, 0.011f)
        private val BOUNCE = floatArrayOf(0.85f, 0.85f, 1f)
    }
}
