package com.worldwake.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.worldwake.game.game.Ambience
import com.worldwake.game.game.Sfx
import com.worldwake.game.game.SoundSink
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software audio engine: every sound is synthesized at startup and mixed in real time on its own thread.
 *
 * Two buses. The suit bus (breathing, WREN, the interface, your own boots through the suit) is always clear.
 * The world bus (wind, rain, machines, anything positional) passes through a filter that follows the air: on the
 * airless world it is thin and muffled, and it opens up as the atmosphere thickens until, with the helmet off,
 * you hear the planet directly.
 */
class SoundEngine(ctx: Context) : SoundSink {
    private val srcRate = 22050
    private val outRate: Int = try {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt() ?: 48000
    } catch (_: Throwable) { 48000 }

    @Volatile var master = 0.8f
    @Volatile var musicVolume = 0.7f
    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

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

    private class Voice(val data: FloatArray, var gainL: Float, var gainR: Float, val step: Double, val world: Boolean, var delay: Int = 0) {
        var pos = 0.0
        var done = false
    }

    private class Bed(val world: Boolean) {
        var data: FloatArray? = null
        var pos = 0.0
        var vol = 0f
        var target = 0f
        var rate = 1.0
    }

    private val voices = ArrayList<Voice>()
    private val pending = ArrayList<Voice>()
    // 0 thin wind, 1 full wind, 2 rain, 3 machines, 4 water, 5 planet hum, 6 room tone, 7 breath, 8 whispers
    private val beds = arrayOf(Bed(true), Bed(true), Bed(true), Bed(true), Bed(true), Bed(false), Bed(false), Bed(false), Bed(false))
    private lateinit var blips: Array<FloatArray>
    private var seed = 1234567

    // ------------------------------------------------------------------ lifecycle

