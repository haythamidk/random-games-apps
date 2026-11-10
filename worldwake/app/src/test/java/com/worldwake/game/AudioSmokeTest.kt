package com.worldwake.game

import android.app.Activity
import com.worldwake.game.audio.MusicGen
import com.worldwake.game.audio.SoundEngine
import com.worldwake.game.game.Ambience
import com.worldwake.game.game.Sfx
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthesizes every sound and runs the mixer for a moment. A crash here would mean a silent game on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioSmokeTest {
    @Test
    fun everySoundSynthesizesAndMixes() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val s = SoundEngine(act)
        s.loadAll()
        val a = Ambience()
        for (stage in listOf(0f, 3f, 6f, 9f, 11f)) {
            a.stage = stage; a.atmo = stage / 11f; a.wind = 0.6f; a.rain = if (stage > 4f) 0.7f else 0f; a.life = stage / 11f
            a.machines = 0.8f; a.water = 0.5f; a.watcher = 0.9f; a.hum = 0.7f; a.breath = 0.8f; a.night = if (stage > 5f) 1f else 0f
            a.helmet = stage < 10f; a.indoors = stage == 3f
            s.setAmbience(a)
            for (fx in Sfx.entries) { s.play(fx, 0.5f); s.playAt(fx, 3f, 1f, 2f, 0.5f) }
            s.speak(80)
            Thread.sleep(120)
        }
        s.release()
    }

    @Test
    fun musicFollowsThePlanetWithoutClipping() {
        val m = MusicGen(48000f)
        val l = FloatArray(256); val r = FloatArray(256)
        val a = Ambience()
        var peak = 0f; var energy = 0.0
        for (stage in listOf(0f, 2f, 4f, 6f, 8f, 10f, 11f)) {
            a.stage = stage; a.menu = true
            repeat(48000 * 20 / 256) {
                l.fill(0f); r.fill(0f)
                m.mix(l, r, 256, 0.25f, a)
                for (i in 0 until 256) { peak = maxOf(peak, kotlin.math.abs(l[i]), kotlin.math.abs(r[i])); energy += l[i] * l[i] }
            }
        }
        println("music peak $peak energy $energy")
        assertTrue("music is audible", energy > 1.0)
        assertTrue("music stays below clipping ($peak)", peak < 0.9f)
    }
}
