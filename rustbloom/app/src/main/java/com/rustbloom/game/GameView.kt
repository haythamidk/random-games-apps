package com.rustbloom.game

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.Log
import android.view.Surface
import com.rustbloom.game.game.GameState
import com.rustbloom.game.game.SaveSystem
import com.rustbloom.game.input.InputState
import com.rustbloom.game.render.RenderAssets
import com.rustbloom.game.render.WorldRenderer
import java.io.File
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** OpenGL ES 3 surface that drives the simulation and rendering loop. */
@SuppressLint("ViewConstructor")
class GameView(ctx: Context, private val game: GameState, private val input: InputState, private val saveDir: File) : GLSurfaceView(ctx), GLSurfaceView.Renderer {
    private var renderer: WorldRenderer? = null
    private var last = 0L
    private var frameStart = 0L
    private val io = Executors.newSingleThreadExecutor()
    @Volatile var onSaved: ((Boolean) -> Unit)? = null
    @Volatile var onFatal: ((String) -> Unit)? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private var appliedRate = 0

    init {
        Thread { RenderAssets.prepare() }.apply { name = "asset-prep" }.start()
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Applies resolution scaling through the hardware scaler. Call on the UI thread. */
    fun applyRenderScale() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val s = game.settings.renderScale.coerceIn(0.4f, 1f)
        holder.setFixedSize((w * s).toInt(), (h * s).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { applyRenderScale() }
    }

    private var needInit = true

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (renderer != null) {
            // context was recreated: every GPU object is gone
            game.chunks.contextLost()
            renderer = null
        }
        needInit = true
    }

    /** GL setup happens on the first frame after the CPU assets are ready, keeping surface callbacks quick. */
    private fun tryInit(): Boolean {
        if (!needInit) return renderer != null
        if (!RenderAssets.ready) return false
        needInit = false
        try {
            val r = WorldRenderer(game)
            r.init()
            r.resize(surfaceW, surfaceH)
            renderer = r
            last = System.nanoTime()
        } catch (t: Throwable) {
            Log.e("Rustbloom", "GL init failed", t)
            onFatal?.invoke(t.message ?: "GL init failed")
        }
        return renderer != null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width; surfaceH = height
        renderer?.resize(width, height)
        appliedRate = 0
    }

    private fun applyFrameRate() {
        val cap = game.settings.fpsCap
        if (cap == appliedRate) return
        appliedRate = cap
        if (Build.VERSION.SDK_INT >= 30) {
            try { holder.surface.setFrameRate(cap.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } catch (_: Throwable) {}
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!tryInit()) {
            android.opengl.GLES30.glClearColor(0.03f, 0.05f, 0.06f, 1f)
            android.opengl.GLES30.glClear(android.opengl.GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val r = renderer ?: return
        applyFrameRate()
        val now = System.nanoTime()
        // frame cap: sleep away remaining time
        val target = 1_000_000_000L / game.settings.fpsCap.coerceIn(20, 144)
        val elapsed = now - frameStart
        if (elapsed < target - 1_500_000L) {
            try { Thread.sleep((target - elapsed - 1_000_000L) / 1_000_000L) } catch (_: InterruptedException) {}
        }
        frameStart = System.nanoTime()
        val dt = ((frameStart - last) / 1e9f).coerceIn(0.0001f, 0.25f)
        last = frameStart
        synchronized(game) {
            try {
                game.update(dt, input)
            } catch (t: Throwable) {
                Log.e("Rustbloom", "update failed", t)
            }
            if (game.saveRequested && game.started && game.worldReady && !game.player.dead) {
                game.saveRequested = false
                val json = SaveSystem.toJson(game).toString()
                io.execute { writeSave(json) }
            }
        }
        try {
            r.render(dt)
        } catch (t: Throwable) {
            Log.e("Rustbloom", "render failed", t)
        }
    }

    private fun writeSave(json: String) {
        val ok = try {
            val tmp = File(saveDir, "rustbloom_save.tmp")
            tmp.writeText(json)
            val f = SaveSystem.saveFile(saveDir)
            if (f.exists()) f.copyTo(File(saveDir, "rustbloom_save.bak"), overwrite = true)
            tmp.renameTo(f)
        } catch (t: Throwable) { Log.e("Rustbloom", "save write failed", t); false }
        onSaved?.invoke(ok)
    }

    /** Saves synchronously from the UI thread (used when the app goes to the background). */
    fun saveNowBlocking() {
        val json = synchronized(game) {
            if (!game.started || !game.worldReady || game.player.dead) return
            SaveSystem.toJson(game).toString()
        }
        writeSave(json)
    }

    fun requestSave() { game.saveRequested = true }
}
