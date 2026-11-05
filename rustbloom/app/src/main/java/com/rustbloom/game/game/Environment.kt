package com.rustbloom.game.game

import com.rustbloom.game.engine.Rng
import com.rustbloom.game.engine.TWO_PI
import com.rustbloom.game.engine.approach
import com.rustbloom.game.engine.clamp01
import com.rustbloom.game.engine.lerp
import com.rustbloom.game.engine.smoothstep
import com.rustbloom.game.world.Biome
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class Weather(val label: String, val clouds: Float, val rain: Float, val fog: Float, val wind: Float) {
    CLEAR("Clear", 0.1f, 0f, 0f, 0.2f),
    CLOUDY("Overcast", 0.65f, 0f, 0.15f, 0.45f),
    RAIN("Rain", 0.85f, 0.7f, 0.35f, 0.6f),
    STORM("Storm", 1f, 1f, 0.45f, 1f),
    FOG("Fog", 0.5f, 0f, 1f, 0.1f),
}

/** Time of day, sun/moon, sky colors and weather. All colors are linear RGB. */
class Environment {
    /** Hours 0..24 */
    var time = 7.5f
    var day = 1
    var weather = Weather.CLEAR
    var nextWeatherIn = 6f // in game hours
    private val rng = Rng(System.nanoTime())

    // smoothed weather parameters
    var clouds = 0.1f
    var rain = 0f
    var fog = 0f
    var wind = 0.2f
    var flash = 0f
    var flashTimer = 8f
    var thunderPending = -1f
    var snowing = false
    var wetness = 0f

    val sunDir = FloatArray(3)
    val moonDir = FloatArray(3)
    val lightDir = FloatArray(3)
    val sunColor = FloatArray(3)
    val skyAmb = FloatArray(3)
    val groundAmb = FloatArray(3)
    val zenith = FloatArray(3)
    val horizon = FloatArray(3)
    val fogColor = FloatArray(3)
    val fogSunColor = FloatArray(3)
    var fogDensity = 0.002f
    var nightFactor = 0f
    var exposure = 1f

    val totalDays: Double get() = day + time / 24.0
    val isNight get() = time < 5.5f || time > 19.5f

    /** Advances time. [dt] real seconds, [dayLengthMin] real minutes per in-game day. */
    fun update(dt: Float, dayLengthMin: Int, biome: Biome, cold: Float) {
        val hours = dt * 24f / (dayLengthMin * 60f)
        advanceHours(hours)
        // weather target smoothing
        val k = dt * 0.05f
        clouds = approach(clouds, weather.clouds, k)
        rain = approach(rain, if (biome == Biome.DESERT) weather.rain * 0.2f else weather.rain, k)
        fog = approach(fog, weather.fog + (if (biome == Biome.MARSH) 0.35f else 0f) + (if (time in 4.5f..7.5f) 0.25f else 0f), k)
        wind = approach(wind, weather.wind, k)
        snowing = cold > 0.45f || biome == Biome.SNOW
        wetness = approach(wetness, if (rain > 0.2f && !snowing) 1f else 0f, dt * (if (rain > 0.2f) 0.02f else 0.008f))
        // lightning
        if (weather == Weather.STORM && rain > 0.6f) {
            flashTimer -= dt
            if (flashTimer <= 0f) {
                flash = 1f
                flashTimer = 6f + rng.nextFloat() * 14f
                thunderPending = 0.6f + rng.nextFloat() * 2f
            }
        }
        flash = max(0f, flash - dt * 3.5f)
        if (thunderPending > 0f) thunderPending -= dt
        computeLighting()
    }

    fun advanceHours(h: Float) {
        time += h
        while (time >= 24f) { time -= 24f; day++ }
        nextWeatherIn -= h
        if (nextWeatherIn <= 0f) {
            val r = rng.nextFloat()
            weather = when {
                r < 0.42f -> Weather.CLEAR
                r < 0.64f -> Weather.CLOUDY
                r < 0.82f -> Weather.RAIN
                r < 0.9f -> Weather.STORM
                else -> Weather.FOG
            }
            nextWeatherIn = 4f + rng.nextFloat() * 10f
        }
    }

    fun forceWeather(w: Weather) { weather = w; clouds = w.clouds; rain = w.rain; fog = w.fog; wind = w.wind }

    private fun set(a: FloatArray, r: Float, g: Float, b: Float) { a[0] = r; a[1] = g; a[2] = b }
    private fun mix(out: FloatArray, a: FloatArray, b: FloatArray, t: Float) { for (i in 0..2) out[i] = lerp(a[i], b[i], t) }

