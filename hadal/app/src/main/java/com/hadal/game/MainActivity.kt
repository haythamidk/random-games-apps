package com.hadal.game

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
import com.hadal.game.audio.SoundEngine
import com.hadal.game.engine.Workers
import com.hadal.game.game.ControlScheme
import com.hadal.game.game.Difficulty
import com.hadal.game.game.GameState
import com.hadal.game.game.SaveSystem
import com.hadal.game.game.Settings
import com.hadal.game.input.Action
import com.hadal.game.input.InputState
import com.hadal.game.ui.HudView
import com.hadal.game.ui.MapImage
import com.hadal.game.ui.UiManager
import kotlin.math.abs

class MainActivity : Activity(), UiManager.Callbacks {
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
        game.sound = sound
        Thread { try { sound.loadAll() } catch (t: Throwable) { android.util.Log.e("Hadal", "audio", t) } }.start()
        Workers.gen.execute { MapImage.generate() }

        // title backdrop: the Wren resting under Lantern Station
        game.post { game.placeAtDock() }

        game.onDocked = { runOnUiThread { if (game.started && !game.dead) { sound.setMusicWanted(true); hud.releaseAll(); input.reset(); releasePointer(); ui.showStation() } } }
        game.onDeath = { runOnUiThread { hud.releaseAll(); input.reset(); releasePointer(); hud.postDelayed({ ui.showDeath() }, 2600) } }
        game.onEnding = { runOnUiThread { game.paused = true; hud.releaseAll(); input.reset(); releasePointer(); sound.setMusicWanted(true); ui.showEnding() } }
        game.onVibrate = { ms -> if (settings.vibration) vibrate(ms.toLong()) }

