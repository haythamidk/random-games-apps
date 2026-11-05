package com.rustbloom.game

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
import com.rustbloom.game.audio.SoundEngine
import com.rustbloom.game.game.Difficulty
import com.rustbloom.game.game.GameState
import com.rustbloom.game.game.GameUi
import com.rustbloom.game.game.Placed
import com.rustbloom.game.game.SaveSystem
import com.rustbloom.game.game.Settings
import com.rustbloom.game.game.Station
import com.rustbloom.game.input.Action
import com.rustbloom.game.input.InputState
import com.rustbloom.game.ui.HudView
import com.rustbloom.game.ui.ItemIcons
import com.rustbloom.game.ui.MapImage
import com.rustbloom.game.ui.UiManager
import com.rustbloom.game.world.WorldGen
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
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = Settings().also { it.load(this) }
        game = GameState(settings)
        input = InputState()
        sound = SoundEngine(this)
        sound.master = settings.masterVolume
        game.sound = sound
        game.ui = this
        Thread { try { sound.loadAll() } catch (t: Throwable) { android.util.Log.e("Rustbloom", "audio", t) } }.start()
        com.rustbloom.game.engine.Workers.gen.execute { MapImage.generate() }
        com.rustbloom.game.engine.Workers.gen.execute { ItemIcons.warmUp() }

        // menu backdrop: stand outside the vault and let the camera drift
        val v = WorldGen.vaultPoi
        game.player.pos.set(v.x + 4f, v.y + 1f, v.z + 14f)
        game.player.yaw = 0.4f

        root = FrameLayout(this)
        glView = GameView(this, game, input, filesDir)
        glView.onSaved = { ok -> runOnUiThread { if (!ok) Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show() } }
        glView.onFatal = { msg -> runOnUiThread { Toast.makeText(this, "Graphics error: $msg", Toast.LENGTH_LONG).show() } }
        hud = HudView(this, game, input)
        hud.onMenu = { openPause() }
        hud.onInventory = { openGameMenu("inventory") }
        hud.onMap = { openGameMenu("map") }
        root.addView(glView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ui = UiManager(this, root, game, this)
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

    override fun onResume() {
        super.onResume()
        resumed = true
        glView.onResume()
        sound.resume()
        hideSystemUi()
    }

    override fun onPause() {
        resumed = false
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

    private fun openPause() {
        if (!game.started) return
        game.paused = true
        hud.releaseAll()
        input.reset()
        releasePointer()
        ui.showPause()
    }

    private fun openGameMenu(tab: String) {
        if (!game.started || game.player.dead) return
        game.paused = true
        hud.releaseAll()
        input.reset()
        releasePointer()
        ui.showGameMenu(tab)
    }

    private fun pauseFor(fn: () -> Unit) {
        runOnUiThread {
            game.paused = true
            hud.releaseAll()
            input.reset()
            releasePointer()
            fn()
        }
    }

    override fun onStartNew(d: Difficulty) {
        hud.visibility = View.VISIBLE
        game.post { game.newGame(d) }
        enterGame()
        glView.requestSave()
    }

    override fun onContinue() {
        game.post {
            val ok = SaveSystem.load(game, filesDir)
            if (!ok) runOnUiThread { Toast.makeText(this, "Could not load save", Toast.LENGTH_LONG).show(); ui.showMainMenu() }
        }
        enterGame()
    }

    override fun onResumeGame() { enterGame() }

    override fun onSaveNow() {
        game.post {
            game.saveRequested = true
            runOnUiThread { Toast.makeText(this, "Game saved", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onQuitToMenu() {
        glView.saveNowBlocking()
        game.post { game.started = false; game.worldReady = false }
        game.paused = true
        hud.visibility = View.INVISIBLE
        ui.showMainMenu()
    }

    override fun onSettingsChanged() {
        sound.master = settings.masterVolume
        glView.applyRenderScale()
        updatePointerCapture()
    }

    override fun onQuitApp() { finish() }

    override fun onRespawn() {
        game.post { game.respawn() }
        enterGame()
    }

    // ------------------------------------------------------------------ GameUi (called from the GL thread)

    override fun onPlayerDied() = runOnUiThread { hud.releaseAll(); input.reset(); releasePointer(); ui.showDeath() }
    override fun showLog(title: String, body: String) = pauseFor { ui.showLog(title, body) }
    override fun openChest(p: Placed) = pauseFor { ui.openChest(p) }
    override fun openBounties() = pauseFor { ui.openBounties() }
    override fun openCrafting(station: Station) = pauseFor { ui.openCrafting(station) }
    override fun onWorldReady() {}
    override fun onVictory() = pauseFor { ui.showVictory() }
    override fun refreshMenus() = runOnUiThread { ui.refresh() }
    override fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    override fun vibrate(ms: Long) {
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ mouse capture

    private fun wantsCapture() = game.started && !game.paused && !ui.isOpen && settings.controls == com.rustbloom.game.game.ControlScheme.KEYBOARD_MOUSE

    private fun updatePointerCapture() {
        if (wantsCapture()) {
            glView.requestFocus()
            glView.post { try { glView.requestPointerCapture() } catch (_: Throwable) {} }
        } else releasePointer()
    }

    private fun releasePointer() { try { glView.releasePointerCapture() } catch (_: Throwable) {} }

    private fun onCapturedPointer(e: MotionEvent): Boolean {
        if (!wantsCapture()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                // captured pointer events report relative motion in x/y
                input.addLook(e.x * 0.12f, e.y * 0.12f)
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v > 0) input.push(Action.HOTBAR_PREV) else if (v < 0) input.push(Action.HOTBAR_NEXT)
            }
        }
        // relative movement may also arrive with button changes
        if (e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_UP ||
            e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || e.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            val bs = e.buttonState
            val atk = bs and MotionEvent.BUTTON_PRIMARY != 0
            if (atk && !input.attackHeld && game.buildType != null) input.push(Action.ATTACK_PRESS)
            input.attackHeld = atk
            input.aimHeld = bs and MotionEvent.BUTTON_SECONDARY != 0
        }
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
                    KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_BUTTON_SELECT -> { if (ui.screen == "game") { onResumeGame(); return true } }
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
        when (code) {
            KeyEvent.KEYCODE_W -> { input.kW = down; input.updateKeys() }
            KeyEvent.KEYCODE_A -> { input.kA = down; input.updateKeys() }
            KeyEvent.KEYCODE_S -> { input.kS = down; input.updateKeys() }
            KeyEvent.KEYCODE_D -> { input.kD = down; input.updateKeys() }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> input.sprintHeld = down
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A -> act(Action.JUMP)
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_BUTTON_X -> act(Action.INTERACT)
            KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_BUTTON_Y -> act(Action.USE_ITEM)
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_BUTTON_THUMBR -> act(Action.CYCLE_AMMO)
            KeyEvent.KEYCODE_R -> act(Action.ROTATE)
            KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_CTRL_LEFT -> act(Action.CROUCH_TOGGLE)
            KeyEvent.KEYCODE_BUTTON_B -> act(if (build) Action.CANCEL else Action.CROUCH_TOGGLE)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> act(Action.SPRINT_TOGGLE)
            KeyEvent.KEYCODE_BUTTON_L1 -> act(Action.HOTBAR_PREV)
            KeyEvent.KEYCODE_BUTTON_R1 -> act(Action.HOTBAR_NEXT)
            KeyEvent.KEYCODE_BUTTON_R2 -> { input.padAttack = down; if (first && build) input.push(Action.ATTACK_PRESS) }
            KeyEvent.KEYCODE_BUTTON_L2 -> input.padAim = down
            KeyEvent.KEYCODE_1 -> act(Action.HOTBAR_1)
            KeyEvent.KEYCODE_2 -> act(Action.HOTBAR_2)
            KeyEvent.KEYCODE_3 -> act(Action.HOTBAR_3)
            KeyEvent.KEYCODE_4 -> act(Action.HOTBAR_4)
            KeyEvent.KEYCODE_5 -> act(Action.HOTBAR_5)
            KeyEvent.KEYCODE_6 -> act(Action.HOTBAR_6)
            KeyEvent.KEYCODE_DPAD_UP -> act(Action.CYCLE_AMMO)
            KeyEvent.KEYCODE_DPAD_LEFT -> act(if (build) Action.ROTATE else Action.HOTBAR_PREV)
            KeyEvent.KEYCODE_DPAD_RIGHT -> act(if (build) Action.ROTATE else Action.HOTBAR_NEXT)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_M -> if (first) openGameMenu("map")
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_BUTTON_SELECT -> if (first) openGameMenu("inventory")
            KeyEvent.KEYCODE_B -> if (first) openGameMenu("build")
            KeyEvent.KEYCODE_J -> if (first) openGameMenu("journal")
            KeyEvent.KEYCODE_K -> if (first) openGameMenu("skills")
            KeyEvent.KEYCODE_ENTER -> act(Action.SKIP_DIALOG)
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                if (first) { if (build && code != KeyEvent.KEYCODE_BUTTON_START) input.push(Action.CANCEL) else openPause() }
            }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ gamepad sticks, triggers, mouse

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
            val lx = axis(ev, dev, MotionEvent.AXIS_X)
            val ly = axis(ev, dev, MotionEvent.AXIS_Y)
            input.setPadMove(lx, -ly)
            var rx = axis(ev, dev, MotionEvent.AXIS_Z)
            var ry = axis(ev, dev, MotionEvent.AXIS_RZ)
            if (rx == 0f && ry == 0f) { rx = axis(ev, dev, MotionEvent.AXIS_RX); ry = axis(ev, dev, MotionEvent.AXIS_RY) }
            input.setPadLook(rx, ry)
            val rt = maxOf(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
            val lt = maxOf(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
            val atk = rt > 0.4f
            if (atk && !input.padAttack && game.buildType != null) input.push(Action.ATTACK_PRESS)
            input.padAttack = atk
            input.padAim = lt > 0.4f
            // d-pad reported as hat axes
            val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val build = game.buildType != null
            if (hx != hatX) { if (hx < -0.5f) input.push(if (build) Action.ROTATE else Action.HOTBAR_PREV) else if (hx > 0.5f) input.push(if (build) Action.ROTATE else Action.HOTBAR_NEXT); hatX = hx }
            if (hy != hatY) { if (hy < -0.5f) input.push(Action.CYCLE_AMMO) else if (hy > 0.5f) openGameMenu("map"); hatY = hy }
            return true
        }
        if ((src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE && ev.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v > 0) input.push(Action.HOTBAR_PREV) else if (v < 0) input.push(Action.HOTBAR_NEXT)
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // a real mouse click on the game view captures the pointer in keyboard & mouse mode
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE) && wantsCapture() && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            updatePointerCapture()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
