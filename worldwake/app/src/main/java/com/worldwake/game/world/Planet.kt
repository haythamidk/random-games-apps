package com.worldwake.game.world

import com.worldwake.game.engine.clamp01
import com.worldwake.game.engine.lerp
import com.worldwake.game.engine.smoothstep

/** What a growing prop responds to. */
enum class Grow { NONE, FROST, MOSS, GRASS, FLOWER, BUSH, TREE, REED, FUNGI }

/**
 * Ysmere: a frozen moon in a giant impact basin, under a ringed gas giant. Everything that makes this planet look
 * and progress the way it does lives here, so another planet is another object like this one.
 *
 * Progress is one number, the Wake Index. It maps to a fractional stage s in [0, 11]; every visual derives from s.
 */
object Planet {
    const val NAME = "Ysmere"
    const val GIANT = "Maren"

    val stageNames = arrayOf(
        "Dead World", "Violet Hour", "Thaw", "First Clouds", "Rain", "Lakes",
        "Moss", "Meadow", "Groves", "Motes", "Breathable", "Awake",
    )
    val stageNotes = arrayOf(
        "Frozen, airless, silent.",
        "There is enough air to colour the sky.",
        "The frost is letting go of the ground.",
        "Vapour is gathering into clouds.",
        "It rains. Water can be pulled from the air.",
        "The basins are filling.",
        "Something red is growing on the wet ground.",
        "Grass and flowers spread across the flats.",
        "The first trees stand up.",
        "The air is full of small lights.",
        "You can take the helmet off.",
        "Ysmere is awake.",
    )

    /** Wake Index needed to reach each stage. */
    val thresholds = doubleArrayOf(0.0, 150.0, 1500.0, 12e3, 60e3, 300e3, 1.0e6, 3.6e6, 13e6, 45e6, 115e6, 270e6)
    const val LAST = 11

    fun stageOf(wi: Double): Float {
        if (wi >= thresholds[LAST]) return LAST.toFloat()
        var k = 0
        while (k < LAST - 1 && wi >= thresholds[k + 1]) k++
        val f = ((wi - thresholds[k]) / (thresholds[k + 1] - thresholds[k])).toFloat()
        return k + clamp01(f)
    }

    fun livability(wi: Double): Float = stageOf(wi) / LAST * 100f

    class Visuals {
        var s = 0f
        var frost = 1f
        var atmo = 0f
        var cloudBase = 0f
        var rainAllowed = false
        var waterLevel = -100f
        var moss = 0f
        var grass = 0f
        var flowers = 0f
        var bushes = 0f
        var trees = 0f
        var reeds = 0f
        var fungi = 0f
        var motes = 0f
        var starDay = 1f
        var aurora = 0f
        var breathable = false
        var wake = 0f

        fun grow(g: Grow): Float = when (g) {
            Grow.NONE -> 10f
            Grow.FROST -> frost * 1.2f
            Grow.MOSS -> moss * 1.2f
            Grow.GRASS -> grass * 1.2f
            Grow.FLOWER -> flowers * 1.2f
            Grow.BUSH -> bushes * 1.2f
            Grow.TREE -> trees * 1.2f
            Grow.REED -> reeds * 1.2f
            Grow.FUNGI -> fungi * 1.2f
        }
    }

    fun visuals(s: Float, v: Visuals) {
        v.s = s
        v.frost = 1f - smoothstep(2f, 5f, s)
        v.atmo = smoothstep(0f, 6f, s)
        v.cloudBase = smoothstep(3f, 4.2f, s) * 0.42f
        v.rainAllowed = s >= 4f
        v.waterLevel = if (s < 5f) -100f else lerp(-9f, 0f, smoothstep(5f, 6.3f, s))
        v.moss = clamp01((s - 6f) / 1.6f)
        v.grass = clamp01((s - 7f) / 1.6f)
        v.flowers = clamp01((s - 7.4f) / 1.8f)
        v.bushes = clamp01((s - 7.8f) / 1.6f)
        v.trees = clamp01((s - 8f) / 2.2f)
        v.reeds = clamp01((s - 6.2f) / 1.5f)
        v.fungi = clamp01((s - 6.5f) / 2f)
        v.motes = smoothstep(9f, 10f, s)
        v.starDay = 1f - smoothstep(0.5f, 4f, s)
        v.aurora = 0.9f - 0.5f * smoothstep(6f, 10f, s)
        v.breathable = s >= 10f
        v.wake = smoothstep(10f, 11f, s)
    }

