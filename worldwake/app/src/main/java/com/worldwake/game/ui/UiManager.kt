package com.worldwake.game.ui

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
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.worldwake.game.game.BuildType
import com.worldwake.game.game.Cat
import com.worldwake.game.game.ControlScheme
import com.worldwake.game.game.Cost
import com.worldwake.game.game.Fmt
import com.worldwake.game.game.GameState
import com.worldwake.game.game.GraphicsPreset
import com.worldwake.game.game.Inventory
import com.worldwake.game.game.Item
import com.worldwake.game.game.ItemKind
import com.worldwake.game.game.Recipe
import com.worldwake.game.game.Recipes
import com.worldwake.game.game.SaveSystem
import com.worldwake.game.game.Sfx
import com.worldwake.game.game.Story
import com.worldwake.game.world.Planet
import com.worldwake.game.world.WorldGen

/** All menus, built from plain Android views so they work with touch, gamepad focus and a mouse. */
class UiManager(
    private val act: Activity,
    private val root: FrameLayout,
    private val game: GameState,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onStartNew()
        fun onContinue()
        fun onResumeGame()
        fun onSaveNow()
        fun onQuitToMenu()
        fun onSettingsChanged()
        fun onQuitApp()
        fun onRespawn()
    }

    private val d = act.resources.displayMetrics.density
    private var current: View? = null
    var isOpen = false
        private set
    var screen = ""
        private set
    private var tab = "pack"
    private var buildCat = Cat.BASE
    private var selected: Item? = null
    private var storage: Inventory? = null
    private var storageTitle = ""
    private var logTitle = ""
    private var logBody = ""
    private var settingsFrom = "main"
    private var backAction: (() -> Unit)? = null
    private var rebuild: (() -> Unit)? = null

    private val TEAL = Color.rgb(89, 240, 224)
    private val GOLD = Color.rgb(255, 208, 96)
    private val ROSE = Color.rgb(255, 106, 140)
    private val PANEL = Color.argb(238, 9, 12, 24)
    private val CARD = Color.argb(255, 22, 28, 46)
    private val DIM = Color.rgb(150, 160, 185)
    private val tabs = listOf("pack", "craft", "build", "planet", "journal")
    private val tabNames = mapOf("pack" to "Pack", "craft" to "Craft", "build" to "Build", "planet" to "Planet", "journal" to "Journal")

    private fun dp(v: Float) = (v * d).toInt()

    // ------------------------------------------------------------------ helpers

    private fun bg(color: Int, radius: Float = 10f, strokeColor: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * d
        if (strokeColor != 0) setStroke(dp(1.4f), strokeColor)
    }

    private fun btnBg(base: Int, accent: Int = TEAL): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), bg(Color.argb(255, 40, 52, 84), 10f, GOLD))
        addState(intArrayOf(android.R.attr.state_pressed), bg(Color.argb(255, 46, 70, 96), 10f, accent))
        addState(intArrayOf(-android.R.attr.state_enabled), bg(Color.argb(140, 22, 26, 38), 10f))
        addState(intArrayOf(), bg(base, 10f, (accent and 0x00ffffff) or 0x55000000))
    }

    private fun label(t: String, size: Float = 13f, color: Int = Color.WHITE, bold: Boolean = false): TextView = TextView(act).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(t: String, enabled: Boolean = true, size: Float = 13f, base: Int = CARD, accent: Int = TEAL, onClick: () -> Unit): Button = Button(act).apply {
        text = t; textSize = size; isAllCaps = false
        setTextColor(if (enabled) Color.WHITE else Color.rgb(105, 110, 125))
        background = btnBg(base, accent)
        isEnabled = enabled
        isFocusable = true
        minHeight = dp(38f); minimumHeight = dp(38f)
        minWidth = 0; minimumWidth = 0
        setPadding(dp(14f), dp(5f), dp(14f), dp(5f))
        setOnClickListener { game.sound?.play(Sfx.UI, 0.5f); onClick() }
        stateListAnimator = null
    }

    private fun vbox(pad: Float = 0f): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun hbox(): LinearLayout = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, margin: Float = 4f) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(margin), dp(margin), dp(margin), dp(margin)) }

    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private fun icon(item: Item, size: Float): ImageView = ImageView(act).apply {
        setImageDrawable(ItemIcon(item))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun findScroll(v: View?): ScrollView? {
        if (v is ScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun firstButton(v: View?): View? {
        if (v is Button && v.isEnabled) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstButton(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun show(v: View, name: String, back: (() -> Unit)?, again: () -> Unit) {
        val same = current != null && screen == name
        val scrollY = if (same) findScroll(current)?.scrollY ?: 0 else 0
        if (!same && name != "main") game.sound?.play(Sfx.UI_OPEN, 0.5f)
        current?.let { root.removeView(it) }
        current = v
        screen = name
        isOpen = true
        backAction = back
        rebuild = again
        game.menuOpen = name == "game" || name == "storage" || name == "log"
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        if (scrollY > 0) findScroll(v)?.let { s -> s.post { s.scrollTo(0, scrollY) } }
        if (game.settings.controls != ControlScheme.TOUCH) v.post { firstButton(v)?.requestFocus() }
    }

    fun close() {
        current?.let { root.removeView(it) }
        current = null
        isOpen = false
        screen = ""
        game.menuOpen = false
    }

    fun refresh() { if (isOpen) rebuild?.invoke() }

    fun back(): Boolean {
        val b = backAction ?: return false
        b()
        return true
    }

    fun cycleTab(dir: Int): Boolean {
        if (screen != "game") return false
        val i = tabs.indexOf(tab)
        tab = tabs[(i + dir + tabs.size) % tabs.size]
        showGameMenu(tab)
        return true
    }

    private fun framed(content: View, dim: Int = Color.argb(150, 2, 3, 8)): FrameLayout = FrameLayout(act).apply {
        setBackgroundColor(dim)
        isClickable = true
        addView(content)
    }

    // ------------------------------------------------------------------ title, pause, death

    fun showMainMenu() {
        val hasSave = SaveSystem.exists(act.filesDir)
        val box = vbox(22f)
        box.background = bg(Color.argb(205, 6, 8, 18), 18f, Color.argb(60, 89, 240, 224))
        box.addView(label("W O R L D W A K E", 30f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        box.addView(label("a dead moon, a ringed sky, and nine people who never came home", 12f, DIM), lp(margin = 2f))
        box.addView(View(act), lp(h = dp(10f)))
        if (hasSave) box.addView(button("Continue", size = 15f, accent = GOLD) { callbacks.onContinue() }, lp())
        box.addView(button(if (hasSave) "New Game" else "Begin", size = 15f) { if (hasSave) confirmNew() else showIntro() }, lp())
        box.addView(button("Planets") { showPlanets() }, lp())
        box.addView(button("Settings") { settingsFrom = "main"; showSettings() }, lp())
        box.addView(button("Quit") { callbacks.onQuitApp() }, lp())
        val f = FrameLayout(act)
        f.addView(box, FrameLayout.LayoutParams(dp(330f), WRAP, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(40f) })
        f.addView(label("v1.0  ·  Ysmere", 10f, Color.argb(160, 255, 255, 255)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(14f), dp(10f)) })
        show(f, "main", null) { showMainMenu() }
    }

    private fun confirmNew() {
        val box = vbox(22f)
        box.background = bg(PANEL, 16f, ROSE)
        box.addView(label("Start over?", 20f, Color.WHITE, true))
        box.addView(label("Your current save on Ysmere will be replaced.", 13f, DIM), lp())
        val row = hbox()
        row.addView(button("Keep my save") { showMainMenu() }, lp(0, WRAP, 1f))
        row.addView(button("Start over", accent = ROSE) { showIntro() }, lp(0, WRAP, 1f))
        box.addView(row)
        val f = framed(box)
        (box.layoutParams as FrameLayout.LayoutParams).apply { width = dp(380f); height = WRAP; gravity = Gravity.CENTER }
        show(f, "confirm", { showMainMenu() }) { confirmNew() }
    }

    private fun reader(title: String, body: String, name: String, button: String, accent: Int, onDone: () -> Unit, again: () -> Unit) {
        val box = vbox(20f)
        box.background = bg(PANEL, 16f, (accent and 0x00ffffff) or 0x66000000)
        box.addView(label(title, 21f, accent).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        val sv = ScrollView(act)
        sv.addView(label(body, 14f, Color.rgb(226, 230, 240)).apply { setLineSpacing(0f, 1.25f); setPadding(0, dp(8f), dp(8f), dp(8f)) })
        box.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        box.addView(button(button, size = 14f, accent = accent) { onDone() }, lp(dp(220f), WRAP).apply { gravity = Gravity.END })
        val f = framed(box, Color.argb(190, 2, 3, 8))
        (box.layoutParams as FrameLayout.LayoutParams).apply { width = (root.width * 0.62f).toInt().coerceAtLeast(dp(420f)); height = (root.height * 0.86f).toInt(); gravity = Gravity.CENTER }
        show(f, name, onDone, again)
    }

    fun showIntro(): Unit = reader("Waker Seven", Story.INTRO, "intro", "Land on Ysmere", TEAL, { callbacks.onStartNew() }) { showIntro() }

    fun showLog(title: String, body: String) {
        logTitle = title; logBody = body
        reader(title, body, "log", "Close", GOLD, { callbacks.onResumeGame() }) { showLog(logTitle, logBody) }
    }

    fun showEnding(): Unit = reader(Story.endingTitle, Story.ENDING, "ending", "Stay on Ysmere", ROSE, { callbacks.onResumeGame() }) { showEnding() }

    private fun showPlanets() {
        val box = vbox(20f)
        box.background = bg(PANEL, 16f, Color.argb(90, 89, 240, 224))
        box.addView(label("The Charter's list", 20f, Color.WHITE, true))
        box.addView(label("Each world wakes differently. Different air, different light, different life.", 12f, DIM), lp())
        fun row(name: String, note: String, open: Boolean) {
            val r = vbox(12f)
            r.background = bg(CARD, 10f, if (open) TEAL else 0)
            r.addView(label(name, 16f, if (open) Color.WHITE else DIM, true))
            r.addView(label(note, 12f, DIM))
            box.addView(r, lp())
        }
        row("Ysmere", "Frozen moon of the ringed giant Maren. Lilac frost over dark slate; it wakes red and gold under a sea-green sky.", true)
        row("Ombra", "A world under one endless storm. Signal lost. Opens after Ysmere is awake.", false)
        row("Solenne", "No surface survey exists. The Charter will not say why. Locked.", false)
        box.addView(button("Back") { showMainMenu() }, lp(dp(160f), WRAP))
        val f = framed(box)
        (box.layoutParams as FrameLayout.LayoutParams).apply { width = dp(460f); height = WRAP; gravity = Gravity.CENTER }
        show(f, "planets", { showMainMenu() }) { showPlanets() }
    }

    fun showPause() {
        val box = vbox(22f)
        box.background = bg(PANEL, 16f, Color.argb(90, 89, 240, 224))
        box.addView(label("Paused", 22f, Color.WHITE, true))
        box.addView(label(Planet.NAME + "  ·  " + String.format("%.1f%% livable", game.stage / Planet.LAST * 100f) + "  ·  " + Fmt.time(game.time) + " played", 12f, DIM), lp())
        box.addView(button("Resume", size = 15f, accent = GOLD) { callbacks.onResumeGame() }, lp())
        box.addView(button("Save") { callbacks.onSaveNow() }, lp())
        box.addView(button("Settings") { settingsFrom = "pause"; showSettings() }, lp())
        box.addView(button("Save and quit to title") { callbacks.onQuitToMenu() }, lp())
        val f = framed(box)
        (box.layoutParams as FrameLayout.LayoutParams).apply { width = dp(340f); height = WRAP; gravity = Gravity.CENTER }
        show(f, "pause", { callbacks.onResumeGame() }) { showPause() }
    }

    fun showDeath() {
        val box = vbox(22f)
        box.background = bg(PANEL, 16f, ROSE)
        box.addView(label("You blacked out", 22f, ROSE, true))
        box.addView(label("WREN is hauling you back to shelter. Nothing was lost.", 13f, DIM), lp())
        box.addView(button("Wake up", size = 15f, accent = ROSE) { callbacks.onRespawn() }, lp())
        val f = framed(box, Color.argb(200, 20, 2, 8))
        (box.layoutParams as FrameLayout.LayoutParams).apply { width = dp(360f); height = WRAP; gravity = Gravity.CENTER }
        show(f, "death", null) { showDeath() }
    }

    // ------------------------------------------------------------------ in-game menu

    fun showGameMenu(which: String) {
        tab = if (tabs.contains(which)) which else "pack"
        val outer = hbox()
        outer.background = bg(PANEL, 0f)
        outer.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        val rail = vbox(2f)
        for (t in tabs) {
            val b = button(tabNames[t]!!, size = 13f, base = if (t == tab) Color.argb(255, 34, 60, 78) else CARD, accent = if (t == tab) GOLD else TEAL) { showGameMenu(t) }
            rail.addView(b, lp(dp(96f), WRAP, margin = 3f))
        }
        rail.addView(View(act), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(button("Close", accent = ROSE) { callbacks.onResumeGame() }, lp(dp(96f), WRAP, margin = 3f))
        outer.addView(rail, LinearLayout.LayoutParams(WRAP, MATCH))
        val content: View = when (tab) {
            "craft" -> craftTab()
            "build" -> buildTab()
            "planet" -> planetTab()
            "journal" -> journalTab()
            else -> packTab()
        }
        outer.addView(content, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dp(8f) })
        show(framed(outer.apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH) }), "game", { callbacks.onResumeGame() }) { showGameMenu(tab) }
    }

    private fun header(title: String, sub: String): LinearLayout {
        val h = vbox()
        h.addView(label(title, 18f, Color.WHITE, true))
        if (sub.isNotEmpty()) h.addView(label(sub, 11.5f, DIM))
        return h
    }

    private fun itemTile(item: Item, count: Int, sel: Boolean, onClick: () -> Unit): View {
        val b = Button(act)
        b.isAllCaps = false
        b.text = count.toString()
        b.textSize = 12f
        b.setTextColor(Color.WHITE)
        b.gravity = Gravity.BOTTOM or Gravity.END
        b.setPadding(0, 0, dp(7f), dp(3f))
        b.background = btnBg(if (sel) Color.argb(255, 34, 60, 78) else CARD, if (sel) GOLD else TEAL)
        b.stateListAnimator = null
        b.minWidth = 0; b.minimumWidth = 0; b.minHeight = 0; b.minimumHeight = 0
        b.setOnClickListener { game.sound?.play(Sfx.UI, 0.5f); onClick() }
        val f = FrameLayout(act)
        f.addView(b, FrameLayout.LayoutParams(MATCH, MATCH))
        val iv = ImageView(act).apply { setImageDrawable(ItemIcon(item)) }
        f.addView(iv, FrameLayout.LayoutParams(dp(36f), dp(36f), Gravity.TOP or Gravity.START).apply { setMargins(dp(5f), dp(4f), 0, 0) })
        return f
    }

    private fun grid(inv: Inventory, columns: Int, sel: Item?, onClick: (Item) -> Unit): GridLayout {
        val g = GridLayout(act)
        g.columnCount = columns
        for (i in Item.entries) {
            val n = inv.count(i)
            if (n <= 0) continue
            val tile = itemTile(i, n, i == sel) { onClick(i) }
            g.addView(tile, GridLayout.LayoutParams().apply { width = dp(62f); height = dp(58f); setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) })
        }
        return g
    }

    private fun packTab(): View {
        val p = game.player
        val row = hbox()
        row.gravity = Gravity.TOP
        val left = vbox(6f)
        left.addView(header("Pack", p.inv.total.toString() + " / " + p.inv.capacity + " carried"))
        val sv = ScrollView(act)
        val cols = ((root.width - dp(430f)) / dp(68f)).coerceIn(3, 9)
        sv.addView(grid(p.inv, cols, selected) { selected = it; showGameMenu("pack") })
        left.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        if (p.inv.total == 0) left.addView(label("Nothing yet. Ore lies on the ground all over the flats.", 12f, DIM))
        row.addView(left, LinearLayout.LayoutParams(0, MATCH, 1f))

        val right = vbox(12f)
        right.background = bg(CARD, 12f)
        val s = selected
        if (s != null && p.inv.count(s) > 0) {
            val top = hbox()
            top.addView(icon(s, 44f))
            top.addView(label("  " + s.label, 16f, Color.WHITE, true))
            right.addView(top)
            right.addView(label(s.desc, 12.5f, DIM), lp())
            if (s.kind == ItemKind.SUPPLY && s != Item.SPORE) {
                right.addView(button("Use", accent = GOLD) { game.post { game.useItem(s); game.ui?.refreshMenus() } }, lp())
            }
        } else {
            right.addView(label("Suit", 16f, Color.WHITE, true))
            right.addView(label("Tap an item to see what it is for.", 12f, DIM), lp())
        }
        right.addView(View(act), lp(h = dp(8f)))
        right.addView(label("Installed", 12f, TEAL, true))
        val ups = Item.entries.filter { it.isUpgrade && p.has(it) }
        if (ups.isEmpty()) right.addView(label("No upgrades yet. Make them at a fabricator.", 12f, DIM))
        for (u in ups) {
            val r = hbox(); r.addView(icon(u, 26f)); r.addView(label("  " + u.label, 12.5f)); right.addView(r, lp(margin = 2f))
        }
        right.addView(label("Air " + p.o2Max.toInt() + " s   ·   pack " + p.packSize, 11.5f, DIM), lp())
        row.addView(right, LinearLayout.LayoutParams(dp(250f), MATCH).apply { leftMargin = dp(8f) })
        return row
    }

    private fun costRow(cost: Array<Cost>, inv: Inventory): LinearLayout {
        val r = hbox()
        for (c in cost) {
            val have = inv.count(c.item)
            r.addView(icon(c.item, 20f))
            r.addView(label(" " + c.item.label + " " + have + "/" + c.n + "   ", 11.5f, if (have >= c.n) Color.rgb(170, 240, 200) else ROSE))
        }
        return r
    }

    private fun craftTab(): View {
        val p = game.player
        val near = game.nearFabricator()
        val box = vbox(6f)
        box.addView(header("Craft", if (near) "Fabricator in range. Everything is available." else "No fabricator nearby. Only field recipes work. The lander has one built in."))
        val sv = ScrollView(act)
        val list = vbox()
        fun section(name: String, items: List<Recipe>) {
            if (items.isEmpty()) return
            list.addView(label(name, 12f, TEAL, true), lp(margin = 6f))
            for (r in items) {
                val owned = r.out.isUpgrade && p.has(r.out)
                val locked = game.stageReached < r.stage
                val needPrev = r.needs != null && !p.has(r.needs)
                val can = !owned && !locked && !needPrev && (r.hand || near) && p.inv.has(r.cost)
                val card = hbox()
                card.background = bg(CARD, 10f)
                card.setPadding(dp(10f), dp(6f), dp(8f), dp(6f))
                card.addView(icon(r.out, 38f))
                val mid = vbox()
                mid.setPadding(dp(10f), 0, 0, 0)
                mid.addView(label(r.out.label + (if (r.count > 1) "  x" + r.count else "") + (if (!r.out.isUpgrade) "   (have " + p.inv.count(r.out) + ")" else ""), 13.5f, if (owned) DIM else Color.WHITE, true))
                when {
                    owned -> mid.addView(label("Installed", 11.5f, TEAL))
                    locked -> mid.addView(label("Unlocks at stage " + r.stage + ", " + Planet.stageNames[r.stage], 11.5f, DIM))
                    needPrev -> mid.addView(label("Needs " + r.needs!!.label + " first", 11.5f, DIM))
                    else -> mid.addView(costRow(r.cost, p.inv))
                }
                card.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
                val why = if (!r.hand && !near && !owned && !locked) "Fabricator" else "Make"
                card.addView(button(why, enabled = can, accent = GOLD) { game.post { game.craft(r); game.ui?.refreshMenus() } }, LinearLayout.LayoutParams(dp(104f), WRAP))
                list.addView(card, lp(margin = 3f))
            }
        }
        section("FIELD RECIPES", Recipes.all.filter { it.hand })
        section("PARTS", Recipes.all.filter { !it.hand && !it.out.isUpgrade })
        section("SUIT UPGRADES", Recipes.all.filter { it.out.isUpgrade })
        sv.addView(list)
        box.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return box
    }

    private fun outputLine(t: BuildType): String {
        val parts = ArrayList<String>()
        if (t.heat > 0) parts.add("+" + Fmt.wi(t.heat) + " heat/s")
        if (t.press > 0) parts.add("+" + Fmt.wi(t.press) + " pressure/s")
        if (t.oxy > 0) parts.add("+" + Fmt.wi(t.oxy) + " oxygen/s")
        if (t.bio > 0) parts.add("+" + Fmt.wi(t.bio) + " life/s")
        if (t.power > 0) parts.add("makes " + t.power.toInt() + " kW")
        if (t.power < 0) parts.add("uses " + (-t.power).toInt() + " kW")
        if (t.storage > 0) parts.add("holds " + t.storage)
        return parts.joinToString("   ·   ")
    }

    private fun buildTab(): View {
        val p = game.player
        val box = vbox(6f)
        val top = hbox()
        top.addView(header("Build", "Power " + game.powerUse.toInt() + " / " + game.powerGen.toInt() + " kW" + (if (game.efficiency < 0.999f) "   ·   machines at " + (game.efficiency * 100).toInt() + "%" else "")), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(button("Take apart", accent = ROSE) { game.post { game.buildType = null; game.deconMode = true }; callbacks.onResumeGame() }, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(top)
        val cats = hbox()
        for (c in Cat.entries) {
            cats.addView(button(c.label, size = 12f, base = if (c == buildCat) Color.argb(255, 34, 60, 78) else CARD, accent = if (c == buildCat) GOLD else TEAL) { buildCat = c; showGameMenu("build") }, lp(0, WRAP, 1f, 2f))
        }
        box.addView(cats)
        val sv = ScrollView(act)
        val list = vbox()
        for (t in BuildType.entries) {
            if (t.cat != buildCat) continue
            val locked = !game.unlocked(t)
            val can = !locked && p.inv.has(t.cost)
            val card = hbox()
            card.background = bg(CARD, 10f)
            card.setPadding(dp(12f), dp(7f), dp(8f), dp(7f))
            val mid = vbox()
            val count = game.placed.count { it.type == t }
            mid.addView(label(t.label + (if (count > 0) "   (built " + count + ")" else ""), 14f, if (locked) DIM else Color.WHITE, true))
            if (locked) mid.addView(label("Unlocks at stage " + t.stage + ", " + Planet.stageNames[t.stage], 11.5f, DIM))
            else {
                mid.addView(label(t.desc, 11.5f, DIM))
                val o = outputLine(t)
                if (o.isNotEmpty()) mid.addView(label(o, 11.5f, GOLD))
                mid.addView(costRow(t.cost, p.inv))
            }
            card.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(button("Build", enabled = can, accent = GOLD) { game.post { game.startBuild(t) }; callbacks.onResumeGame() }, LinearLayout.LayoutParams(dp(96f), WRAP))
            list.addView(card, lp(margin = 3f))
        }
        sv.addView(list)
        box.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return box
    }

    private fun bar(frac: Float, color: Int): View {
        val f = FrameLayout(act)
        f.background = bg(Color.argb(255, 14, 18, 32), 4f)
        val fillV = View(act)
        fillV.background = bg(color, 4f)
        f.addView(fillV, FrameLayout.LayoutParams(0, MATCH))
        f.post { fillV.layoutParams = FrameLayout.LayoutParams((f.width * frac.coerceIn(0f, 1f)).toInt(), MATCH); fillV.requestLayout() }
        return f
    }

    private fun planetTab(): View {
        val g = game
        val box = vbox(6f)
        val sv = ScrollView(act)
        val list = vbox()
        val pct = g.stage / Planet.LAST * 100f
        list.addView(label(Planet.NAME + "   " + String.format(if (pct < 10f) "%.2f%%" else "%.1f%%", pct) + " livable", 22f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        list.addView(label("Stage " + g.stageReached + "  ·  " + Planet.stageNames[g.stageReached] + "  ·  " + Planet.stageNotes[g.stageReached], 12.5f, TEAL), lp(margin = 2f))
        if (g.stageReached < Planet.LAST) {
            val need = Planet.thresholds[g.stageReached + 1]
            val left = need - g.wi
            val eta = if (g.rate > 1e-9) Fmt.time(left / g.rate) + " of play at this pace" else "build machines to start"
            list.addView(bar(g.stage - g.stageReached, TEAL), lp(h = dp(8f)))
            list.addView(label("Next: " + Planet.stageNames[g.stageReached + 1] + " at " + Fmt.wi(need) + " Wi   ·   " + eta, 12f, DIM), lp(margin = 2f))
        }
        list.addView(label("Wake Index " + Fmt.wi(g.wi) + "   +" + Fmt.wi(g.rate) + " per second", 14f, Color.WHITE, true), lp(margin = 6f))
        fun stat(name: String, value: Double, rate: Double, color: Int) {
            val r = hbox()
            r.background = bg(CARD, 8f)
            r.setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            r.addView(label(name, 13f, color, true), LinearLayout.LayoutParams(dp(110f), WRAP))
            r.addView(label(Fmt.wi(value), 13f), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(label("+" + Fmt.wi(rate * g.efficiency) + " /s", 12.5f, DIM))
            list.addView(r, lp(margin = 2f))
        }
        stat("Heat", g.heat, g.rateHeat, Color.rgb(255, 150, 110))
        stat("Pressure", g.press, g.ratePress, Color.rgb(140, 170, 255))
        stat("Oxygen", g.oxy, g.rateOxy, TEAL)
        stat("Life", g.bio, g.rateBio, ROSE)
        list.addView(label("Power " + g.powerUse.toInt() + " kW used of " + g.powerGen.toInt() + " kW made" + (if (g.efficiency < 0.999f) ". Short on power, so machines run at " + (g.efficiency * 100).toInt() + "%." else "."), 12f, if (g.efficiency < 0.999f) ROSE else DIM), lp())
        list.addView(label("Machines keep running while the game is closed, at one fifth of their pace.", 12f, DIM), lp(margin = 2f))
        list.addView(label("Stages", 12f, TEAL, true), lp(margin = 6f))
        for (i in 1..Planet.LAST) {
            val done = g.stageReached >= i
            list.addView(label((if (done) "●  " else "○  ") + i + "  " + Planet.stageNames[i] + "   " + Fmt.wi(Planet.thresholds[i]) + " Wi" + (if (done) "   " + Planet.stageNotes[i] else ""), 12.5f, if (done) Color.WHITE else DIM), lp(margin = 1f))
        }
        sv.addView(list)
        box.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return box
    }

    private fun journalTab(): View {
        val box = vbox(6f)
        box.addView(header("Journal", "Lantern Nine logs found: " + game.logsRead.size + " of " + WorldGen.LOG_COUNT))
        val sv = ScrollView(act)
        val list = vbox()
        list.addView(button("The contract  ·  how you got here", accent = TEAL) { reader("Waker Seven", Story.INTRO, "log", "Back", TEAL, { showGameMenu("journal") }) { showGameMenu("journal") } }, lp(margin = 3f))
        for (i in Story.logs.indices) {
            val log = Story.logs[i]
            if (game.logsRead.contains(i)) {
                list.addView(button("Day " + log.day + "  ·  " + log.title + "  ·  " + log.author, accent = GOLD) {
                    reader(log.title, log.author + "  ·  day " + log.day + "\n\n" + log.body, "log", "Back", GOLD, { showGameMenu("journal") }) { showGameMenu("journal") }
                }, lp(margin = 3f))
            } else list.addView(label("      Day ???  ·  not found yet", 12.5f, DIM), lp(margin = 6f))
        }
        if (game.ended) list.addView(button("Awake  ·  the ending", accent = ROSE) { reader(Story.endingTitle, Story.ENDING, "log", "Back", ROSE, { showGameMenu("journal") }) { showGameMenu("journal") } }, lp(margin = 3f))
        sv.addView(list)
        box.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return box
    }

    // ------------------------------------------------------------------ storage

    fun openStorage(title: String, inv: Inventory) {
        storage = inv; storageTitle = title
        showStorage()
    }

    private fun showStorage() {
        val st = storage ?: return
        val p = game.player
        val outer = vbox(10f)
        outer.background = bg(PANEL, 0f)
        val top = hbox()
        top.addView(header(storageTitle, "Tap an item to move one. Use the buttons to move everything."), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(button("Take all", accent = GOLD) {
            game.post { for (i in Item.entries) { val n = st.count(i); if (n > 0) { val k = p.inv.add(i, n); st.remove(i, k) } }; game.ui?.refreshMenus() }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6f) })
        top.addView(button("Store all") {
            game.post { for (i in Item.entries) { val n = p.inv.count(i); if (n > 0) { val k = st.add(i, n); p.inv.remove(i, k) } }; game.ui?.refreshMenus() }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6f) })
        top.addView(button("Close", accent = ROSE) { callbacks.onResumeGame() }, LinearLayout.LayoutParams(WRAP, WRAP))
        outer.addView(top)
        val row = hbox()
        row.gravity = Gravity.TOP
        val cols = ((root.width / 2 - dp(30f)) / dp(68f)).coerceIn(3, 7)
        fun side(name: String, inv: Inventory, other: Inventory): View {
            val b = vbox(8f)
            b.background = bg(CARD, 12f)
            b.addView(label(name + "   " + inv.total + " / " + inv.capacity, 13f, TEAL, true))
            val sv = ScrollView(act)
            sv.addView(grid(inv, cols, null) { item ->
                game.post { if (other.add(item, 1) == 1) inv.remove(item, 1); game.ui?.refreshMenus() }
            })
            b.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
            return b
        }
        row.addView(side("Your pack", p.inv, st), LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(5f) })
        row.addView(side(storageTitle, st, p.inv), LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dp(5f) })
        outer.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8f) })
        show(framed(outer.apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH) }), "storage", { callbacks.onResumeGame() }) { showStorage() }
    }

    // ------------------------------------------------------------------ settings

    fun showSettings() {
        val s = game.settings
        val box = vbox(16f)
        box.background = bg(PANEL, 16f, Color.argb(90, 89, 240, 224))
        box.addView(label("Settings", 20f, Color.WHITE, true))
        val sv = ScrollView(act)
        val list = vbox()
        fun changed() { s.save(act); callbacks.onSettingsChanged(); showSettings() }
        fun cycle(name: String, value: String, next: () -> Unit) {
            val r = hbox()
            r.addView(label(name, 13f), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(button(value, size = 12.5f) { next(); changed() }, LinearLayout.LayoutParams(dp(190f), WRAP))
            list.addView(r, lp(margin = 2f))
        }
        fun step(name: String, value: String, minus: () -> Unit, plus: () -> Unit) {
            val r = hbox()
            r.addView(label(name, 13f), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(button("-", size = 14f) { minus(); changed() }, LinearLayout.LayoutParams(dp(48f), WRAP))
            r.addView(label(value, 13f, GOLD).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(94f), WRAP))
            r.addView(button("+", size = 14f) { plus(); changed() }, LinearLayout.LayoutParams(dp(48f), WRAP))
            list.addView(r, lp(margin = 2f))
        }
        fun onOff(b: Boolean) = if (b) "On" else "Off"
        list.addView(label("CONTROLS", 11.5f, TEAL, true), lp(margin = 4f))
        cycle("Control scheme", s.controls.label) { s.controls = ControlScheme.entries[(s.controls.ordinal + 1) % ControlScheme.entries.size] }
        step("Look sensitivity", String.format("%.1f", s.lookSensitivity), { s.lookSensitivity = (s.lookSensitivity - 0.1f).coerceAtLeast(0.3f) }, { s.lookSensitivity = (s.lookSensitivity + 0.1f).coerceAtMost(3f) })
        cycle("Invert look up and down", onOff(s.invertY)) { s.invertY = !s.invertY }
        cycle("Run when the stick is pushed fully", onOff(s.autoSprint)) { s.autoSprint = !s.autoSprint }
        step("Touch button opacity", (s.touchOpacity * 100).toInt().toString() + "%", { s.touchOpacity = (s.touchOpacity - 0.1f).coerceAtLeast(0.2f) }, { s.touchOpacity = (s.touchOpacity + 0.1f).coerceAtMost(1f) })
        cycle("Vibration", onOff(s.vibration)) { s.vibration = !s.vibration }
        list.addView(label("SOUND", 11.5f, TEAL, true), lp(margin = 4f))
        step("Volume", (s.masterVolume * 100).toInt().toString() + "%", { s.masterVolume = (s.masterVolume - 0.1f).coerceAtLeast(0f) }, { s.masterVolume = (s.masterVolume + 0.1f).coerceAtMost(1f) })
        step("Music", (s.musicVolume * 100).toInt().toString() + "%", { s.musicVolume = (s.musicVolume - 0.1f).coerceAtLeast(0f) }, { s.musicVolume = (s.musicVolume + 0.1f).coerceAtMost(1f) })
        cycle("WREN subtitles", onOff(s.subtitles)) { s.subtitles = !s.subtitles }
        list.addView(label("GRAPHICS", 11.5f, TEAL, true), lp(margin = 4f))
        cycle("Quality", s.preset.label) {
            val order = listOf(GraphicsPreset.LOW, GraphicsPreset.MEDIUM, GraphicsPreset.HIGH, GraphicsPreset.ULTRA)
            s.applyPreset(order[(order.indexOf(s.preset) + 1).coerceAtLeast(0) % order.size])
        }
        step("Resolution", (s.renderScale * 100).toInt().toString() + "%", { s.renderScale = (s.renderScale - 0.05f).coerceAtLeast(0.4f); s.preset = GraphicsPreset.CUSTOM }, { s.renderScale = (s.renderScale + 0.05f).coerceAtMost(1f); s.preset = GraphicsPreset.CUSTOM })
        step("View distance", (s.viewDistance * 64).toString() + " m", { s.viewDistance = (s.viewDistance - 1).coerceAtLeast(2); s.preset = GraphicsPreset.CUSTOM }, { s.viewDistance = (s.viewDistance + 1).coerceAtMost(8); s.preset = GraphicsPreset.CUSTOM })
        cycle("Shadows", arrayOf("Off", "Hard", "Soft", "Soft, far")[s.shadowQuality.coerceIn(0, 3)]) { s.shadowQuality = (s.shadowQuality + 1) % 4; s.preset = GraphicsPreset.CUSTOM }
        cycle("Bloom and film tone", onOff(s.postFx)) { s.postFx = !s.postFx; s.preset = GraphicsPreset.CUSTOM }
        cycle("Textures", arrayOf("Flat", "Textured", "Textured with relief")[s.texQuality.coerceIn(0, 2)]) { s.texQuality = (s.texQuality + 1) % 3; s.preset = GraphicsPreset.CUSTOM }
        step("Ground plants", (s.decorDensity * 100).toInt().toString() + "%", { s.decorDensity = (s.decorDensity - 0.1f).coerceAtLeast(0.1f); s.preset = GraphicsPreset.CUSTOM }, { s.decorDensity = (s.decorDensity + 0.1f).coerceAtMost(1f); s.preset = GraphicsPreset.CUSTOM })
        cycle("Frame rate limit", s.fpsCap.toString()) { val o = listOf(30, 45, 60, 90, 120); s.fpsCap = o[(o.indexOf(s.fpsCap) + 1).coerceAtLeast(0) % o.size] }
        step("Field of view", s.fov.toInt().toString(), { s.fov = (s.fov - 5f).coerceAtLeast(55f) }, { s.fov = (s.fov + 5f).coerceAtMost(100f) })
        cycle("Show frame rate", onOff(s.showFps)) { s.showFps = !s.showFps }
        list.addView(label("WORLD", 11.5f, TEAL, true), lp(margin = 4f))
        step("Length of a day", s.dayLengthMinutes.toString() + " min", { s.dayLengthMinutes = (s.dayLengthMinutes - 4).coerceAtLeast(8) }, { s.dayLengthMinutes = (s.dayLengthMinutes + 4).coerceAtMost(60) })
        sv.addView(list)
        box.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val backTo = { if (settingsFrom == "pause") showPause() else showMainMenu() }
        box.addView(button("Done", accent = GOLD) { backTo() }, lp(dp(160f), WRAP))
        val f = framed(box)
        (box.layoutParams as FrameLayout.LayoutParams).apply { width = dp(520f); height = (root.height * 0.92f).toInt(); gravity = Gravity.CENTER }
        show(f, "settings", { backTo() }) { showSettings() }
    }
}
