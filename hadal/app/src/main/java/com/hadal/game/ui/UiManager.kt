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
import com.hadal.game.game.DroneStats
import com.hadal.game.game.DroneUpgrade
import com.hadal.game.game.GameState
import com.hadal.game.game.GraphicsPreset
import com.hadal.game.game.Item
import com.hadal.game.game.Locator
import com.hadal.game.game.Logs
import com.hadal.game.game.OutpostRules
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
    private var tab = "home"
    private var settingsTab = 0
    private var journalTab = "biodex"
    private var infoText = ""
    /** Called with true when a menu opens and false when all menus are closed. */
    var onOpenChanged: ((Boolean) -> Unit)? = null
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
        val wasOpen = isOpen
        current = v; screen = name; screenKey = key; isOpen = true; backAction = back
        if (!wasOpen) onOpenChanged?.invoke(true)
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

    fun close() { current?.let { root.removeView(it) }; current = null; isOpen = false; screen = ""; screenKey = ""; infoText = ""; onOpenChanged?.invoke(false) }

    fun back(): Boolean { val b = backAction ?: return false; b(); return true }

    fun cycleTab(dir: Int): Boolean {
        if (screen == "station") {
            val i = stationTabs.indexOfFirst { it.first == tab }
            tab = stationTabs[(i + dir + stationTabs.size) % stationTabs.size].first
            showStation(); return true
        }
        if (screen == "settings") { settingsTab = (settingsTab + dir + 4) % 4; settingsBack?.let { showSettings(it) }; return true }
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

    /** Tapping a material shows what it is and leads you to it: a blue circle on the map and compass. */
    private fun tip(i: Item) {
        val key = "mat:" + i.name
        val canFind = Locator.zonesFor(i).isNotEmpty()
        val was = game.trackingKey == key
        val on = canFind && synchronized(game) { game.toggleTrack(key) }
        infoText = "${i.title}: ${i.desc}" + when {
            on -> "\nBlue circle on the map and compass: ${game.tracked?.note ?: ""}. Tap it again to stop."
            was -> "\nStopped looking for it."
            else -> ""
        }
        if (screen == "station") showStation()
        else android.widget.Toast.makeText(act, infoText, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Same for story items: data cores, echo stones, beacons and the Choir. */
    private fun findLore(key: String, what: String, how: String) {
        val was = game.trackingKey == key
        val on = synchronized(game) { game.toggleTrack(key) }
        infoText = if (on) "$what: blue circle on the map and compass. $how Tap again to stop." else if (was) "Stopped looking for $what." else "Nothing left to find: $what."
        if (screen == "station") showStation()
    }

    private fun icon(i: Item, sizeDp: Float) = ImageView(act).apply {
        setImageBitmap(ItemIcons.get(i))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { setMargins(dp(2f), dp(2f), dp(6f), dp(2f)) }
        setOnClickListener { tip(i) }
    }

    private fun costRow(cost: Map<Item, Int>): View {
        val row = hbox()
        for ((i, n) in cost) {
            val have = game.storage[i.ordinal]
            val chip = hbox().apply { setPadding(dp(4f), dp(2f), dp(8f), dp(2f)); background = bg(Color.argb(255, 10, 20, 26), 8f) }
            chip.addView(icon(i, 22f))
            chip.addView(label("$have/$n", 11.5f, if (have >= n) GOOD else BAD, true))
            chip.setOnClickListener { tip(i) }
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

    /** Short card: what to do next and where. */
    private fun goalCard(): View {
        val card = vbox(12f).apply { background = bg(Color.argb(255, 26, 36, 30), 14f, Color.argb(200, 255, 200, 100)) }
        card.addView(label(game.story.currentChapter?.title ?: "Free dive", 11.5f, AMBER, true))
        card.addView(label(game.story.objectiveText(game), 16f, Color.WHITE, true))
        val hint = game.story.hintText(game)
        if (hint.isNotEmpty()) card.addView(label(hint, 13f, Color.rgb(200, 220, 232)))
        val g = game.goal
        if (g != null) {
            val dx = g.x - game.sub.pos.x; val dz = g.z - game.sub.pos.z
            val dist = kotlin.math.sqrt(dx * dx + dz * dz)
            card.addView(label("Gold circle on the map: " + (if (dist < g.radius) "you are in the area" else "${dist.toInt()} m ${Locator.direction(dx, dz)}"), 12.5f, AMBER))
        }
        return card
    }

    fun showPause() {
        val col = vbox()
        col.addView(label("Paused", 20f, Color.WHITE, true))
        col.addView(goalCard(), lp())
        col.addView(button("Resume", size = 17f, base = Color.argb(255, 24, 96, 120)) { cb.onResumeGame() }, lp())
        val row = hbox()
        row.addView(button("Map") { showMap { showPause() } }, lp(0, weight = 1f))
        row.addView(button("Journal") { showJournal { showPause() } }, lp(0, weight = 1f))
        row.addView(button("Settings") { showSettings { showPause() } }, lp(0, weight = 1f))
        col.addView(row)
        val row2 = hbox()
        row2.addView(button("How to play") { showHelp { showPause() } }, lp(0, weight = 1f))
        row2.addView(button("Save and quit") { cb.onSaveAndQuit() }, lp(0, weight = 1f))
        col.addView(row2)
        show(panel(scroll(col), 0.55f), "pause") { cb.onResumeGame() }
    }

    private fun showHelp(back: () -> Unit) {
        val col = vbox()
        col.addView(label("Controls", 20f, Color.WHITE, true))
        val t = """
Touch: left stick moves, drag the right side to look. Arrow buttons rise and sink, the double chevron boosts. The big gold button grabs, docks and interacts. Hold the eye button to scan creatures. Lamp toggles the floodlamp, the rings send a sonar ping. When you carry an outpost kit a house button appears: fly low over flat sea floor and press it to place a second base.

Controller: left stick move, right stick look, R2 rise, L2 sink, A use, X hold to scan, Y lamp, B sonar, L1 flare, R1 shock pulse, press left stick to boost, right stick to change camera, D-pad down repair, D-pad left deploy outpost, D-pad up map, Start pause.

Keyboard and mouse: WASD move, mouse look, Space rise, Ctrl or C sink, Shift boost, E use, Q hold to scan, F lamp, R sonar, G flare, V shock pulse, H repair, B deploy outpost, T camera, M map, Esc pause.

Survival: watch hull, power and oxygen. Do not go deeper than the hull rating. Dock at Lantern Station to unload cargo, recharge and fabricate upgrades. Noise and light draw predators. When the clicking starts, kill your lamp and drift.
""".trimIndent()
        col.addView(label(t, 13.5f, Color.rgb(215, 230, 240)))
        col.addView(button("Back") { back() }, lp(dp(160f)))
        show(panel(scroll(col), 0.66f), "help", back)
    }

    // ------------------------------------------------------------------ station (docked)

    private val stationTabs = listOf("home" to "Home", "workshop" to "Workshop", "drones" to "Drones", "comforts" to "Comforts", "journal" to "Journal")

    private fun readyUpgrades(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (u in Upgrade.entries) { if (u.needsOutpost && game.outposts.isEmpty()) continue; val l = game.upgrades[u.ordinal]; if (l < u.maxLevel && game.storageHas(Costs.upgrade(u, l))) out.add("workshop" to "${u.title} Mk${l + 1}") }
        if (game.outposts.size + game.outpostKits < OutpostRules.MAX_OUTPOSTS && game.storageHas(OutpostRules.kitCost(game.outposts.size + game.outpostKits))) out.add("workshop" to "Outpost kit")
        if (game.fleet.drones.size < game.maxDrones && game.storageHas(DroneStats.buildCost(game.fleet.drones.size))) out.add("drones" to "Drone ${game.fleet.drones.size + 1}")
        for (u in DroneUpgrade.entries) { val l = game.fleet.levels[u.ordinal]; if (l < DroneStats.MAX_LEVEL && game.storageHas(DroneStats.upgradeCost(u, l))) out.add("drones" to "Drone ${u.title} Mk${l + 1}") }
        for (dc in Decor.entries) if (!game.decor[dc.ordinal] && game.storageHas(dc.cost)) out.add("comforts" to dc.title)
        return out
    }

    /** Little number on a menu tab: how many things in it can be built right now. */
    private fun badge(id: String): String {
        val n = readyUpgrades().count { it.first == id }
        return if (n > 0) "  \u25CF$n" else ""
    }

    private fun itemButton(i: Item): View {
        val tracked = game.trackingKey == "mat:" + i.name
        val b = button("${i.title}  x${game.storage[i.ordinal]}", true, 12.5f, if (tracked) Color.argb(255, 20, 70, 96) else CARD) { tip(i) }
        val dr = android.graphics.drawable.BitmapDrawable(act.resources, ItemIcons.get(i))
        dr.setBounds(0, 0, dp(26f), dp(26f))
        b.setCompoundDrawables(dr, null, null, null)
        b.compoundDrawablePadding = dp(6f)
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        return b
    }

    private fun homePage(body: LinearLayout) {
        body.addView(goalCard(), lp())
        val ready = readyUpgrades()
        val rc = vbox(12f).apply { background = bg(CARD, 14f) }
        rc.addView(label("Ready to build", 14f, Color.WHITE, true))
        if (ready.isEmpty()) rc.addView(label("Nothing yet. Collect more materials, or send your drones out.", 12.5f, DIM))
        else for ((tabId, name) in ready.take(4)) {
            val r = hbox()
            r.addView(label(name, 13.5f, GOOD), lp(0, weight = 1f))
            r.addView(button("Open", size = 12.5f) { tab = tabId; showStation() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            rc.addView(r)
        }
        if (ready.size > 4) rc.addView(label("and ${ready.size - 4} more", 12f, DIM))
        body.addView(rc, lp())

        val fc = vbox(12f).apply { background = bg(CARD, 14f) }
        fc.addView(label("Find something", 14f, Color.WHITE, true))
        fc.addView(label("Tap a material. A blue circle shows on the map and compass: it is somewhere inside, so search it with sonar.", 12.5f, DIM))
        val items = Item.entries.filter { Locator.zonesFor(it).isNotEmpty() }
        var k = 0
        while (k < items.size) {
            val r = hbox()
            for (j in k until minOf(k + 2, items.size)) r.addView(itemButton(items[j]), lp(0, weight = 1f, margin = 2f))
            if (items.size - k == 1) r.addView(View(act), lp(0, weight = 1f, margin = 2f))
            fc.addView(r); k += 2
        }
        fc.addView(label("Story and secrets", 13f, AMBER, true), lp(margin = 2f).apply { topMargin = dp(8f) })
        val lore = hbox()
        lore.addView(button("Data cores", size = 12.5f, base = if (game.trackingKey == "cores") Color.argb(255, 20, 70, 96) else CARD) { findLore("cores", "Data cores", "Ping sonar for pink squares.") }, lp(0, weight = 1f, margin = 2f))
        lore.addView(button("Echo Stones", size = 12.5f, base = if (game.trackingKey == "echo") Color.argb(255, 20, 70, 96) else CARD) { findLore("echo", "Echo Stones", "Ping sonar for cyan squares.") }, lp(0, weight = 1f, margin = 2f))
        fc.addView(lore)
        if (game.story.chapter >= 4) {
            val lore2 = hbox()
            lore2.addView(button("Descent beacons", size = 12.5f, base = if (game.trackingKey == "beacon") Color.argb(255, 20, 70, 96) else CARD) { findLore("beacon", "Descent beacons", "They stand on the trench wall.") }, lp(0, weight = 1f, margin = 2f))
            lore2.addView(button("The Choir", size = 12.5f, base = if (game.trackingKey == "choir") Color.argb(255, 20, 70, 96) else CARD) { findLore("choir", "The Choir", "It lies at the bottom of the trench.") }, lp(0, weight = 1f, margin = 2f))
            fc.addView(lore2)
        }
        if (game.trackingKey != null) fc.addView(button("Stop looking", size = 12.5f) { synchronized(game) { game.clearTrack() }; infoText = "Stopped looking."; showStation() }, lp())
        body.addView(fc, lp())
    }

    private fun journalPage(body: LinearLayout) {
        val tabs = hbox()
        for ((id, name) in listOf("biodex" to "Creatures", "logs" to "Logs", "storage" to "Storage"))
            tabs.addView(button(name, size = 12.5f, base = if (id == journalTab) Color.argb(255, 30, 84, 108) else CARD) { journalTab = id; showStation() }, lp(0, weight = 1f, margin = 2f))
        body.addView(tabs)
        when (journalTab) { "biodex" -> biodexList(body); "logs" -> logList(body); else -> storageList(body) }
    }

    fun showStation(t: String? = null) {
        if (t != null) tab = t
        // left: a short menu that is always there. right: header, then one page
        val rail = vbox()
        for ((id, name) in stationTabs) {
            val sel = id == tab
            rail.addView(button(name + badge(id), size = 14f, base = if (sel) Color.argb(255, 30, 84, 108) else CARD) { tab = id; infoText = ""; showStation() }
                .apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, lp(margin = 3f))
        }
        val col = vbox()
        val header = hbox()
        header.addView(label("Lantern Station", 19f, Color.rgb(255, 214, 140), true), lp(0, weight = 1f))
        header.addView(button("Map") { showMap { showStation() } }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(button("Menu") { showPause() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(button("Dive", size = 16f, base = Color.argb(255, 24, 96, 120)) { cb.onDive() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(header)
        val body = vbox()
        when (tab) {
            "workshop" -> fabricator(body)
            "drones" -> drones(body)
            "comforts" -> comforts(body)
            "journal" -> journalPage(body)
            else -> homePage(body)
        }
        col.addView(scroll(body), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (infoText.isNotEmpty()) {
            col.addView(label(infoText, 12.5f, Color.WHITE).apply { setPadding(dp(10f), dp(6f), dp(10f), dp(6f)); background = bg(Color.argb(255, 8, 24, 32), 8f, Color.argb(120, 110, 220, 255)) }, lp(margin = 3f))
        }
        val outer = hbox()
        outer.addView(scroll(rail), LinearLayout.LayoutParams(dp(140f), ViewGroup.LayoutParams.MATCH_PARENT))
        outer.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        show(panel(outer, 0.72f, Gravity.END), "station", null)
    }

    private val openSec = HashSet<String>()
    private val seenSec = HashSet<String>()

    /** A section that can be folded away, so a page never shows everything at once. */
    private fun collapsible(body: LinearLayout, key: String, title: String, ready: Int, defaultOpen: Boolean, fill: (LinearLayout) -> Unit) {
        if (seenSec.add(key) && defaultOpen) openSec.add(key)
        val open = key in openSec
        val txt = (if (open) "\u25BE  " else "\u25B8  ") + title + (if (ready > 0) "    \u25CF$ready ready" else "")
        body.addView(button(txt, size = 14f, base = Color.argb(255, 18, 44, 56)) { if (open) openSec.remove(key) else openSec.add(key); showStation() }
            .apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, lp())
        if (open) fill(body)
    }

    private fun fabricator(body: LinearLayout) {
        body.addView(label("Build upgrades from stored materials. Docking unloads your cargo. Tap a section to open it. Green outline: you can build it now.", 12.5f, DIM))
        val kitReady = if (game.outposts.size + game.outpostKits < OutpostRules.MAX_OUTPOSTS && game.storageHas(OutpostRules.kitCost(game.outposts.size + game.outpostKits))) 1 else 0
        collapsible(body, "ws_bases", "Outposts (second homes)", kitReady, game.outposts.isEmpty() && game.outpostKits == 0 && game.story.chapter >= 2) { b -> outpostsSection(b) }
        val groups = listOf(
            Triple("hull", "Hull and safety", listOf(Upgrade.HULL, Upgrade.OXYGEN, Upgrade.FLARES, Upgrade.PULSE)),
            Triple("power", "Power and movement", listOf(Upgrade.BATTERY, Upgrade.THRUSTERS, Upgrade.SILENT, Upgrade.THERMAL)),
            Triple("sense", "Sensors and tools", listOf(Upgrade.LAMP, Upgrade.SONAR, Upgrade.SCANNER, Upgrade.CARGO)),
        )
        for ((key, title, list) in groups) {
            val ready = list.count { u -> game.upgrades[u.ordinal] < u.maxLevel && game.storageHas(Costs.upgrade(u, game.upgrades[u.ordinal])) }
            collapsible(body, "ws_$key", title, ready, key == "hull") { b ->
                for (u in list) upgradeCard(b, u, true)
            }
        }
        val sysReady = if (game.outposts.isEmpty()) 0 else listOf(Upgrade.ARMOR, Upgrade.STEALTH, Upgrade.HOLD).count { u -> game.upgrades[u.ordinal] < u.maxLevel && game.storageHas(Costs.upgrade(u, game.upgrades[u.ordinal])) }
        collapsible(body, "ws_out", "Outpost systems", sysReady, false) { b ->
            if (game.outposts.isEmpty()) b.addView(label("Locked. Build and deploy an outpost to unlock armor, stealth and extra cargo.", 12.5f, AMBER), lp())
            for (u in listOf(Upgrade.ARMOR, Upgrade.STEALTH, Upgrade.HOLD)) upgradeCard(b, u, game.outposts.isNotEmpty())
        }
        val supplyReady = (if (game.upgrades[Upgrade.FLARES.ordinal] > 0 && game.sub.flares < 6 && game.storageHas(Costs.flare)) 1 else 0) +
            (if (game.sub.repairKits < 3 && game.storageHas(Costs.repairKit)) 1 else 0)
        collapsible(body, "ws_supplies", "Supplies (flares, repair kits)", supplyReady, false) { b -> supplies(b) }
    }

    private fun upgradeCard(b: LinearLayout, u: Upgrade, unlocked: Boolean) {
        val lvl = game.upgrades[u.ordinal]
        val cost = if (lvl < u.maxLevel) Costs.upgrade(u, lvl) else null
        val can = unlocked && cost != null && game.storageHas(cost)
        val card = vbox(10f).apply { background = bg(CARD, 12f, if (can) Color.argb(200, 140, 230, 160) else 0) }
        val top = hbox()
        val name = if (u.maxLevel > 1) "${u.title}  Mk$lvl / Mk${u.maxLevel}" else u.title + (if (lvl > 0) "  (installed)" else "")
        top.addView(label(name, 15f, Color.WHITE, true), lp(0, weight = 1f))
        if (cost != null) {
            top.addView(button(if (can) (if (lvl == 0 && u.maxLevel == 1) "Build" else "Upgrade") else if (unlocked) "Need more" else "Locked", can, 13f) {
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
        b.addView(card, lp())
    }

    private fun outpostsSection(b: LinearLayout) {
        val built = game.outposts.size
        b.addView(label("An outpost is a second home you place anywhere on flat sea floor. It has its own dock and three more drone bays that search around it, and it unlocks Outpost systems upgrades. Storage is shared, so you never carry materials between bases.", 12.5f, DIM))
        b.addView(label("Built: $built / ${OutpostRules.MAX_OUTPOSTS}   Carrying a kit: ${game.outpostKits}   Drone bays: ${game.maxDrones}", 13f, Color.WHITE, true), lp())
        val canMore = built + game.outpostKits < OutpostRules.MAX_OUTPOSTS
        if (canMore) {
            val cost = OutpostRules.kitCost(built + game.outpostKits)
            val can = game.storageHas(cost)
            val card = vbox(10f).apply { background = bg(CARD, 12f, if (can) Color.argb(200, 140, 230, 160) else 0) }
            val top = hbox()
            top.addView(label("Outpost kit", 15f, Color.WHITE, true), lp(0, weight = 1f))
            top.addView(button(if (can) "Build kit" else "Need more", can, 13f) { synchronized(game) { game.craftOutpostKit() }; showStation() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(top)
            card.addView(costRow(cost))
            b.addView(card, lp())
        }
        if (game.outpostKits > 0) b.addView(label("You are carrying a kit. Dive to flat ground at least ${OutpostRules.MIN_SPACING.toInt()} m from your other bases, fly low over the sea floor and press the Deploy button.", 12.5f, GOOD), lp())
        for (i in 1 until game.bases.size) {
            val bs = game.bases[i]
            val r = hbox().apply { setPadding(dp(10f), dp(4f), dp(4f), dp(4f)); background = bg(CARD, 10f) }
            val zn = com.hadal.game.world.SeaGen.zone(bs.x, bs.z)
            r.addView(label("${bs.name}   ${zn.title}, ${(-bs.y).toInt()} m deep", 13.5f, Color.WHITE), lp(0, weight = 1f))
            r.addView(button(if (game.trackingKey == "base:$i") "Hide" else "Show on map", size = 12.5f) { synchronized(game) { game.toggleTrack("base:$i") }; showStation() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            b.addView(r, lp(margin = 3f))
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

    private val droneStatus = ArrayList<Pair<Int, TextView>>()
    private val droneTicker = object : Runnable {
        override fun run() {
            if (screen != "station" || tab != "drones") return
            synchronized(game) { for ((i, tv) in droneStatus) tv.text = game.fleet.statusText(i) }
            root.postDelayed(this, 1000)
        }
    }

    private fun drones(body: LinearLayout) {
        val fleet = game.fleet
        droneStatus.clear()
        body.addView(label("Drones fly out on their own and bring materials home, even while you dive. Press a drone's Target button to pick one material: that trip takes twice as long but brings back about 60% more. Deep water gives rarer finds but can ambush a drone. Upgrades apply to every drone.", 12.5f, DIM))
        val far = Item.entries.filter { Locator.zonesFor(it).isNotEmpty() && !fleet.canReachAny(it) }
        if (far.isNotEmpty()) body.addView(label("Out of range for now: " + far.joinToString { it.title } + ". Upgrade Range to reach them.", 12f, AMBER), lp(margin = 2f))
        for (u in DroneUpgrade.entries) {
            val lvl = fleet.levels[u.ordinal]
            val card = vbox(10f).apply { background = bg(CARD, 12f) }
            val top = hbox()
            top.addView(label("${u.title}  Mk$lvl / Mk${DroneStats.MAX_LEVEL}", 15f, Color.WHITE, true), lp(0, weight = 1f))
            val detail = when (u) {
                DroneUpgrade.SPEED -> "${DroneStats.speed[lvl]} m/s now" + if (lvl < DroneStats.MAX_LEVEL) ", ${DroneStats.speed[lvl + 1]} next." else "."
                DroneUpgrade.CAPACITY -> "${DroneStats.capacity[lvl]} materials per trip now" + if (lvl < DroneStats.MAX_LEVEL) ", ${DroneStats.capacity[lvl + 1]} next." else "."
                DroneUpgrade.RANGE -> "${DroneStats.range[lvl].toInt()} m now" + if (lvl < DroneStats.MAX_LEVEL) ", ${DroneStats.range[lvl + 1].toInt()} m next." else "."
            }
            if (lvl < DroneStats.MAX_LEVEL) {
                val cost = DroneStats.upgradeCost(u, lvl)
                top.addView(button("Upgrade", game.storageHas(cost), 13f) { synchronized(game) { game.upgradeDrones(u) }; showStation() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
                card.addView(top)
                card.addView(label(u.desc + " " + detail, 12.5f, DIM))
                card.addView(costRow(cost))
            } else {
                card.addView(top)
                card.addView(label(u.desc + " " + detail + " Fully upgraded.", 12.5f, GOOD))
            }
            body.addView(card, lp())
        }
        var lastBase = -1
        for (i in 0 until game.maxDrones) {
            if (i / DroneStats.PER_BASE != lastBase) {
                lastBase = i / DroneStats.PER_BASE
                body.addView(label(game.base(lastBase).name + (if (lastBase > 0) "  (drones search around this base)" else ""), 14f, AMBER, true), lp(margin = 2f).apply { topMargin = dp(8f) })
            }
            val card = vbox(10f).apply { background = bg(if (i < fleet.drones.size) Color.argb(255, 18, 36, 40) else CARD, 12f) }
            when {
                i < fleet.drones.size -> {
                    val d0 = fleet.drones[i]
                    val topRow = hbox()
                    topRow.addView(label("Drone ${i + 1}", 15f, Color.WHITE, true), lp(0, weight = 1f))
                    topRow.addView(button("Target: " + (d0.focus?.title ?: "Anything"), size = 12.5f, base = if (d0.focus != null) Color.argb(255, 20, 70, 96) else CARD) {
                        synchronized(game) {
                            val opts = listOf<Item?>(null) + fleet.reachableItems(d0.baseIndex)
                            d0.focus = opts[(opts.indexOf(d0.focus).coerceAtLeast(0) + 1) % opts.size]
                        }
                        showStation()
                    }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
                    card.addView(topRow)
                    val st = label(fleet.statusText(i), 12.5f, ACCENT)
                    droneStatus.add(i to st)
                    card.addView(st)
                }
                i == fleet.drones.size -> {
                    val cost = DroneStats.buildCost(i)
                    val top = hbox()
                    top.addView(label("Drone ${i + 1}  not built", 15f, Color.WHITE, true), lp(0, weight = 1f))
                    top.addView(button("Build", game.storageHas(cost), 13f) { synchronized(game) { game.buildDrone() }; showStation() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
                    card.addView(top)
                    card.addView(costRow(cost))
                }
                else -> {
                    card.addView(label("Drone ${i + 1}", 15f, DIM, true))
                    card.addView(label("Build the previous drone first.", 12.5f, DIM))
                }
            }
            body.addView(card, lp())
        }
        root.removeCallbacks(droneTicker)
        root.postDelayed(droneTicker, 1000)
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
        if (game.tracked != null) top.addView(button("Stop looking") { synchronized(game) { game.clearTrack() }; showMap(back) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        top.addView(button("Close") { back() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(top)
        col.addView(mv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        show(panel(col, 0.9f), "map", back)
    }

    /** Creatures, logs and storage, reachable from the pause menu. */
    private fun showJournal(back: () -> Unit) {
        val col = vbox()
        val top = hbox()
        top.addView(label("Journal", 20f, Color.WHITE, true), lp(0, weight = 1f))
        top.addView(button("Back") { back() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(top)
        val body = vbox()
        val tabs = hbox()
        for ((id, name) in listOf("biodex" to "Creatures", "logs" to "Logs", "storage" to "Storage"))
            tabs.addView(button(name, size = 12.5f, base = if (id == journalTab) Color.argb(255, 30, 84, 108) else CARD) { journalTab = id; showJournal(back) }, lp(0, weight = 1f, margin = 2f))
        col.addView(tabs)
        when (journalTab) { "biodex" -> biodexList(body); "logs" -> logList(body); else -> storageList(body) }
        col.addView(scroll(body), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        show(panel(col, 0.7f), "journal", back)
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
        top.addView(button("Done", size = 15f, base = Color.argb(255, 24, 96, 120)) { game.settings.save(act); cb.onSettingsChanged(); back() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(top)
        val tabs = hbox()
        listOf("Quick", "Graphics", "Controls", "Sound").forEachIndexed { i, n -> tabs.addView(button(n, size = 13f, base = if (i == settingsTab) Color.argb(255, 30, 84, 108) else CARD) { settingsTab = i; showSettings(back) }, lp(0, weight = 1f, margin = 2f)) }
        col.addView(tabs)
        val body = vbox()
        fun changed() { s.preset = GraphicsPreset.CUSTOM; s.save(act); cb.onSettingsChanged(); showSettings(back) }
        fun saved() { s.save(act); cb.onSettingsChanged(); showSettings(back) }
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
        fun volume(v: Float) = "${(v * 100).toInt()}%"
        when (settingsTab) {
            0 -> {
                body.addView(label("Pick how good it should look. Low is fastest, Ultra is prettiest. High suits phones like the Galaxy A54.", 12.5f, DIM))
                val pr = hbox()
                for (p in GraphicsPreset.entries.filter { it != GraphicsPreset.CUSTOM }) pr.addView(button(p.label, size = 14f, base = if (s.preset == p) Color.argb(255, 30, 84, 108) else CARD) { s.applyPreset(p); saved() }, lp(0, weight = 1f))
                body.addView(pr)
                if (s.preset == GraphicsPreset.CUSTOM) body.addView(label("You changed some graphics options by hand (Custom).", 12f, AMBER))
                row("Frame rate", "${s.fpsCap} fps. Lower saves battery.", "30" to { s.fpsCap = 30; saved() }, "60" to { s.fpsCap = 60; saved() }, "90" to { s.fpsCap = 90; saved() })
                row("Sound volume", volume(s.masterVolume), "-" to { s.masterVolume = (s.masterVolume - 0.1f).coerceAtLeast(0f); saved() }, "+" to { s.masterVolume = (s.masterVolume + 0.1f).coerceAtMost(1f); saved() })
                row("Music volume", volume(s.musicVolume), "-" to { s.musicVolume = (s.musicVolume - 0.1f).coerceAtLeast(0f); saved() }, "+" to { s.musicVolume = (s.musicVolume + 0.1f).coerceAtMost(1f); saved() })
                row("Look speed", String.format("%.1f", s.lookSensitivity), "-" to { s.lookSensitivity = (s.lookSensitivity - 0.1f).coerceAtLeast(0.3f); saved() }, "+" to { s.lookSensitivity = (s.lookSensitivity + 0.1f).coerceAtMost(3f); saved() })
                row("Camera", if (s.cockpitCamera) "Inside the sub" else "Behind the sub", "Switch" to { s.cockpitCamera = !s.cockpitCamera; saved() })
                row("Show FPS counter", onOff(s.showFps), "Switch" to { s.showFps = !s.showFps; saved() })
            }
            1 -> {
                body.addView(label("For tinkering. Changing anything here switches the preset to Custom.", 12.5f, DIM))
                row("Resolution", "${(s.renderScale * 100).toInt()}%", "-" to { s.renderScale = (s.renderScale - 0.05f).coerceAtLeast(0.5f); changed() }, "+" to { s.renderScale = (s.renderScale + 0.05f).coerceAtMost(1f); changed() })
                row("Dynamic resolution", onOff(s.dynamicRes) + ". Keeps the frame rate steady.", "Switch" to { s.dynamicRes = !s.dynamicRes; changed() })
                row("View distance", "${s.viewDistance * 64} m", "-" to { s.viewDistance = (s.viewDistance - 1).coerceAtLeast(2); changed() }, "+" to { s.viewDistance = (s.viewDistance + 1).coerceAtMost(4); changed() })
                row("Post effects", onOff(s.postFx) + ". Glow, light shafts, sonar wave.", "Switch" to { s.postFx = !s.postFx; changed() })
                row("Light shafts", listOf("Off", "Normal", "High")[s.volumetrics], "Off" to { s.volumetrics = 0; changed() }, "Normal" to { s.volumetrics = 1; changed() }, "High" to { s.volumetrics = 2; changed() })
                row("Lamp shadows", listOf("Off", "On", "Soft")[s.shadows], "Off" to { s.shadows = 0; changed() }, "On" to { s.shadows = 1; changed() }, "Soft" to { s.shadows = 2; changed() })
                row("Textures", listOf("Flat", "Textured", "Detailed")[s.texQuality], "Flat" to { s.texQuality = 0; changed() }, "Tex" to { s.texQuality = 1; changed() }, "Detail" to { s.texQuality = 2; changed() })
                row("Caustics", onOff(s.caustics), "Switch" to { s.caustics = !s.caustics; changed() })
                row("Floating specks", listOf("Off", "Normal", "Dense")[s.particles], "Off" to { s.particles = 0; changed() }, "Normal" to { s.particles = 1; changed() }, "Dense" to { s.particles = 2; changed() })
                row("Sea life amount", volume(s.floraDensity), "-" to { s.floraDensity = (s.floraDensity - 0.1f).coerceAtLeast(0.3f); changed() }, "+" to { s.floraDensity = (s.floraDensity + 0.1f).coerceAtMost(1f); changed() })
                row("Glow strength", volume(s.bloom), "-" to { s.bloom = (s.bloom - 0.1f).coerceAtLeast(0f); changed() }, "+" to { s.bloom = (s.bloom + 0.1f).coerceAtMost(1.2f); changed() })
                row("Smooth edges (FXAA)", onOff(s.fxaa), "Switch" to { s.fxaa = !s.fxaa; changed() })
                row("Field of view", "${s.fov.toInt()}", "-" to { s.fov = (s.fov - 4f).coerceAtLeast(56f); changed() }, "+" to { s.fov = (s.fov + 4f).coerceAtMost(96f); changed() })
            }
            2 -> {
                row("Controls", s.controls.label, *ControlScheme.entries.map { c -> c.label.split(' ').first() to { s.controls = c; saved() } }.toTypedArray())
                row("Invert look", onOff(s.invertY), "Switch" to { s.invertY = !s.invertY; saved() })
                row("Touch button opacity", volume(s.touchOpacity), "-" to { s.touchOpacity = (s.touchOpacity - 0.1f).coerceAtLeast(0.2f); saved() }, "+" to { s.touchOpacity = (s.touchOpacity + 0.1f).coerceAtMost(1f); saved() })
                row("Vibration", onOff(s.vibration), "Switch" to { s.vibration = !s.vibration; saved() })
                row("Difficulty", s.difficulty.label, *Difficulty.entries.map { df -> df.label to { s.difficulty = df; saved() } }.toTypedArray())
            }
            else -> {
                row("Sound volume", volume(s.masterVolume), "-" to { s.masterVolume = (s.masterVolume - 0.1f).coerceAtLeast(0f); saved() }, "+" to { s.masterVolume = (s.masterVolume + 0.1f).coerceAtMost(1f); saved() })
                row("Music volume", volume(s.musicVolume) + ". Score made with Higgsfield.", "-" to { s.musicVolume = (s.musicVolume - 0.1f).coerceAtLeast(0f); saved() }, "+" to { s.musicVolume = (s.musicVolume + 0.1f).coerceAtMost(1f); saved() })
            }
        }
        col.addView(scroll(body), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        show(panel(col, 0.66f), "settings") { game.settings.save(act); cb.onSettingsChanged(); back() }
    }
}
