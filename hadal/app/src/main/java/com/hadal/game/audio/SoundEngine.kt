package com.hadal.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import com.hadal.game.game.Ambience
import com.hadal.game.game.Sfx
import com.hadal.game.game.SoundSink
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software audio engine. Every effect is synthesized at startup in several variants, then mixed on a dedicated
 * audio thread into one low-latency stream with random pitch and volume so nothing repeats exactly. Positional
 * sounds are panned and muffled with distance (water eats the highs). Continuous beds follow the dive: the deep
 * ocean rumble, thrusters, the station hum, black smokers, a hull under pressure and the Pale's presence.
 * The music track (made with Higgsfield) plays in menus and while docked.
 */
class SoundEngine(private val ctx: Context) : SoundSink {
    private val srcRate = 22050
    private val outRate: Int = try {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt() ?: 48000
    } catch (_: Throwable) { 48000 }

    @Volatile var master = 0.85f
    @Volatile var musicVolume = 0.6f
    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var music: MediaPlayer? = null
    private var musicLevel = 0f
    @Volatile private var musicWanted = true

    private val variants = arrayOfNulls<Array<FloatArray>>(Sfx.entries.size)
    private val lastVariant = IntArray(Sfx.entries.size) { -1 }
    private val lastPlayed = LongArray(Sfx.entries.size)
    @Volatile private var loaded = false

    private val amb = Ambience()
    private val lock = Any()
    @Volatile private var lx = 0f
    @Volatile private var ly = 0f
    @Volatile private var lz = 0f
    @Volatile private var lyaw = 0f

    private class Voice(val data: FloatArray, var gainL: Float, var gainR: Float, val step: Double, val muffle: Float) {
        var pos = 0.0
        var done = false
        var lpL = 0f
    }

    private class Bed(var data: FloatArray?) {
        var pos = 0.0
        var vol = 0f
        var target = 0f
        var rate = 1f
        var targetRate = 1f
    }

    private val voices = ArrayList<Voice>()
    private val pending = ArrayList<Voice>()
    private val beds = Array(7) { Bed(null) }
    private var seed = 1234567

    // ------------------------------------------------------------------ lifecycle

    fun loadAll() {
        for (s in Sfx.entries) {
            val n = variantCount(s)
            variants[s.ordinal] = Array(n) { v -> seed = 1000 + s.ordinal * 97 + v * 7919; synth(s, v) }
        }
        beds[0].data = oceanLoop(); beds[1].data = thrusterLoop(); beds[2].data = humLoop(); beds[3].data = ventLoop()
        beds[4].data = stressLoop(); beds[5].data = surfaceLoop(); beds[6].data = fearLoop()
        loaded = true
        start()
        startMusic()
    }

