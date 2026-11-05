package com.rustbloom.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.rustbloom.game.game.Sfx
import com.rustbloom.game.game.SoundSink
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * All audio is synthesized at startup (no asset files): short effects through SoundPool,
 * plus looping ambience beds (wind, rain, fire, night insects) whose volumes follow the world.
 */
class SoundManager(private val ctx: Context) : SoundSink {
    private val rate = 22050
    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = IntArray(Sfx.entries.size)
    private var windId = 0; private var rainId = 0; private var fireId = 0; private var nightId = 0
    private var windStream = 0; private var rainStream = 0; private var fireStream = 0; private var nightStream = 0
    private val loaded = HashSet<Int>()
    @Volatile var master = 0.8f
    @Volatile var muted = false
    private var seed = 1234567

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) synchronized(loaded) { loaded.add(sampleId) } }
    }

    fun loadAll() {
        val dir = File(ctx.cacheDir, "sfx").apply { mkdirs() }
        for (s in Sfx.entries) {
            val f = File(dir, "${s.name.lowercase()}.wav")
            if (!f.exists()) writeWav(f, synth(s))
            ids[s.ordinal] = pool.load(f.path, 1)
        }
        fun loop(name: String, gen: () -> ShortArray): Int {
            val f = File(dir, "$name.wav")
            if (!f.exists()) writeWav(f, gen())
            return pool.load(f.path, 1)
        }
        windId = loop("loop_wind") { windLoop() }
        rainId = loop("loop_rain") { rainLoop() }
        fireId = loop("loop_fire") { fireLoop() }
        nightId = loop("loop_night") { nightLoop() }
    }

    private fun isLoaded(id: Int) = synchronized(loaded) { loaded.contains(id) }

    override fun play(s: Sfx, volume: Float, pitch: Float) {
        if (muted) return
        val id = ids[s.ordinal]
        if (id == 0 || !isLoaded(id)) return
        val v = (volume * master).coerceIn(0f, 1f)
        if (v < 0.01f) return
        pool.play(id, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    override fun setAmbience(wind: Float, rain: Float, fire: Float, night: Float) {
        if (muted) { pauseAmbience(); return }
        windStream = ensureLoop(windId, windStream, (0.08f + wind * 0.35f) * master)
        rainStream = ensureLoop(rainId, rainStream, rain * 0.55f * master)
        fireStream = ensureLoop(fireId, fireStream, fire * 0.5f * master)
        nightStream = ensureLoop(nightId, nightStream, night * 0.25f * master * (1f - rain))
    }

    private fun ensureLoop(id: Int, stream: Int, vol: Float): Int {
        if (id == 0 || !isLoaded(id)) return stream
        var s = stream
        if (vol < 0.01f) {
            if (s != 0) { pool.stop(s); s = 0 }
            return s
        }
        if (s == 0) s = pool.play(id, vol, vol, 0, -1, 1f)
        else pool.setVolume(s, vol, vol)
        return s
    }

    fun pauseAmbience() {
        if (windStream != 0) { pool.stop(windStream); windStream = 0 }
        if (rainStream != 0) { pool.stop(rainStream); rainStream = 0 }
        if (fireStream != 0) { pool.stop(fireStream); fireStream = 0 }
        if (nightStream != 0) { pool.stop(nightStream); nightStream = 0 }
    }

    fun release() { pool.release() }

    // ------------------------------------------------------------------ synthesis

    private fun rnd(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 8) and 0xffff) / 32768f - 1f
    }

    private fun buf(sec: Float) = FloatArray((rate * sec).toInt())

    private fun env(i: Int, n: Int, attack: Float, decay: Float): Float {
        val t = i.toFloat() / rate
        val a = if (attack > 0f) (t / attack).coerceAtMost(1f) else 1f
        return a * exp(-t / decay)
    }

    /** One-pole lowpass over the buffer, cutoff coefficient 0..1. */
    private fun lowpass(b: FloatArray, k: Float): FloatArray {
        var y = 0f
        for (i in b.indices) { y += (b[i] - y) * k; b[i] = y }
        return b
    }

    private fun noise(sec: Float, k: Float, attack: Float, decay: Float, gain: Float = 1f): FloatArray {
        val b = buf(sec)
        for (i in b.indices) b[i] = rnd() * gain
        lowpass(b, k)
        for (i in b.indices) b[i] *= env(i, b.size, attack, decay)
        return b
    }

    private fun tone(sec: Float, f0: Float, f1: Float, attack: Float, decay: Float, harmonics: Int = 1, gain: Float = 0.6f): FloatArray {
        val b = buf(sec)
        var ph = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / b.size
            val f = f0 + (f1 - f0) * t
            ph += 2.0 * PI * f / rate
            var v = 0f
            for (h in 1..harmonics) v += (sin(ph * h) / h).toFloat()
            b[i] = v * gain * env(i, b.size, attack, decay)
        }
        return b
    }

    private fun mix(vararg parts: FloatArray): FloatArray {
        val n = parts.maxOf { it.size }
        val out = FloatArray(n)
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out
    }

    private fun synth(s: Sfx): ShortArray {
        val b: FloatArray = when (s) {
            Sfx.CHOP -> mix(noise(0.25f, 0.25f, 0.001f, 0.05f, 1.2f), tone(0.2f, 180f, 90f, 0.001f, 0.05f, 2, 0.5f))
            Sfx.MINE -> mix(noise(0.3f, 0.6f, 0.0005f, 0.04f, 1f), tone(0.3f, 1400f, 1200f, 0.0005f, 0.06f, 3, 0.25f))
            Sfx.SWING -> {
                val b0 = buf(0.25f)
                var y = 0f
                for (i in b0.indices) {
                    val t = i.toFloat() / b0.size
                    val k = 0.05f + 0.25f * sin(t * PI.toFloat())
                    y += (rnd() - y) * k
                    b0[i] = y * sin(t * PI.toFloat()) * 1.4f
                }
                b0
            }
            Sfx.HIT_FLESH -> mix(noise(0.2f, 0.12f, 0.001f, 0.05f, 1.5f), tone(0.2f, 120f, 60f, 0.001f, 0.06f, 1, 0.7f))
            Sfx.HIT_METAL -> mix(tone(0.5f, 880f, 860f, 0.0005f, 0.12f, 4, 0.35f), tone(0.5f, 1320f, 1290f, 0.0005f, 0.08f, 2, 0.2f), noise(0.1f, 0.7f, 0f, 0.015f, 0.8f))
            Sfx.BOW_DRAW -> {
                val b0 = buf(0.5f)
                var y = 0f
                for (i in b0.indices) { val t = i.toFloat() / b0.size; y += (rnd() - y) * 0.08f; b0[i] = y * 0.6f * t * (1 - t) * 4f }
                b0
            }
            Sfx.BOW_RELEASE -> mix(tone(0.35f, 220f, 160f, 0.001f, 0.08f, 3, 0.5f), noise(0.15f, 0.3f, 0f, 0.03f, 0.6f))
            Sfx.ARROW_HIT -> mix(noise(0.15f, 0.35f, 0f, 0.02f, 1.2f), tone(0.15f, 300f, 200f, 0f, 0.03f, 1, 0.4f))
            Sfx.PICKUP -> mix(tone(0.15f, 660f, 990f, 0.002f, 0.06f, 1, 0.35f))
            Sfx.CRAFT -> mix(tone(0.12f, 520f, 520f, 0.002f, 0.05f, 2, 0.3f), shift(tone(0.18f, 780f, 780f, 0.002f, 0.07f, 2, 0.3f), 0.09f))
            Sfx.EAT -> {
                val b0 = buf(0.35f)
                for (i in b0.indices) { val t = i.toFloat() / rate; val gate = if (((t * 14).toInt() % 2) == 0) 1f else 0.2f; b0[i] = rnd() * gate * exp(-t * 5f) * 0.7f }
                lowpass(b0, 0.3f)
            }
            Sfx.DRINK -> {
                val b0 = buf(0.5f)
                var ph = 0.0
                for (i in b0.indices) { val t = i.toFloat() / rate; val f = 300 + 400 * abs(sin(t * 18.0)); ph += 2 * PI * f / rate; b0[i] = (sin(ph) * 0.3 * exp(-t * 3.0)).toFloat() }
                b0
            }
            Sfx.HURT -> mix(tone(0.3f, 160f, 80f, 0.002f, 0.1f, 2, 0.6f), noise(0.2f, 0.1f, 0.001f, 0.06f, 1f))
            Sfx.MACHINE_ALERT -> mix(tone(0.18f, 900f, 1400f, 0.002f, 0.1f, 2, 0.35f), shift(tone(0.25f, 1400f, 700f, 0.002f, 0.12f, 2, 0.35f), 0.16f))
            Sfx.MACHINE_DIE -> mix(tone(1.2f, 600f, 60f, 0.005f, 0.5f, 3, 0.4f), noise(0.8f, 0.2f, 0.002f, 0.25f, 0.8f))
            Sfx.BOLT -> mix(tone(0.35f, 1800f, 300f, 0.001f, 0.12f, 1, 0.35f), noise(0.3f, 0.5f, 0.001f, 0.08f, 0.5f))
            Sfx.STOMP -> mix(tone(0.8f, 70f, 35f, 0.002f, 0.3f, 2, 0.9f), noise(0.6f, 0.05f, 0.002f, 0.2f, 1.5f))
            Sfx.BUILD -> mix(noise(0.2f, 0.2f, 0.001f, 0.04f, 1f), shift(noise(0.2f, 0.2f, 0.001f, 0.04f, 0.8f), 0.12f), tone(0.3f, 140f, 100f, 0.001f, 0.08f, 2, 0.4f))
            Sfx.UI -> tone(0.06f, 1000f, 1100f, 0.001f, 0.02f, 1, 0.25f)
            Sfx.LEVEL_UP -> mix(tone(0.2f, 523f, 523f, 0.005f, 0.15f, 2, 0.3f), shift(tone(0.2f, 659f, 659f, 0.005f, 0.15f, 2, 0.3f), 0.12f), shift(tone(0.5f, 784f, 784f, 0.005f, 0.3f, 2, 0.3f), 0.24f))
            Sfx.QUEST -> mix(tone(0.4f, 392f, 392f, 0.01f, 0.3f, 3, 0.25f), shift(tone(0.6f, 587f, 587f, 0.01f, 0.4f, 3, 0.25f), 0.18f), shift(tone(0.9f, 784f, 784f, 0.01f, 0.6f, 3, 0.25f), 0.36f))
            Sfx.THUNDER -> {
                val b0 = buf(3f)
                var y = 0f
                for (i in b0.indices) {
                    val t = i.toFloat() / rate
                    y += (rnd() - y) * 0.02f
                    val rumble = exp(-t * 1.2f) * (0.6f + 0.4f * sin(t * 7f) * sin(t * 2.3f))
                    b0[i] = y * 6f * rumble * (t / 0.05f).coerceAtMost(1f)
                }
                b0
            }
            Sfx.SPIRE -> {
                val b0 = buf(2.5f)
                var ph1 = 0.0; var ph2 = 0.0; var ph3 = 0.0
                for (i in b0.indices) {
                    val t = i.toFloat() / rate
                    ph1 += 2 * PI * 110 / rate; ph2 += 2 * PI * 165.5 / rate; ph3 += 2 * PI * 220.8 / rate
                    val e = (t / 0.4f).coerceAtMost(1f) * exp(-t * 0.9f)
                    b0[i] = ((sin(ph1) + 0.7 * sin(ph2) + 0.5 * sin(ph3)) * 0.22 * e).toFloat()
                }
                b0
            }
            Sfx.STEP -> noise(0.09f, 0.1f, 0.002f, 0.025f, 1.2f)
            Sfx.SPLASH -> noise(0.6f, 0.4f, 0.005f, 0.15f, 0.9f)
            Sfx.ZAP -> {
                val b0 = buf(0.3f)
                for (i in b0.indices) { val t = i.toFloat() / rate; b0[i] = (if (rnd() > 0.3f) 1f else -1f) * exp(-t * 12f) * 0.35f * (if ((i / 40) % 2 == 0) 1f else 0.3f) }
                b0
            }
            Sfx.DEATH -> mix(tone(1.5f, 220f, 110f, 0.01f, 0.7f, 3, 0.35f), shift(tone(1.5f, 165f, 82f, 0.01f, 0.7f, 3, 0.3f), 0.3f))
        }
        return toPcm(b)
    }

    private fun shift(b: FloatArray, sec: Float): FloatArray {
        val off = (sec * rate).toInt()
        val out = FloatArray(b.size + off)
        System.arraycopy(b, 0, out, off, b.size)
        return out
    }

    /** Seamless loop: crossfade the end of the buffer into the start. */
    private fun seamless(b: FloatArray, fade: Int): FloatArray {
        val n = b.size - fade
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = b[i]
        for (i in 0 until fade) {
            val t = i.toFloat() / fade
            out[i] = b[i] * t + b[n + i] * (1 - t)
        }
        return out
    }

    private fun windLoop(): ShortArray {
        val b = buf(6f)
        var y = 0f; var y2 = 0f
        for (i in b.indices) {
            val t = i.toFloat() / rate
            val gust = 0.55f + 0.45f * sin(t * 0.9f) * sin(t * 0.37f + 1f)
            val k = 0.01f + 0.02f * gust
            y += (rnd() - y) * k
            y2 += (y - y2) * 0.3f
            b[i] = y2 * 5f * gust
        }
        return toPcm(seamless(b, rate / 2))
    }

    private fun rainLoop(): ShortArray {
        val b = buf(4f)
        var y = 0f
        for (i in b.indices) {
            y += (rnd() - y) * 0.45f
            var v = y * 0.35f
            if (rnd() > 0.9985f) v += rnd() * 0.6f
            b[i] = v
        }
        return toPcm(seamless(b, rate / 4))
    }

    private fun fireLoop(): ShortArray {
        val b = buf(4f)
        var y = 0f
        var crackle = 0f
        for (i in b.indices) {
            y += (rnd() - y) * 0.05f
            if (rnd() > 0.9993f) crackle = 1f
            crackle *= 0.993f
            b[i] = y * 0.9f + rnd() * crackle * 0.5f
        }
        return toPcm(seamless(b, rate / 4))
    }

    private fun nightLoop(): ShortArray {
        val b = buf(5f)
        var ph = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / rate
            ph += 2 * PI * 4200 / rate
            val chirp = if ((t * 3.1f) % 1f < 0.18f) sin((t * 3.1f % 1f) / 0.18f * PI.toFloat()) else 0f
            val chirp2 = if (((t + 0.7f) * 2.3f) % 1f < 0.12f) 0.6f else 0f
            b[i] = (sin(ph) * (chirp * 0.12f + chirp2 * 0.06f)).toFloat() * (if ((i / 90) % 2 == 0) 1f else 0.4f)
        }
        return toPcm(seamless(b, rate / 4))
    }

    private fun toPcm(b: FloatArray): ShortArray {
        var peak = 0.0001f
        for (v in b) peak = maxOf(peak, abs(v))
        val g = if (peak > 0.95f) 0.95f / peak else 1f
        return ShortArray(b.size) { (b[it] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(rate); bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        FileOutputStream(f).use { it.write(bb.array()) }
    }
}
