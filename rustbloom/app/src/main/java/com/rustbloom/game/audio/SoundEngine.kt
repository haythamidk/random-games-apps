package com.rustbloom.game.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.content.Context
import com.rustbloom.game.game.Ambience
import com.rustbloom.game.game.Sfx
import com.rustbloom.game.game.SoundSink
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software audio engine: every sound is synthesized at startup, then mixed in real time on a dedicated audio thread
 * into one low-latency AudioTrack. Features: several variants per effect, random pitch/volume so nothing repeats
 * exactly, stereo positional sound, ambience beds that follow the world (wind, rain, surf, fire), scheduled one-shot
 * wildlife ambience (birds, crickets, owls, frogs, gulls, wolves) and quiet generative music.
 */
class SoundEngine(ctx: Context) : SoundSink {
    private val srcRate = 22050
    private val outRate: Int = try {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt() ?: 48000
    } catch (_: Throwable) { 48000 }

    @Volatile var master = 0.8f
    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    private val variants = arrayOfNulls<Array<FloatArray>>(Sfx.entries.size)
    private val lastVariant = IntArray(Sfx.entries.size) { -1 }
    private val lastPlayed = LongArray(Sfx.entries.size)
    @Volatile private var loaded = false

    // ambience beds
    private lateinit var bedWind: FloatArray
    private lateinit var bedRain: FloatArray
    private lateinit var bedSurf: FloatArray
    private lateinit var bedFire: FloatArray
    private lateinit var bedDrone: FloatArray
    private val amb = Ambience()
    private val lock = Any()

    // listener
    @Volatile private var lx = 0f
    @Volatile private var ly = 0f
    @Volatile private var lz = 0f
    @Volatile private var lyaw = 0f

    private class Voice(val data: FloatArray, var gainL: Float, var gainR: Float, val step: Double, val loop: Boolean) {
        var pos = 0.0
        var done = false
    }

    private class Bed(var data: FloatArray?) {
        var pos = 0.0
        var vol = 0f
        var target = 0f
        var lp = 0f
        var lpAmount = 0f
    }

    private val voices = ArrayList<Voice>()
    private val pending = ArrayList<Voice>()
    private val beds = Array(5) { Bed(null) }
    private var seed = 1234567

    // ------------------------------------------------------------------ lifecycle