    private fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val frames = max(minBuf / 4, outRate / 25)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(outRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(frames * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t
        running = true
        t.play()
        thread = Thread({ mixLoop() }, "audio-mix").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun startMusic() {
        try {
            val afd = ctx.assets.openFd("abyss_theme.ogg")
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.prepare()
            mp.start()
            music = mp
        } catch (t: Throwable) { android.util.Log.w("Hadal", "music unavailable", t) }
    }

    /** Music plays in menus and at the station, and fades out on dives (the sea is the soundtrack down there). */
    fun setMusicWanted(w: Boolean) { musicWanted = w }

    fun pause() { paused = true; try { track?.pause() } catch (_: Throwable) {}; try { music?.pause() } catch (_: Throwable) {} }
    fun resume() { if (!loaded) return; paused = false; try { track?.play() } catch (_: Throwable) {}; try { music?.start() } catch (_: Throwable) {} }

    fun release() {
        running = false
        try { thread?.join(300) } catch (_: Throwable) {}
        try { track?.stop(); track?.release() } catch (_: Throwable) {}
        try { music?.release() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ SoundSink

    override fun play(s: Sfx, volume: Float, pitch: Float) = start(s, volume, pitch, 0f, 0f)

    override fun playAt(s: Sfx, x: Float, y: Float, z: Float, volume: Float, pitch: Float) {
        val dx = x - lx; val dy = y - ly; val dz = z - lz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val range = if (s == Sfx.WHALE || s == Sfx.PALE_CALL || s == Sfx.ROAR) 260f else 80f
        if (d > range) return
        val k = 1f - d / range
        val att = k * k * 0.85f + 0.15f * k
        val pan = if (d > 0.5f) ((-cos(lyaw) * dx + sin(lyaw) * dz) / d) * 0.8f else 0f
        start(s, volume * att, pitch, pan, min(0.92f, d / range * 1.4f))
    }

    override fun setListener(x: Float, y: Float, z: Float, yaw: Float) { lx = x; ly = y; lz = z; lyaw = yaw }

    override fun setAmbience(a: Ambience) { synchronized(lock) { amb.copyFrom(a) } }

    private fun variantCount(s: Sfx) = when (s) {
        Sfx.CLANG, Sfx.HULL_HIT, Sfx.CREAK, Sfx.GRAB, Sfx.BITE -> 4
        Sfx.WHALE, Sfx.PALE_CALL, Sfx.PALE_CLICK, Sfx.ROAR, Sfx.GROAN, Sfx.BUBBLES -> 3
        else -> 2
    }

    private fun start(s: Sfx, volume: Float, pitch: Float, pan: Float, muffle: Float) {
        if (!loaded || paused) return
        val vars = variants[s.ordinal] ?: return
        val now = System.nanoTime()
        if (now - lastPlayed[s.ordinal] < 35_000_000L && s != Sfx.UI) return
        lastPlayed[s.ordinal] = now
        var v = rnd(vars.size)
        if (vars.size > 1 && v == lastVariant[s.ordinal]) v = (v + 1) % vars.size
        lastVariant[s.ordinal] = v
        val vol = (volume * master * (0.9f + rndF() * 0.2f)).coerceIn(0f, 1.5f)
        if (vol < 0.004f) return
        val p = pitch * (0.95f + rndF() * 0.1f)
        val voice = Voice(vars[v], vol * min(1f, 1f - pan), vol * min(1f, 1f + pan), srcRate.toDouble() / outRate * p, muffle)
        synchronized(pending) { pending.add(voice) }
    }

    private fun rnd(n: Int): Int { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0x7fffff) % n }
    private fun rndF(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xffff) / 65535f }

    // ------------------------------------------------------------------ mixing

    private var lpL = 0f
    private var lpR = 0f
    private var ambTimer = 3f
    private var revL = FloatArray(1)
    private var revR = FloatArray(1)
    private var revIdx = 0

    private fun mixLoop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) } catch (_: Throwable) {}
        val block = 256
        val mixL = FloatArray(block); val mixR = FloatArray(block)
        val out = ShortArray(block * 2)
        val a = Ambience()
        revL = FloatArray((outRate * 0.31f).toInt()); revR = FloatArray((outRate * 0.43f).toInt())
        while (running) {
            if (paused) { try { Thread.sleep(50) } catch (_: InterruptedException) {}; continue }
            synchronized(pending) { voices.addAll(pending); pending.clear() }
            synchronized(lock) { a.copyFrom(amb) }
            val dt = block.toFloat() / outRate
            scheduleAmbience(a, dt)
            val m = master
            val quiet = if (a.menu || a.paused) 0.25f else 1f
            beds[0].target = (0.25f + a.depth * 0.45f) * m * quiet * (if (a.docked) 0.4f else 1f)
            beds[1].target = (if (a.docked || a.lowPower) 0f else 0.04f + a.thrust * 0.32f) * m * quiet
            beds[1].targetRate = 0.8f + a.thrust * 0.5f + (if (a.boost) 0.3f else 0f)
            beds[2].target = (a.station * 0.25f + (if (a.docked) 0.35f else 0f)) * m * quiet
            beds[3].target = a.vents * 0.5f * m * quiet
            beds[4].target = a.stress * 0.6f * m * quiet
            beds[5].target = a.surface * 0.45f * m * quiet
            beds[6].target = a.fear * 0.55f * m
            for (i in 0 until block) { mixL[i] = 0f; mixR[i] = 0f }
            for (b in beds) {
                val data = b.data ?: continue
                if (b.target < 0.001f && b.vol < 0.001f) continue
                for (i in 0 until block) {
                    b.vol += (b.target - b.vol) * 0.0005f
                    b.rate += (b.targetRate - b.rate) * 0.0003f
                    val idx = b.pos.toInt()
                    val f = (b.pos - idx).toFloat()
                    val s0 = data[idx]; val s1 = data[if (idx + 1 >= data.size) 0 else idx + 1]
                    val smp = (s0 + (s1 - s0) * f) * b.vol
                    mixL[i] += smp; mixR[i] += smp
                    b.pos += srcRate.toDouble() / outRate * b.rate
                    if (b.pos >= data.size) b.pos -= data.size
                }
            }
            if (voices.size > 28) { voices.sortBy { -(it.gainL + it.gainR) }; while (voices.size > 28) voices.removeAt(voices.size - 1) }
            for (v in voices) {
                val data = v.data
                val k = 1f - v.muffle * 0.9f
                for (i in 0 until block) {
                    val idx = v.pos.toInt()
                    if (idx >= data.size - 1) { v.done = true; break }
                    val f = (v.pos - idx).toFloat()
                    var smp = data[idx] + (data[idx + 1] - data[idx]) * f
                    if (v.muffle > 0f) { v.lpL += (smp - v.lpL) * k; smp = v.lpL }
                    mixL[i] += smp * v.gainL; mixR[i] += smp * v.gainR
                    v.pos += v.step
                }
            }
            voices.removeAll { it.done }
            // a long, dark underwater space: two feedback delays
            for (i in 0 until block) {
                val tapL = revL[revIdx % revL.size]; val tapR = revR[revIdx % revR.size]
                revL[revIdx % revL.size] = mixL[i] * 0.35f + tapR * 0.55f
                revR[revIdx % revR.size] = mixR[i] * 0.35f + tapL * 0.55f
                mixL[i] += tapL * 0.4f; mixR[i] += tapR * 0.4f
                revIdx++
            }
            if (revIdx > 1 shl 30) revIdx = 0
            val lpk = if (a.docked) 0.5f else 0.35f
            for (i in 0 until block) {
                lpL += (mixL[i] - lpL) * lpk; lpR += (mixR[i] - lpR) * lpk
                val l = softClip(lpL); val r = softClip(lpR)
                out[i * 2] = (l * 32000f).toInt().toShort()
                out[i * 2 + 1] = (r * 32000f).toInt().toShort()
            }
            // music fade
            val mt = if (musicWanted && !a.paused) musicVolume * m * 0.8f else 0f
            musicLevel += (mt - musicLevel) * 0.02f
            try { music?.setVolume(musicLevel, musicLevel) } catch (_: Throwable) {}
            try { track?.write(out, 0, out.size) } catch (_: Throwable) { try { Thread.sleep(5) } catch (_: InterruptedException) {} }
        }
    }

    private fun softClip(x: Float): Float = if (x > 1f) 1f - 0.25f / (x + 0.5f) else if (x < -1f) -1f + 0.25f / (-x + 0.5f) else x - x * x * x * 0.08f

    // ------------------------------------------------------------------ distant ambience

    private val ambSets = HashMap<String, Array<FloatArray>>()
    private fun set(name: String, n: Int, gen: (Int) -> FloatArray) = ambSets.getOrPut(name) { Array(n) { v -> seed = name.hashCode() + v * 131; gen(v) } }
    private val ambCool = FloatArray(4)

    private fun scheduleAmbience(a: Ambience, dt: Float) {
        if (a.paused || a.menu) return
        for (i in ambCool.indices) ambCool[i] -= dt
        if (ambCool[0] <= 0f) {
            ambCool[0] = 4f + rndF() * 9f
            if (!a.docked) ambient(set("bubbles", 3) { bubbles(it) }, 0.12f + rndF() * 0.1f)
        }
        if (ambCool[1] <= 0f) {
            ambCool[1] = 15f + rndF() * 25f
            // far groans of the deep: rock settling, old metal, things too big to see
            if (a.depth > 0.1f && rndF() < 0.6f) ambient(set("far", 4) { farGroan(it) }, 0.2f + a.depth * 0.25f)
        }
        if (ambCool[2] <= 0f) {
            ambCool[2] = 8f + rndF() * 14f
            if (a.depth > 0.3f && rndF() < 0.5f) ambient(set("clicks", 3) { clicks(it) }, 0.08f + a.depth * 0.1f)
        }
        if (ambCool[3] <= 0f) {
            ambCool[3] = 20f + rndF() * 30f
            if (a.docked || a.station > 0.5f) ambient(set("station", 3) { stationTick(it) }, 0.15f)
        }
    }

    private fun ambient(set: Array<FloatArray>, vol: Float) {
        val d = set[rnd(set.size)]
        val pan = rndF() * 1.6f - 0.8f
        val v = vol * master
        synchronized(pending) { pending.add(Voice(d, v * min(1f, 1f - pan), v * min(1f, 1f + pan), srcRate.toDouble() / outRate * (0.9f + rndF() * 0.2f), 0.4f)) }
    }

    // ------------------------------------------------------------------ synthesis helpers

    private fun noise(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xffff) / 32768f - 1f }
    private fun buf(sec: Float) = FloatArray((srcRate * sec).toInt().coerceAtLeast(2))
    private fun env(i: Int, attack: Float, decay: Float): Float {
        val t = i.toFloat() / srcRate
        val a = if (attack > 0f) (t / attack).coerceAtMost(1f) else 1f
        return a * exp(-t / decay)
    }
    private fun lowpass(b: FloatArray, k: Float): FloatArray { var y = 0f; for (i in b.indices) { y += (b[i] - y) * k; b[i] = y }; return b }
    private fun highpass(b: FloatArray, k: Float): FloatArray { var y = 0f; var px = 0f; for (i in b.indices) { val x = b[i]; y = k * (y + x - px); px = x; b[i] = y }; return b }
    private fun noiseBurst(sec: Float, k: Float, attack: Float, decay: Float, gain: Float = 1f): FloatArray {
        val b = buf(sec); for (i in b.indices) b[i] = noise() * gain; lowpass(b, k); for (i in b.indices) b[i] *= env(i, attack, decay); return b
    }
    private fun tone(sec: Float, f0: Float, f1: Float, attack: Float, decay: Float, harmonics: Int = 1, gain: Float = 0.6f, vib: Float = 0f): FloatArray {
        val b = buf(sec); var ph = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / b.size
            val f = f0 + (f1 - f0) * t + vib * sin(i.toFloat() / srcRate * 30f) * f0 * 0.02f
            ph += 2.0 * PI * f / srcRate
            var v = 0f; for (h in 1..harmonics) v += (sin(ph * h) / (h * h)).toFloat()
            b[i] = v * gain * env(i, attack, decay)
        }
        return b
    }
    private fun mix(vararg parts: FloatArray): FloatArray { val n = parts.maxOf { it.size }; val o = FloatArray(n); for (p in parts) for (i in p.indices) o[i] += p[i]; return o }
    private fun shift(b: FloatArray, sec: Float): FloatArray { val off = (sec * srcRate).toInt(); val o = FloatArray(b.size + off); System.arraycopy(b, 0, o, off, b.size); return o }
    private fun norm(b: FloatArray, peak: Float = 0.9f): FloatArray { var m = 1e-4f; for (v in b) m = max(m, abs(v)); val g = peak / m; for (i in b.indices) b[i] *= g; return b }
    private fun seamless(b: FloatArray, fade: Int): FloatArray {
        val n = b.size - fade; val o = FloatArray(n)
        for (i in 0 until n) o[i] = b[i]
        for (i in 0 until fade) { val t = i.toFloat() / fade; o[i] = b[i] * t + b[n + i] * (1 - t) }
        return o
    }
    /** Metallic ringing: a few inharmonic partials with long decay. */
    private fun ring(sec: Float, base: Float, decay: Float, gain: Float, v: Int): FloatArray {
        val ratios = floatArrayOf(1f, 2.32f, 3.17f, 4.53f, 6.1f)
        val parts = ratios.mapIndexed { k, r -> tone(sec, base * r * (1f + v * 0.013f), base * r * 0.99f, 0.001f, decay / (1f + k * 0.6f), 1, gain / (1f + k)) }
        return mix(*parts.toTypedArray())
    }

    private fun synth(s: Sfx, v: Int): FloatArray = when (s) {
        Sfx.UI -> tone(0.05f, 1000f + v * 80f, 1100f, 0.001f, 0.015f, 1, 0.18f)
        Sfx.UI_OPEN -> mix(tone(0.08f, 600f, 800f, 0.002f, 0.03f, 1, 0.16f), shift(tone(0.1f, 900f, 1100f, 0.002f, 0.04f, 1, 0.14f), 0.05f))
        Sfx.PING -> {
            // the classic: a pure sine ping with a long watery tail
            val b = buf(3f); var ph = 0.0
            for (i in b.indices) { val t = i.toFloat() / srcRate; ph += 2 * PI * (1480f + v * 30f) / srcRate; b[i] = (sin(ph) * (if (t < 0.005f) t / 0.005f else 1f) * exp(-t * 2.2f) * (0.8f + 0.2f * sin(t * 40f))).toFloat() }
            norm(mix(b, shift(lowpass(b.copyOf(), 0.2f).also { for (i in it.indices) it[i] *= 0.4f }, 0.42f)), 0.75f)
        }
        Sfx.ECHO -> norm(lowpass(tone(1.2f, 1480f, 1470f, 0.01f, 0.25f, 1, 0.4f), 0.3f), 0.4f)
        Sfx.HULL_HIT -> norm(mix(ring(1.6f, 90f + v * 9f, 0.5f, 0.8f, v), noiseBurst(0.5f, 0.12f, 0.001f, 0.08f, 1.4f), tone(0.6f, 55f, 35f, 0.001f, 0.2f, 1, 0.9f)))
        Sfx.CLANG -> norm(mix(ring(1.2f, 160f + v * 25f, 0.35f, 0.7f, v), noiseBurst(0.2f, 0.3f, 0f, 0.03f, 0.8f)), 0.8f)
        Sfx.CREAK -> {
            val b = buf(1.6f); var ph = 0.0
            for (i in b.indices) {
                val t = i.toFloat() / srcRate
                val f = 70f + v * 18f + 40f * sin(t * (2.2f + v)) + 15f * sin(t * 13f)
                ph += 2 * PI * f / srcRate
                val stick = if (noise() > 0.6f) 1f else 0.4f
                b[i] = (sin(ph) + sin(ph * 2.7) * 0.4f).toFloat() * stick * sin(t / 1.6f * PI.toFloat())
            }
            norm(lowpass(b, 0.25f), 0.65f)
        }
        Sfx.GROAN -> {
            val b = buf(3.2f); var ph = 0.0
            for (i in b.indices) { val t = i.toFloat() / srcRate; ph += 2 * PI * (42f + v * 6f + 12f * sin(t * 1.3f)) / srcRate
                b[i] = (sin(ph) + sin(ph * 1.5) * 0.6f + noise() * 0.25f).toFloat() * sin(t / 3.2f * PI.toFloat()) }
            norm(mix(lowpass(b, 0.08f), shift(ring(1.5f, 210f + v * 30f, 0.4f, 0.25f, v), 1.4f)), 0.8f)
        }
        Sfx.GRAB -> norm(mix(tone(0.35f, 300f + v * 40f, 520f, 0.01f, 0.12f, 3, 0.4f), shift(noiseBurst(0.12f, 0.4f, 0f, 0.02f, 0.8f), 0.28f), shift(ring(0.4f, 600f, 0.08f, 0.2f, v), 0.3f)), 0.6f)
        Sfx.PICKUP -> norm(mix(tone(0.25f, 660f + v * 40f, 660f, 0.004f, 0.1f, 2, 0.25f), shift(tone(0.35f, 990f, 990f, 0.004f, 0.14f, 2, 0.25f), 0.08f)), 0.45f)
        Sfx.SCAN_TICK -> tone(0.06f, 1800f + v * 100f, 2000f, 0.001f, 0.015f, 1, 0.2f)
        Sfx.SCAN_DONE -> norm(mix(tone(0.3f, 880f, 880f, 0.005f, 0.12f, 2, 0.3f), shift(tone(0.3f, 1320f, 1320f, 0.005f, 0.12f, 2, 0.3f), 0.1f), shift(tone(0.6f, 1760f, 1760f, 0.005f, 0.25f, 1, 0.25f), 0.2f)), 0.55f)
        Sfx.LAMP -> norm(mix(noiseBurst(0.06f, 0.6f, 0f, 0.008f), shift(tone(0.25f, 120f, 120f, 0.01f, 0.1f, 4, 0.2f), 0.02f)), 0.5f)
        Sfx.ALARM -> norm(mix(tone(0.35f, 880f, 880f, 0.01f, 0.3f, 3, 0.3f), shift(tone(0.35f, 660f, 660f, 0.01f, 0.3f, 3, 0.3f), 0.38f)), 0.45f)
        Sfx.DOCK -> norm(mix(ring(2f, 70f, 0.8f, 0.8f, v), noiseBurst(1.5f, 0.05f, 0.05f, 0.5f, 1.2f), shift(noiseBurst(1.2f, 0.3f, 0.2f, 0.4f, 0.5f), 0.6f)), 0.8f)
        Sfx.UNDOCK -> norm(mix(noiseBurst(2f, 0.2f, 0.1f, 0.6f, 1f), ring(1.2f, 85f, 0.5f, 0.5f, v), shift(bubbles(v), 0.3f)), 0.7f)
        Sfx.FLARE -> norm(mix(noiseBurst(0.4f, 0.5f, 0.001f, 0.06f, 1.2f), shift(noiseBurst(1.5f, 0.6f, 0.05f, 0.6f, 0.3f), 0.1f), tone(0.3f, 200f, 90f, 0.001f, 0.08f, 2, 0.5f)), 0.7f)
        Sfx.PULSE -> {
            val b = buf(1.2f); var ph = 0.0
            for (i in b.indices) { val t = i.toFloat() / srcRate; ph += 2 * PI * (60f + 900f * exp(-t * 8f)) / srcRate
                b[i] = ((if (sin(ph) > 0) 1f else -1f) * 0.4f + noise() * 0.5f * exp(-t * 6f)) * exp(-t * 3f) }
            norm(lowpass(b, 0.35f), 0.8f)
        }
        Sfx.ZAP -> { val b = buf(0.4f); for (i in b.indices) { val t = i.toFloat() / srcRate; b[i] = (if (noise() > 0.2f) 1f else -1f) * exp(-t * 9f) * 0.35f * (if ((i / (30 + v * 9)) % 2 == 0) 1f else 0.3f) }; norm(lowpass(b, 0.5f), 0.55f) }
        Sfx.BITE -> norm(mix(noiseBurst(0.25f, 0.25f, 0.001f, 0.04f, 1.5f), shift(noiseBurst(0.15f, 0.5f, 0f, 0.02f, 1f), 0.08f), tone(0.3f, 140f + v * 15f, 60f, 0.001f, 0.08f, 2, 0.7f)))
        Sfx.SNAP -> norm(mix(noiseBurst(0.12f, 0.7f, 0f, 0.012f, 1.5f), tone(0.15f, 400f, 200f, 0f, 0.03f, 1, 0.3f)), 0.7f)
        Sfx.HISS -> norm(highpass(noiseBurst(0.8f, 0.6f, 0.05f, 0.3f), 0.9f), 0.5f)
        Sfx.ROAR -> {
            val b = buf(2.8f); var ph = 0.0; var ph2 = 0.0
            for (i in b.indices) {
                val t = i.toFloat() / srcRate
                val f = 55f + v * 8f + 25f * sin(t * 2.3f) + 18f * exp(-t * 2f)
                ph += 2 * PI * f / srcRate; ph2 += 2 * PI * f * 1.51 / srcRate
                val formant = 0.6f + 0.4f * sin(t * 17f + v)
                b[i] = ((sin(ph) + sin(ph2) * 0.5 + sin(ph * 3) * 0.3).toFloat() * formant + noise() * 0.5f) * min(1f, t * 4f) * exp(-max(0f, t - 1.6f) * 2.5f)
            }
            norm(lowpass(b, 0.14f), 0.9f)
        }
        Sfx.WHALE -> {
            val b = buf(5f); var ph = 0.0
            for (i in b.indices) {
                val t = i.toFloat() / srcRate
                val f = when (v) {
                    0 -> 180f + 140f * sin(t / 5f * PI.toFloat())
                    1 -> 320f - 160f * (t / 5f) + 30f * sin(t * 3f)
                    else -> 140f + 220f * (t / 5f).let { it * it }
                }
                ph += 2 * PI * (f + sin(t * 6f) * 4f) / srcRate
                b[i] = (sin(ph) + sin(ph * 2) * 0.35f + sin(ph * 3) * 0.12f).toFloat() * sin(t / 5f * PI.toFloat()) * (0.7f + 0.3f * sin(t * 1.7f))
            }
            norm(lowpass(b, 0.3f), 0.65f)
        }
        Sfx.PALE_CALL -> {
            // a deep, chordal moan with a slow beating between the voices: the three notes of the Choir, wrong
            val b = buf(5.5f); var p1 = 0.0; var p2 = 0.0; var p3 = 0.0
            for (i in b.indices) {
                val t = i.toFloat() / srcRate
                val bend = 1f - 0.12f * (t / 5.5f) + 0.03f * sin(t * 2f)
                p1 += 2 * PI * (41f + v * 3f) * bend / srcRate; p2 += 2 * PI * (61.7f + v * 2f) * bend / srcRate; p3 += 2 * PI * (97f - v * 4f) * bend / srcRate
                val e = sin(t / 5.5f * PI.toFloat()).let { it * it }
                b[i] = ((sin(p1) + sin(p2) * 0.7 + sin(p3) * 0.45 + sin(p1 * 4.01) * 0.15).toFloat() + noise() * 0.12f) * e
            }
            norm(lowpass(b, 0.12f), 0.85f)
        }
        Sfx.PALE_CLICK -> {
            val b = buf(1.6f)
            val n = 6 + v * 3
            for (k in 0 until n) {
                val at = (k * (0.09f + v * 0.02f) + rndF() * 0.02f)
                val st = (at * srcRate).toInt()
                for (j in 0 until 500) { val i = st + j; if (i >= b.size) break; b[i] += noise() * exp(-j / 60f) * (1f - k * 0.05f) }
            }
            norm(lowpass(b, 0.35f), 0.6f)
        }
        Sfx.CRAFT -> norm(mix(noiseBurst(0.2f, 0.3f, 0.001f, 0.04f), shift(ring(0.6f, 420f, 0.15f, 0.4f, v), 0.15f), shift(noiseBurst(0.3f, 0.6f, 0.01f, 0.08f, 0.5f), 0.3f), shift(tone(0.5f, 880f, 880f, 0.01f, 0.2f, 2, 0.25f), 0.5f)), 0.6f)
        Sfx.QUEST -> norm(mix(tone(0.8f, 196f, 196f, 0.02f, 0.5f, 3, 0.3f), shift(tone(1f, 294f, 294f, 0.02f, 0.6f, 3, 0.3f), 0.25f), shift(tone(1.6f, 392f, 392f, 0.02f, 0.9f, 3, 0.3f), 0.5f)), 0.55f)
        Sfx.BUBBLES -> bubbles(v)
        Sfx.HEARTBEAT -> norm(mix(tone(0.2f, 55f, 40f, 0.005f, 0.06f, 2, 0.9f), shift(tone(0.2f, 50f, 36f, 0.005f, 0.07f, 2, 0.7f), 0.18f)), 0.8f)
        Sfx.DATA -> norm(mix(tone(0.1f, 1200f, 1200f, 0.002f, 0.04f, 1, 0.2f), shift(tone(0.1f, 1600f, 1600f, 0.002f, 0.04f, 1, 0.2f), 0.09f), shift(tone(0.1f, 2000f, 2000f, 0.002f, 0.04f, 1, 0.2f), 0.18f), shift(tone(0.4f, 1600f, 1600f, 0.002f, 0.15f, 1, 0.15f), 0.27f)), 0.45f)
        Sfx.IMPLODE -> {
            val b = buf(3.5f); var y = 0f
            for (i in b.indices) { val t = i.toFloat() / srcRate; y += (noise() - y) * (0.05f + 0.3f * exp(-t * 3f)); b[i] = y * 4f * exp(-t * 0.9f) * (if (t < 0.02f) t / 0.02f else 1f) }
            norm(mix(b, ring(3f, 48f, 1.2f, 1f, v), shift(bubbles(v), 0.5f)), 0.95f)
        }
        Sfx.REPAIR -> norm(mix(noiseBurst(0.6f, 0.5f, 0.02f, 0.2f, 0.6f), shift(ring(0.3f, 900f, 0.08f, 0.3f, v), 0.2f), shift(ring(0.3f, 900f, 0.08f, 0.3f, v), 0.45f)), 0.55f)
        Sfx.BEACON -> norm(mix(tone(1.2f, 523f, 523f, 0.02f, 0.6f, 2, 0.3f), shift(tone(1.2f, 784f, 784f, 0.02f, 0.6f, 2, 0.25f), 0.3f), shift(tone(1.6f, 1046f, 1046f, 0.02f, 0.8f, 1, 0.2f), 0.6f)), 0.55f)
        Sfx.CHOIR -> {
            val b = buf(7f); var p1 = 0.0; var p2 = 0.0; var p3 = 0.0
            for (i in b.indices) {
                val t = i.toFloat() / srcRate
                p1 += 2 * PI * 110.0 / srcRate; p2 += 2 * PI * 164.8 / srcRate; p3 += 2 * PI * 220.0 * (1 + 0.003 * sin(t * 5.0)) / srcRate
                val e = sin(t / 7f * PI.toFloat())
                val vowel = 0.6f + 0.4f * sin(t * 0.9f)
                b[i] = ((sin(p1) + sin(p2) * 0.8 + sin(p3) * 0.6 + sin(p1 * 3) * 0.2 * vowel + sin(p2 * 2) * 0.15 * vowel).toFloat()) * e
            }
            norm(b, 0.6f)
        }
    }

    private fun bubbles(v: Int): FloatArray {
        val b = buf(1.4f)
        var ph = 0.0
        val n = 5 + v * 3
        for (k in 0 until n) {
            val st = (rndF() * 1.1f * srcRate).toInt()
            val f0 = 400f + rndF() * 900f
            ph = 0.0
            for (j in 0 until 2200) {
                val i = st + j; if (i >= b.size) break
                val t = j.toFloat() / srcRate
                ph += 2 * PI * f0 * (1f + t * 6f) / srcRate
                b[i] += (sin(ph) * exp(-t * 28f) * 0.5f).toFloat()
            }
        }
        return norm(b, 0.45f)
    }

    private fun farGroan(v: Int): FloatArray = when (v) {
        0 -> norm(lowpass(tone(4f, 60f, 38f, 0.6f, 2f, 3, 0.8f, vib = 2f), 0.1f), 0.7f)
        1 -> norm(mix(ring(4f, 130f, 1.6f, 0.5f, v), noiseBurst(3f, 0.04f, 0.4f, 1.2f, 0.8f)), 0.6f)
        2 -> { val b = buf(5f); var y = 0f; for (i in b.indices) { val t = i.toFloat() / srcRate; y += (noise() - y) * 0.01f; b[i] = y * 8f * sin(t / 5f * PI.toFloat()) }; norm(b, 0.7f) }
        else -> norm(lowpass(tone(3.5f, 95f, 70f, 0.4f, 1.5f, 4, 0.7f, vib = 4f), 0.15f), 0.6f)
    }

    private fun clicks(v: Int): FloatArray {
        val b = buf(1.2f)
        val n = 4 + v * 4
        for (k in 0 until n) {
            val st = (k * 0.12f * srcRate + rndF() * 400).toInt()
            for (j in 0 until 300) { val i = st + j; if (i >= b.size) break; b[i] += noise() * exp(-j / 30f) }
        }
        return norm(highpass(b, 0.95f), 0.5f)
    }

    private fun stationTick(v: Int): FloatArray = norm(mix(ring(1f, 300f + v * 120f, 0.3f, 0.4f, v), shift(ring(1f, 450f + v * 60f, 0.3f, 0.3f, v), 0.4f)), 0.35f)

    // ------------------------------------------------------------------ beds

    private fun oceanLoop(): FloatArray {
        val b = buf(10f); var y = 0f; var y2 = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            val swell = 0.6f + 0.4f * sin(t / 10f * 2 * PI.toFloat() * 2f) * sin(t / 10f * 2 * PI.toFloat() * 3f + 1f)
            y += (noise() - y) * 0.006f
            y2 += (y - y2) * 0.05f
            b[i] = y2 * 16f * swell + sin(t * 2 * PI.toFloat() * 31f) * 0.03f
        }
        return norm(seamless(b, srcRate), 0.8f)
    }

    private fun thrusterLoop(): FloatArray {
        val b = buf(4f); var y = 0f; var ph = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            ph += 2 * PI * 95.0 / srcRate
            y += (noise() - y) * 0.15f
            val blade = 0.5f + 0.5f * sin((ph * 4).toFloat())
            b[i] = (sin(ph).toFloat() * 0.25f + sin(ph * 2).toFloat() * 0.15f) + y * 0.5f * blade + (if (noise() > 0.995f) noise() * 0.3f else 0f)
        }
        return norm(seamless(lowpass(b, 0.4f), srcRate / 4), 0.6f)
    }

    private fun humLoop(): FloatArray {
        val b = buf(6f); var ph = 0.0; var y = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            ph += 2 * PI * 60.0 / srcRate
            y += (noise() - y) * 0.02f
            b[i] = (sin(ph) * 0.4 + sin(ph * 2) * 0.2 + sin(ph * 3) * 0.08).toFloat() * (0.85f + 0.15f * sin(t * 0.7f)) + y * 0.6f
        }
        return norm(seamless(b, srcRate / 2), 0.5f)
    }

    private fun ventLoop(): FloatArray {
        val b = buf(6f); var y = 0f; var y2 = 0f
        for (i in b.indices) {
            y += (noise() - y) * 0.03f
            y2 += (y - y2) * 0.2f
            var s = y2 * 7f
            if (noise() > 0.9985f) s += noise() * 0.6f
            b[i] = s
        }
        return norm(seamless(b, srcRate / 2), 0.7f)
    }

    private fun stressLoop(): FloatArray {
        val b = buf(7f); var ph = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            ph += 2 * PI * (48f + 10f * sin(t * 0.9f) + 6f * sin(t * 3.1f)) / srcRate
            val grind = if (noise() > 0.7f) 1f else 0.5f
            b[i] = (sin(ph) + sin(ph * 2.3) * 0.5f).toFloat() * grind * (0.6f + 0.4f * sin(t * 1.3f))
        }
        return norm(seamless(lowpass(b, 0.2f), srcRate / 2), 0.7f)
    }

    private fun surfaceLoop(): FloatArray {
        val b = buf(9f); var y = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            y += (noise() - y) * 0.2f
            val w = max(0f, sin(t / 9f * 2 * PI.toFloat() * 3f)).let { it * it }
            b[i] = y * (0.2f + w * 0.6f)
        }
        return norm(seamless(lowpass(b, 0.3f), srcRate), 0.5f)
    }

    private fun fearLoop(): FloatArray {
        val b = buf(8f); var p1 = 0.0; var p2 = 0.0; var p3 = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            p1 += 2 * PI * 36.7 / srcRate; p2 += 2 * PI * 38.9 / srcRate; p3 += 2 * PI * 207.6 * (1 + 0.01 * sin(t * 0.8)) / srcRate
            b[i] = (sin(p1) + sin(p2) + sin(p3) * 0.12 * (0.5 + 0.5 * sin(t * 0.4))).toFloat() * 0.5f + noise() * 0.03f
        }
        return norm(seamless(b, srcRate), 0.7f)
    }
}
