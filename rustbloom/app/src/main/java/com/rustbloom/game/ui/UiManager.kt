package com.rustbloom.game.ui

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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rustbloom.game.game.BuildType
import com.rustbloom.game.game.Category
import com.rustbloom.game.game.ControlScheme
import com.rustbloom.game.game.Difficulty
import com.rustbloom.game.game.GameState
import com.rustbloom.game.game.GraphicsPreset
import com.rustbloom.game.game.Item
import com.rustbloom.game.game.ItemStack
import com.rustbloom.game.game.Placed
import com.rustbloom.game.game.Recipes
import com.rustbloom.game.game.SaveSystem
import com.rustbloom.game.game.Skill
import com.rustbloom.game.game.Station
import com.rustbloom.game.game.Story

/**
 * All menus, built from plain Android views so they work with touch, D-pad/gamepad focus and mouse.
 */
class UiManager(
    private val act: Activity,
    private val root: FrameLayout,
    private val game: GameState,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onStartNew(d: Difficulty)
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
    private var tab = "inventory"
    private var selectedSlot = -1
    private var moveFrom = -1
    private var craftStation: Station? = null
    private var chest: Placed? = null
    private var backAction: (() -> Unit)? = null

    private val ACCENT = Color.rgb(79, 227, 210)
    private val AMBER = Color.rgb(255, 210, 110)
    private val PANEL = Color.argb(236, 12, 18, 20)
    private val CARD = Color.argb(255, 26, 34, 37)
    private val DIM = Color.rgb(150, 165, 165)

    private fun dp(v: Float) = (v * d).toInt()

    // ------------------------------------------------------------------ helpers

    private fun bg(color: Int, radius: Float = 10f, strokeColor: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * d
        if (strokeColor != 0) setStroke(dp(1.5f), strokeColor)
    }

    private fun btnBg(base: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), bg(Color.argb(255, 46, 70, 70), 10f, AMBER))
        addState(intArrayOf(android.R.attr.state_pressed), bg(Color.argb(255, 60, 90, 88), 10f, ACCENT))
        addState(intArrayOf(-android.R.attr.state_enabled), bg(Color.argb(150, 30, 34, 36), 10f))
        addState(intArrayOf(), bg(base, 10f, Color.argb(90, 79, 227, 210)))
    }

    private fun label(t: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false): TextView = TextView(act).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(t: String, enabled: Boolean = true, size: Float = 14f, base: Int = CARD, onClick: () -> Unit): Button = Button(act).apply {
        text = t; textSize = size; isAllCaps = false
        setTextColor(if (enabled) Color.WHITE else Color.rgb(110, 115, 115))
        background = btnBg(base)
        isEnabled = enabled
        isFocusable = true
        minHeight = dp(40f); minimumHeight = dp(40f)
        setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        setOnClickListener { game.sound?.play(com.rustbloom.game.game.Sfx.UI, 0.5f); onClick() }
        stateListAnimator = null
    }

    private fun vbox(pad: Float = 0f): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun hbox(): LinearLayout = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, margin: Float = 4f) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(margin), dp(margin), dp(margin), dp(margin)) }

    private var screenKey = ""

    private fun collect(v: View, scrolls: MutableList<View>, buttons: MutableList<View>) {
        if (v is ScrollView || v is HorizontalScrollView) scrolls.add(v)
        if (v is Button) buttons.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), scrolls, buttons)
    }

    /**
     * Shows a screen. When the same screen is rebuilt (after crafting, changing a setting...), scroll positions and
     * the focused button are kept so nothing jumps.
     */
    private fun show(v: View, name: String, back: (() -> Unit)?) {
        val key = name + ":" + tab + ":" + settingsTab
        val oldScrolls = ArrayList<View>(); val oldButtons = ArrayList<View>()
        val same = current != null && key == screenKey
        if (same) collect(current!!, oldScrolls, oldButtons)
        val scrollPos = oldScrolls.map { it.scrollX to it.scrollY }
        val focusIdx = oldButtons.indexOfFirst { it.isFocused }
        if (!same && name != "main") game.sound?.play(com.rustbloom.game.game.Sfx.UI_OPEN, 0.5f)
        current?.let { root.removeView(it) }
        current = v
        screen = name
        screenKey = key
        isOpen = true
        backAction = back
        root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        v.post {
            val newScrolls = ArrayList<View>(); val newButtons = ArrayList<View>()
            collect(v, newScrolls, newButtons)
            if (same) {
                for (k in 0 until minOf(scrollPos.size, newScrolls.size)) newScrolls[k].scrollTo(scrollPos[k].first, scrollPos[k].second)
                if (focusIdx >= 0 && focusIdx < newButtons.size && newButtons[focusIdx].isEnabled) { newButtons[focusIdx].requestFocus(); return@post }
            }
            focusFirst(v)
        }
    }

    private fun focusFirst(v: View) {
        if (v is Button && v.isEnabled) { v.requestFocus(); return }
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c is Button && c.isEnabled && c.visibility == View.VISIBLE) { c.requestFocus(); return }
            if (c is ViewGroup) { focusFirst(c); if (c.findFocus() != null) return }
        }
    }

    /** Gamepad bumpers switch tabs in the pack menu and settings. */
    fun cycleTab(dir: Int): Boolean {
        if (screen == "game") {
            val idx = tabs.indexOfFirst { it.first == tab }
            showGameMenu(tabs[(idx + dir + tabs.size) % tabs.size].first)
            return true
        }
        if (screen == "settings") {
            settingsTab = (settingsTab + dir + 3) % 3
            settingsBack?.let { showSettings(it) }
            return true
        }
        return false
    }

    private fun iconButton(t: String, icon: android.graphics.Bitmap?, enabled: Boolean = true, size: Float = 11f, base: Int = CARD, iconDp: Float = 34f, onClick: () -> Unit): Button {
        val b = button(t, enabled, size, base, onClick)
        if (icon != null) {
            val dr = android.graphics.drawable.BitmapDrawable(act.resources, icon)
            dr.setBounds(0, 0, dp(iconDp), dp(iconDp))
            if (!enabled) dr.alpha = 110
            b.setCompoundDrawables(null, dr, null, null)
            b.compoundDrawablePadding = dp(2f)
        }
        b.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        return b
    }

    private fun iconView(bmp: android.graphics.Bitmap, sizeDp: Float): android.widget.ImageView = android.widget.ImageView(act).apply {
        setImageBitmap(bmp)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { setMargins(dp(2f), dp(2f), dp(6f), dp(2f)) }
    }

    /** A small "icon count/need" chip used in recipe and cost lists. */
    private fun costChip(item: com.rustbloom.game.game.Item, need: Int): View {
        val have = game.player.inv.count(item)
        val row = hbox().apply { setPadding(dp(4f), dp(2f), dp(8f), dp(2f)); background = bg(Color.argb(255, 18, 24, 26), 8f) }
        row.addView(iconView(ItemIcons.get(item), 22f))
        row.addView(label("$have/$need", 11.5f, if (have >= need) Color.rgb(150, 225, 150) else Color.rgb(235, 140, 120), true))
        return row
    }

    private fun chipRow(items: List<Pair<com.rustbloom.game.game.Item, Int>>): View {
        val sc = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false }
        val row = hbox()
        for ((it, n) in items) row.addView(costChip(it, n), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2f), dp(5f), dp(2f)) })
        sc.addView(row)
        return sc
    }

    fun close() {
        current?.let { root.removeView(it) }
        current = null
        isOpen = false
        screen = ""
        chest = null
    }

    /** Back / B / Escape. Returns true if handled. */
    fun back(): Boolean {
        if (!isOpen) return false
        val b = backAction
        if (b != null) b() else return false
        return true
    }

    private fun overlay(dim: Int = Color.argb(150, 0, 0, 0)): FrameLayout = FrameLayout(act).apply {
        setBackgroundColor(dim)
        isClickable = true
        isFocusable = false
    }

    private fun centeredPanel(o: FrameLayout, content: View, widthFrac: Float = 0.6f, heightFrac: Float = 0f) {
        val dm = act.resources.displayMetrics
        val w = (dm.widthPixels * widthFrac).toInt()
        val h = if (heightFrac > 0f) (dm.heightPixels * heightFrac).toInt() else FrameLayout.LayoutParams.WRAP_CONTENT
        content.background = bg(PANEL, 14f, Color.argb(120, 79, 227, 210))
        o.addView(content, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
    }

    /** Runs an action on the game thread and refreshes the current screen afterwards. */
    private fun onGame(fn: () -> Unit) {
        game.post {
            fn()
            act.runOnUiThread { refresh() }
        }
    }

    fun refresh() {
        when (screen) {
            "game" -> showGameMenu(tab)
            "chest" -> chest?.let { openChest(it) }
            "bounties" -> openBounties()
        }
    }

    // ------------------------------------------------------------------ main menu

    fun showMainMenu() {
        val o = overlay(Color.argb(120, 6, 10, 12))
        val col = vbox(24f).apply { gravity = Gravity.CENTER_HORIZONTAL; background = bg(Color.argb(170, 6, 12, 14), 18f) }
        col.addView(label("RUSTBLOOM", 40f, Color.rgb(210, 240, 230), true).apply { gravity = Gravity.CENTER; letterSpacing = 0.2f })
        col.addView(label("The machines remember the garden. Do you?", 13f, DIM).apply { gravity = Gravity.CENTER })
        val has = SaveSystem.exists(act.filesDir)
        if (has) {
            val sum = SaveSystem.summary(act.filesDir) ?: ""
            col.addView(button("Continue\n$sum", size = 15f, base = Color.argb(255, 30, 54, 52)) { callbacks.onContinue() }, lp(dp(360f), margin = 8f))
        }
        col.addView(button("New Game") { showNewGame(has) }, lp(dp(360f)))
        col.addView(button("Settings") { showSettings { showMainMenu() } }, lp(dp(360f)))
        col.addView(button("Controls") { showControlsHelp { showMainMenu() } }, lp(dp(360f)))
        col.addView(button("Quit") { callbacks.onQuitApp() }, lp(dp(360f)))
        col.addView(label("Offline single-player  -  Saves automatically", 11f, DIM).apply { gravity = Gravity.CENTER; setPadding(0, dp(12f), 0, 0) })
        val sc = ScrollView(act).apply { addView(col) }
        o.addView(sc, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        show(o, "main", null)
    }

    private fun showNewGame(hasSave: Boolean) {
        val o = overlay(Color.argb(255, 6, 10, 12))
        val col = vbox(20f)
        col.addView(label("Choose your difficulty", 20f, Color.WHITE, true))
        if (hasSave) col.addView(label("Warning: starting a new game overwrites your current save.", 12f, Color.rgb(255, 150, 120)))
        for (df in Difficulty.entries) {
            val desc = when (df) {
                Difficulty.STORY -> "Gentle survival, weaker machines. Enjoy the world and the story."
                Difficulty.NORMAL -> "The intended experience. Hunger, cold and machines all matter."
                Difficulty.HARD -> "Brutal. Faster drain, deadlier machines, lose resources on death."
            }
            col.addView(button("${df.label}\n$desc", size = 13f) { callbacks.onStartNew(df) }, lp())
        }
        col.addView(button("Back") { showMainMenu() }, lp())
        val sc = ScrollView(act).apply { addView(col) }
        centeredPanel(o, sc, 0.55f)
        show(o, "newgame") { showMainMenu() }
    }

    // ------------------------------------------------------------------ pause

    fun showPause() {
        val o = overlay()
        val col = vbox(18f)
        col.addView(label("Paused", 22f, Color.WHITE, true))
        val q = game.quest
        col.addView(label(if (q != null) "Current: ${q.title}" else "Story complete", 13f, AMBER))
        col.addView(button("Resume") { callbacks.onResumeGame() }, lp())
        col.addView(button("Pack, Crafting & Map") { showGameMenu("inventory") }, lp())
        col.addView(button("Save Game") { callbacks.onSaveNow() }, lp())
        col.addView(button("Settings") { showSettings { showPause() } }, lp())
        col.addView(button("Controls") { showControlsHelp { showPause() } }, lp())
        col.addView(button("Save & Quit to Title") { callbacks.onQuitToMenu() }, lp())
        val sc = ScrollView(act).apply { addView(col) }
        centeredPanel(o, sc, 0.4f)
        show(o, "pause") { callbacks.onResumeGame() }
    }

    // ------------------------------------------------------------------ game menu (tabs)

    private val tabs = listOf("inventory" to "Pack", "craft" to "Craft", "build" to "Build", "skills" to "Skills", "journal" to "Journal", "map" to "Map")

    fun showGameMenu(t: String) {
        tab = t
        val o = overlay(Color.argb(200, 0, 0, 0))
        val col = vbox(10f)
        val bar = hbox()
        for ((id, nm) in tabs) {
            val active = id == t
            val b = button(nm, size = 14f, base = if (active) Color.argb(255, 44, 78, 74) else CARD) { selectedSlot = -1; moveFrom = -1; showGameMenu(id) }
            if (active) b.setTextColor(AMBER)
            bar.addView(b, lp(0, weight = 1f, margin = 3f))
        }
        bar.addView(button("✕", base = Color.argb(255, 70, 38, 34)) { callbacks.onResumeGame() }, lp(dp(56f), margin = 3f))
        col.addView(bar, lp(margin = 0f))
        val hint = when (game.settings.controls) {
            com.rustbloom.game.game.ControlScheme.CONTROLLER -> "LB / RB switch tabs  ·  A select  ·  B close"
            com.rustbloom.game.game.ControlScheme.KEYBOARD_MOUSE -> "Click to select  ·  Esc or Tab to close"
            else -> "Tap a tab above  ·  ✕ to go back to the game"
        }
        col.addView(label(hint, 11f, DIM).apply { setPadding(dp(6f), 0, 0, dp(4f)) })
        val body: View = when (t) {
            "inventory" -> inventoryView()
            "craft" -> craftView()
            "build" -> buildView()
            "skills" -> skillsView()
            "journal" -> journalView()
            else -> mapView()
        }
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        centeredPanel(o, col, 0.95f, 0.94f)
        show(o, "game") { callbacks.onResumeGame() }
    }

    private fun stackLabel(s: ItemStack?): String {
        if (s == null) return ""
        val n = if (s.count > 1) "x${s.count}" else ""
        val dur = if (s.item == Item.WATERSKIN) "${s.durability}/5" else if (s.item.hasDurability) "${(s.durability * 100 / s.item.durability)}%" else ""
        return listOf(n, dur).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private var invFilter = 0
    private val filters = listOf("All", "Resources", "Food & Meds", "Tools & Weapons", "Clothing")

    private fun matchesFilter(item: Item): Boolean = when (invFilter) {
        1 -> item.category == Category.RESOURCE || item.category == Category.KEY
        2 -> item.category == Category.FOOD || item.category == Category.MEDICAL
        3 -> item.category == Category.TOOL || item.category == Category.WEAPON || item.category == Category.AMMO
        4 -> item.category == Category.ARMOR
        else -> true
    }

    private fun inventoryView(): View {
        val p = game.player
        val inv = p.inv
        val row = hbox().apply { gravity = Gravity.TOP }
        val left = vbox()
        val fl = hbox()
        for ((k, f) in filters.withIndex()) {
            val b = button(f, size = 11.5f, base = if (k == invFilter) Color.argb(255, 44, 78, 74) else CARD) { invFilter = k; showGameMenu("inventory") }
            if (k == invFilter) b.setTextColor(AMBER)
            fl.addView(b, lp(ViewGroup.LayoutParams.WRAP_CONTENT, margin = 2f))
        }
        left.addView(HorizontalScrollView(act).apply { addView(fl); isHorizontalScrollBarEnabled = false })
        left.addView(label("Hotbar (slots 1-6)", 11f, DIM).apply { setPadding(dp(4f), dp(4f), 0, 0) })
        val grid = GridLayout(act).apply { columnCount = 6 }
        for (i in 0 until inv.capacity) {
            if (i == 6) {
                // spacer row between hotbar and backpack
                val sp = label("Backpack", 11f, DIM).apply { setPadding(dp(4f), dp(6f), 0, 0) }
                grid.addView(sp, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(0, 6)))
            }
            val locked = i >= inv.size
            val s = inv.slots[i]
            val dim = s != null && !matchesFilter(s.item)
            val b = iconButton(if (locked) "locked" else stackLabel(s), if (s != null) ItemIcons.get(s.item) else null, enabled = !locked, size = 10f,
                base = if (i < 6) Color.argb(255, 34, 44, 40) else CARD) {
                if (moveFrom >= 0) {
                    val from = moveFrom
                    moveFrom = -1
                    selectedSlot = i
                    onGame { inv.swap(from, i) }
                } else {
                    selectedSlot = i
                    showGameMenu("inventory")
                }
            }
            if (dim) b.alpha = 0.35f
            if (i == selectedSlot) b.background = bg(Color.argb(255, 60, 70, 50), 10f, AMBER)
            if (i == moveFrom) b.background = bg(Color.argb(255, 70, 50, 40), 10f, Color.rgb(255, 140, 90))
            val glp = GridLayout.LayoutParams().apply { width = dp(76f); height = dp(70f); setMargins(dp(2f), dp(2f), dp(2f), dp(2f)) }
            grid.addView(b, glp)
        }
        left.addView(ScrollView(act).apply { addView(grid) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f))
        row.addView(left, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val det = vbox(12f)
        det.background = bg(CARD, 12f)
        val armor = inv.armor
        val wornRow = hbox()
        if (armor != null) wornRow.addView(iconView(ItemIcons.get(armor.item), 36f))
        wornRow.addView(label("Wearing: ${armor?.item?.displayName ?: "nothing"}" + if (armor != null) "\nArmor ${(armor.item.armor * 100).toInt()}%  ·  Warmth +${armor.item.warmth.toInt()}" else "", 13f, AMBER, true))
        det.addView(wornRow)
        if (armor != null) det.addView(button("Take off") { onGame { game.unequipArmor() } }, lp())
        det.addView(label("Body ${p.feltTemp.toInt()}°C  ·  Health ${p.hp.toInt()}/${p.maxHp.toInt()}  ·  Free slots ${inv.freeSlots()}", 12f, DIM).apply { setPadding(0, dp(4f), 0, dp(8f)) })
        val s = if (selectedSlot >= 0) inv.slots[selectedSlot] else null
        if (s != null) {
            val it = s.item
            val head = hbox()
            head.addView(iconView(ItemIcons.get(it), 64f))
            val hv = vbox()
            hv.addView(label(it.displayName, 18f, Color.WHITE, true))
            hv.addView(label(it.desc, 13f, DIM))
            head.addView(hv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            det.addView(head)
            val stats = ArrayList<String>()
            if (it.hunger != 0f) stats.add("Food +${it.hunger.toInt()}")
            if (it.thirst != 0f) stats.add("Water +${it.thirst.toInt()}")
            if (it.heal != 0f) stats.add("Health ${if (it.heal > 0) "+" else ""}${it.heal.toInt()}")
            if (it.damage > 0f && it.category != Category.AMMO) stats.add("Damage ${it.damage.toInt()}")
            if (it.armor > 0f) stats.add("Armor ${(it.armor * 100).toInt()}%")
            if (it.warmth > 0f) stats.add("Warmth +${it.warmth.toInt()}")
            if (it.hasDurability && it != Item.WATERSKIN) stats.add("Durability ${s.durability}/${it.durability}")
            if (stats.isNotEmpty()) det.addView(label(stats.joinToString("   "), 12.5f, ACCENT).apply { setPadding(0, dp(6f), 0, dp(6f)) })
            val slot = selectedSlot
            val actions = hbox()
            fun action(t: String, base: Int = CARD, fn: () -> Unit) = actions.addView(button(t, base = base, size = 13f, onClick = fn), lp(0, weight = 1f, margin = 3f))
            if (it.isConsumable || it == Item.WATERSKIN) action(if (it == Item.WATERSKIN) "Drink" else if (it.category == Category.MEDICAL) "Use" else "Eat") { onGame { game.consume(slot) } }
            if (it.category == Category.ARMOR) action("Wear") { onGame { game.equipArmor(slot) } }
            if (slot >= 6) action("To hotbar ${p.selected + 1}") { onGame { inv.swap(slot, p.selected) } }
            det.addView(actions)
            val actions2 = hbox()
            actions2.addView(button("Move…", size = 13f) { moveFrom = slot; showGameMenu("inventory") }, lp(0, weight = 1f, margin = 3f))
            actions2.addView(button("Drop", size = 13f, base = Color.argb(255, 70, 38, 34)) { selectedSlot = -1; onGame { inv.slots[slot] = null } }, lp(0, weight = 1f, margin = 3f))
            det.addView(actions2)
        } else {
            det.addView(label(if (moveFrom >= 0) "Now pick the slot to swap with." else "Select an item to see what it does.\nItems in slots 1-6 are on your hotbar.", 13f, DIM))
        }
        row.addView(ScrollView(act).apply { addView(det) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(8f), 0, 0, 0) })
        return row
    }

    private fun craftView(): View {
        val col = vbox(4f)
        val inv = game.player.inv
        val stations = hbox()
        val all = listOf<Station?>(null) + Station.entries
        for (st in all) {
            val near = st == null || game.stationNear(st)
            val nm = (st?.displayName ?: "Everything") + if (st != null && st != Station.HAND) (if (near) "  ✓" else "") else ""
            val b = button(nm, size = 12f, base = if (st == craftStation) Color.argb(255, 44, 78, 74) else CARD) { craftStation = st; showGameMenu("craft") }
            if (st == craftStation) b.setTextColor(AMBER)
            stations.addView(b, lp(ViewGroup.LayoutParams.WRAP_CONTENT, margin = 3f))
        }
        col.addView(HorizontalScrollView(act).apply { addView(stations); isHorizontalScrollBarEnabled = false })
        col.addView(label("✓ = you are standing next to it. Recipes you can make right now are listed first.", 11f, DIM).apply { setPadding(dp(4f), 0, 0, dp(4f)) })
        val list = vbox(2f)
        val recipes = Recipes.all.filter { (craftStation == null || it.station == craftStation) && !(it.station == Station.TINKER && !game.tinkerUnlocked) }
            .sortedBy { r -> if (Recipes.canCraft(r, inv) && game.stationNear(r.station)) 0 else if (Recipes.canCraft(r, inv)) 1 else 2 }
        for (r in recipes) {
            val can = Recipes.canCraft(r, inv)
            val near = game.stationNear(r.station)
            val row = hbox().apply { background = bg(CARD, 10f); setPadding(dp(8f), dp(6f), dp(8f), dp(6f)) }
            row.addView(iconView(ItemIcons.get(r.output), 48f))
            val info = vbox()
            info.addView(label("${r.output.displayName}${if (r.amount > 1) "  ×${r.amount}" else ""}", 14.5f, if (can && near) Color.WHITE else DIM, true))
            info.addView(chipRow(r.inputs))
            info.addView(label(if (near) r.station.displayName else "Needs a ${r.station.displayName} nearby", 11f, if (near) ACCENT else Color.rgb(220, 150, 110)))
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Craft", enabled = can && near) { onGame { game.craft(r) } }, lp(dp(96f)))
            list.addView(row, lp(margin = 3f))
        }
        if (!game.tinkerUnlocked) list.addView(label("More recipes are locked in the memory of a distant Spire...", 12f, DIM).apply { setPadding(dp(6f), dp(6f), 0, 0) })
        col.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun buildView(): View {
        val outer = vbox(2f)
        val inv = game.player.inv
        outer.addView(label("Pick a piece, then aim where you want it. Foundations, walls and roofs snap together. Rotate before placing.", 12f, DIM).apply { setPadding(dp(4f), 0, 0, dp(4f)) })
        val grid = GridLayout(act).apply { columnCount = 2 }
        for (t in com.rustbloom.game.game.BuildType.entries) {
            if ((t == com.rustbloom.game.game.BuildType.TINKER_BENCH || t == com.rustbloom.game.game.BuildType.SHOCK_PYLON) && !game.tinkerUnlocked) continue
            val can = t.cost.all { (i, n) -> inv.has(i, n) }
            val card = hbox().apply { background = bg(CARD, 10f); setPadding(dp(8f), dp(6f), dp(8f), dp(6f)) }
            card.addView(iconView(ItemIcons.get(t), 52f))
            val info = vbox()
            info.addView(label(t.displayName, 14f, if (can) Color.WHITE else DIM, true))
            info.addView(label(t.desc, 11f, DIM))
            info.addView(chipRow(t.cost))
            card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(button("Build", enabled = can) {
                game.post { game.startBuild(t) }
                callbacks.onResumeGame()
            }, lp(dp(84f)))
            val glp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) }
            grid.addView(card, glp)
        }
        val list = vbox()
        list.addView(grid)
        list.addView(button("Demolish nearest piece (refunds half)", base = Color.argb(255, 70, 38, 34)) {
            onGame {
                val p = game.player.pos
                val near = game.placed.minByOrNull { (it.x - p.x) * (it.x - p.x) + (it.z - p.z) * (it.z - p.z) + (it.y - p.y) * (it.y - p.y) }
                if (near != null && kotlin.math.abs(near.x - p.x) < 4f && kotlin.math.abs(near.z - p.z) < 4f) {
                    game.demolish(near); game.note("Demolished ${near.type.displayName}", Color.rgb(255, 200, 150))
                } else game.note("Nothing to demolish nearby", Color.rgb(200, 200, 200))
            }
        }, lp())
        outer.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return outer
    }

    private fun skillsView(): View {
        val p = game.player
        val col = vbox(4f)
        col.addView(label("Skill points: ${p.skillPoints}     Level ${p.level}  (${p.xp}/${p.xpForNext()} XP)", 15f, AMBER, true))
        val trees = hbox().apply { gravity = Gravity.TOP }
        for (tree in listOf("Survivor", "Hunter", "Tinker")) {
            val tc = vbox(4f)
            tc.addView(label(tree, 16f, ACCENT, true))
            for (s in Skill.entries.filter { it.tree == tree }) {
                val rank = p.skills[s.ordinal]
                val card = vbox(6f).apply { background = bg(CARD, 8f) }
                card.addView(label("${s.displayName}  ${"●".repeat(rank)}${"○".repeat(s.maxRank - rank)}", 13f, Color.WHITE, true))
                card.addView(label(s.desc, 11f, DIM))
                card.addView(button(if (rank >= s.maxRank) "Mastered" else "Learn", enabled = p.skillPoints > 0 && rank < s.maxRank, size = 12f) { onGame { game.unlockSkill(s) } }, lp(margin = 2f))
                tc.addView(card, lp(margin = 3f))
            }
            trees.addView(tc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        col.addView(trees)
        return ScrollView(act).apply { addView(col) }
    }

    private fun journalView(): View {
        val col = vbox(6f)
        val qi = game.questIndex
        col.addView(label("Story", 17f, AMBER, true))
        for ((i, q) in Story.quests.withIndex()) {
            if (i > qi) break
            val done = i < qi
            col.addView(label((if (done) "✓ " else "▶ ") + q.title, 14f, if (done) Color.rgb(140, 210, 140) else Color.WHITE, true))
            col.addView(label(q.summary, 12f, DIM))
            if (!done) for (o in q.objectives) {
                val pr = game.objectiveProgress(o)
                col.addView(label("   ${if (pr >= o.count) "✓" else "•"} ${o.text}${if (o.count > 1) " ($pr/${o.count})" else ""}", 12f, if (pr >= o.count) Color.rgb(140, 210, 140) else Color.WHITE))
            }
        }
        if (qi >= Story.quests.size) col.addView(label("The main story is complete. Find every Echo Log, master every skill, and take on bounties.", 12f, ACCENT))
        col.addView(label("Echo Logs (${game.logsRead.size}/${Story.logs.size})", 17f, AMBER, true).apply { setPadding(0, dp(10f), 0, 0) })
        for (i in Story.logs.indices.sorted()) {
            if (!game.logsRead.contains(i)) continue
            val (t, body) = Story.logs[i]
            col.addView(button(t, size = 12f) { showLog(t, body) { showGameMenu("journal") } }, lp(margin = 2f))
        }
        if (game.logsRead.isEmpty()) col.addView(label("Glowing terminals in ruins hold memories of the old world.", 12f, DIM))
        col.addView(label("Spires awake: ${game.spiresActive.size}/4     Caches opened: ${game.cachesOpened.size}     Places discovered: ${game.discovered.size}/${com.rustbloom.game.world.WorldGen.pois.size}", 12f, DIM).apply { setPadding(0, dp(10f), 0, 0) })
        val mins = (game.playTime / 60).toInt()
        col.addView(label("Time survived: ${mins / 60}h ${mins % 60}m  -  Day ${game.env.day}", 12f, DIM))
        return ScrollView(act).apply { addView(col) }
    }

    private fun mapView(): View {
        val frame = FrameLayout(act)
        frame.addView(MapView(act, game), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return frame
    }

    // ------------------------------------------------------------------ dialogs

    fun showLog(title: String, body: String, onClose: (() -> Unit)? = null) {
        val o = overlay()
        val col = vbox(18f)
        col.addView(label(title, 19f, ACCENT, true))
        col.addView(label(body, 14f, Color.rgb(225, 235, 230)).apply { setLineSpacing(0f, 1.25f); setPadding(0, dp(8f), 0, dp(8f)) })
        val close = onClose ?: { callbacks.onResumeGame() }
        col.addView(button("Close") { close() }, lp())
        centeredPanel(o, ScrollView(act).apply { addView(col) }, 0.6f)
        show(o, "log") { close() }
    }

    fun openChest(pl: Placed) {
        chest = pl
        val store = pl.storage ?: return
        val inv = game.player.inv
        val o = overlay()
        val col = vbox(10f)
        val head = hbox()
        head.addView(label("Storage Chest", 18f, AMBER, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(button("Store all resources") {
            onGame {
                for (i in 6 until inv.capacity) {
                    val s = inv.slots[i] ?: continue
                    if (s.item.category != Category.RESOURCE) continue
                    val left = store.add(s.item, s.count, s.durability)
                    if (left == 0) inv.slots[i] = null else s.count = left
                }
            }
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        head.addView(button("Close") { callbacks.onResumeGame() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(head)
        val row = hbox().apply { gravity = Gravity.TOP }
        fun grid(source: com.rustbloom.game.game.Inventory, target: com.rustbloom.game.game.Inventory, title: String): View {
            val box = vbox()
            box.addView(label(title, 13f, DIM))
            val g = GridLayout(act).apply { columnCount = 4 }
            for (i in 0 until source.size) {
                val s = source.slots[i]
                val b = iconButton(if (s != null) "${s.item.displayName} ${stackLabel(s)}" else "", s?.let { ItemIcons.get(it.item) }, enabled = s != null, size = 9.5f, iconDp = 28f) {
                    onGame {
                        val st = source.slots[i] ?: return@onGame
                        val left = target.add(st.item, st.count, st.durability)
                        if (left == 0) source.slots[i] = null else st.count = left
                    }
                }
                g.addView(b, GridLayout.LayoutParams().apply { width = dp(96f); height = dp(66f); setMargins(dp(2f), dp(2f), dp(2f), dp(2f)) })
            }
            box.addView(ScrollView(act).apply { addView(g) })
            return box
        }
        row.addView(grid(inv, store, "Your pack (tap to store)"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(grid(store, inv, "Chest (tap to take)"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        centeredPanel(o, col, 0.94f, 0.9f)
        show(o, "chest") { callbacks.onResumeGame() }
    }

    fun openBounties() {
        val o = overlay()
        val col = vbox(14f)
        col.addView(label("Bounty Board  -  Day ${game.env.day}", 19f, AMBER, true))
        col.addView(label("New contracts are posted every morning.", 12f, DIM))
        for (b in game.bounties) {
            val card = vbox(8f).apply { background = bg(CARD, 8f) }
            card.addView(label(b.title, 14f, Color.WHITE, true))
            val prog = if (b.kill != null) "Progress ${minOf(b.progress, b.count)}/${b.count}" else "Have ${game.player.inv.count(b.deliver!!)}/${b.count}"
            card.addView(label("$prog   -   Reward ${b.xp} XP, " + b.reward.joinToString(", ") { (i, n) -> "${i.displayName} x$n" }, 12f, DIM))
            val can = !b.claimed && (if (b.kill != null) b.complete else game.player.inv.has(b.deliver!!, b.count))
            card.addView(button(if (b.claimed) "Claimed" else if (b.deliver != null) "Deliver" else "Claim reward", enabled = can) { onGame { game.claimBounty(b) } }, lp())
            col.addView(card, lp())
        }
        col.addView(button("Close") { callbacks.onResumeGame() }, lp())
        centeredPanel(o, ScrollView(act).apply { addView(col) }, 0.6f, 0.9f)
        show(o, "bounties") { callbacks.onResumeGame() }
    }

    fun openCrafting(st: Station) {
        craftStation = st
        showGameMenu("craft")
    }

    fun showDeath() {
        val o = overlay(Color.argb(170, 40, 0, 0))
        val col = vbox(20f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(label("You have fallen", 26f, Color.rgb(255, 180, 170), true).apply { gravity = Gravity.CENTER })
        val where = if (game.player.bedId >= 0) "You will wake at your bed." else "You will wake near Cradle-7."
        col.addView(label(where, 13f, Color.rgb(230, 210, 200)).apply { gravity = Gravity.CENTER })
        col.addView(button("Wake up") { callbacks.onRespawn() }, lp(dp(260f)))
        o.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        show(o, "death", null)
    }

    fun showVictory() {
        val o = overlay(Color.argb(180, 0, 20, 16))
        val col = vbox(22f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(label("HALCYON is quiet", 26f, Color.rgb(150, 255, 210), true).apply { gravity = Gravity.CENTER })
        col.addView(label("The Colossus has fallen and the Bloom Signal reaches the Crown.\nThe machines of the wilds grow calmer. Return to Cradle-7.\n\nThe world remains yours: echo logs, bounties, skills and every corner of the map.", 13f, Color.rgb(210, 235, 225)).apply { gravity = Gravity.CENTER })
        col.addView(button("Continue") { callbacks.onResumeGame() }, lp(dp(260f)))
        o.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        show(o, "victory") { callbacks.onResumeGame() }
    }

    fun showControlsHelp(back: () -> Unit) {
        val o = overlay()
        val col = vbox(16f)
        col.addView(label("Controls", 20f, Color.WHITE, true))
        col.addView(label("Touch", 15f, AMBER, true))
        col.addView(label("Bottom-left stick: move (push to the edge to sprint)  -  Drag anywhere else: look\nATTACK: hit / harvest (hold with a bow to draw, drag to aim)\nUSE appears when you can interact (pick up, drink, open, talk)  -  JUMP  -  SNEAK\nTap hotbar slots to equip  -  PACK opens inventory, crafting and building", 12.5f, DIM))
        col.addView(label("Fishing: craft a Fishing Rod, hold it, aim at water and attack to cast. When it says BITE, attack again quickly to reel in.", 12.5f, DIM))
        col.addView(label("Controller (Xbox / PlayStation)", 15f, AMBER, true))
        col.addView(label("Left stick: move  -  Right stick: look  -  L3: sprint\nRT / R2: attack, draw bow  -  LT / L2: aim\nA / Cross: jump  -  X / Square: interact  -  B / Circle: crouch, back\nY / Triangle: use or eat held item  -  LB / RB: switch hotbar\nD-pad up: cycle arrows  -  D-pad down: map  -  D-pad left/right: rotate build\nStart: pause  -  Select / Share: pack", 12.5f, DIM))
        col.addView(label("Keyboard & Mouse", 15f, AMBER, true))
        col.addView(label("WASD: move  -  Mouse: look (click the game to capture the mouse)\nLeft click: attack  -  Right click: aim  -  Space: jump  -  Shift: sprint  -  C / Ctrl: crouch\nE: interact  -  F: use held item  -  Q: cycle arrows  -  R: rotate build  -  1-6 / wheel: hotbar\nTab / I: pack  -  M: map  -  B: build  -  J: journal  -  Esc: pause", 12.5f, DIM))
        col.addView(button("Back") { back() }, lp())
        centeredPanel(o, ScrollView(act).apply { addView(col) }, 0.75f, 0.9f)
        show(o, "controls") { back() }
    }

    // ------------------------------------------------------------------ settings

    private var settingsTab = 0
    private var settingsBack: (() -> Unit)? = null

    fun showSettings(back: () -> Unit) {
        settingsBack = back
        val s = game.settings
        val o = overlay()
        val col = vbox(14f)
        val head = hbox()
        head.addView(label("Settings", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(button("Done") { s.save(act); callbacks.onSettingsChanged(); back() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(head)
        val tabBar = hbox()
        for ((k, nm) in listOf("Graphics", "Controls", "Sound & Game").withIndex()) {
            val b = button(nm, base = if (k == settingsTab) Color.argb(255, 44, 78, 74) else CARD) { settingsTab = k; showSettings(back) }
            if (k == settingsTab) b.setTextColor(AMBER)
            tabBar.addView(b, lp(0, weight = 1f, margin = 3f))
        }
        col.addView(tabBar)
        val body = vbox(2f)

        fun desc(t: String) { body.addView(label(t, 11f, DIM).apply { setPadding(dp(10f), 0, dp(8f), dp(6f)) }) }
        fun section(t: String) { body.addView(label(t, 14f, AMBER, true).apply { setPadding(dp(4f), dp(10f), 0, dp(2f)) }) }
        fun custom() { s.preset = GraphicsPreset.CUSTOM; presetLabel?.text = "Preset: Custom" }
        // value rows update in place: no rebuild, so the list never jumps
        fun stepper(name: String, value: () -> String, dec: () -> Unit, inc: () -> Unit, info: String? = null) {
            val r = hbox().apply { background = bg(CARD, 8f); setPadding(dp(12f), 0, dp(4f), 0) }
            r.addView(label(name, 13.5f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val v = label(value(), 13.5f, ACCENT, true).apply { gravity = Gravity.CENTER; minWidth = dp(96f) }
            r.addView(button("−") { dec(); v.text = value() }, lp(dp(54f), margin = 2f))
            r.addView(v)
            r.addView(button("+") { inc(); v.text = value() }, lp(dp(54f), margin = 2f))
            body.addView(r, lp(margin = 2f))
            if (info != null) desc(info)
        }
        fun cycle(name: String, value: () -> String, next: () -> Unit, info: String? = null) {
            val r = hbox().apply { background = bg(CARD, 8f); setPadding(dp(12f), 0, dp(4f), 0) }
            r.addView(label(name, 13.5f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            lateinit var b: Button
            b = button(value()) { next(); b.text = value() }
            r.addView(b, lp(dp(210f), margin = 2f))
            body.addView(r, lp(margin = 2f))
            if (info != null) desc(info)
        }

        when (settingsTab) {
            0 -> {
                section("Quality preset")
                val presets = hbox()
                for (p in GraphicsPreset.entries) {
                    if (p == GraphicsPreset.CUSTOM) continue
                    val b = button(p.label, base = if (s.preset == p) Color.argb(255, 44, 78, 74) else CARD) { s.applyPreset(p); showSettings(back) }
                    if (s.preset == p) b.setTextColor(AMBER)
                    presets.addView(b, lp(0, weight = 1f, margin = 3f))
                }
                body.addView(presets)
                presetLabel = label("Preset: ${s.preset.label}", 12f, ACCENT, true).apply { setPadding(dp(6f), dp(2f), 0, 0) }
                body.addView(presetLabel)
                desc("Low/Medium: smooth on any phone.  High: realistic look, holds 60 FPS on a Galaxy A54 by adjusting resolution.  Ultra: maximum realism (cascaded shadows, leaf shadows, anti-aliasing, far view), may run around 30-45 FPS.")
                section("Image")
                stepper("Render resolution", { "${(s.renderScale * 100).toInt()}%" }, { custom(); s.renderScale = (s.renderScale - 0.05f).coerceAtLeast(0.5f) }, { custom(); s.renderScale = (s.renderScale + 0.05f).coerceAtMost(1f) }, "Lower = faster. With dynamic resolution this is the maximum.")
                cycle("Dynamic resolution", { if (s.dynamicRes) "On" else "Off" }, { custom(); s.dynamicRes = !s.dynamicRes }, "Drops resolution a little in heavy scenes to keep the frame rate steady.")
                cycle("HDR, bloom & color grading", { if (s.postFx) "On" else "Off" }, { custom(); s.postFx = !s.postFx }, "Filmic lighting, glowing lights and the sun, water refraction.")
                stepper("Bloom strength", { "${(s.bloom * 100).toInt()}%" }, { s.bloom = (s.bloom - 0.1f).coerceAtLeast(0f) }, { s.bloom = (s.bloom + 0.1f).coerceAtMost(1.5f) })
                cycle("Anti-aliasing (FXAA)", { if (s.fxaa) "On" else "Off" }, { custom(); s.fxaa = !s.fxaa }, "Smooths jagged edges. Needs HDR on.")
                cycle("Frame rate cap", { "${s.fpsCap} FPS" }, { s.fpsCap = when (s.fpsCap) { 30 -> 60; 60 -> 90; 90 -> 120; else -> 30 } }, "30 saves battery. Your A54 screen supports up to 120.")
                stepper("Field of view", { "${s.fov.toInt()}°" }, { s.fov = (s.fov - 5f).coerceAtLeast(55f) }, { s.fov = (s.fov + 5f).coerceAtMost(95f) })
                section("World")
                stepper("View distance", { "${s.viewDistance * 64} m" }, { custom(); s.viewDistance = (s.viewDistance - 1).coerceAtLeast(2) }, { custom(); s.viewDistance = (s.viewDistance + 1).coerceAtMost(7) })
                cycle("Textures", { when (s.texQuality) { 0 -> "Off (flat colors)"; 1 -> "On"; else -> "High (normal maps)" } }, { custom(); s.texQuality = (s.texQuality + 1) % 3 }, "Detailed ground, rock, bark and metal surfaces.")
                cycle("Foliage", { when (s.foliage) { 0 -> "Simple"; 1 -> "Leafy"; else -> "Leafy + leaf shadows" } }, { custom(); s.foliage = (s.foliage + 1) % 3 }, "Leafy trees use cut-out leaves. Leaf shadows are the most expensive option.")
                stepper("Grass density", { "${(s.decorDensity * 100).toInt()}%" }, { custom(); s.decorDensity = (s.decorDensity - 0.1f).coerceAtLeast(0f) }, { custom(); s.decorDensity = (s.decorDensity + 0.1f).coerceAtMost(1f) }, "Applies to newly loaded areas.")
                stepper("Grass distance", { "${s.grassDistance.toInt()} m" }, { custom(); s.grassDistance = (s.grassDistance - 6f).coerceAtLeast(20f) }, { custom(); s.grassDistance = (s.grassDistance + 6f).coerceAtMost(90f) })
                cycle("Terrain detail", { if (s.terrainDetail == 0) "Normal" else "High" }, { custom(); s.terrainDetail = 1 - s.terrainDetail })
                section("Lighting & sky")
                cycle("Shadows", { when (s.shadowQuality) { 0 -> "Off"; 1 -> "Hard"; 2 -> "Soft"; else -> "Soft + far cascade" } }, { custom(); s.shadowQuality = (s.shadowQuality + 1) % 4 })
                cycle("Shadow resolution", { "${s.shadowRes}" }, { custom(); s.shadowRes = if (s.shadowRes == 1024) 2048 else 1024 })
                cycle("Water", { if (s.waterQuality == 0) "Simple" else "Waves & refraction" }, { custom(); s.waterQuality = 1 - s.waterQuality })
                cycle("Clouds", { if (s.clouds) "On" else "Off" }, { custom(); s.clouds = !s.clouds })
                cycle("Rain & snow particles", { if (s.weatherParticles) "On" else "Off" }, { custom(); s.weatherParticles = !s.weatherParticles })
                cycle("Show FPS counter", { if (s.showFps) "On" else "Off" }, { s.showFps = !s.showFps })
            }
            1 -> {
                section("Input")
                cycle("Control scheme", { s.controls.label }, { s.controls = ControlScheme.entries[(s.controls.ordinal + 1) % ControlScheme.entries.size] }, "Controllers and keyboards always work. This picks which on-screen controls and button hints you see.")
                stepper("Look sensitivity", { String.format("%.1f", s.lookSensitivity) }, { s.lookSensitivity = (s.lookSensitivity - 0.1f).coerceAtLeast(0.2f) }, { s.lookSensitivity = (s.lookSensitivity + 0.1f).coerceAtMost(4f) })
                cycle("Invert look up/down", { if (s.invertY) "On" else "Off" }, { s.invertY = !s.invertY })
                cycle("Auto sprint (touch)", { if (s.autoSprint) "On" else "Off" }, { s.autoSprint = !s.autoSprint }, "Push the stick all the way forward to run.")
                stepper("Touch button opacity", { "${(s.touchOpacity * 100).toInt()}%" }, { s.touchOpacity = (s.touchOpacity - 0.1f).coerceAtLeast(0.2f) }, { s.touchOpacity = (s.touchOpacity + 0.1f).coerceAtMost(1f) })
                cycle("Vibration", { if (s.vibration) "On" else "Off" }, { s.vibration = !s.vibration })
                body.addView(button("Show all controls") { showControlsHelp { showSettings(back) } }, lp())
            }
            else -> {
                section("Sound")
                stepper("Volume", { "${(s.masterVolume * 100).toInt()}%" }, { s.masterVolume = (s.masterVolume - 0.1f).coerceAtLeast(0f); callbacks.onSettingsChanged() }, { s.masterVolume = (s.masterVolume + 0.1f).coerceAtMost(1f); callbacks.onSettingsChanged() })
                section("Game")
                cycle("Day length", { "${s.dayLengthMinutes} min" }, { s.dayLengthMinutes = when (s.dayLengthMinutes) { 20 -> 30; 30 -> 45; 45 -> 60; else -> 20 } }, "Real minutes for one full day and night.")
            }
        }
        col.addView(ScrollView(act).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        centeredPanel(o, col, 0.74f, 0.95f)
        show(o, "settings") { s.save(act); callbacks.onSettingsChanged(); back() }
    }

    private var presetLabel: TextView? = null
}