    fun loadAll() {
        for (s in Sfx.entries) {
            val n = variantCount(s)
            variants[s.ordinal] = Array(n) { v -> seed = 1000 + s.ordinal * 97 + v * 7919; synth(s, v) }
        }
        bedWind = windLoop(); bedRain = rainLoop(); bedSurf = surfLoop(); bedFire = fireLoop(); bedDrone = droneLoop()
        beds[0].data = bedWind; beds[1].data = bedRain; beds[2].data = bedSurf; beds[3].data = bedFire; beds[4].data = bedDrone
        loaded = true
        start()
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

    fun pause() { paused = true; try { track?.pause() } catch (_: Throwable) {} }
    fun resume() { if (!loaded) return; paused = false; try { track?.play() } catch (_: Throwable) {} }

    fun release() {
        running = false
        try { thread?.join(300) } catch (_: Throwable) {}
        try { track?.stop(); track?.release() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ SoundSink

    override fun play(s: Sfx, volume: Float, pitch: Float) = start(s, volume, pitch, 0f, false)

    override fun playAt(s: Sfx, x: Float, y: Float, z: Float, volume: Float, pitch: Float) {
        val dx = x - lx; val dy = y - ly; val dz = z - lz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val range = 60f
        if (d > range) return
        val att = (1f - d / range).let { it * it } * 0.9f + 0.1f * (1f - d / range)
        // pan from the listener's right vector (-cos yaw, sin yaw)
        val pan = if (d > 0.5f) ((-cos(lyaw) * dx + sin(lyaw) * dz) / d) else 0f
        start(s, volume * att, pitch, pan, true)
    }

    override fun setListener(x: Float, y: Float, z: Float, yaw: Float) { lx = x; ly = y; lz = z; lyaw = yaw }

    override fun setAmbience(a: Ambience) {
        synchronized(lock) {
            amb.wind = a.wind; amb.rain = a.rain; amb.snow = a.snow; amb.fire = a.fire; amb.ocean = a.ocean
            amb.night = a.night; amb.forest = a.forest; amb.marsh = a.marsh; amb.cold = a.cold; amb.desert = a.desert
            amb.sheltered = a.sheltered; amb.underwater = a.underwater; amb.combat = a.combat; amb.paused = a.paused
        }
    }

    private fun variantCount(s: Sfx) = when (s) {
        Sfx.STEP, Sfx.STEP_STONE, Sfx.STEP_SAND, Sfx.STEP_SNOW, Sfx.STEP_WOOD, Sfx.STEP_WATER -> 5
        Sfx.CHOP, Sfx.MINE, Sfx.HIT_FLESH, Sfx.HIT_METAL, Sfx.SWING, Sfx.ARROW_HIT, Sfx.HURT, Sfx.ANIMAL_HURT -> 4
        Sfx.EAT, Sfx.BUILD, Sfx.PICKUP, Sfx.MACHINE_ALERT, Sfx.GROWL, Sfx.SPLASH, Sfx.THUNDER -> 3
        else -> 2
    }

    private fun start(s: Sfx, volume: Float, pitch: Float, pan: Float, positional: Boolean) {
        if (!loaded || paused) return
        val vars = variants[s.ordinal] ?: return
        val now = System.nanoTime()
        // a tiny cooldown stops identical sounds stacking into one loud click
        if (now - lastPlayed[s.ordinal] < 35_000_000L && s != Sfx.UI) return
        lastPlayed[s.ordinal] = now
        var v = rnd(vars.size)
        if (vars.size > 1 && v == lastVariant[s.ordinal]) v = (v + 1) % vars.size
        lastVariant[s.ordinal] = v
        val vol = (volume * master * (0.9f + rndF() * 0.2f)).coerceIn(0f, 1.5f)
        if (vol < 0.005f) return
        val p = pitch * (0.94f + rndF() * 0.12f)
        val l = vol * min(1f, 1f - pan) * (if (positional) 1f else 1f)
        val r = vol * min(1f, 1f + pan)
        val voice = Voice(vars[v], l, r, srcRate.toDouble() / outRate * p, false)
        synchronized(pending) { pending.add(voice) }
    }

    private fun rnd(n: Int): Int { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0x7fffff) % n }
    private fun rndF(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xffff) / 65535f }

    // ------------------------------------------------------------------ mixing

    private val music = MusicGen(this::outRateF)
    private fun outRateF() = outRate.toFloat()

    private var ambTimer = 0f
    private var lpL = 0f
    private var lpR = 0f

    private fun mixLoop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) } catch (_: Throwable) {}
        val block = 256
        val mixL = FloatArray(block); val mixR = FloatArray(block)
        val out = ShortArray(block * 2)
        val a = Ambience()
        while (running) {
            if (paused) { try { Thread.sleep(50) } catch (_: InterruptedException) {}; continue }
            synchronized(pending) { voices.addAll(pending); pending.clear() }
            synchronized(lock) {
                a.wind = amb.wind; a.rain = amb.rain; a.snow = amb.snow; a.fire = amb.fire; a.ocean = amb.ocean
                a.night = amb.night; a.forest = amb.forest; a.marsh = amb.marsh; a.cold = amb.cold; a.desert = amb.desert
                a.sheltered = amb.sheltered; a.underwater = amb.underwater; a.combat = amb.combat; a.paused = amb.paused
            }
            val dt = block.toFloat() / outRate
            scheduleAmbience(a, dt)
            // bed targets
            val m = master
            beds[0].target = (a.wind * 0.42f + (if (a.cold > 0.6f) 0.06f else 0f)) * m
            beds[1].target = (if (a.snow) 0f else a.rain * 0.5f) * m
            beds[1].lpAmount = if (a.sheltered) 0.8f else 0f
            beds[2].target = a.ocean * 0.45f * m
            beds[3].target = a.fire * 0.45f * m
            beds[4].target = if (a.underwater) 0.5f * m else 0f
            for (i in 0 until block) { mixL[i] = 0f; mixR[i] = 0f }
            // beds
            for (b in beds) {
                val data = b.data ?: continue
                if (b.target < 0.001f && b.vol < 0.001f) continue
                val step = srcRate.toDouble() / outRate
                for (i in 0 until block) {
                    b.vol += (b.target - b.vol) * 0.0004f
                    val idx = b.pos.toInt()
                    val f = (b.pos - idx).toFloat()
                    val s0 = data[idx]; val s1 = data[if (idx + 1 >= data.size) 0 else idx + 1]
                    var smp = s0 + (s1 - s0) * f
                    if (b.lpAmount > 0f) { b.lp += (smp - b.lp) * (1f - b.lpAmount * 0.9f); smp = b.lp }
                    smp *= b.vol
                    mixL[i] += smp; mixR[i] += smp
                    b.pos += step
                    if (b.pos >= data.size) b.pos -= data.size
                }
            }
            // effect voices
            if (voices.size > 28) {
                voices.sortBy { -(it.gainL + it.gainR) }
                while (voices.size > 28) voices.removeAt(voices.size - 1)
            }
            for (v in voices) {
                val data = v.data
                for (i in 0 until block) {
                    val idx = v.pos.toInt()
                    if (idx >= data.size - 1) { v.done = true; break }
                    val f = (v.pos - idx).toFloat()
                    val smp = data[idx] + (data[idx + 1] - data[idx]) * f
                    mixL[i] += smp * v.gainL; mixR[i] += smp * v.gainR
                    v.pos += v.step
                }
            }
            voices.removeAll { it.done }
            // music
            music.mix(mixL, mixR, block, m * 0.22f, a)
            // master: underwater muffling and a soft limiter
            val lpk = if (a.underwater) 0.08f else 1f
            for (i in 0 until block) {
                var l = mixL[i]; var r = mixR[i]
                if (lpk < 1f) { lpL += (l - lpL) * lpk; lpR += (r - lpR) * lpk; l = lpL; r = lpR }
                l = softClip(l); r = softClip(r)
                out[i * 2] = (l * 32000f).toInt().toShort()
                out[i * 2 + 1] = (r * 32000f).toInt().toShort()
            }
            try { track?.write(out, 0, out.size) } catch (_: Throwable) { try { Thread.sleep(5) } catch (_: InterruptedException) {} }
        }
    }

    private fun softClip(x: Float): Float = if (x > 1f) 1f - 0.5f / (x + 0.5f) * 0.5f else if (x < -1f) -1f + 0.5f / (-x + 0.5f) * 0.5f else x - x * x * x * 0.08f

    // ------------------------------------------------------------------ ambient wildlife director

    private val ambCooldown = FloatArray(8)
    private val ambientSounds = HashMap<String, Array<FloatArray>>()

    private fun ambientSet(name: String, n: Int, gen: (Int) -> FloatArray): Array<FloatArray> =
        ambientSounds.getOrPut(name) { Array(n) { v -> seed = name.hashCode() + v * 131; gen(v) } }

    private fun scheduleAmbience(a: Ambience, dt: Float) {
        if (a.paused || a.underwater) return
        for (i in ambCooldown.indices) ambCooldown[i] -= dt
        val day = 1f - a.night
        // birdsong: daytime, more in forests, none in heavy rain
        if (ambCooldown[0] <= 0f) {
            ambCooldown[0] = 1.5f + rndF() * 6f
            val chance = day * (0.25f + a.forest * 0.6f) * (1f - a.rain) * (1f - a.desert * 0.8f) * (1f - a.cold * 0.5f)
            if (rndF() < chance) ambient(ambientSet("bird", 6) { birdCall(it) }, 0.16f + rndF() * 0.12f)
        }
        // crickets at night (warm places), frogs in the marsh
        if (ambCooldown[1] <= 0f) {
            ambCooldown[1] = 0.6f + rndF() * 2.5f
            if (rndF() < a.night * (1f - a.cold) * (1f - a.rain * 0.7f) * 0.8f) ambient(ambientSet("cricket", 4) { cricket(it) }, 0.07f + rndF() * 0.06f)
            if (rndF() < a.marsh * 0.5f * (0.4f + a.night)) ambient(ambientSet("frog", 3) { frog(it) }, 0.12f)
        }
        // owls and distant wolves at night
        if (ambCooldown[2] <= 0f) {
            ambCooldown[2] = 12f + rndF() * 25f
            if (rndF() < a.night * a.forest * 0.6f) ambient(ambientSet("owl", 2) { owl(it) }, 0.18f)
            else if (rndF() < a.night * a.cold * 0.5f) ambient(ambientSet("howl", 2) { howl(it) }, 0.12f)
        }
        // gulls on the coast
        if (ambCooldown[3] <= 0f) {
            ambCooldown[3] = 6f + rndF() * 12f
            if (rndF() < a.ocean * day * 0.7f) ambient(ambientSet("gull", 3) { gull(it) }, 0.12f)
        }
        // wind gusts through the trees
        if (ambCooldown[4] <= 0f) {
            ambCooldown[4] = 8f + rndF() * 14f
            if (rndF() < a.wind * (0.3f + a.forest)) ambient(ambientSet("gust", 3) { gust(it) }, 0.18f * (0.5f + a.wind))
        }
    }

    private fun ambient(set: Array<FloatArray>, vol: Float) {
        val d = set[rnd(set.size)]
        val pan = rndF() * 1.6f - 0.8f
        val v = vol * master
        synchronized(pending) { pending.add(Voice(d, v * min(1f, 1f - pan), v * min(1f, 1f + pan), srcRate.toDouble() / outRate * (0.92f + rndF() * 0.16f), false)) }
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
            val f = f0 + (f1 - f0) * t + vib * sin(i.toFloat() / srcRate * 34f) * f0 * 0.02f
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

    /** Short footstep: filtered noise with a body thump; character set by the surface. */
    private fun step(k: Float, decay: Float, thump: Float, crunch: Float, v: Int): FloatArray {
        val b = buf(0.16f)
        var y = 0f
        for (i in b.indices) {
            y += (noise() - y) * k
            var s = y * env(i, 0.003f, decay)
            if (crunch > 0f && noise() > 0.85f) s += noise() * crunch * env(i, 0.002f, decay * 1.5f)
            b[i] = s
        }
        val th = tone(0.1f, 90f + v * 7f, 60f, 0.002f, 0.025f, 1, thump)
        return norm(mix(b, th), 0.55f + v * 0.04f)
    }

    private fun synth(s: Sfx, v: Int): FloatArray = when (s) {
        Sfx.STEP -> step(0.16f + v * 0.02f, 0.03f, 0.25f, 0.2f, v)
        Sfx.STEP_STONE -> step(0.5f, 0.018f, 0.35f, 0f, v)
        Sfx.STEP_SAND -> step(0.3f, 0.05f, 0.1f, 0.5f, v)
        Sfx.STEP_SNOW -> step(0.12f, 0.06f, 0.15f, 0.7f, v)
        Sfx.STEP_WOOD -> norm(mix(tone(0.15f, 180f + v * 15f, 140f, 0.001f, 0.03f, 3, 0.6f), noiseBurst(0.08f, 0.4f, 0f, 0.012f, 0.4f)), 0.6f)
        Sfx.STEP_WATER -> norm(mix(noiseBurst(0.25f, 0.35f, 0.01f, 0.07f), tone(0.2f, 500f + v * 80f, 250f, 0.005f, 0.05f, 1, 0.2f)), 0.55f)
        Sfx.CHOP -> norm(mix(noiseBurst(0.3f, 0.22f + v * 0.03f, 0.001f, 0.05f, 1.2f), tone(0.25f, 170f + v * 12f, 95f, 0.001f, 0.06f, 3, 0.6f)))
        Sfx.MINE -> norm(mix(noiseBurst(0.3f, 0.6f, 0.0005f, 0.035f), tone(0.35f, 1300f + v * 140f, 1180f, 0.0005f, 0.06f, 3, 0.3f), tone(0.3f, 2600f + v * 90f, 2500f, 0f, 0.03f, 1, 0.12f)))
        Sfx.SWING -> {
            val b = buf(0.28f); var y = 0f
            for (i in b.indices) { val t = i.toFloat() / b.size; y += (noise() - y) * (0.04f + 0.3f * sin(t * PI.toFloat()) + v * 0.02f); b[i] = y * sin(t * PI.toFloat()) }
            norm(b, 0.6f)
        }
        Sfx.HIT_FLESH -> norm(mix(noiseBurst(0.2f, 0.1f + v * 0.02f, 0.001f, 0.05f, 1.5f), tone(0.22f, 120f + v * 10f, 55f, 0.001f, 0.07f, 1, 0.8f)))
        Sfx.HIT_METAL -> norm(mix(tone(0.6f, 820f + v * 70f, 800f, 0.0005f, 0.14f, 4, 0.35f), tone(0.5f, 1310f + v * 55f, 1290f, 0.0005f, 0.09f, 2, 0.2f), tone(0.6f, 2210f, 2200f, 0f, 0.05f, 1, 0.1f), noiseBurst(0.1f, 0.7f, 0f, 0.012f, 0.8f)))
        Sfx.BOW_DRAW -> { val b = buf(0.5f); var y = 0f; for (i in b.indices) { val t = i.toFloat() / b.size; y += (noise() - y) * 0.08f; b[i] = y * t * (1 - t) * 4f + sin(i * 0.02f + t * 30f) * 0.03f * t }; norm(b, 0.4f) }
        Sfx.BOW_RELEASE -> norm(mix(tone(0.4f, 210f + v * 10f, 150f, 0.001f, 0.09f, 4, 0.6f), noiseBurst(0.15f, 0.3f, 0f, 0.03f, 0.6f)), 0.7f)
        Sfx.ARROW_HIT -> norm(mix(noiseBurst(0.15f, 0.35f, 0f, 0.02f, 1.2f), tone(0.18f, 300f + v * 30f, 190f, 0f, 0.035f, 2, 0.5f)), 0.7f)
        Sfx.PICKUP -> norm(mix(noiseBurst(0.12f, 0.3f, 0.002f, 0.03f, 0.6f), tone(0.12f, 700f + v * 60f, 1000f, 0.004f, 0.04f, 1, 0.25f)), 0.45f)
        Sfx.CRAFT -> norm(mix(noiseBurst(0.15f, 0.25f, 0.001f, 0.03f), shift(noiseBurst(0.15f, 0.35f, 0.001f, 0.03f), 0.12f), shift(tone(0.3f, 660f, 660f, 0.005f, 0.1f, 2, 0.25f), 0.25f)), 0.6f)
        Sfx.EAT -> { val b = buf(0.4f); for (i in b.indices) { val t = i.toFloat() / srcRate; val gate = if (((t * (12 + v * 2)).toInt() % 2) == 0) 1f else 0.15f; b[i] = noise() * gate * exp(-t * 5f) * 0.7f }; norm(lowpass(b, 0.3f), 0.5f) }
        Sfx.DRINK -> { val b = buf(0.6f); var ph = 0.0; for (i in b.indices) { val t = i.toFloat() / srcRate; val f = 280 + 380 * abs(sin(t * (16.0 + v * 3))); ph += 2 * PI * f / srcRate; b[i] = (sin(ph) * 0.3 * exp(-t * 2.5)).toFloat() }; norm(b, 0.5f) }
        Sfx.HURT -> norm(mix(tone(0.35f, 170f + v * 15f, 85f, 0.002f, 0.1f, 3, 0.6f), noiseBurst(0.2f, 0.1f, 0.001f, 0.06f, 0.8f)), 0.8f)
        Sfx.ANIMAL_HURT -> norm(mix(tone(0.4f, 420f + v * 60f, 260f, 0.01f, 0.12f, 3, 0.5f, vib = 2f), noiseBurst(0.3f, 0.2f, 0.01f, 0.1f, 0.3f)), 0.7f)
        Sfx.GROWL -> { val b = buf(1.2f); var ph = 0.0; for (i in b.indices) { val t = i.toFloat() / srcRate; ph += 2 * PI * (80 + v * 12 + 10 * sin(t * 9.0)) / srcRate; b[i] = (sin(ph) + sin(ph * 2.01) * 0.5f + noise() * 0.6f).toFloat() * (0.6f + 0.4f * sin(t * 23f + v)) * env(i, 0.15f, 0.5f) }; norm(lowpass(b, 0.12f), 0.75f) }
        Sfx.SCREECH -> norm(mix(tone(0.7f, 1800f + v * 200f, 900f, 0.01f, 0.2f, 3, 0.4f, vib = 6f), noiseBurst(0.6f, 0.6f, 0.01f, 0.15f, 0.25f)), 0.6f)
        Sfx.MACHINE_ALERT -> norm(mix(tone(0.2f, 850f + v * 60f, 1350f, 0.002f, 0.1f, 2, 0.35f), shift(tone(0.28f, 1380f, 700f + v * 50f, 0.002f, 0.12f, 2, 0.35f), 0.17f)), 0.6f)
        Sfx.MACHINE_DIE -> norm(mix(tone(1.3f, 600f + v * 40f, 55f, 0.005f, 0.5f, 3, 0.4f), noiseBurst(0.9f, 0.18f, 0.002f, 0.28f, 0.8f), shift(noiseBurst(0.4f, 0.6f, 0f, 0.05f, 0.6f), 0.3f)))
        Sfx.BOLT -> norm(mix(tone(0.4f, 1700f + v * 100f, 280f, 0.001f, 0.13f, 1, 0.35f), noiseBurst(0.35f, 0.5f, 0.001f, 0.08f, 0.5f)), 0.6f)
        Sfx.STOMP -> norm(mix(tone(0.9f, 65f + v * 5f, 32f, 0.002f, 0.32f, 2, 0.9f), noiseBurst(0.7f, 0.05f, 0.002f, 0.22f, 1.5f)))
        Sfx.BUILD -> norm(mix(noiseBurst(0.22f, 0.2f, 0.001f, 0.04f), shift(noiseBurst(0.2f, 0.25f, 0.001f, 0.04f, 0.8f), 0.13f + v * 0.02f), tone(0.3f, 140f + v * 10f, 100f, 0.001f, 0.08f, 3, 0.4f)), 0.7f)
        Sfx.UI -> tone(0.05f, 1050f + v * 80f, 1150f, 0.001f, 0.015f, 1, 0.2f)
        Sfx.UI_OPEN -> mix(tone(0.08f, 700f, 900f, 0.002f, 0.03f, 1, 0.18f), shift(tone(0.1f, 1000f, 1200f, 0.002f, 0.04f, 1, 0.15f), 0.05f))
        Sfx.LEVEL_UP -> norm(mix(tone(0.25f, 523f, 523f, 0.005f, 0.18f, 2, 0.3f), shift(tone(0.25f, 659f, 659f, 0.005f, 0.18f, 2, 0.3f), 0.12f), shift(tone(0.7f, 784f, 784f, 0.005f, 0.35f, 2, 0.3f), 0.24f), shift(tone(0.8f, 1046f, 1046f, 0.01f, 0.4f, 1, 0.15f), 0.3f)), 0.6f)
        Sfx.QUEST -> norm(mix(tone(0.5f, 392f, 392f, 0.01f, 0.35f, 3, 0.25f), shift(tone(0.7f, 587f, 587f, 0.01f, 0.45f, 3, 0.25f), 0.2f), shift(tone(1.0f, 784f, 784f, 0.01f, 0.65f, 3, 0.25f), 0.4f)), 0.6f)
        Sfx.THUNDER -> {
            val b = buf(3.5f); var y = 0f; var y2 = 0f
            for (i in b.indices) { val t = i.toFloat() / srcRate; y += (noise() - y) * 0.03f; y2 += (y - y2) * 0.2f
                val crack = if (t < 0.15f) noise() * (1f - t / 0.15f) * 0.6f else 0f
                b[i] = y2 * 7f * exp(-t * (0.9f + v * 0.2f)) * (0.6f + 0.4f * sin(t * (6f + v)) * sin(t * 2.3f)) + crack }
            norm(b, 0.95f)
        }
        Sfx.SPIRE -> { val b = buf(2.8f); var p1 = 0.0; var p2 = 0.0; var p3 = 0.0
            for (i in b.indices) { val t = i.toFloat() / srcRate; p1 += 2 * PI * 110 / srcRate; p2 += 2 * PI * 165.5 / srcRate; p3 += 2 * PI * 220.8 / srcRate
                b[i] = ((sin(p1) + 0.7 * sin(p2) + 0.5 * sin(p3)) * 0.22 * (t / 0.4f).coerceAtMost(1f) * exp(-t * 0.8f)).toFloat() }
            norm(b, 0.7f) }
        Sfx.SPLASH -> norm(mix(noiseBurst(0.7f, 0.4f, 0.004f, 0.16f), shift(noiseBurst(0.3f, 0.6f, 0.002f, 0.05f, 0.5f), 0.08f)), 0.6f)
        Sfx.CAST -> norm(mix(noiseBurst(0.35f, 0.08f, 0.05f, 0.1f), tone(0.3f, 900f, 400f, 0.01f, 0.08f, 1, 0.1f)), 0.4f)
        Sfx.ZAP -> { val b = buf(0.35f); for (i in b.indices) { val t = i.toFloat() / srcRate; b[i] = (if (noise() > 0.3f) 1f else -1f) * exp(-t * 11f) * 0.35f * (if ((i / (35 + v * 8)) % 2 == 0) 1f else 0.3f) }; norm(lowpass(b, 0.5f), 0.6f) }
        Sfx.DEATH -> norm(mix(tone(1.6f, 220f, 110f, 0.01f, 0.75f, 3, 0.35f), shift(tone(1.6f, 165f, 82f, 0.01f, 0.75f, 3, 0.3f), 0.3f)), 0.7f)
    }

    // ------------------------------------------------------------------ ambience sounds

    private fun birdCall(v: Int): FloatArray {
        val b = buf(1.2f)
        val base = 2200f + (v % 3) * 700f
        val notes = 2 + v % 4
        var ph = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            val noteLen = 0.11f + (v % 2) * 0.05f
            val idx = (t / (noteLen + 0.05f)).toInt()
            if (idx >= notes) break
            val lt = t - idx * (noteLen + 0.05f)
            if (lt > noteLen) continue
            val u = lt / noteLen
            val sweep = when (v % 3) { 0 -> 1f + u * 0.5f; 1 -> 1.5f - u * 0.6f; else -> 1.2f + sin(u * 20f) * 0.15f }
            val f = base * sweep * (1f + idx * 0.06f * (if (v % 2 == 0) 1f else -1f))
            ph += 2 * PI * f / srcRate
            b[i] = sin(ph).toFloat() * sin(u * PI.toFloat()) * 0.5f * (0.7f + 0.3f * sin(lt * 180f))
        }
        return norm(b, 0.5f)
    }

    private fun cricket(v: Int): FloatArray {
        val b = buf(0.45f)
        val f = 4300f + v * 250f
        var ph = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            ph += 2 * PI * f / srcRate
            val pulse = ((t * (28f + v * 3)).toInt() % 4 != 3)
            val gate = if (pulse) sin(((t * (28f + v * 3)) % 1f) * PI.toFloat()) else 0f
            b[i] = sin(ph).toFloat() * gate * 0.4f * env(i, 0.01f, 0.25f)
        }
        return b
    }

    private fun frog(v: Int): FloatArray {
        val b = buf(0.5f)
        var ph = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            ph += 2 * PI * (130 + v * 25) / srcRate
            val am = (0.5f + 0.5f * sin(t * 2f * PI.toFloat() * (40f + v * 6))).let { it * it }
            b[i] = (sin(ph) + sin(ph * 3) * 0.4f).toFloat() * am * env(i, 0.02f, 0.18f)
        }
        return norm(lowpass(b, 0.4f), 0.5f)
    }

    private fun owl(v: Int): FloatArray {
        val a = tone(0.5f, 380f - v * 30f, 350f - v * 30f, 0.06f, 0.25f, 2, 0.5f)
        val c = tone(0.7f, 360f - v * 30f, 330f - v * 30f, 0.06f, 0.35f, 2, 0.5f)
        return norm(lowpass(mix(a, shift(c, 0.55f), shift(tone(0.4f, 360f, 340f, 0.05f, 0.2f, 2, 0.3f), 1.25f)), 0.3f), 0.5f)
    }

    private fun howl(v: Int): FloatArray {
        val b = buf(3.2f)
        var ph = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            val f = when { t < 0.6f -> 380f + t / 0.6f * 260f; t < 2.2f -> 640f + sin(t * 3f) * 30f; else -> 640f - (t - 2.2f) * 220f } * (1f + v * 0.06f)
            ph += 2 * PI * (f + sin(t * 30f) * 6f) / srcRate
            b[i] = (sin(ph) + sin(ph * 2) * 0.25f + noise() * 0.05f).toFloat() * min(1f, t * 3f) * exp(-max(0f, t - 2.4f) * 3f)
        }
        return norm(lowpass(b, 0.35f), 0.5f)
    }

    private fun gull(v: Int): FloatArray {
        val b = buf(0.9f)
        var ph = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            val call = (t / 0.28f).toInt()
            if (call > 2) break
            val lt = (t % 0.28f) / 0.22f
            if (lt > 1f) continue
            val f = (1300f + v * 150f) * (1f + 0.4f * sin(lt * PI.toFloat()))
            ph += 2 * PI * f / srcRate
            b[i] = (sin(ph) + 0.5 * sin(ph * 2) + 0.3 * sin(ph * 3)).toFloat() * sin(lt * PI.toFloat()) * 0.4f
        }
        return norm(b, 0.5f)
    }

    private fun gust(v: Int): FloatArray {
        val b = buf(4f)
        var y = 0f; var y2 = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            y += (noise() - y) * (0.02f + 0.03f * sin(t * 0.8f + v))
            y2 += (y - y2) * 0.25f
            b[i] = y2 * sin(t / 4f * PI.toFloat()) * 3f
        }
        return norm(highpass(b, 0.98f), 0.6f)
    }

    // ------------------------------------------------------------------ beds (seamless loops)

    private fun windLoop(): FloatArray {
        val b = buf(8f); var y = 0f; var y2 = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            val gust = 0.5f + 0.5f * sin(t * 0.7f) * sin(t * 0.29f + 1f)
            y += (noise() - y) * (0.008f + 0.02f * gust)
            y2 += (y - y2) * 0.3f
            b[i] = y2 * 5f * (0.4f + gust * 0.6f)
        }
        return norm(seamless(highpass(b, 0.995f), srcRate / 2), 0.7f)
    }

    private fun rainLoop(): FloatArray {
        val b = buf(5f); var y = 0f; var p = 0f
        for (i in b.indices) {
            y += (noise() - y) * 0.5f
            p += (y - p) * 0.08f
            var v = (y - p) * 0.4f
            if (noise() > 0.997f) v += noise() * 0.5f
            b[i] = v
        }
        return norm(seamless(b, srcRate / 4), 0.6f)
    }

    private fun surfLoop(): FloatArray {
        val b = buf(14f); var y = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            y += (noise() - y) * 0.07f
            // waves: swell up, crash, wash back
            val w1 = sin(t / 14f * 2 * PI.toFloat() * 2f).let { max(0f, it) }.let { it * it }
            val w2 = sin(t / 14f * 2 * PI.toFloat() * 3f + 1.3f).let { max(0f, it) }.let { it * it * it }
            b[i] = y * (0.25f + w1 * 0.8f + w2 * 0.5f)
        }
        return norm(seamless(b, srcRate), 0.7f)
    }

    private fun fireLoop(): FloatArray {
        val b = buf(5f); var y = 0f; var crackle = 0f
        for (i in b.indices) {
            y += (noise() - y) * 0.04f
            if (noise() > 0.9992f) crackle = 0.6f + noise() * 0.4f
            crackle *= 0.992f
            b[i] = y * 0.8f + noise() * crackle * 0.5f
        }
        return norm(seamless(b, srcRate / 4), 0.6f)
    }

    private fun droneLoop(): FloatArray {
        val b = buf(6f); var y = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            y += (noise() - y) * 0.01f
            b[i] = y * 2f + sin(t * 2 * PI.toFloat() * 55f) * 0.05f
        }
        return norm(seamless(b, srcRate / 2), 0.4f)
    }
}

