package com.worldwake.game

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.worldwake.game.audio.SoundEngine
import com.worldwake.game.game.ControlScheme
import com.worldwake.game.game.GameState
import com.worldwake.game.game.GameUi
import com.worldwake.game.game.Inventory
import com.worldwake.game.game.SaveSystem
import com.worldwake.game.game.Settings
import com.worldwake.game.input.Action
import com.worldwake.game.input.InputState
import com.worldwake.game.ui.HudView
import com.worldwake.game.ui.UiManager
import kotlin.math.abs

class MainActivity : Activity(), GameUi, UiManager.Callbacks {
    private lateinit var settings: Settings
    private lateinit var game: GameState
    private lateinit var input: InputState
    private lateinit var glView: GameView
    private lateinit var hud: HudView
    private lateinit var ui: UiManager
    private lateinit var sound: SoundEngine
    private lateinit var root: FrameLayout
    private var hatX = 0f
    private var hatY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = Settings().also { it.load(this) }
        game = GameState(settings)
        input = InputState()
        sound = SoundEngine(this)
        sound.master = settings.masterVolume
        sound.musicVolume = settings.musicVolume
        sound.voiceOn = settings.wrenVoice
        game.sound = sound
        game.ui = this
        game.menuStage = SaveSystem.peekStage(filesDir).coerceAtLeast(0f)
        Thread { try { sound.loadAll() } catch (t: Throwable) { android.util.Log.e("Worldwake", "audio", t) } }.start()