        root = FrameLayout(this)
        glView = GameView(this, game, input, filesDir)
        glView.onSaved = { ok -> runOnUiThread { if (!ok) Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show() } }
        glView.onFatal = { msg -> runOnUiThread { Toast.makeText(this, "Graphics error: $msg", Toast.LENGTH_LONG).show() } }
        hud = HudView(this, game, input)
        hud.onMenu = { openPause() }
        hud.onMap = { openMap() }
        root.addView(glView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ui = UiManager(this, root, game, this)
        ui.onOpenChanged = { open -> hud.menuOpen = open }
        glView.setOnCapturedPointerListener { _, e -> onCapturedPointer(e) }
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

    override fun onResume() { super.onResume(); glView.onResume(); sound.resume(); hideSystemUi() }

    override fun onPause() {
        if (game.started && !ui.isOpen && !game.docked) openPause()
        glView.saveNowBlocking()
        sound.pause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() { sound.release(); super.onDestroy() }

    // ------------------------------------------------------------------ flow

    private fun enterGame() {
        ui.close()
        hud.visibility = View.VISIBLE
        hud.releaseAll()
        input.reset()
        game.paused = false
        updatePointerCapture()
    }

    private fun openPause() {
        if (!game.started || game.dead) return
        game.paused = !game.docked
        hud.releaseAll(); input.reset(); releasePointer()
        ui.showPause()
    }

    private fun openMap() {
        if (!game.started || game.dead) return
        game.paused = !game.docked
        hud.releaseAll(); input.reset(); releasePointer()
        ui.showMap { if (game.docked) ui.showStation() else onResumeGame() }
    }

    override fun hasSave(): Boolean = SaveSystem.exists(filesDir)

    override fun onStartNew(d: Difficulty) {
        settings.difficulty = d; settings.save(this)
        game.post { game.newGame(); game.worldReady = false }
        hud.visibility = View.VISIBLE
        ui.close()
        game.paused = false
        sound.setMusicWanted(true)
        // the intro plays over the station screen
        hud.postDelayed({ if (game.docked) ui.showStation("home") }, 400)
        glView.requestSave()
    }

    override fun onContinue() {
        game.post {
            val ok = SaveSystem.load(game, filesDir)
            game.worldReady = false
            runOnUiThread {
                if (!ok) { Toast.makeText(this, "Could not load save", Toast.LENGTH_LONG).show(); ui.showMainMenu() }
                else { hud.visibility = View.VISIBLE; ui.showStation("home") }
            }
        }
        ui.close()
        game.paused = false
    }

    override fun onResumeGame() {
        if (game.docked) { game.paused = false; ui.showStation(); return }
        enterGame()
        sound.setMusicWanted(false)
    }

    override fun onDive() {
        game.post { game.undock() }
        enterGame()
        sound.setMusicWanted(false)
    }

    override fun onSaveAndQuit() {
        glView.saveNowBlocking()
        game.post { game.started = false; game.worldReady = false; game.ecology.clear(); game.placeAtDock() }
        game.paused = false
        hud.visibility = View.INVISIBLE
        sound.setMusicWanted(true)
        ui.showMainMenu()
    }

    override fun onSettingsChanged() {
        sound.master = settings.masterVolume
        sound.musicVolume = settings.musicVolume
        glView.applyRenderScale()
        updatePointerCapture()
    }

    override fun onQuitApp() { finish() }

    override fun onRespawn() {
        game.post { game.respawn() }
        ui.close()
        game.paused = false
    }

    override fun onEndingChoice(choice: Int) {
        game.post { game.paused = false; game.chooseEnding(choice) }
        ui.close()
    }

    private fun vibrate(ms: Long) {
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ mouse capture

    private fun wantsCapture() = game.started && !game.paused && !ui.isOpen && !game.docked && settings.controls == ControlScheme.KEYBOARD_MOUSE

    private fun updatePointerCapture() {
        if (wantsCapture()) { glView.requestFocus(); glView.post { try { glView.requestPointerCapture() } catch (_: Throwable) {} } } else releasePointer()
    }

    private fun releasePointer() { try { glView.releasePointerCapture() } catch (_: Throwable) {} }

    private fun onCapturedPointer(e: MotionEvent): Boolean {
        if (!wantsCapture()) return false
        if (e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_HOVER_MOVE) input.addLook(e.x * 0.12f, e.y * 0.12f)
        val bs = e.buttonState
        input.scanHeld = bs and MotionEvent.BUTTON_SECONDARY != 0
        if (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && e.actionButton == MotionEvent.BUTTON_PRIMARY) input.push(Action.USE)
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
                    KeyEvent.KEYCODE_BUTTON_L1 -> if (ui.cycleTab(-1)) return true
                    KeyEvent.KEYCODE_BUTTON_R1 -> if (ui.cycleTab(1)) return true
                    KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> { if (ui.screen == "pause") onResumeGame() else if (ui.screen == "station") openPause(); return true }
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
        when (code) {
            KeyEvent.KEYCODE_W -> { input.kW = down; input.updateKeys() }
            KeyEvent.KEYCODE_A -> { input.kA = down; input.updateKeys() }
            KeyEvent.KEYCODE_S -> { input.kS = down; input.updateKeys() }
            KeyEvent.KEYCODE_D -> { input.kD = down; input.updateKeys() }
            KeyEvent.KEYCODE_SPACE -> input.keyUp = down
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C -> input.keyDown = down
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_BUTTON_THUMBL -> input.boostHeld = down
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_BUTTON_X -> input.padScan = down
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_BUTTON_A -> act(Action.USE)
            KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_BUTTON_Y -> act(Action.LAMP)
            KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_BUTTON_B -> act(Action.SONAR)
            KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_BUTTON_L1 -> act(Action.FLARE)
            KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_BUTTON_R1 -> act(Action.PULSE)
            KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_DPAD_DOWN -> act(Action.REPAIR)
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_BUTTON_THUMBR -> act(Action.CAMERA)
            KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_DPAD_LEFT -> act(Action.DEPLOY)
            KeyEvent.KEYCODE_ENTER -> act(Action.SKIP)
            KeyEvent.KEYCODE_BUTTON_R2 -> input.keyUp = down
            KeyEvent.KEYCODE_BUTTON_L2 -> input.keyDown = down
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_BUTTON_SELECT -> if (first) openMap()
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> if (first) openPause()
            else -> return false
        }
        return true
    }

    private fun axis(e: MotionEvent, dev: InputDevice?, a: Int): Float {
        val range = dev?.getMotionRange(a, e.source) ?: return e.getAxisValue(a)
        val v = e.getAxisValue(a)
        return if (abs(v) > range.flat.coerceAtLeast(0.12f)) v else 0f
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ui.isOpen || !game.started) return super.dispatchGenericMotionEvent(ev)
        val src = ev.source
        if ((src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && ev.action == MotionEvent.ACTION_MOVE) {
            val dev = ev.device
            input.setPadMove(axis(ev, dev, MotionEvent.AXIS_X), -axis(ev, dev, MotionEvent.AXIS_Y))
            var rx = axis(ev, dev, MotionEvent.AXIS_Z); var ry = axis(ev, dev, MotionEvent.AXIS_RZ)
            if (rx == 0f && ry == 0f) { rx = axis(ev, dev, MotionEvent.AXIS_RX); ry = axis(ev, dev, MotionEvent.AXIS_RY) }
            input.setPadLook(rx, ry)
            val rt = maxOf(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
            val lt = maxOf(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
            input.setPadVertical(rt - lt)
            val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (hy != hatY) { if (hy > 0.5f) input.push(Action.REPAIR) else if (hy < -0.5f) openMap(); hatY = hy }
            hatX = hx
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE) && wantsCapture() && ev.actionMasked == MotionEvent.ACTION_DOWN) { updatePointerCapture(); return true }
        return super.dispatchTouchEvent(ev)
    }
}