    fun loadAll() {
        for (s in Sfx.entries) {
            val n = variantCount(s)
            variants[s.ordinal] = Array(n) { v -> seed = 1000 + s.ordinal * 97 + v * 7919; synth(s, v) }
        }
        beds[0].data = thinWindLoop(); beds[1].data = fullWindLoop(); beds[2].data = rainLoop(); beds[3].data = machineLoop()
        beds[4].data = waterLoop(); beds[5].data = humLoop(); beds[6].data = roomLoop(); beds[7].data = breathLoop(); beds[8].data = whisperLoop()
        blips = Array(14) { i -> seed = 500 + i * 31; blip(i) }
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

    private fun isWorld(s: Sfx) = when (s) {
        Sfx.THUNDER, Sfx.STONE, Sfx.MACHINE_ON, Sfx.SPLASH, Sfx.BUILD, Sfx.DECON -> true
        else -> false
    }

    override fun play(s: Sfx, volume: Float, pitch: Float) = start(s, volume, pitch, 0f, isWorld(s))

    override fun playAt(s: Sfx, x: Float, y: Float, z: Float, volume: Float, pitch: Float) {
        val dx = x - lx; val dy = y - ly; val dz = z - lz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val range = 60f
        if (d > range) return
        val att = (1f - d / range).let { it * it } * 0.9f + 0.1f * (1f - d / range)
        val pan = if (d > 0.5f) ((-cos(lyaw) * dx + sin(lyaw) * dz) / d) else 0f
        start(s, volume * att, pitch, pan, true)
    }

    override fun setListener(x: Float, y: Float, z: Float, yaw: Float) { lx = x; ly = y; lz = z; lyaw = yaw }

    override fun setAmbience(a: Ambience) { synchronized(lock) { amb.copyFrom(a) } }

    override fun speak(chars: Int) {
        if (!loaded || paused) return
        val n = (chars / 3).coerceIn(4, 34)
        val gap = (outRate * 0.068f).toInt()
        var delay = 0
        var note = 4 + rnd(4)
        synchronized(pending) {
            for (i in 0 until n) {
                // a little melody that wanders and settles, with pauses like commas
                note = (note + rnd(5) - 2).coerceIn(0, blips.size - 1)
                if (i == n - 1) note = max(0, note - 3)
                val v = 0.2f * master
                pending.add(Voice(blips[note], v, v, srcRate.toDouble() / outRate, false, delay))
                delay += gap + (if (rnd(7) == 0) gap * 2 else 0) + rnd(gap / 3)
            }
        }
    }

    private fun variantCount(s: Sfx) = when (s) {
        Sfx.STEP_FROST, Sfx.STEP_ROCK, Sfx.STEP_SOIL, Sfx.STEP_GRASS, Sfx.STEP_METAL, Sfx.STEP_WATER -> 5
        Sfx.PICKUP, Sfx.BUILD, Sfx.WHISPER, Sfx.STONE, Sfx.THUNDER, Sfx.JET -> 3
        else -> 2
    }

    private fun start(s: Sfx, volume: Float, pitch: Float, pan: Float, world: Boolean) {
        if (!loaded || paused) return
        val vars = variants[s.ordinal] ?: return
        val now = System.nanoTime()
        if (now - lastPlayed[s.ordinal] < 30_000_000L && s != Sfx.UI) return
        lastPlayed[s.ordinal] = now
        var v = rnd(vars.size)
        if (vars.size > 1 && v == lastVariant[s.ordinal]) v = (v + 1) % vars.size
        lastVariant[s.ordinal] = v
        val vol = (volume * master * (0.9f + rndF() * 0.2f)).coerceIn(0f, 1.5f)
        if (vol < 0.004f) return
        val p = pitch * (0.95f + rndF() * 0.1f)
        val voice = Voice(vars[v], vol * min(1f, 1f - pan), vol * min(1f, 1f + pan), srcRate.toDouble() / outRate * p, world)
        synchronized(pending) { pending.add(voice) }
    }

    private fun rnd(n: Int): Int { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0x7fffff) % max(1, n) }
    private fun rndF(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xffff) / 65535f }

    // ------------------------------------------------------------------ mixing

    private val music = MusicGen(outRate.toFloat())
    private var wlpL = 0f
    private var wlpR = 0f
    private var wk = 0.1f
    private var wgain = 0.4f
    private val ambCooldown = FloatArray(8) { 3f + it * 2f }
    private val ambientSounds = HashMap<String, Array<FloatArray>>()

    private fun mixLoop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) } catch (_: Throwable) {}
        val block = 256
        val wL = FloatArray(block); val wR = FloatArray(block)
        val sL = FloatArray(block); val sR = FloatArray(block)
        val out = ShortArray(block * 2)
        val a = Ambience()
        while (running) {
            if (paused) { try { Thread.sleep(50) } catch (_: InterruptedException) {}; continue }
            synchronized(pending) { voices.addAll(pending); pending.clear() }
            synchronized(lock) { a.copyFrom(amb) }
            val dt = block.toFloat() / outRate
            val m = master
            val live = !a.paused && !a.menu
            if (live) scheduleAmbience(a, dt)

            // how much of the outside world reaches your ears
            val air = if (a.helmet) a.atmo.pow(0.7f) else 1f
            var kTarget = 0.05f + 0.95f * air * air
            var gTarget = 0.3f + 0.7f * air
            if (a.indoors) { kTarget = min(kTarget, 0.1f); gTarget *= 0.45f }
            if (a.underwater) { kTarget = 0.045f; gTarget *= 0.8f }
            wk += (kTarget - wk) * 0.05f
            wgain += (gTarget - wgain) * 0.05f

            val out0 = if (a.paused) 0f else 1f
            beds[0].target = a.wind * (1f - a.atmo) * 0.34f * m * out0
            beds[1].target = a.wind * a.atmo * 0.42f * m * out0
            beds[2].target = a.rain * 0.55f * m * out0
            beds[3].target = a.machines * 0.4f * m * out0
            beds[4].target = a.water * 0.3f * m * out0
            beds[5].target = (a.hum * 0.2f + (if (a.menu) 0.12f else 0f)) * m * out0
            beds[6].target = (if (a.indoors) 0.16f else 0f) * m * out0
            beds[7].target = (if (a.helmet && !a.menu) 0.07f + a.breath * 0.3f else 0f) * m * out0
            beds[7].rate = 0.8 + a.breath * 0.9
            beds[8].target = a.watcher * 0.24f * m * out0

            for (i in 0 until block) { wL[i] = 0f; wR[i] = 0f; sL[i] = 0f; sR[i] = 0f }
            for (b in beds) {
                val data = b.data ?: continue
                if (b.target < 0.0008f && b.vol < 0.0008f) continue
                val step = srcRate.toDouble() / outRate * b.rate
                val dl = if (b.world) wL else sL; val dr = if (b.world) wR else sR
                for (i in 0 until block) {
                    b.vol += (b.target - b.vol) * 0.0005f
                    val idx = b.pos.toInt()
                    val f = (b.pos - idx).toFloat()
                    val s0 = data[idx]; val s1 = data[if (idx + 1 >= data.size) 0 else idx + 1]
                    val smp = (s0 + (s1 - s0) * f) * b.vol
                    dl[i] += smp; dr[i] += smp
                    b.pos += step
                    if (b.pos >= data.size) b.pos -= data.size
                }
            }
            if (voices.size > 30) {
                voices.sortBy { -(it.gainL + it.gainR) }
                while (voices.size > 30) voices.removeAt(voices.size - 1)
            }
            for (v in voices) {
                val data = v.data
                val dl = if (v.world) wL else sL; val dr = if (v.world) wR else sR
                var i = 0
                if (v.delay > 0) {
                    if (v.delay >= block) { v.delay -= block; continue }
                    i = v.delay; v.delay = 0
                }
                while (i < block) {
                    val idx = v.pos.toInt()
                    if (idx >= data.size - 1) { v.done = true; break }
                    val f = (v.pos - idx).toFloat()
                    val smp = data[idx] + (data[idx + 1] - data[idx]) * f
                    dl[i] += smp * v.gainL; dr[i] += smp * v.gainR
                    v.pos += v.step
                    i++
                }
            }
            voices.removeAll { it.done }
            music.mix(sL, sR, block, m * musicVolume * 0.55f, a)
            for (i in 0 until block) {
                wlpL += (wL[i] - wlpL) * wk; wlpR += (wR[i] - wlpR) * wk
                val l = softClip(sL[i] + wlpL * wgain); val r = softClip(sR[i] + wlpR * wgain)
                out[i * 2] = (l * 32000f).toInt().toShort()
                out[i * 2 + 1] = (r * 32000f).toInt().toShort()
            }
            try { track?.write(out, 0, out.size) } catch (_: Throwable) { try { Thread.sleep(5) } catch (_: InterruptedException) {} }
        }
    }

    private fun softClip(x: Float): Float = if (x > 1f) 1f - 0.5f / (x + 0.5f) * 0.5f else if (x < -1f) -1f + 0.5f / (-x + 0.5f) * 0.5f else x - x * x * x * 0.08f

    // ------------------------------------------------------------------ scheduled world sounds

    private fun ambientSet(name: String, n: Int, gen: (Int) -> FloatArray): Array<FloatArray> =
        ambientSounds.getOrPut(name) { Array(n) { v -> seed = name.hashCode() + v * 131; gen(v) } }

    private fun ambient(set: Array<FloatArray>, vol: Float, world: Boolean = true) {
        val d = set[rnd(set.size)]
        val pan = rndF() * 1.6f - 0.8f
        val v = vol * master
        synchronized(pending) { pending.add(Voice(d, v * min(1f, 1f - pan), v * min(1f, 1f + pan), srcRate.toDouble() / outRate * (0.9f + rndF() * 0.2f), world)) }
    }

    private fun scheduleAmbience(a: Ambience, dt: Float) {
        if (a.underwater) return
        for (i in ambCooldown.indices) ambCooldown[i] -= dt
        val st = a.stage
        // ice creaks and cracks: loudest while the world thaws
        if (ambCooldown[0] <= 0f) {
            ambCooldown[0] = 7f + rndF() * 22f
            val thaw = if (st < 2f) 0.25f else if (st < 4.8f) 1f else 0f
            if (!a.indoors && rndF() < thaw) ambient(ambientSet("ice", 5) { iceCrack(it) }, 0.3f + rndF() * 0.3f)
        }
        // far rumbles on the dead world
        if (ambCooldown[1] <= 0f) {
            ambCooldown[1] = 40f + rndF() * 70f
            if (st < 3f && rndF() < 0.6f) ambient(ambientSet("rumble", 2) { rumble(it) }, 0.5f, false)
        }
        // gusts
        if (ambCooldown[2] <= 0f) {
            ambCooldown[2] = 9f + rndF() * 16f
            if (!a.indoors && rndF() < a.wind + 0.2f) ambient(ambientSet("gust", 3) { gust(it) }, 0.22f * (0.5f + a.wind))
        }
        // day life: trills in the grass
        if (ambCooldown[3] <= 0f) {
            ambCooldown[3] = 1.2f + rndF() * 5f
            if (!a.indoors && rndF() < a.life * (1f - a.night) * (1f - a.rain) * 0.9f) ambient(ambientSet("trill", 6) { trill(it) }, 0.1f + rndF() * 0.1f)
        }
        // night life: soft peeping, and the motes chiming
        if (ambCooldown[4] <= 0f) {
            ambCooldown[4] = 0.7f + rndF() * 2.6f
            if (!a.indoors && rndF() < a.life * a.night * (1f - a.rain * 0.7f)) ambient(ambientSet("peep", 4) { peep(it) }, 0.07f + rndF() * 0.06f)
            if (!a.indoors && st > 9f && rndF() < 0.3f) ambient(ambientSet("chime", 5) { chime(it) }, 0.06f + rndF() * 0.05f)
        }
        // drips after rain, indoors a tick from the hull
        if (ambCooldown[5] <= 0f) {
            ambCooldown[5] = 3f + rndF() * 9f
            if (a.indoors && rndF() < 0.5f) ambient(ambientSet("tick", 3) { tick(it) }, 0.12f, false)
        }
        // close to a Watcher at night: one clear whisper now and then
        if (ambCooldown[6] <= 0f) {
            ambCooldown[6] = 9f + rndF() * 14f
            if (rndF() < a.watcher) { val vars = variants[Sfx.WHISPER.ordinal]; if (vars != null) ambient(vars, 0.3f * a.watcher, false) }
        }
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
    private fun bandpass(b: FloatArray, f: Float, q: Float): FloatArray {
        // two-pole resonator
        val w = (2.0 * PI * f / srcRate).toFloat()
        val r = 1f - w / (2f * q)
        val c = 2f * r * cos(w)
        var y1 = 0f; var y2 = 0f
        for (i in b.indices) { val y = b[i] * (1f - r) + c * y1 - r * r * y2; y2 = y1; y1 = y; b[i] = y }
        return b
    }
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
    private fun echo(b: FloatArray, delaySec: Float, fb: Float, tail: Float): FloatArray {
        val d = (delaySec * srcRate).toInt()
        val o = FloatArray(b.size + (tail * srcRate).toInt())
        System.arraycopy(b, 0, o, 0, b.size)
        for (i in d until o.size) o[i] += o[i - d] * fb
        return o
    }

    /** Footstep heard through the suit: a dull body thump plus whatever the ground adds. */
    private fun step(k: Float, decay: Float, thump: Float, crunch: Float, v: Int): FloatArray {
        val b = buf(0.18f)
        var y = 0f
        for (i in b.indices) {
            y += (noise() - y) * k
            var s = y * env(i, 0.003f, decay)
            if (crunch > 0f && noise() > 0.82f) s += noise() * crunch * env(i, 0.002f, decay * 1.6f)
            b[i] = s
        }
        val th = tone(0.1f, 82f + v * 6f, 55f, 0.002f, 0.028f, 1, thump)
        return norm(mix(b, th), 0.5f + v * 0.04f)
    }

    private fun synth(s: Sfx, v: Int): FloatArray = when (s) {
        Sfx.STEP_FROST -> step(0.13f, 0.055f, 0.2f, 0.75f, v)
        Sfx.STEP_ROCK -> step(0.42f, 0.02f, 0.35f, 0.05f, v)
        Sfx.STEP_SOIL -> step(0.16f + v * 0.02f, 0.035f, 0.3f, 0.15f, v)
        Sfx.STEP_GRASS -> step(0.24f, 0.05f, 0.18f, 0.4f, v)
        Sfx.STEP_METAL -> norm(mix(tone(0.2f, 210f + v * 18f, 190f, 0.001f, 0.045f, 3, 0.45f), tone(0.25f, 540f + v * 40f, 520f, 0.001f, 0.03f, 1, 0.12f), noiseBurst(0.07f, 0.4f, 0f, 0.012f, 0.4f)), 0.5f)
        Sfx.STEP_WATER -> norm(mix(noiseBurst(0.28f, 0.33f, 0.01f, 0.08f), tone(0.2f, 480f + v * 70f, 240f, 0.005f, 0.05f, 1, 0.2f)), 0.5f)
        Sfx.LAND -> norm(mix(tone(0.3f, 95f, 45f, 0.002f, 0.09f, 2, 0.9f), noiseBurst(0.25f, 0.12f, 0.002f, 0.07f, 1f)), 0.8f)
        Sfx.JET -> { val b = buf(0.22f); var y = 0f; for (i in b.indices) { val t = i.toFloat() / b.size; y += (noise() - y) * (0.2f + v * 0.05f); b[i] = y * sin(t * PI.toFloat()) + sin(i * 0.05f) * 0.1f * sin(t * PI.toFloat()) }; norm(highpass(b, 0.9f), 0.5f) }
        Sfx.PICKUP -> norm(mix(noiseBurst(0.1f, 0.3f, 0.002f, 0.025f, 0.5f), tone(0.16f, 740f + v * 90f, 1110f + v * 130f, 0.004f, 0.06f, 1, 0.3f), shift(tone(0.2f, 1480f + v * 180f, 1480f + v * 180f, 0.003f, 0.08f, 1, 0.14f), 0.05f)), 0.5f)
        Sfx.BUILD -> norm(mix(
            tone(0.5f, 180f, 560f + v * 60f, 0.02f, 0.3f, 3, 0.35f),
            shift(noiseBurst(0.2f, 0.25f, 0.001f, 0.04f, 1f), 0.34f),
            shift(tone(0.4f, 110f + v * 10f, 80f, 0.001f, 0.1f, 3, 0.6f), 0.36f),
            shift(tone(0.5f, 880f, 880f, 0.005f, 0.16f, 2, 0.16f), 0.42f)), 0.75f)
        Sfx.DECON -> norm(mix(tone(0.45f, 600f, 150f, 0.01f, 0.25f, 3, 0.35f), shift(noiseBurst(0.3f, 0.35f, 0.001f, 0.07f, 0.9f), 0.1f), shift(noiseBurst(0.2f, 0.2f, 0.001f, 0.05f, 0.7f), 0.28f)), 0.65f)
        Sfx.CRAFT -> norm(mix(tone(0.35f, 300f, 900f, 0.02f, 0.2f, 2, 0.25f), shift(noiseBurst(0.12f, 0.4f, 0.001f, 0.025f, 0.6f), 0.2f), shift(tone(0.35f, 988f, 988f, 0.004f, 0.12f, 2, 0.22f), 0.3f), shift(tone(0.5f, 1318f, 1318f, 0.004f, 0.18f, 2, 0.2f), 0.4f)), 0.55f)
        Sfx.UI -> tone(0.05f, 980f + v * 120f, 1080f + v * 120f, 0.001f, 0.016f, 1, 0.18f)
        Sfx.UI_OPEN -> mix(tone(0.09f, 620f, 830f, 0.002f, 0.035f, 1, 0.16f), shift(tone(0.12f, 930f, 1240f, 0.002f, 0.045f, 1, 0.13f), 0.05f))
        Sfx.DENY -> norm(mix(tone(0.12f, 190f, 170f, 0.002f, 0.05f, 4, 0.5f), shift(tone(0.16f, 150f, 130f, 0.002f, 0.07f, 4, 0.5f), 0.11f)), 0.4f)
        Sfx.DRINK -> { val b = buf(0.6f); var ph = 0.0; for (i in b.indices) { val t = i.toFloat() / srcRate; val f = 280 + 380 * abs(sin(t * (16.0 + v * 3))); ph += 2 * PI * f / srcRate; b[i] = (sin(ph) * 0.3 * exp(-t * 2.5)).toFloat() }; norm(b, 0.4f) }
        Sfx.EAT -> { val b = buf(0.4f); for (i in b.indices) { val t = i.toFloat() / srcRate; val gate = if (((t * (12 + v * 2)).toInt() % 2) == 0) 1f else 0.15f; b[i] = noise() * gate * exp(-t * 5f) * 0.7f }; norm(lowpass(b, 0.3f), 0.45f) }
        Sfx.O2_REFILL -> { val b = buf(1.1f); var y = 0f; for (i in b.indices) { val t = i.toFloat() / b.size; y += (noise() - y) * (0.25f + 0.4f * t); b[i] = y * (1f - t) * min(1f, t * 20f) }; norm(mix(highpass(b, 0.93f), shift(tone(0.25f, 660f, 990f, 0.01f, 0.1f, 1, 0.12f), 0.85f)), 0.5f) }
        Sfx.AIRLOCK -> { val b = buf(0.9f); var y = 0f; for (i in b.indices) { val t = i.toFloat() / b.size; y += (noise() - y) * 0.5f; b[i] = y * exp(-t * 5f) * min(1f, t * 40f) }; norm(mix(highpass(b, 0.9f), tone(0.5f, 70f, 50f, 0.01f, 0.2f, 2, 0.6f), shift(tone(0.12f, 880f, 880f, 0.002f, 0.05f, 1, 0.12f), 0.55f)), 0.55f) }
        Sfx.ALARM -> norm(mix(tone(0.16f, 880f, 880f, 0.004f, 0.09f, 2, 0.35f), shift(tone(0.16f, 660f, 660f, 0.004f, 0.09f, 2, 0.35f), 0.19f)), 0.4f)
        Sfx.HURT -> norm(mix(tone(0.3f, 150f + v * 15f, 80f, 0.002f, 0.1f, 3, 0.6f), noiseBurst(0.2f, 0.1f, 0.001f, 0.06f, 0.8f)), 0.7f)
        Sfx.DEATH -> norm(mix(tone(1.8f, 196f, 98f, 0.01f, 0.8f, 3, 0.35f), shift(tone(1.8f, 147f, 73f, 0.01f, 0.8f, 3, 0.3f), 0.3f)), 0.6f)
        Sfx.STAGE -> {
            // a rising open fifth that blooms: the planet taking a breath
            val notes = floatArrayOf(146.8f, 220f, 293.7f, 440f, 587.3f, 880f)
            var acc = FloatArray(1)
            for (i in notes.indices) acc = mix(acc, shift(tone(2.6f, notes[i], notes[i], 0.25f, 1.1f, 3, 0.22f), i * 0.16f))
            norm(echo(acc, 0.31f, 0.4f, 1.6f), 0.7f)
        }
        Sfx.LOG -> norm(echo(mix(tone(0.5f, 523f, 523f, 0.01f, 0.3f, 2, 0.25f), shift(tone(0.6f, 784f, 784f, 0.01f, 0.35f, 2, 0.25f), 0.14f), shift(tone(0.9f, 1175f, 1175f, 0.01f, 0.5f, 2, 0.2f), 0.28f)), 0.24f, 0.35f, 1f), 0.55f)
        Sfx.WREN -> blip(4 + v * 3)
        Sfx.WHISPER -> whisper(v)
        Sfx.STONE -> { val b = buf(1.4f); var y = 0f; var y2 = 0f; for (i in b.indices) { val t = i.toFloat() / b.size; y += (noise() - y) * 0.06f; y2 += (y - y2) * 0.3f; b[i] = y2 * sin(t * PI.toFloat()) * (0.6f + 0.4f * sin(i * (0.012f + v * 0.003f))) }; norm(mix(b, tone(1.2f, 58f + v * 6f, 50f, 0.2f, 0.5f, 2, 0.2f)), 0.6f) }
        Sfx.THUNDER -> {
            val b = buf(3.6f); var y = 0f; var y2 = 0f
            for (i in b.indices) { val t = i.toFloat() / srcRate; y += (noise() - y) * 0.03f; y2 += (y - y2) * 0.2f
                val crack = if (t < 0.15f) noise() * (1f - t / 0.15f) * 0.6f else 0f
                b[i] = y2 * 7f * exp(-t * (0.9f + v * 0.2f)) * (0.6f + 0.4f * sin(t * (6f + v)) * sin(t * 2.3f)) + crack }
            norm(b, 0.95f)
        }
        Sfx.CRATE -> norm(mix(noiseBurst(0.12f, 0.3f, 0.001f, 0.03f, 0.8f), tone(0.22f, 240f, 200f, 0.002f, 0.06f, 3, 0.4f), shift(tone(0.14f, 720f, 900f, 0.003f, 0.05f, 1, 0.14f), 0.1f)), 0.5f)
        Sfx.MACHINE_ON -> norm(mix(tone(0.9f, 60f, 180f, 0.05f, 0.5f, 4, 0.4f), shift(tone(0.3f, 660f, 660f, 0.005f, 0.12f, 1, 0.15f), 0.7f)), 0.5f)
        Sfx.SPLASH -> norm(mix(noiseBurst(0.7f, 0.4f, 0.004f, 0.16f), shift(noiseBurst(0.3f, 0.6f, 0.002f, 0.05f, 0.5f), 0.08f)), 0.6f)
        Sfx.UNLOCK -> norm(echo(mix(tone(0.3f, 659f, 659f, 0.005f, 0.2f, 2, 0.3f), shift(tone(0.3f, 880f, 880f, 0.005f, 0.2f, 2, 0.3f), 0.1f), shift(tone(0.8f, 1319f, 1319f, 0.005f, 0.45f, 2, 0.28f), 0.2f)), 0.2f, 0.3f, 0.8f), 0.6f)
        Sfx.ENDING -> {
            val notes = floatArrayOf(146.8f, 185f, 220f, 293.7f, 370f, 440f, 587.3f, 740f)
            var acc = FloatArray(1)
            for (i in notes.indices) acc = mix(acc, shift(tone(5f, notes[i], notes[i], 0.6f, 2.4f, 3, 0.18f), i * 0.33f))
            norm(echo(acc, 0.41f, 0.5f, 3f), 0.75f)
        }
    }

    /** One syllable of WREN: a short, round, slightly vowel-like chirp. */
    private fun blip(i: Int): FloatArray {
        val scale = floatArrayOf(0f, 2f, 4f, 7f, 9f, 12f, 14f, 16f, 19f, 21f, 24f, 26f, 28f, 31f)
        val f = 392f * 2f.pow(scale[i % scale.size] / 12f)
        val b = buf(0.085f)
        var ph = 0.0
        for (k in b.indices) {
            val t = k.toFloat() / b.size
            val ff = f * (1f + 0.06f * sin(t * PI.toFloat())) * (1f - 0.05f * t)
            ph += 2.0 * PI * ff / srcRate
            val e = sin(t * PI.toFloat()).pow(0.7f)
            b[k] = ((sin(ph) + 0.35 * sin(ph * 2.0) + 0.12 * sin(ph * 3.0)) * e * 0.5).toFloat()
        }
        return b
    }

    /** Breathy, wordless, almost a voice. */
    private fun whisper(v: Int): FloatArray {
        val b = buf(2.2f)
        for (i in b.indices) b[i] = noise()
        val f1 = bandpass(b.copyOf(), 620f + v * 140f, 9f)
        val f2 = bandpass(b.copyOf(), 1700f + v * 260f, 12f)
        val f3 = bandpass(b.copyOf(), 2900f, 14f)
        val o = FloatArray(b.size)
        for (i in o.indices) {
            val t = i.toFloat() / srcRate
            // syllable shaped gating, like speech heard through a wall
            val syl = max(0f, sin(t * (7.5f + v * 1.3f) + sin(t * 2.1f) * 2f)).pow(1.5f)
            val env = sin((i.toFloat() / o.size) * PI.toFloat())
            o[i] = (f1[i] * 1.0f + f2[i] * 0.7f + f3[i] * 0.35f) * syl * env
        }
        return norm(echo(o, 0.19f, 0.35f, 0.8f), 0.6f)
    }

    private fun iceCrack(v: Int): FloatArray {
        val b = buf(1.6f)
        var y = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            if (i == 0 || (noise() > 0.9992f && t < 0.5f)) y = 1f
            y *= 0.9965f
            b[i] = noise() * y
        }
        val ping = tone(1.2f, 1400f + v * 420f, 380f + v * 90f, 0.001f, 0.25f, 2, 0.3f)
        return norm(echo(mix(bandpass(b, 900f + v * 300f, 3f), ping), 0.13f, 0.4f, 1f), 0.6f)
    }

    private fun rumble(v: Int): FloatArray {
        val b = buf(6f); var y = 0f; var y2 = 0f
        for (i in b.indices) { val t = i.toFloat() / b.size; y += (noise() - y) * 0.012f; y2 += (y - y2) * 0.1f; b[i] = y2 * sin(t * PI.toFloat()).pow(2f) * (0.7f + 0.3f * sin(i * 0.002f * (1 + v))) }
        return norm(b, 0.6f)
    }

    private fun gust(v: Int): FloatArray {
        val b = buf(4f); var y = 0f; var y2 = 0f
        for (i in b.indices) { val t = i.toFloat() / srcRate; y += (noise() - y) * (0.02f + 0.03f * sin(t * 0.8f + v)); y2 += (y - y2) * 0.25f; b[i] = y2 * sin(t / 4f * PI.toFloat()) * 3f }
        return norm(highpass(b, 0.98f), 0.6f)
    }

    /** A soft rolling trill, like something small in the grass that has never been afraid. */
    private fun trill(v: Int): FloatArray {
        val b = buf(0.9f)
        val base = 1500f + (v % 3) * 520f
        var ph = 0.0
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            val f = base * (1f + 0.08f * sin(t * (38f + v * 6f))) * (1f + (if (v % 2 == 0) 0.12f else -0.1f) * t)
            ph += 2 * PI * f / srcRate
            val am = (0.5f + 0.5f * sin(t * (52f + v * 7f))).pow(2f)
            b[i] = (sin(ph) + 0.3 * sin(ph * 2)).toFloat() * am * sin((i.toFloat() / b.size) * PI.toFloat()) * 0.4f
        }
        return norm(b, 0.45f)
    }

    private fun peep(v: Int): FloatArray {
        val a = tone(0.14f, 1900f + v * 230f, 2300f + v * 260f, 0.01f, 0.05f, 1, 0.4f)
        return norm(mix(a, shift(tone(0.14f, 1900f + v * 230f, 2300f + v * 260f, 0.01f, 0.05f, 1, 0.3f), 0.22f + v * 0.03f)), 0.4f)
    }

    private fun chime(v: Int): FloatArray {
        val f = 1175f * 2f.pow(floatArrayOf(0f, 2f, 4f, 7f, 9f)[v % 5] / 12f)
        return norm(echo(mix(tone(1.2f, f, f, 0.003f, 0.4f, 1, 0.3f), tone(0.8f, f * 2.76f, f * 2.76f, 0.002f, 0.12f, 1, 0.1f)), 0.27f, 0.4f, 1f), 0.4f)
    }

    private fun tick(v: Int): FloatArray = norm(mix(tone(0.12f, 1300f + v * 300f, 1250f + v * 300f, 0.0005f, 0.02f, 2, 0.3f), noiseBurst(0.03f, 0.6f, 0f, 0.006f, 0.4f)), 0.35f)

    // ------------------------------------------------------------------ beds (seamless loops)

    /** Wind in almost no air: a faint, high, hollow whistle against the helmet. */
    private fun thinWindLoop(): FloatArray {
        val b = buf(9f); var y = 0f; var y2 = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            val g = 0.5f + 0.5f * sin(t * 0.55f) * sin(t * 0.23f + 1f)
            y += (noise() - y) * 0.2f
            y2 += (y - y2) * 0.2f
            b[i] = y2 * (0.3f + g * 0.7f)
        }
        val o = bandpass(b, 1500f, 6f)
        return norm(seamless(o, srcRate / 2), 0.6f)
    }

    private fun fullWindLoop(): FloatArray {
        val b = buf(9f); var y = 0f; var y2 = 0f
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
            // drops on the helmet
            if (noise() > 0.9965f) v += noise() * 0.6f
            b[i] = v
        }
        return norm(seamless(b, srcRate / 4), 0.6f)
    }

    private fun machineLoop(): FloatArray {
        val b = buf(6f); var y = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            y += (noise() - y) * 0.02f
            val throb = 0.7f + 0.3f * sin(t * 2f * PI.toFloat() * 1.5f)
            b[i] = (sin(t * 2 * PI.toFloat() * 55f) * 0.5f + sin(t * 2 * PI.toFloat() * 110.5f) * 0.25f + sin(t * 2 * PI.toFloat() * 165.3f) * 0.1f) * throb + y * 1.2f
        }
        return norm(seamless(b, srcRate / 2), 0.6f)
    }

    private fun waterLoop(): FloatArray {
        val b = buf(10f); var y = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            y += (noise() - y) * 0.09f
            val w1 = max(0f, sin(t / 10f * 2 * PI.toFloat() * 3f)).pow(2f)
            val w2 = max(0f, sin(t / 10f * 2 * PI.toFloat() * 5f + 1.3f)).pow(3f)
            b[i] = y * (0.2f + w1 * 0.6f + w2 * 0.4f)
        }
        return norm(seamless(b, srcRate), 0.6f)
    }

    /** The planet's own note: a low fifth that beats slowly against itself. */
    private fun humLoop(): FloatArray {
        val b = buf(12f)
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            val swell = 0.6f + 0.4f * sin(t * 2 * PI.toFloat() / 12f)
            b[i] = (sin(t * 2 * PI.toFloat() * 55f) * 0.5f + sin(t * 2 * PI.toFloat() * 55.333333f) * 0.5f +
                sin(t * 2 * PI.toFloat() * 82.5f) * 0.3f + sin(t * 2 * PI.toFloat() * 110.25f) * 0.14f) * swell
        }
        return norm(b, 0.7f)
    }

    private fun roomLoop(): FloatArray {
        val b = buf(6f); var y = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            y += (noise() - y) * 0.06f
            b[i] = y * 1.4f + sin(t * 2 * PI.toFloat() * 100f) * 0.05f + sin(t * 2 * PI.toFloat() * 150f) * 0.02f
        }
        return norm(seamless(b, srcRate / 2), 0.4f)
    }

    /** Four seconds: in, out. Played faster and louder when the tank runs low or you run. */
    private fun breathLoop(): FloatArray {
        val b = buf(4f); var y = 0f
        for (i in b.indices) {
            val t = i.toFloat() / srcRate
            y += (noise() - y) * (if (t < 1.6f) 0.12f else 0.07f)
            val e = if (t < 1.6f) sin(t / 1.6f * PI.toFloat()).pow(1.4f) * 0.8f else if (t < 3.5f) sin((t - 1.6f) / 1.9f * PI.toFloat()).pow(1.2f) else 0f
            b[i] = y * e
        }
        return norm(bandpass(b, 700f, 1.2f), 0.6f)
    }

    private fun whisperLoop(): FloatArray {
        val a = whisper(0); val c = whisper(1); val e = whisper(2)
        val o = FloatArray((srcRate * 9f).toInt())
        fun put(src: FloatArray, at: Float, g: Float) { val off = (at * srcRate).toInt(); for (i in src.indices) { val k = (off + i) % o.size; o[k] += src[i] * g } }
        put(a, 0.2f, 0.8f); put(c, 2.9f, 0.6f); put(e, 5.4f, 0.8f); put(a, 7.2f, 0.5f)
        return norm(o, 0.6f)
    }
}

