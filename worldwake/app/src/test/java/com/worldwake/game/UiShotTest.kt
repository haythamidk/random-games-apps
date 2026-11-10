package com.worldwake.game

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import com.worldwake.game.game.BuildType
import com.worldwake.game.game.GameState
import com.worldwake.game.game.Inventory
import com.worldwake.game.game.Item
import com.worldwake.game.game.Marker
import com.worldwake.game.game.Placed
import com.worldwake.game.game.Settings
import com.worldwake.game.game.Story
import com.worldwake.game.game.TargetKind
import com.worldwake.game.input.InputState
import com.worldwake.game.ui.HudView
import com.worldwake.game.ui.UiManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the real HUD and menus to PNG files at phone size, so layout problems show up without a device.
 * Runs only when WW_SHOTS points at an output folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiShotTest {
    private val w = 2340
    private val h = 1080

    private fun shot(root: View, dir: File, name: String) {
        ShadowLooper.idleMainLooper()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        ShadowLooper.idleMainLooper()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // stand-in for the 3D view: dusk sky over pale frost
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Color.rgb(28, 20, 66), Color.rgb(150, 90, 150), Color.rgb(206, 200, 235), Color.rgb(120, 118, 150)), floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        root.draw(c)
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun renderScreens() {
        val out = System.getenv("WW_SHOTS") ?: return
        val dir = File(out); dir.mkdirs()
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val settings = Settings()
        val g = GameState(settings)
        val input = InputState()
        val root = FrameLayout(act)
        act.setContentView(root)
        val hud = HudView(act, g, input)
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        var resumed = 0
        val ui = UiManager(act, root, g, object : UiManager.Callbacks {
            override fun onStartNew() {}
            override fun onContinue() {}
            override fun onResumeGame() { resumed++ }
            override fun onSaveNow() {}
            override fun onQuitToMenu() {}
            override fun onSettingsChanged() {}
            override fun onQuitApp() {}
            override fun onRespawn() {}
        })
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)

        hud.visibility = View.INVISIBLE
        ui.showMainMenu(); shot(root, dir, "01_title")
        ui.showIntro(); shot(root, dir, "02_intro")
        ui.close()

        // a mid-game state
        hud.visibility = View.VISIBLE
        g.started = true; g.worldReady = true; g.paused = false
        g.heat = 21000.0; g.press = 14000.0; g.oxy = 6500.0
        g.stageReached = 3; g.stage = 3.62f
        val p = g.player
        p.o2 = 96f; p.water = 64f; p.food = 41f
        p.inv.capacity = 64
        for ((i, n) in listOf(Item.FERRITE to 9, Item.SILICA to 6, Item.ICE to 4, Item.COBALT to 5, Item.MAGNESIUM to 3, Item.TITANIUM to 2, Item.ALLOY to 4,
            Item.GLASS to 3, Item.CIRCUIT to 2, Item.WATER to 2, Item.RATION to 3, Item.SPORE to 5, Item.SULFUR to 2, Item.O2_CAP to 1)) p.inv.add(i, n)
        p.upgrades[Item.TANK_2.ordinal] = true; p.upgrades[Item.LAMP.ordinal] = true
        p.inv.capacity = 64
        var id = 1
        for (t in listOf(BuildType.WIND, BuildType.WIND, BuildType.SOLAR, BuildType.SOLAR, BuildType.HEATER_1, BuildType.HEATER_1, BuildType.HEATER_2,
            BuildType.DRILL_1, BuildType.DRILL_2, BuildType.DRILL_2, BuildType.OXY_1, BuildType.OXY_1)) g.placed.add(Placed(id++, t, id * 5f, 0f, 0f, 0f))
        g.recalc()
        g.logsRead.addAll(listOf(0, 1, 2))
        g.objective = "Reach stage 4, Rain  ·  logs 3/12"
        g.markers = arrayOf(Marker(p.pos.x - 60f, p.pos.z + 200f, 0xFF59F0E0.toInt(), "Lander"), Marker(p.pos.x + 150f, p.pos.z + 300f, 0xFFFFD060.toInt(), "Goal"))
        g.feedText = "+1 Ferrite  (9)\n+1 Cobalt  (5)"
        g.targetKind = TargetKind.ORE
        g.prompt = "Pick up Magnesium"
        g.subtitle = Story.stageLines[3]
        shot(root, dir, "03_hud")

        g.subtitle = ""; g.feedText = ""
        g.buildType = BuildType.HEATER_2; g.buildValid = false; g.buildWhy = "Too steep"
        g.banner = "STAGE 3  ·  FIRST CLOUDS"
        shot(root, dir, "04_hud_build")
        g.buildType = null; g.banner = ""; g.prompt = ""; g.targetKind = TargetKind.NONE

        hud.visibility = View.INVISIBLE
        ui.setSafeArea(90, 0)
        ui.showGameMenu("pack"); shot(root, dir, "05_pack")
        ui.showGameMenu("craft"); shot(root, dir, "06_craft")
        ui.showGameMenu("build"); shot(root, dir, "07_build")
        ui.showGameMenu("planet"); shot(root, dir, "08_planet")
        ui.showGameMenu("journal"); shot(root, dir, "09_journal")
        val crate = Inventory(30)
        crate.add(Item.ALLOY, 4); crate.add(Item.GLASS, 2); crate.add(Item.CIRCUIT, 2); crate.add(Item.SPORE, 4); crate.add(Item.RATION, 3)
        ui.openStorage("Outpost Supplies", crate); shot(root, dir, "10_storage")
        val log = Story.logs[3]
        ui.showLog(log.title, log.author + "  ·  day " + log.day + "\n\n" + log.body); shot(root, dir, "11_log")
        ui.showPause(); shot(root, dir, "12_pause")
        ui.showSettings(); shot(root, dir, "13_settings")
        ui.showDeath(); shot(root, dir, "14_death")
    }
}