    // ------------------------------------------------------------------ sky keyframes (linear colour)

    private val keyS = floatArrayOf(0f, 1f, 3f, 5f, 7f, 9f, 11f)
    private val zenith = arrayOf(
        floatArrayOf(0.004f, 0.004f, 0.012f), floatArrayOf(0.022f, 0.012f, 0.075f), floatArrayOf(0.065f, 0.035f, 0.19f),
        floatArrayOf(0.1f, 0.1f, 0.3f), floatArrayOf(0.065f, 0.2f, 0.36f), floatArrayOf(0.035f, 0.26f, 0.36f), floatArrayOf(0.022f, 0.3f, 0.38f),
    )
    private val horizon = arrayOf(
        floatArrayOf(0.02f, 0.015f, 0.045f), floatArrayOf(0.1f, 0.05f, 0.2f), floatArrayOf(0.3f, 0.14f, 0.34f),
        floatArrayOf(0.5f, 0.33f, 0.46f), floatArrayOf(0.55f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.62f, 0.55f), floatArrayOf(0.48f, 0.7f, 0.6f),
    )
    private val sunset = arrayOf(
        floatArrayOf(0.12f, 0.1f, 0.3f), floatArrayOf(0.5f, 0.2f, 0.6f), floatArrayOf(0.9f, 0.3f, 0.6f),
        floatArrayOf(1f, 0.4f, 0.5f), floatArrayOf(1f, 0.48f, 0.38f), floatArrayOf(1f, 0.52f, 0.34f), floatArrayOf(1f, 0.55f, 0.32f),
    )
    private val sun = arrayOf(
        floatArrayOf(2.15f, 2.3f, 2.8f), floatArrayOf(2.15f, 2.25f, 2.7f), floatArrayOf(2.15f, 2.15f, 2.45f),
        floatArrayOf(2.15f, 2.1f, 2.2f), floatArrayOf(2.2f, 2.08f, 1.95f), floatArrayOf(2.25f, 2.1f, 1.8f), floatArrayOf(2.3f, 2.1f, 1.75f),
    )

    private fun key(table: Array<FloatArray>, s: Float, out: FloatArray) {
        var i = 0
        while (i < keyS.size - 2 && s >= keyS[i + 1]) i++
        val t = clamp01((s - keyS[i]) / (keyS[i + 1] - keyS[i]))
        for (c in 0 until 3) out[c] = lerp(table[i][c], table[i + 1][c], t)
    }

    fun dayZenith(s: Float, out: FloatArray) = key(zenith, s, out)
    fun dayHorizon(s: Float, out: FloatArray) = key(horizon, s, out)
    fun sunsetColor(s: Float, out: FloatArray) = key(sunset, s, out)
    fun sunColor(s: Float, out: FloatArray) = key(sun, s, out)

    // ------------------------------------------------------------------ fixed palette

    /** Linear frost colour used where shaders cannot sample the texture. */
    val frostColor = floatArrayOf(0.5f, 0.48f, 0.72f)
    val waterAbsorb = floatArrayOf(0.5f, 0.085f, 0.16f)
    val waterScatter = floatArrayOf(0.008f, 0.085f, 0.075f)
    val waterFog = floatArrayOf(0.015f, 0.1f, 0.095f)
    val waterTint = floatArrayOf(0.35f, 0.75f, 0.7f)

    /** Direction to the gas giant; it never moves in the sky. */
    val giantDir: FloatArray = run {
        val x = -0.52f; val y = 0.42f; val z = 0.74f
        val l = kotlin.math.sqrt(x * x + y * y + z * z)
        floatArrayOf(x / l, y / l, z / l)
    }
}