/**
 * Quiet generative ambient music: slow pad chords with sparse bell melodies in a pentatonic scale,
 * played in sessions with long silences between them so it never gets old. Darker tones at night,
 * a low pulse when machines are hunting the player.
 */
class MusicGen(private val rate: () -> Float) {
    private class Note(val freq: Float, val start: Long, val len: Long, val gain: Float, val bell: Boolean) { var ph = 0.0 }

    private val notes = ArrayList<Note>()
    private var t: Long = 0
    private var sessionLeft = 0L
    private var silenceLeft = (45 * 48000).toLong()
    private var nextEvent = 0L
    private var chord = 0
    private var seed = 4242
    private var level = 0f
    private val delayL = FloatArray(48000)
    private val delayR = FloatArray(48000)
    private var dIdx = 0

    private fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xffff) / 65535f }

    private val dayScale = floatArrayOf(0f, 2f, 4f, 7f, 9f)     // major pentatonic
    private val nightScale = floatArrayOf(0f, 3f, 5f, 7f, 10f)  // minor pentatonic
    private val chordsDay = arrayOf(intArrayOf(0, 4, 7), intArrayOf(5, 9, 12), intArrayOf(-3, 0, 4), intArrayOf(7, 11, 14))
    private val chordsNight = arrayOf(intArrayOf(0, 3, 7), intArrayOf(-4, 0, 3), intArrayOf(5, 8, 12), intArrayOf(-2, 2, 5))

    private fun hz(semi: Float, root: Float) = root * Math.pow(2.0, semi / 12.0).toFloat()

    fun mix(l: FloatArray, r: FloatArray, n: Int, vol: Float, a: Ambience) {
        val sr = rate()
        val night = a.night > 0.5f
        if (sessionLeft <= 0L) {
            silenceLeft -= n
            if (silenceLeft <= 0L) { sessionLeft = ((60 + rnd() * 50) * sr).toLong(); silenceLeft = ((120 + rnd() * 200) * sr).toLong(); nextEvent = t }
        } else sessionLeft -= n
        val playing = sessionLeft > 0L || a.combat
        val targetLevel = if (playing && !a.paused) 1f else 0f
        val root = if (night) 196f else 220f
        if (playing && t >= nextEvent) {
            val beat = (sr * (if (a.combat) 0.45f else 0.9f)).toLong()
            if (rnd() < 0.3f || notes.isEmpty()) {
                chord = (chord + 1 + (rnd() * 2).toInt()) % 4
                val ch = if (night) chordsNight[chord] else chordsDay[chord]
                for (s in ch) notes.add(Note(hz(s.toFloat() - 12f, root), t, beat * 8, 0.05f, false))
            }
            if (rnd() < (if (a.combat) 0.9f else 0.55f)) {
                val sc = if (night || a.combat) nightScale else dayScale
                val deg = sc[(rnd() * sc.size).toInt()] + (if (rnd() < 0.4f) 12f else 0f)
                notes.add(Note(hz(deg, root * 2f), t, (beat * (1 + rnd() * 2)).toLong(), 0.06f, true))
            }
            if (a.combat) notes.add(Note(hz(-24f, root), t, beat / 2, 0.12f, false))
            nextEvent = t + beat * (1 + (rnd() * 2).toInt())
        }
        for (i in 0 until n) {
            level += (targetLevel - level) * 0.00002f
            var sL = 0f; var sR = 0f
            if (level > 0.001f) {
                for (note in notes) {
                    val age = t - note.start
                    if (age < 0) continue
                    val u = age.toFloat() / note.len
                    if (u >= 1.3f) continue
                    note.ph += 2.0 * Math.PI * note.freq / sr
                    val e = if (note.bell) Math.exp(-age / (sr * 0.9)).toFloat() * min(1f, age / (sr * 0.005f))
                    else (min(1f, u * 4f) * (1f - max(0f, (u - 0.75f) / 0.55f))).coerceIn(0f, 1f)
                    val w = if (note.bell) (sin(note.ph) + 0.3 * sin(note.ph * 2.76) * Math.exp(-age / (sr * 0.2))).toFloat()
                    else (sin(note.ph) + 0.25 * sin(note.ph * 2.0 + 0.3) + 0.12 * sin(note.ph * 3.0)).toFloat()
                    val pan = ((note.freq * 7.3f) % 1f) - 0.5f
                    val s = w * e * note.gain
                    sL += s * (1f - pan * 0.6f); sR += s * (1f + pan * 0.6f)
                }
            }
            // simple stereo delay for space
            val di = dIdx
            val tapL = delayL[(di + delayL.size - (sr * 0.37f).toInt()) % delayL.size]
            val tapR = delayR[(di + delayR.size - (sr * 0.53f).toInt()) % delayR.size]
            delayL[di] = sL + tapR * 0.45f
            delayR[di] = sR + tapL * 0.45f
            dIdx = (di + 1) % delayL.size
            l[i] += (sL + tapL * 0.5f) * vol * level
            r[i] += (sR + tapR * 0.5f) * vol * level
            t++
        }
        if (notes.size > 40 || (t % 4096L) < n) notes.removeAll { (t - it.start) > it.len * 1.3f }
    }
}