        root = FrameLayout(this)
        glView = GameView(this, game, input, filesDir)
        glView.onSaved = { ok -> runOnUiThread { if (!ok) Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show() } }
        glView.onFatal = { msg -> runOnUiThread { Toast.makeText(this, "Graphics error: $msg", Toast.LENGTH_LONG).show() } }
        hud = HudView(this, game, input)
        hud.onMenu = { openPause() }
        hud.onPack = { openGameMenu("pack") }
        hud.onBuild = { openGameMenu("build") }
        root.addView(glView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ui = UiManager(this, root, game, this)
        glView.setOnCapturedPointerListener { _, e -> onCapturedPointer(e) }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val cut = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
            val l = cut?.safeInsetLeft ?: 0; val r = cut?.safeInsetRight ?: 0
            hud.setSafeArea(l, r)
            ui.setSafeArea(l, r)
            insets
        }
        hideSystemUi()
        hud.visibility = View.INVISIBLE
        ui.showMainMenu()
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { hideSystemUi(); updatePointerCapture() }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        sound.resume()
        hideSystemUi()
    }

    override fun onPause() {
        if (game.started && !ui.isOpen) openPause()
        glView.saveNowBlocking()
        sound.pause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sound.release()
        game.chunks.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ flow

    private fun enterGame() {
        ui.close()
        hud.visibility = View.VISIBLE
        hud.releaseAll()
        input.reset()
        game.paused = false
        updatePointerCapture()
    }

    private fun freeze() {
        game.paused = true
        hud.visibility = View.INVISIBLE
        hud.releaseAll()
        input.reset()
        releasePointer()
    }

    private fun openPause() {
        if (!game.started) return
        freeze()
        ui.showPause()
    }

    private fun openGameMenu(tab: String) {
        if (!game.started || game.player.dead || !game.worldReady) return
        freeze()
        ui.showGameMenu(tab)
    }

    private fun pauseFor(fn: () -> Unit) = runOnUiThread { freeze(); fn() }

    override fun onStartNew() {
        hud.visibility = View.VISIBLE
        game.post { game.newGame() }
        enterGame()
        glView.requestSave()
    }

    override fun onContinue() {
        game.post {
            val ok = SaveSystem.load(game, filesDir)
            if (!ok) runOnUiThread { Toast.makeText(this, "Could not load the save", Toast.LENGTH_LONG).show(); hud.visibility = View.INVISIBLE; game.paused = true; ui.showMainMenu() }
        }
        enterGame()
    }

    override fun onResumeGame() { enterGame() }

    override fun onSaveNow() {
        game.post {
            game.saveRequested = true
            runOnUiThread { Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onQuitToMenu() {
        glView.saveNowBlocking()
        game.post { game.menuStage = game.stage; game.started = false; game.worldReady = false; game.buildType = null; game.deconMode = false }
        game.paused = true
        hud.visibility = View.INVISIBLE
        ui.showMainMenu()
    }

    override fun onSettingsChanged() {
        sound.master = settings.masterVolume
        sound.musicVolume = settings.musicVolume
        sound.voiceOn = settings.wrenVoice
        glView.applyRenderScale()
        updatePointerCapture()
    }

    override fun onQuitApp() { finish() }

    override fun onRespawn() {
        game.post { game.respawn() }
        enterGame()
    }

    // ------------------------------------------------------------------ GameUi (called from the GL thread)

    override fun onPlayerDied() = runOnUiThread { freeze(); game.paused = false; ui.showDeath() }
    // (the HUD stays hidden behind every menu and comes back in enterGame)
    override fun showLog(title: String, body: String) = pauseFor { ui.showLog(title, body) }
    override fun openStorage(title: String, inv: Inventory) = pauseFor { ui.openStorage(title, inv) }
    override fun openFabricator() = pauseFor { ui.showGameMenu("craft") }
    override fun onEnding() = pauseFor { ui.showEnding() }
    override fun refreshMenus() = runOnUiThread { ui.refresh() }
    override fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    override fun vibrate(ms: Long) {
        if (!settings.vibration) return
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ mouse capture

    private fun wantsCapture() = game.started && !game.paused && !ui.isOpen && settings.controls == ControlScheme.KEYBOARD_MOUSE

    private fun updatePointerCapture() {
        if (wantsCapture()) {
            glView.requestFocus()
            glView.post { try { glView.requestPointerCapture() } catch (_: Throwable) {} }
        } else releasePointer()
    }

    private fun releasePointer() { try { glView.releasePointerCapture() } catch (_: Throwable) {} }

    private var mouseDown = false

    private fun onCapturedPointer(e: MotionEvent): Boolean {
        if (!wantsCapture()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> input.addLook(e.x * 0.45f, e.y * 0.45f)
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v > 0) input.push(Action.ROTATE) else if (v < 0) input.push(Action.ROTATE_BACK)
            }
        }
        val bs = e.buttonState
        val primary = bs and MotionEvent.BUTTON_PRIMARY != 0
        if (primary && !mouseDown) input.push(Action.PLACE)
        mouseDown = primary
        if (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && bs and MotionEvent.BUTTON_SECONDARY != 0) input.push(Action.CANCEL)
        return true
    }

    // ------------------------------------------------------------------ keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        if (ui.isOpen) {
            if (first) {
                when (code) {
                    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> { if (!ui.back() && ui.screen == "main") return super.dispatchKeyEvent(event); return true }
                    KeyEvent.KEYCODE_BUTTON_A -> { currentFocus?.performClick(); return true }
                    KeyEvent.KEYCODE_BUTTON_L1 -> { if (ui.cycleTab(-1)) return true }
                    KeyEvent.KEYCODE_BUTTON_R1 -> { if (ui.cycleTab(1)) return true }
                    KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> { if (ui.screen == "pause") onResumeGame(); return true }
                    KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_SELECT -> { if (ui.screen == "game") { onResumeGame(); return true } }
                }
            } else if (!down && (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_B)) return true
            return super.dispatchKeyEvent(event)
        }
        if (!game.started) return super.dispatchKeyEvent(event)
        if (handleGameKey(code, down, first)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleGameKey(code: Int, down: Boolean, first: Boolean): Boolean {
        fun act(a: Action) { if (first) input.push(a) }
        val build = game.buildType != null
        val busy = build || game.deconMode
        when (code) {
            KeyEvent.KEYCODE_W -> { input.kW = down; input.updateKeys() }
            KeyEvent.KEYCODE_A -> { input.kA = down; input.updateKeys() }
            KeyEvent.KEYCODE_S -> { input.kS = down; input.updateKeys() }
            KeyEvent.KEYCODE_D -> { input.kD = down; input.updateKeys() }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_BUTTON_THUMBL -> input.sprintHeld = down
            KeyEvent.KEYCODE_SPACE -> { input.keyJump = down; act(Action.JUMP) }
            KeyEvent.KEYCODE_BUTTON_A -> { input.padJump = down; act(Action.JUMP) }
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_BUTTON_X -> act(Action.INTERACT)
            KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_BUTTON_R2 -> act(Action.PLACE)
            KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_BUTTON_R1 -> if (build) act(Action.ROTATE) else if (first && code == KeyEvent.KEYCODE_BUTTON_R1) openGameMenu("build")
            KeyEvent.KEYCODE_BUTTON_L1 -> if (build) act(Action.ROTATE_BACK) else if (first) openGameMenu("craft")
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_Q -> if (busy) act(Action.CANCEL)
            KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_BUTTON_L2 -> act(Action.DECON_TOGGLE)
            KeyEvent.KEYCODE_DPAD_LEFT -> act(Action.PREV_BUILD)
            KeyEvent.KEYCODE_DPAD_RIGHT -> act(Action.NEXT_BUILD)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_B -> if (first) openGameMenu("build")
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_M -> if (first) openGameMenu("map")
            KeyEvent.KEYCODE_P -> if (first) openGameMenu("planet")
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_SELECT -> if (first) openGameMenu("pack")
            KeyEvent.KEYCODE_C -> if (first) openGameMenu("craft")
            KeyEvent.KEYCODE_J -> if (first) openGameMenu("journal")
            KeyEvent.KEYCODE_ENTER -> act(Action.SKIP_DIALOG)
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                if (first) { if (busy && code != KeyEvent.KEYCODE_BUTTON_START) input.push(Action.CANCEL) else openPause() }
            }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ gamepad sticks and triggers

    private fun axis(e: MotionEvent, dev: InputDevice?, a: Int): Float {
        val range = dev?.getMotionRange(a, e.source) ?: return e.getAxisValue(a)
        val v = e.getAxisValue(a)
        return if (abs(v) > range.flat.coerceAtLeast(0.12f)) v else 0f
    }

    private var trigR = false
    private var trigL = false

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ui.isOpen || !game.started) return super.dispatchGenericMotionEvent(ev)
        val src = ev.source
        if ((src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && ev.action == MotionEvent.ACTION_MOVE) {
            val dev = ev.device
            val lx = axis(ev, dev, MotionEvent.AXIS_X)
            val ly = axis(ev, dev, MotionEvent.AXIS_Y)
            input.setPadMove(lx, -ly)
            var rx = axis(ev, dev, MotionEvent.AXIS_Z)
            var ry = axis(ev, dev, MotionEvent.AXIS_RZ)
            if (rx == 0f && ry == 0f) { rx = axis(ev, dev, MotionEvent.AXIS_RX); ry = axis(ev, dev, MotionEvent.AXIS_RY) }
            input.setPadLook(rx, ry)
            // triggers that report as axes rather than buttons
            val rt = maxOf(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS)) > 0.45f
            val lt = maxOf(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.45f
            if (rt && !trigR) input.push(Action.PLACE)
            if (lt && !trigL) input.push(Action.DECON_TOGGLE)
            trigR = rt; trigL = lt
            val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (hx != hatX) { if (hx < -0.5f) input.push(Action.PREV_BUILD) else if (hx > 0.5f) input.push(Action.NEXT_BUILD); hatX = hx }
            if (hy != hatY) { if (hy < -0.5f) openGameMenu("build") else if (hy > 0.5f) openGameMenu("map"); hatY = hy }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE) && wantsCapture() && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            updatePointerCapture()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
