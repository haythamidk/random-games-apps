package com.hadal.game.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hadal.game.game.ControlScheme
import com.hadal.game.game.Costs
import com.hadal.game.game.Decor
import com.hadal.game.game.Difficulty
import com.hadal.game.game.GameState
import com.hadal.game.game.GraphicsPreset
import com.hadal.game.game.Item
import com.hadal.game.game.Logs
import com.hadal.game.game.Sfx
import com.hadal.game.game.Species
import com.hadal.game.game.Upgrade

/** Every menu, built from plain Android views so touch, gamepad focus and mouse all work. */
class UiManager(
    private val act: Activity,
    private val root: FrameLayout,
    private val game: GameState,
    private val cb: Callbacks,
) {
    interface Callbacks {
        fun onStartNew(d: Difficulty)
        fun onContinue()
        fun onResumeGame()
        fun onDive()
        fun onSaveAndQuit()
        fun onSettingsChanged()
        fun onQuitApp()
        fun onRespawn()
        fun onEndingChoice(choice: Int)
        fun hasSave(): Boolean
    }

    private val d = act.resources.displayMetrics.density
    private var current: View? = null
    var isOpen = false
        private set
    var screen = ""
        private set
    private var tab = "fab"
    private var settingsTab = 0
    private var backAction: (() -> Unit)? = null
    private var screenKey = ""

    private val ACCENT = Color.rgb(110, 220, 255)
    private val AMBER = Color.rgb(255, 200, 100)
    private val PANEL = Color.argb(225, 4, 14, 20)
    private val CARD = Color.argb(255, 14, 30, 38)
    private val DIM = Color.rgb(140, 170, 185)
    private val GOOD = Color.rgb(140, 230, 160)
    private val BAD = Color.rgb(240, 140, 120)

    private fun dp(v: Float) = (v * d).toInt()

    // ------------------------------------------------------------------ helpers

    private fun bg(color: Int, radius: Float = 10f, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * d
        if (strokeColor != 0) setStroke(dp(1.5f), strokeColor)
    }

    private fun btnBg(base: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), bg(Color.argb(255, 30, 64, 78), 10f, AMBER))
        addState(intArrayOf(android.R.attr.state_pressed), bg(Color.argb(255, 40, 90, 110), 10f, ACCENT))
        addState(intArrayOf(-android.R.attr.state_enabled), bg(Color.argb(150, 18, 26, 30), 10f))
        addState(intArrayOf(), bg(base, 10f, Color.argb(80, 110, 220, 255)))
    }

    private fun label(t: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(act).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun button(t: String, enabled: Boolean = true, size: Float = 14f, base: Int = CARD, onClick: () -> Unit) = Button(act).apply {
        text = t; textSize = size; isAllCaps = false
        setTextColor(if (enabled) Color.WHITE else Color.rgb(100, 110, 115))
        background = btnBg(base)
        isEnabled = enabled; isFocusable = true
        minHeight = dp(40f); minimumHeight = dp(40f)
        setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        setOnClickListener { game.sound.play(Sfx.UI, 0.5f); onClick() }
        stateListAnimator = null
    }

    private fun vbox(pad: Float = 0f) = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad)) }
    private fun hbox() = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, margin: Float = 4f) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(margin), dp(margin), dp(margin), dp(margin)) }

    private fun collect(v: View, scrolls: MutableList<View>, buttons: MutableList<View>) {
        if (v is ScrollView || v is HorizontalScrollView) scrolls.add(v)
        if (v is Button) buttons.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), scrolls, buttons)
    }

    /** Shows a screen, keeping scroll and focus when the same screen is rebuilt so nothing jumps. */
    private fun show(v: View, name: String, back: (() -> Unit)?) {
        val key = "$name:$tab:$settingsTab"
        val oldScrolls = ArrayList<View>(); val oldButtons = ArrayList<View>()
        val same = current != null && key == screenKey
        if (same) collect(current!!, oldScrolls, oldButtons)
        val scrollPos = oldScrolls.map { it.scrollX to it.scrollY }
        val focusIdx = oldButtons.indexOfFirst { it.isFocused }
        if (!same && name != "main") game.sound.play(Sfx.UI_OPEN, 0.5f)
        current?.let { root.removeView(it) }
        current = v; screen = name; screenKey = key; isOpen = true; backAction = back
        root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        v.post {
            val ns = ArrayList<View>(); val nb = ArrayList<View>()
            collect(v, ns, nb)
            if (same) {
                for (k in 0 until minOf(scrollPos.size, ns.size)) ns[k].scrollTo(scrollPos[k].first, scrollPos[k].second)
                if (focusIdx >= 0 && focusIdx < nb.size && nb[focusIdx].isEnabled) { nb[focusIdx].requestFocus(); return@post }
            }
            nb.firstOrNull { it.isEnabled }?.requestFocus()
        }
    }

    fun close() { current?.let { root.removeView(it) }; current = null; isOpen = false; screen = ""; screenKey = "" }

    fun back(): Boolean { val b = backAction ?: return false; b(); return true }

    fun cycleTab(dir: Int): Boolean {
        if (screen == "station") {
            val i = stationTabs.indexOfFirst { it.first == tab }
            tab = stationTabs[(i + dir + stationTabs.size) % stationTabs.size].first
            showStation(); return true
        }
        if (screen == "settings") { settingsTab = (settingsTab + dir + 3) % 3; settingsBack?.let { showSettings(it) }; return true }
        return false
    }

    private fun panel(content: View, widthFrac: Float = 0.62f, gravity: Int = Gravity.CENTER): View {
        val frame = FrameLayout(act)
        frame.setBackgroundColor(Color.argb(110, 0, 0, 0))
        val p = vbox(16f).apply { background = bg(PANEL, 16f, Color.argb(70, 110, 220, 255)) }
        p.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val w = (act.resources.displayMetrics.widthPixels * widthFrac).toInt()
        frame.addView(p, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT, gravity).apply { setMargins(dp(14f), dp(14f), dp(14f), dp(14f)) })
        frame.isClickable = true
        return frame
    }

    private fun scroll(v: View) = ScrollView(act).apply { addView(v); isFillViewport = true; isVerticalScrollBarEnabled = false }

    private fun icon(i: Item, sizeDp: Float) = ImageView(act).apply {
        setImageBitmap(ItemIcons.get(i))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { setMargins(dp(2f), dp(2f), dp(6f), dp(2f)) }
    }

    private fun costRow(cost: Map<Item, Int>): View {
        val row = hbox()
        for ((i, n) in cost) {
            val have = game.storage[i.ordinal]
            val chip = hbox().apply { setPadding(dp(4f), dp(2f), dp(8f), dp(2f)); background = bg(Color.argb(255, 10, 20, 26), 8f) }
            chip.addView(icon(i, 22f))
            chip.addView(label("$have/$n", 11.5f, if (have >= n) GOOD else BAD, true))
            row.addView(chip, lp(ViewGroup.LayoutParams.WRAP_CONTENT, margin = 2f))
        }
        return row
    }

    // ------------------------------------------------------------------ main menu

    fun showMainMenu() {
        val col = vbox(24f).apply { gravity = Gravity.CENTER_VERTICAL }
        col.addView(label("HADAL", 52f, Color.rgb(220, 240, 250), true).apply { letterSpacing = 0.35f })
        col.addView(label("a deep sea survival story", 15f, DIM))
        col.addView(View(act), lp(h = dp(18f)))
        if (cb.hasSave()) col.addView(button("Continue Dive", size = 16f) { cb.onContinue() }, lp(dp(260f)))
        col.addView(button("New Dive", size = 16f) { showDifficulty() }, lp(dp(260f)))
        col.addView(button("Settings") { showSettings { showMainMenu() } }, lp(dp(260f)))
        col.addView(button("Quit") { cb.onQuitApp() }, lp(dp(260f)))
        col.addView(View(act), lp(h = dp(12f)))
        col.addView(label("Headphones recommended. It is very quiet down there.", 12f, DIM))
        val frame = FrameLayout(act)
        frame.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(235, 0, 6, 10), Color.argb(120, 0, 6, 10), Color.argb(0, 0, 0, 0)))
        frame.addView(col, FrameLayout.LayoutParams(dp(420f), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START).apply { setMargins(dp(40f), 0, 0, 0) })
        frame.isClickable = true
        show(frame, "main", null)
    }

    private fun showDifficulty() {
        val col = vbox()
        col.addView(label("How deep is your patience?", 20f, Color.WHITE, true))
        col.addView(View(act), lp(h = dp(8f)))
        for (df in Difficulty.entries) {
            val desc = when (df) {
                Difficulty.CALM -> "Gentle damage, slow drains, fewer predators. For exploring and decorating."
                Difficulty.NORMAL -> "The intended dive. The sea is beautiful and it will hurt you."
                Difficulty.ABYSSAL -> "Everything hits harder and the dark is hungrier."
            }
            col.addView(button("${df.label}\n$desc", size = 13f) { cb.onStartNew(df) }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, lp())
        }
        col.addView(button("Back") { showMainMenu() }, lp(dp(160f)))
        show(panel(scroll(col), 0.55f), "difficulty") { showMainMenu() }
    }

    // ------------------------------------------------------------------ pause

    fun showPause() {
        val col = vbox()
        col.addView(label("Paused", 22f, Color.WHITE, true))
        col.addView(label(game.story.objectiveText(game), 13f, AMBER))
        col.addView(View(act), lp(h = dp(8f)))
        col.addView(button("Resume", size = 16f) { cb.onResumeGame() }, lp())
        val row = hbox()
        row.addView(button("Map") { showMap { showPause() } }, lp(0, weight = 1f))
        row.addView(button("Logbook") { showLogbook { showPause() } }, lp(0, weight = 1f))
        row.addView(button("Biodex") { showBiodex { showPause() } }, lp(0, weight = 1f))
        col.addView(row)
        col.addView(button("Settings") { showSettings { showPause() } }, lp())
        col.addView(button("Controls help") { showHelp { showPause() } }, lp())
        col.addView(button("Save and quit to title") { cb.onSaveAndQuit() }, lp())
        show(panel(scroll(col), 0.5f), "pause") { cb.onResumeGame() }
    }

    private fun showHelp(back: () -> Unit) {
        val col = vbox()
        col.addView(label("Controls", 20f, Color.WHITE, true))
        val t = """
Touch: left stick moves, drag the right side to look. Arrow buttons rise and sink, the double chevron boosts. The big gold button grabs, docks and interacts. Hold the eye button to scan creatures. Lamp toggles the floodlamp, the rings send a sonar ping.

Controller: left stick move, right stick look, R2 rise, L2 sink, A use, X hold to scan, Y lamp, B sonar, L1 flare, R1 shock pulse, press left stick to boost, right stick to change camera, D-pad down repair, D-pad up map, Start pause.

Keyboard and mouse: WASD move, mouse look, Space rise, Ctrl or C sink, Shift boost, E use, Q hold to scan, F lamp, R sonar, G flare, V shock pulse, H repair, T camera, M map, Esc pause.

Survival: watch hull, power and oxygen. Do not go deeper than the hull rating. Dock at Lantern Station to unload cargo, recharge and fabricate upgrades. Noise and light draw predators. When the clicking starts, kill your lamp and drift.
""".trimIndent()
        col.addView(label(t, 13.5f, Color.rgb(215, 230, 240)))
        col.addView(button("Back") { back() }, lp(dp(160f)))
        show(panel(scroll(col), 0.66f), "help", back)
    }

    // ------------------------------------------------------------------ station (docked)

    private val stationTabs = listOf("fab" to "Fabricator", "supplies" to "Supplies", "comforts" to "Comforts", "biodex" to "Biodex", "logs" to "Logs", "storage" to "Storage")

    fun showStation(t: String? = null) {
        if (t != null) tab = t
        val col = vbox()
        val header = hbox()
        header.addView(label("Lantern Station", 20f, Color.rgb(255, 214, 140), true), lp(0, weight = 1f))
        header.addView(button("Dive", size = 15f, base = Color.argb(255, 20, 70, 90)) { cb.onDive() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(button("Map") { showMap { showStation() } }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(button("Menu") { showPause() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(header)
        col.addView(label(game.story.objectiveText(game), 12.5f, AMBER), lp(margin = 2f))
        val tabs = hbox()
        for ((id, name) in stationTabs) tabs.addView(button(name, size = 12.5f, base = if (id == tab) Color.argb(255, 26, 70, 90) else CARD) { tab = id; showStation() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, margin = 2f))
        col.addView(HorizontalScrollView(act).apply { addView(tabs); isHorizontalScrollBarEnabled = false })
        val body = vbox()
        when (tab) {
            "fab" -> fabricator(body)
            "supplies" -> supplies(body)
            "comforts" -> comforts(body)
            "biodex" -> biodexList(body)
            "logs" -> logList(body)
            else -> storageList(body)
        }
        col.addView(scroll(body), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        show(panel(col, 0.6f, Gravity.END), "station", null)
    }

    private fun fabricator(body: LinearLayout) {
        body.addView(label("Upgrades are built from materials stored at the station. Docking unloads your cargo automatically.", 12.5f, DIM))
        for (u in Upgrade.entries) {
            val lvl = game.upgrades[u.ordinal]
            val card = vbox(10f).apply { background = bg(CARD, 12f) }
            val top = hbox()
            val title = if (u.maxLevel > 1) "${u.title}  Mk$lvl / Mk${u.maxLevel}" else u.title + (if (lvl > 0) "  (installed)" else "")
            top.addView(label(title, 15f, Color.WHITE, true), lp(0, weight = 1f))
            if (lvl < u.maxLevel) {
                val cost = Costs.upgrade(u, lvl)
                val can = game.storageHas(cost)
                top.addView(button(if (lvl == 0 && u.maxLevel == 1) "Build" else "Upgrade", can, 13f) {
                    synchronized(game) { game.craftUpgrade(u) }
                    showStation()
                }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
                card.addView(top)
                card.addView(label(u.desc + " " + upgradeDetail(u, lvl), 12.5f, DIM))
                card.addView(costRow(cost))
            } else {
                card.addView(top)
                card.addView(label(u.desc + " Fully upgraded.", 12.5f, GOOD))
            }
            body.addView(card, lp())
        }
    }

    private fun upgradeDetail(u: Upgrade, lvl: Int): String = when (u) {
        Upgrade.HULL -> "Rated ${Costs.hullRating[lvl]} m now, ${Costs.hullRating[lvl + 1]} m next."
        Upgrade.BATTERY -> "${Costs.batteryCap[lvl].toInt()} power now, ${Costs.batteryCap[lvl + 1].toInt()} next."
        Upgrade.OXYGEN -> "${(Costs.oxygenCap[lvl] / 60).toInt()} minutes of air now, ${(Costs.oxygenCap[lvl + 1] / 60).toInt()} next."
        Upgrade.CARGO -> "Holds ${Costs.cargoCap[lvl]} now, ${Costs.cargoCap[lvl + 1]} next."
        Upgrade.LAMP -> "Reaches ${Costs.lampRange[lvl].toInt()} m now, ${Costs.lampRange[lvl + 1].toInt()} m next."
        Upgrade.SONAR -> "Pings ${Costs.sonarRange[lvl].toInt()} m now, ${Costs.sonarRange[lvl + 1].toInt()} m next."
        else -> ""
    }

    private fun supplies(body: LinearLayout) {
        body.addView(label("Consumables are loaded straight into the Wren.", 12.5f, DIM))
        val f = vbox(10f).apply { background = bg(CARD, 12f) }
        val ft = hbox()
        ft.addView(label("Flare  (carrying ${game.sub.flares}/6)", 15f, Color.WHITE, true), lp(0, weight = 1f))
        val hasL = game.upgrades[Upgrade.FLARES.ordinal] > 0
        ft.addView(button("Craft", hasL && game.sub.flares < 6 && game.storageHas(Costs.flare), 13f) { synchronized(game) { game.craftFlare() }; showStation() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        f.addView(ft)
        f.addView(label(if (hasL) Item.FLARE.desc else "Needs the Flare Launcher from the fabricator.", 12.5f, DIM))
        f.addView(costRow(Costs.flare))
        body.addView(f, lp())
        val k = vbox(10f).apply { background = bg(CARD, 12f) }
        val kt = hbox()
        kt.addView(label("Repair Kit  (carrying ${game.sub.repairKits}/3)", 15f, Color.WHITE, true), lp(0, weight = 1f))
        kt.addView(button("Craft", game.sub.repairKits < 3 && game.storageHas(Costs.repairKit), 13f) { synchronized(game) { game.craftKit() }; showStation() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        k.addView(kt)
        k.addView(label(Item.REPAIR_KIT.desc, 12.5f, DIM))
        k.addView(costRow(Costs.repairKit))
        body.addView(k, lp())
    }

    private fun comforts(body: LinearLayout) {
        val n = game.decor.count { it }
        val mood = when {
            n == 0 -> "The station is cold and too quiet. It still smells like the people who left."
            n < 4 -> "It is starting to feel lived in. The lamps help."
            n < 8 -> "Warm light, plants, music. You catch yourself humming on the way to the moon pool."
            else -> "Home. Even ARIA sounds happier."
        }
        body.addView(label(mood, 13.5f, Color.rgb(255, 220, 170)))
        for (dc in Decor.entries) {
            val have = game.decor[dc.ordinal]
            val card = vbox(10f).apply { background = bg(if (have) Color.argb(255, 30, 40, 26) else CARD, 12f) }
            val top = hbox()
            top.addView(label(dc.title, 15f, Color.WHITE, true), lp(0, weight = 1f))
            if (!have) top.addView(button("Build", game.storageHas(dc.cost), 13f) { synchronized(game) { game.buildDecor(dc) }; showStation() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(top)
            card.addView(label(dc.desc, 12.5f, if (have) GOOD else DIM))
            if (!have) card.addView(costRow(dc.cost))
            body.addView(card, lp())
        }
    }

    private fun biodexList(body: LinearLayout) {
        body.addView(label("Species scanned: ${game.biodex.size}/${Species.entries.size}. Hold the scan button with a creature in front of the sub.", 12.5f, DIM))
        for (s in Species.entries) {
            val known = game.biodex.contains(s)
            val card = vbox(10f).apply { background = bg(CARD, 12f) }
            card.addView(label(if (known) s.title else "Unknown", 15f, if (known) Color.WHITE else DIM, true))
            card.addView(label(if (known) s.desc else "Seen in: " + (if (s == Species.PALE) "the dark" else s.zones.joinToString { it.title }), 12.5f, DIM))
            body.addView(card, lp())
        }
    }

    private fun logList(body: LinearLayout) {
        val read = game.story.logsRead.toMutableSet().apply { add(0) }
        for ((i, e) in Logs.entries.withIndex()) {
            val card = vbox(10f).apply { background = bg(CARD, 12f) }
            if (read.contains(i)) {
                card.addView(label(e.first, 15f, Color.rgb(140, 220, 255), true))
                card.addView(label(e.second, 13.5f, Color.rgb(220, 230, 238)))
            } else card.addView(label("Missing data core", 14f, DIM))
            body.addView(card, lp())
        }
        if (game.story.echoes.isNotEmpty()) {
            body.addView(label("Echo Stones ${game.story.echoes.size}/${Logs.echoes.size}", 16f, Color.rgb(140, 230, 255), true), lp())
            for (i in game.story.echoes.sorted()) body.addView(label(Logs.echoes[i], 13.5f, Color.rgb(200, 230, 245)).apply { setTypeface(typeface, Typeface.ITALIC) }, lp(margin = 2f))
        }
    }

    private fun storageList(body: LinearLayout) {
        body.addView(label("Station storage", 15f, Color.WHITE, true))
        val grid = vbox()
        for (i in Item.entries) {
            if (i == Item.FLARE || i == Item.REPAIR_KIT || i == Item.DATA_CORE) continue
            val row = hbox().apply { setPadding(dp(6f), dp(4f), dp(6f), dp(4f)); background = bg(CARD, 10f) }
            row.addView(icon(i, 34f))
            val txt = vbox()
            txt.addView(label("${i.title}  x${game.storage[i.ordinal]}", 14f, Color.WHITE, true))
            txt.addView(label(i.desc, 12f, DIM))
            row.addView(txt, lp(0, weight = 1f))
            grid.addView(row, lp(margin = 3f))
        }
        body.addView(grid)
        body.addView(label("Time in the water: ${(game.playTime / 3600).toInt()} h ${((game.playTime / 60) % 60).toInt()} min   Deepest: ${game.story.deepest.toInt()} m   Pale encounters: ${game.ecology.paleEncounters}", 12f, DIM), lp())
    }

    // ------------------------------------------------------------------ map, logbook, biodex (from pause)

    fun showMap(back: () -> Unit) {
        val col = vbox()
        val top = hbox()
        top.addView(label("Chart", 20f, Color.WHITE, true), lp(0, weight = 1f))
        val mv = MapView(act, game)
        top.addView(button("+") { mv.zoomBy(1.4f) }, lp(dp(56f)))
        top.addView(button("-") { mv.zoomBy(1f / 1.4f) }, lp(dp(56f)))
        top.addView(button("Close") { back() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(top)
        col.addView(mv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        show(panel(col, 0.9f), "map", back)
    }

    private fun showLogbook(back: () -> Unit) {
        val col = vbox()
        col.addView(label("Logbook", 20f, Color.WHITE, true))
        val body = vbox(); logList(body)
        col.addView(scroll(body), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(button("Back") { back() }, lp(dp(160f)))
        show(panel(col, 0.7f), "logbook", back)
    }

    private fun showBiodex(back: () -> Unit) {
        val col = vbox()
        col.addView(label("Biodex", 20f, Color.WHITE, true))
        val body = vbox(); biodexList(body)
        col.addView(scroll(body), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(button("Back") { back() }, lp(dp(160f)))
        show(panel(col, 0.7f), "biodex", back)
    }

    // ------------------------------------------------------------------ death and ending

    fun showDeath() {
        val col = vbox(10f).apply { gravity = Gravity.CENTER }
        col.addView(label("Signal lost", 30f, Color.rgb(255, 120, 100), true).apply { gravity = Gravity.CENTER })
        col.addView(label(game.deathReason, 15f, Color.rgb(220, 225, 230)).apply { gravity = Gravity.CENTER })
        col.addView(label("The station's recall drone will bring the Wren home. Cargo will be lost.", 13f, DIM).apply { gravity = Gravity.CENTER })
        col.addView(button("Recall to Lantern Station", size = 16f) { cb.onRespawn() }, lp(dp(300f)))
        show(panel(col, 0.5f), "death", null)
    }

    fun showEnding() {
        val col = vbox(10f).apply { gravity = Gravity.CENTER }
        col.addView(label("The Choir", 28f, Color.rgb(170, 225, 255), true).apply { gravity = Gravity.CENTER })
        col.addView(label(Logs.entries[11].second, 15f, Color.rgb(225, 235, 245)).apply { gravity = Gravity.CENTER })
        col.addView(View(act), lp(h = dp(8f)))
        col.addView(button("Stay and listen a while", size = 15f) { cb.onEndingChoice(1) }, lp(dp(320f)))
        col.addView(button("Go home to ARIA", size = 15f) { cb.onEndingChoice(2) }, lp(dp(320f)))
        show(panel(scroll(col), 0.6f), "ending", null)
    }

    // ------------------------------------------------------------------ settings

    private var settingsBack: (() -> Unit)? = null

    fun showSettings(back: () -> Unit) {
        settingsBack = back
        val s = game.settings
        val col = vbox()
        val top = hbox()
        top.addView(label("Settings", 20f, Color.WHITE, true), lp(0, weight = 1f))
        top.addView(button("Done") { game.settings.save(act); cb.onSettingsChanged(); back() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(top)
        val tabs = hbox()
        listOf("Graphics", "Controls", "Audio").forEachIndexed { i, n -> tabs.addView(button(n, base = if (i == settingsTab) Color.argb(255, 26, 70, 90) else CARD) { settingsTab = i; showSettings(back) }, lp(0, weight = 1f)) }
        col.addView(tabs)
        val body = vbox()
        fun changed() { s.preset = GraphicsPreset.CUSTOM; s.save(act); cb.onSettingsChanged(); showSettings(back) }
        fun row(name: String, value: String, vararg options: Pair<String, () -> Unit>) {
            val r = hbox().apply { setPadding(dp(8f), dp(2f), dp(4f), dp(2f)); background = bg(CARD, 10f) }
            val tv = vbox()
            tv.addView(label(name, 14f, Color.WHITE))
            tv.addView(label(value, 12f, ACCENT))
            r.addView(tv, lp(0, weight = 1f))
            for ((t, f) in options) r.addView(button(t, size = 13f) { f() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, margin = 2f))
            body.addView(r, lp(margin = 3f))
        }
        fun onOff(b: Boolean) = if (b) "On" else "Off"
        when (settingsTab) {
            0 -> {
                val pr = hbox()
                for (p in GraphicsPreset.entries.filter { it != GraphicsPreset.CUSTOM }) pr.addView(button(p.label, base = if (s.preset == p) Color.argb(255, 26, 70, 90) else CARD) { s.applyPreset(p); s.save(act); cb.onSettingsChanged(); showSettings(back) }, lp(0, weight = 1f))
                body.addView(label("Preset: ${s.preset.label}. High is tuned for phones like the Galaxy A54.", 12.5f, DIM))
                body.addView(pr)
                row("Resolution", "${(s.renderScale * 100).toInt()}%", "-" to { s.renderScale = (s.renderScale - 0.05f).coerceAtLeast(0.5f); changed() }, "+" to { s.renderScale = (s.renderScale + 0.05f).coerceAtMost(1f); changed() })
                row("Dynamic resolution", onOff(s.dynamicRes) + "  keeps the frame rate steady", "Toggle" to { s.dynamicRes = !s.dynamicRes; changed() })
                row("Frame rate cap", "${s.fpsCap} fps", "30" to { s.fpsCap = 30; changed() }, "45" to { s.fpsCap = 45; changed() }, "60" to { s.fpsCap = 60; changed() }, "90" to { s.fpsCap = 90; changed() })
                row("View distance", "${s.viewDistance * 64} m", "-" to { s.viewDistance = (s.viewDistance - 1).coerceAtLeast(2); changed() }, "+" to { s.viewDistance = (s.viewDistance + 1).coerceAtMost(4); changed() })
                row("Post effects", onOff(s.postFx) + "  HDR, bloom, light shafts, sonar wave", "Toggle" to { s.postFx = !s.postFx; changed() })
                row("Volumetric light", listOf("Off", "Normal", "High")[s.volumetrics], "Off" to { s.volumetrics = 0; changed() }, "Normal" to { s.volumetrics = 1; changed() }, "High" to { s.volumetrics = 2; changed() })
                row("Lamp shadows", listOf("Off", "On", "Soft")[s.shadows], "Off" to { s.shadows = 0; changed() }, "On" to { s.shadows = 1; changed() }, "Soft" to { s.shadows = 2; changed() })
                row("Textures", listOf("Flat", "Textured", "Detailed")[s.texQuality], "Flat" to { s.texQuality = 0; changed() }, "Tex" to { s.texQuality = 1; changed() }, "Detail" to { s.texQuality = 2; changed() })
                row("Caustics", onOff(s.caustics), "Toggle" to { s.caustics = !s.caustics; changed() })
                row("Marine snow", listOf("Off", "Normal", "Dense")[s.particles], "Off" to { s.particles = 0; changed() }, "Normal" to { s.particles = 1; changed() }, "Dense" to { s.particles = 2; changed() })
                row("Sea life density", "${(s.floraDensity * 100).toInt()}%", "-" to { s.floraDensity = (s.floraDensity - 0.1f).coerceAtLeast(0.3f); changed() }, "+" to { s.floraDensity = (s.floraDensity + 0.1f).coerceAtMost(1f); changed() })
                row("Bloom", "${(s.bloom * 100).toInt()}%", "-" to { s.bloom = (s.bloom - 0.1f).coerceAtLeast(0f); changed() }, "+" to { s.bloom = (s.bloom + 0.1f).coerceAtMost(1.2f); changed() })
                row("Anti-aliasing (FXAA)", onOff(s.fxaa), "Toggle" to { s.fxaa = !s.fxaa; changed() })
                row("Field of view", "${s.fov.toInt()}", "-" to { s.fov = (s.fov - 4f).coerceAtLeast(56f); changed() }, "+" to { s.fov = (s.fov + 4f).coerceAtMost(96f); changed() })
                row("Show FPS", onOff(s.showFps), "Toggle" to { s.showFps = !s.showFps; changed() })
            }
            1 -> {
                row("Control scheme", s.controls.label, *ControlScheme.entries.map { c -> c.label.split(' ').first() to { s.controls = c; s.save(act); cb.onSettingsChanged(); showSettings(back) } }.toTypedArray())
                row("Look sensitivity", String.format("%.1f", s.lookSensitivity), "-" to { s.lookSensitivity = (s.lookSensitivity - 0.1f).coerceAtLeast(0.3f); s.save(act); showSettings(back) }, "+" to { s.lookSensitivity = (s.lookSensitivity + 0.1f).coerceAtMost(3f); s.save(act); showSettings(back) })
                row("Invert look", onOff(s.invertY), "Toggle" to { s.invertY = !s.invertY; s.save(act); showSettings(back) })
                row("Camera", if (s.cockpitCamera) "Cockpit" else "Chase", "Toggle" to { s.cockpitCamera = !s.cockpitCamera; s.save(act); showSettings(back) })
                row("Touch control opacity", "${(s.touchOpacity * 100).toInt()}%", "-" to { s.touchOpacity = (s.touchOpacity - 0.1f).coerceAtLeast(0.2f); s.save(act); showSettings(back) }, "+" to { s.touchOpacity = (s.touchOpacity + 0.1f).coerceAtMost(1f); s.save(act); showSettings(back) })
                row("Vibration", onOff(s.vibration), "Toggle" to { s.vibration = !s.vibration; s.save(act); showSettings(back) })
                row("Difficulty", s.difficulty.label, *Difficulty.entries.map { df -> df.label to { s.difficulty = df; s.save(act); showSettings(back) } }.toTypedArray())
            }
            else -> {
                row("Master volume", "${(s.masterVolume * 100).toInt()}%", "-" to { s.masterVolume = (s.masterVolume - 0.1f).coerceAtLeast(0f); s.save(act); cb.onSettingsChanged(); showSettings(back) }, "+" to { s.masterVolume = (s.masterVolume + 0.1f).coerceAtMost(1f); s.save(act); cb.onSettingsChanged(); showSettings(back) })
                row("Music", "${(s.musicVolume * 100).toInt()}%  score made with Higgsfield", "-" to { s.musicVolume = (s.musicVolume - 0.1f).coerceAtLeast(0f); s.save(act); cb.onSettingsChanged(); showSettings(back) }, "+" to { s.musicVolume = (s.musicVolume + 0.1f).coerceAtMost(1f); s.save(act); cb.onSettingsChanged(); showSettings(back) })
            }
        }
        col.addView(scroll(body), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        show(panel(col, 0.66f), "settings") { game.settings.save(act); cb.onSettingsChanged(); back() }
    }
}