    private val tA = FloatArray(3)
    private val tB = FloatArray(3)

    fun computeLighting() {
        val ang = (time / 24f) * TWO_PI - TWO_PI / 4f
        val sx = cos(ang); val sy = sin(ang); val sz = 0.32f
        val l = sqrt(sx * sx + sy * sy + sz * sz)
        set(sunDir, sx / l, sy / l, sz / l)
        set(moonDir, -sunDir[0], -sunDir[1], -sunDir[2] * 0.6f + 0.2f)
        val elev = sunDir[1]
        val day = smoothstep(-0.12f, 0.25f, elev)
        val golden = smoothstep(0.35f, 0.02f, elev) * smoothstep(-0.12f, 0.04f, elev)
        nightFactor = smoothstep(0.02f, -0.22f, elev)
        // the light direction switches to the moon at night
        if (elev > -0.05f) { lightDir[0] = sunDir[0]; lightDir[1] = max(sunDir[1], 0.06f); lightDir[2] = sunDir[2] }
        else { lightDir[0] = moonDir[0]; lightDir[1] = max(moonDir[1], 0.2f); lightDir[2] = moonDir[2] }
        val nl = sqrt(lightDir[0] * lightDir[0] + lightDir[1] * lightDir[1] + lightDir[2] * lightDir[2])
        for (i in 0..2) lightDir[i] /= nl

        val overcast = clouds
        // sun color: warm at golden hour, white at noon, cool moonlight at night
        set(tA, 2.6f, 2.45f, 2.2f)
        set(tB, 2.4f, 1.3f, 0.55f)
        mix(sunColor, tA, tB, golden)
        val sunStrength = day * (1f - overcast * 0.75f)
        for (i in 0..2) sunColor[i] *= sunStrength
        val moon = nightFactor * (1f - overcast * 0.6f)
        sunColor[0] += 0.10f * moon; sunColor[1] += 0.14f * moon; sunColor[2] += 0.24f * moon

        // sky dome
        set(tA, 0.16f, 0.36f, 0.78f)  // day zenith
        set(tB, 0.012f, 0.018f, 0.045f) // night zenith
        mix(zenith, tB, tA, day)
        set(tA, 0.62f, 0.74f, 0.9f)
        set(tB, 0.025f, 0.035f, 0.07f)
        mix(horizon, tB, tA, day)
        // golden hour horizon
        horizon[0] = lerp(horizon[0], 1.0f, golden * 0.7f)
        horizon[1] = lerp(horizon[1], 0.5f, golden * 0.6f)
        horizon[2] = lerp(horizon[2], 0.3f, golden * 0.6f)
        // overcast: grey it out
        val grey = 0.45f * day + 0.03f
        for (i in 0..2) {
            zenith[i] = lerp(zenith[i], grey * 0.85f, overcast * 0.8f)
            horizon[i] = lerp(horizon[i], grey, overcast * 0.75f)
        }

        // ambient light
        for (i in 0..2) {
            skyAmb[i] = lerp(zenith[i], horizon[i], 0.5f) * 0.9f + 0.015f
            groundAmb[i] = skyAmb[i] * 0.35f
        }
        groundAmb[0] += 0.02f * day; groundAmb[1] += 0.018f * day

        // fog follows the horizon color
        for (i in 0..2) fogColor[i] = horizon[i] * 0.95f
        fogSunColor[0] = lerp(fogColor[0], sunColor[0] * 0.5f + fogColor[0], 0.6f)
        fogSunColor[1] = lerp(fogColor[1], sunColor[1] * 0.45f + fogColor[1], 0.6f)
        fogSunColor[2] = lerp(fogColor[2], sunColor[2] * 0.4f + fogColor[2], 0.6f)
        fogDensity = 0.0016f + fog * 0.012f + rain * 0.003f + nightFactor * 0.0006f
        exposure = lerp(1.0f, 1.6f, nightFactor) * (1f + flash * 0.4f)
    }

    /** Ambient air temperature modifier from time of day and weather. */
    fun temperatureOffset(biome: Biome): Float {
        val dayCurve = sin(((time - 9f) / 24f) * TWO_PI) // peak mid afternoon
        val swing = if (biome == Biome.DESERT) 14f else 6f
        return dayCurve * swing - rain * 5f - wind * 2f
    }

    fun clamp01Night() = clamp01(nightFactor)

    init { computeLighting() }
}