/**
 * Generative ambient music that follows the planet. On the dead world it is a cold drone with rare glassy notes.
 * As the stages pass it gains pads, then melody, then warmth: the mode moves from dark to bright.
 */
class MusicGen(private val sr: Float) {
    private class Note(val freq: Float, val start: Long, val len: Long, val gain: Float, val kind: Int, val pan: Float) { var ph = 0.0 }

    private val notes = ArrayList<Note>()
    private var t: Long = 0
    private var sessionLeft = 0L
    private var silenceLeft = (25 * sr).toLong()
    private var nextEvent = 0L
    private var chord = 0
    private var step = 0
    private var seed = 4242
    private var level = 0f
    private val delayL = FloatArray(sr.toInt())
    private val delayR = FloatArray(sr.toInt())
    private var dIdx = 0

    private fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xffff) / 65535f }
    private fun hz(semi: Float, root: Float) = root * Math.pow(2.0, semi / 12.0).toFloat()

    // scale degrees in semitones; chords as scale indices
    private val dark = floatArrayOf(0f, 1f, 3f, 5f, 7f, 8f, 10f)     // phrygian
    private val dusk = floatArrayOf(0f, 2f, 3f, 5f, 7f, 9f, 10f)     // dorian
    private val bright = floatArrayOf(0f, 2f, 4f, 6f, 7f, 9f, 11f)   // lydian
    private val prog = arrayOf(intArrayOf(0, 2, 4), intArrayOf(5, 0, 2), intArrayOf(3, 5, 0), intArrayOf(4, 6, 1), intArrayOf(1, 3, 5))

    private fun deg(scale: FloatArray, i: Int): Float { val o = Math.floorDiv(i, 7); return scale[Math.floorMod(i, 7)] + 12f * o }

    fun mix(l: FloatArray, r: FloatArray, n: Int, vol: Float, a: Ambience) {
        val st = a.stage
        val menu = a.menu
        val scale = if (st < 3.5f) dark else if (st < 7f) dusk else bright
        val root = if (st < 3.5f) 73.42f else if (st < 7f) 82.41f else 98f
        if (sessionLeft <= 0L) {
            silenceLeft -= n
            if (silenceLeft <= 0L || (menu && silenceLeft > 3 * sr)) {
                sessionLeft = ((100 + rnd() * 80) * sr).toLong()
                silenceLeft = ((70 + rnd() * 130) * sr).toLong()
                nextEvent = t
            }
        } else sessionLeft -= n
        val playing = (sessionLeft > 0L || menu) && !a.paused
        val target = if (playing) (if (a.watcher > 0.5f) 0.35f else 1f) else 0f
        if (playing && t >= nextEvent) {
            val beat = (sr * (if (st < 3.5f) 1.5f else if (st < 7f) 1.15f else 0.95f)).toLong()
            // pads: every few beats, change chord
            if (step % 8 == 0) {
                chord = (chord + 1 + (rnd() * 2).toInt()) % prog.size
                val ch = prog[chord]
                val richness = if (st < 1f && !menu) 1 else if (st < 5f) 2 else 3
                for (k in 0 until richness) notes.add(Note(hz(deg(scale, ch[k]), root * 2f), t, beat * 9, 0.05f, 0, (k - 1) * 0.5f))
                notes.add(Note(hz(deg(scale, ch[0]) - 12f, root), t, beat * 9, 0.08f, 0, 0f))
            }
            // melody: glassy on the dead world, a soft mallet later
            val density = if (st < 2f) 0.16f else if (st < 5f) 0.3f else if (st < 8f) 0.45f else 0.6f
            if (rnd() < density * (if (a.night > 0.6f) 0.7f else 1f)) {
                val ch = prog[chord]
                val idx = ch[(rnd() * 3).toInt()] + (if (rnd() < 0.4f) 7 else 0) + (if (rnd() < 0.25f) 1 else 0)
                val kind = if (st < 4f) 1 else 2
                notes.add(Note(hz(deg(scale, idx), root * 4f), t, (beat * (1.5f + rnd() * 2f)).toLong(), 0.055f, kind, rnd() * 1.4f - 0.7f))
                // late stages: answer the note a third above, a beat later
                if (st > 8f && rnd() < 0.5f) notes.add(Note(hz(deg(scale, idx + 2), root * 4f), t + beat / 2, beat * 2, 0.04f, 2, rnd() * 1.4f - 0.7f))
            }
            step++
            nextEvent = t + beat
        }
        val dl = (sr * 0.41f).toInt(); val dr = (sr * 0.57f).toInt()
        for (i in 0 until n) {
            level += (target - level) * 0.00003f
            var sL = 0f; var sR = 0f
            if (level > 0.001f) {
                for (k in notes.indices) {
                    val note = notes[k]
                    val age = t - note.start
                    if (age < 0) continue
                    val u = age.toFloat() / note.len
                    if (u >= 1.3f) continue
                    note.ph += 2.0 * Math.PI * note.freq / sr
                    val e: Float; val w: Float
                    when (note.kind) {
                        0 -> { // pad: slow in, slow out
                            e = (min(1f, u * 3.2f) * (1f - max(0f, (u - 0.7f) / 0.6f))).coerceIn(0f, 1f)
                            w = (sin(note.ph) + 0.28 * sin(note.ph * 2.0 + 0.3) + 0.1 * sin(note.ph * 3.0) + 0.5 * sin(note.ph * 1.004)).toFloat()
                        }
                        1 -> { // glass: bright strike, inharmonic shimmer
                            e = Math.exp(-age / (sr * 1.4)).toFloat() * min(1f, age / (sr * 0.004f))
                            w = (sin(note.ph) + 0.4 * sin(note.ph * 2.76) * Math.exp(-age / (sr * 0.3)) + 0.2 * sin(note.ph * 5.4) * Math.exp(-age / (sr * 0.12))).toFloat()
                        }
                        else -> { // mallet: round and warm
                            e = Math.exp(-age / (sr * 0.9)).toFloat() * min(1f, age / (sr * 0.006f))
                            w = (sin(note.ph) + 0.22 * sin(note.ph * 2.0) + 0.1 * sin(note.ph * 4.0) * Math.exp(-age / (sr * 0.15))).toFloat()
                        }
                    }
                    val s = w * e * note.gain
                    sL += s * (1f - note.pan * 0.6f); sR += s * (1f + note.pan * 0.6f)
                }
            }
            val di = dIdx
            val tapL = delayL[(di + delayL.size - dl) % delayL.size]
            val tapR = delayR[(di + delayR.size - dr) % delayR.size]
            delayL[di] = sL + tapR * 0.5f
            delayR[di] = sR + tapL * 0.5f
            dIdx = (di + 1) % delayL.size
            l[i] += (sL + tapL * 0.55f) * vol * level
            r[i] += (sR + tapR * 0.55f) * vol * level
            t++
        }
        if (notes.size > 36 || (t % 8192L) < n) notes.removeAll { (t - it.start) > it.len * 1.3f }
    }
}
