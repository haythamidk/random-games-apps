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

    private fun show(v: View, name: String, back: (() -> Unit)?) {
        current?.let { root.removeView(it) }
        current = v
        screen = name
        isOpen = true
        backAction = back
        root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        v.post { focusFirst(v) }
    }

    private fun focusFirst(v: View) {
        if (v is Button && v.isEnabled) { v.requestFocus(); return }
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c is Button && c.isEnabled && c.visibility == View.VISIBLE) { c.requestFocus(); return }
            if (c is ViewGroup) { focusFirst(c); if (c.findFocus() != null) return }
        }
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

    fun showGameMenu(t: String) {
        tab = t
        val o = overlay(Color.argb(200, 0, 0, 0))
        val col = vbox(10f)
        // tab bar
        val tabs = hbox()
        val names = listOf("inventory" to "Pack", "craft" to "Craft", "build" to "Build", "skills" to "Skills", "journal" to "Journal", "map" to "Map")
        for ((id, nm) in names) {
            val b = button(if (id == t) "[ $nm ]" else nm, base = if (id == t) Color.argb(255, 40, 66, 64) else CARD) { selectedSlot = -1; moveFrom = -1; showGameMenu(id) }
            tabs.addView(b, lp(0, weight = 1f, margin = 3f))
        }
        tabs.addView(button("Close", base = Color.argb(255, 60, 36, 34)) { callbacks.onResumeGame() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, margin = 3f))
        col.addView(tabs, lp(margin = 0f))
        val body: View = when (t) {
            "inventory" -> inventoryView()
            "craft" -> craftView()
            "build" -> buildView()
            "skills" -> skillsView()
            "journal" -> journalView()
            else -> mapView()
        }
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        centeredPanel(o, col, 0.94f, 0.92f)
        show(o, "game") { callbacks.onResumeGame() }
    }

    private fun stackLabel(s: ItemStack?): String {
        if (s == null) return ""
        val n = if (s.count > 1) " x${s.count}" else ""
        val dur = if (s.item.hasDurability && s.item != Item.WATERSKIN) " ${(s.durability * 100 / s.item.durability)}%" else if (s.item == Item.WATERSKIN) " ${s.durability}/5" else ""
        return "${s.item.displayName}$n$dur"
    }

    private fun inventoryView(): View {
        val p = game.player
        val inv = p.inv
        val row = hbox().apply { gravity = Gravity.TOP }
        // slots grid
        val grid = GridLayout(act).apply { columnCount = 6 }
        for (i in 0 until inv.capacity) {
            val locked = i >= inv.size
            val s = inv.slots[i]
            val txt = when {
                locked -> "locked"
                s == null -> if (i < 6) "${i + 1}" else ""
                else -> stackLabel(s)
            }
            val b = button(txt, enabled = !locked, size = 10.5f, base = if (i < 6) Color.argb(255, 36, 44, 40) else CARD) {
                if (moveFrom >= 0) {
                    val from = moveFrom
                    moveFrom = -1
                    onGame { inv.swap(from, i) }
                    selectedSlot = i
                } else {
                    selectedSlot = i
                    showGameMenu("inventory")
                }
            }
            if (s != null) b.setTextColor(Color.WHITE)
            if (i == selectedSlot) b.background = bg(Color.argb(255, 60, 70, 50), 10f, AMBER)
            if (i == moveFrom) b.background = bg(Color.argb(255, 70, 50, 40), 10f, Color.rgb(255, 140, 90))
            val glp = GridLayout.LayoutParams().apply { width = dp(92f); height = dp(52f); setMargins(dp(2f), dp(2f), dp(2f), dp(2f)) }
            grid.addView(b, glp)
        }
        val gridScroll = ScrollView(act).apply { addView(grid) }
        row.addView(gridScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // details
        val det = vbox(10f)
        det.background = bg(CARD, 10f)
        val armor = inv.armor
        det.addView(label("Worn: ${armor?.item?.displayName ?: "nothing"}", 13f, AMBER, true))
        if (armor != null) {
            det.addView(label("Armor ${(armor.item.armor * 100).toInt()}%   Warmth +${armor.item.warmth.toInt()}", 12f, DIM))
            det.addView(button("Take off") { onGame { game.unequipArmor() } }, lp())
        }
        det.addView(label("Temperature ${p.feltTemp.toInt()}°C   HP ${p.hp.toInt()}/${p.maxHp.toInt()}", 12f, DIM))
        val s = if (selectedSlot >= 0) inv.slots[selectedSlot] else null
        if (s != null) {
            val it = s.item
            det.addView(label(it.displayName, 17f, Color.WHITE, true))
            det.addView(label(it.desc, 13f, DIM))
            val stats = ArrayList<String>()
            if (it.hunger != 0f) stats.add("Food +${it.hunger.toInt()}")
            if (it.thirst != 0f) stats.add("Water +${it.thirst.toInt()}")
            if (it.heal != 0f) stats.add("Health ${if (it.heal > 0) "+" else ""}${it.heal.toInt()}")
            if (it.damage > 0f && it.category != Category.AMMO) stats.add("Damage ${it.damage.toInt()}")
            if (it.armor > 0f) stats.add("Armor ${(it.armor * 100).toInt()}%")
            if (it.warmth > 0f) stats.add("Warmth +${it.warmth.toInt()}")
            if (it.hasDurability && it != Item.WATERSKIN) stats.add("Durability ${s.durability}/${it.durability}")
            if (stats.isNotEmpty()) det.addView(label(stats.joinToString("   "), 12f, ACCENT))
            val slot = selectedSlot
            if (it.isConsumable || it == Item.WATERSKIN) det.addView(button(if (it == Item.WATERSKIN) "Drink" else if (it.category == Category.MEDICAL) "Use" else "Eat") { onGame { game.consume(slot) } }, lp())
            if (it.category == Category.ARMOR) det.addView(button("Wear") { onGame { game.equipArmor(slot) } }, lp())
            if (slot >= 6) det.addView(button("Move to hotbar slot ${p.selected + 1}") { onGame { inv.swap(slot, p.selected) } }, lp())
            det.addView(button("Move / swap...") { moveFrom = slot; showGameMenu("inventory") }, lp())
            det.addView(button("Drop", base = Color.argb(255, 60, 36, 34)) { onGame { inv.slots[slot] = null }; selectedSlot = -1 }, lp())
        } else {
            det.addView(label(if (moveFrom >= 0) "Pick a slot to swap with." else "Tap an item to see details.\nSlots 1-6 are your hotbar.", 13f, DIM))
        }
        val detScroll = ScrollView(act).apply { addView(det) }
        row.addView(detScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(8f), 0, 0, 0) })
        return row
    }

    private fun craftView(): View {
        val col = vbox(4f)
        val inv = game.player.inv
        val stations = hbox()
        val all = listOf<Station?>(null) + Station.entries
        for (st in all) {
            val nm = st?.displayName ?: "All"
            val near = st == null || game.stationNear(st)
            val b = button(if (st == craftStation) "[ $nm ]" else nm + if (!near) " (far)" else "", size = 12f) { craftStation = st; showGameMenu("craft") }
            stations.addView(b, lp(ViewGroup.LayoutParams.WRAP_CONTENT, margin = 3f))
        }
        col.addView(HorizontalScrollView(act).apply { addView(stations) })
        val list = vbox(2f)
        for (r in Recipes.all) {
            if (craftStation != null && r.station != craftStation) continue
            if (r.station == Station.TINKER && !game.tinkerUnlocked) continue
            val can = Recipes.canCraft(r, inv)
            val near = game.stationNear(r.station)
            val row = hbox().apply { background = bg(CARD, 8f); setPadding(dp(8f), dp(4f), dp(8f), dp(4f)) }
            val info = vbox()
            info.addView(label("${r.output.displayName}${if (r.amount > 1) " x${r.amount}" else ""}", 14f, if (can && near) Color.WHITE else DIM, true))
            val ins = r.inputs.joinToString("   ") { (i, n) -> "${i.displayName} ${inv.count(i)}/$n" }
            info.addView(label(ins, 11.5f, if (can) Color.rgb(150, 220, 150) else Color.rgb(220, 150, 130)))
            info.addView(label(r.station.displayName + if (!near) "  -  go to one to craft" else "", 11f, if (near) ACCENT else Color.rgb(200, 140, 110)))
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Craft", enabled = can && near) { onGame { game.craft(r) } }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            list.addView(row, lp(margin = 2f))
        }
        if (!game.tinkerUnlocked) list.addView(label("More recipes are locked in the memory of a distant Spire...", 12f, DIM))
        col.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun buildView(): View {
        val list = vbox(4f)
        val inv = game.player.inv
        list.addView(label("Choose a piece, then aim where you want it. Foundations, walls and roofs snap together.", 12f, DIM))
        for (t in BuildType.entries) {
            if ((t == BuildType.TINKER_BENCH || t == BuildType.SHOCK_PYLON) && !game.tinkerUnlocked) continue
            val can = t.cost.all { (i, n) -> inv.has(i, n) }
            val row = hbox().apply { background = bg(CARD, 8f); setPadding(dp(8f), dp(4f), dp(8f), dp(4f)) }
            val info = vbox()
            info.addView(label(t.displayName, 14f, if (can) Color.WHITE else DIM, true))
            info.addView(label(t.desc, 11.5f, DIM))
            info.addView(label(t.cost.joinToString("   ") { (i, n) -> "${i.displayName} ${inv.count(i)}/$n" }, 11.5f, if (can) Color.rgb(150, 220, 150) else Color.rgb(220, 150, 130)))
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Build", enabled = can) {
                game.post { game.startBuild(t) }
                callbacks.onResumeGame()
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            list.addView(row, lp(margin = 2f))
        }
        list.addView(button("Demolish nearest piece (refunds half)", base = Color.argb(255, 60, 36, 34)) {
            onGame {
                val p = game.player.pos
                val near = game.placed.minByOrNull { (it.x - p.x) * (it.x - p.x) + (it.z - p.z) * (it.z - p.z) + (it.y - p.y) * (it.y - p.y) }
                if (near != null && near.x.let { kotlin.math.abs(it - p.x) } < 4f && kotlin.math.abs(near.z - p.z) < 4f) {
                    game.demolish(near); game.note("Demolished ${near.type.displayName}", Color.rgb(255, 200, 150))
                } else game.note("Nothing to demolish nearby", Color.rgb(200, 200, 200))
            }
        }, lp())
        return ScrollView(act).apply { addView(list) }
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
                val b = button(stackLabel(s), enabled = s != null, size = 10.5f) {
                    onGame {
                        val st = source.slots[i] ?: return@onGame
                        val left = target.add(st.item, st.count, st.durability)
                        if (left == 0) source.slots[i] = null else st.count = left
                    }
                }
                g.addView(b, GridLayout.LayoutParams().apply { width = dp(96f); height = dp(48f); setMargins(dp(2f), dp(2f), dp(2f), dp(2f)) })
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
        col.addView(label("Left side: move (push to the edge to sprint)  -  Right side: look\nATK: attack / harvest (hold with a bow to draw, drag to aim)\nUSE: interact, pick up, drink  -  JUMP  -  CRCH: sneak\nTap hotbar slots to equip  -  PACK opens inventory, crafting and building", 12.5f, DIM))
        col.addView(label("Controller (Xbox / PlayStation)", 15f, AMBER, true))
        col.addView(label("Left stick: move  -  Right stick: look  -  L3: sprint\nRT / R2: attack, draw bow  -  LT / L2: aim\nA / Cross: jump  -  X / Square: interact  -  B / Circle: crouch, back\nY / Triangle: use or eat held item  -  LB / RB: switch hotbar\nD-pad up: cycle arrows  -  D-pad down: map  -  D-pad left/right: rotate build\nStart: pause  -  Select / Share: pack", 12.5f, DIM))
        col.addView(label("Keyboard & Mouse", 15f, AMBER, true))
        col.addView(label("WASD: move  -  Mouse: look (click the game to capture the mouse)\nLeft click: attack  -  Right click: aim  -  Space: jump  -  Shift: sprint  -  C / Ctrl: crouch\nE: interact  -  F: use held item  -  Q: cycle arrows  -  R: rotate build  -  1-6 / wheel: hotbar\nTab / I: pack  -  M: map  -  B: build  -  J: journal  -  Esc: pause", 12.5f, DIM))
        col.addView(button("Back") { back() }, lp())
        centeredPanel(o, ScrollView(act).apply { addView(col) }, 0.75f, 0.9f)
        show(o, "controls") { back() }
    }

    // ------------------------------------------------------------------ settings

    fun showSettings(back: () -> Unit) {
        val s = game.settings
        val o = overlay()
        val col = vbox(14f)
        val head = hbox()
        head.addView(label("Settings", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(button("Done") { s.save(act); callbacks.onSettingsChanged(); back() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(head)

        fun section(t: String) { col.addView(label(t, 15f, AMBER, true).apply { setPadding(0, dp(10f), 0, dp(2f)) }) }
        fun refreshSettings() { showSettings(back) }
        fun stepper(name: String, value: String, dec: () -> Unit, inc: () -> Unit) {
            val r = hbox().apply { background = bg(CARD, 8f); setPadding(dp(10f), 0, dp(4f), 0) }
            r.addView(label(name, 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(button("-") { dec(); refreshSettings() }, lp(dp(52f), margin = 2f))
            r.addView(label(value, 13f, ACCENT, true).apply { gravity = Gravity.CENTER; minWidth = dp(90f) })
            r.addView(button("+") { inc(); refreshSettings() }, lp(dp(52f), margin = 2f))
            col.addView(r, lp(margin = 2f))
        }
        fun cycle(name: String, value: String, next: () -> Unit) {
            val r = hbox().apply { background = bg(CARD, 8f); setPadding(dp(10f), 0, dp(4f), 0) }
            r.addView(label(name, 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(button(value) { next(); refreshSettings() }, lp(dp(200f), margin = 2f))
            col.addView(r, lp(margin = 2f))
        }
        fun custom() { s.preset = GraphicsPreset.CUSTOM }

        section("Graphics")
        val presets = hbox()
        for (p in GraphicsPreset.entries) {
            if (p == GraphicsPreset.CUSTOM) continue
            presets.addView(button(if (s.preset == p) "[ ${p.label} ]" else p.label, base = if (s.preset == p) Color.argb(255, 40, 66, 64) else CARD) { s.applyPreset(p); refreshSettings() }, lp(0, weight = 1f, margin = 3f))
        }
        col.addView(presets)
        col.addView(label("Preset: ${s.preset.label}. High is tuned for 60 FPS on phones like the Galaxy A54. Use Medium or Low if it gets hot.", 11.5f, DIM))
        stepper("Render resolution", "${(s.renderScale * 100).toInt()}%", { custom(); s.renderScale = (s.renderScale - 0.05f).coerceAtLeast(0.5f) }, { custom(); s.renderScale = (s.renderScale + 0.05f).coerceAtMost(1f) })
        stepper("View distance", "${s.viewDistance * 64}m", { custom(); s.viewDistance = (s.viewDistance - 1).coerceAtLeast(2) }, { custom(); s.viewDistance = (s.viewDistance + 1).coerceAtMost(6) })
        cycle("Shadows", when (s.shadowQuality) { 0 -> "Off"; 1 -> "Hard"; else -> "Soft" }) { custom(); s.shadowQuality = (s.shadowQuality + 1) % 3 }
        cycle("Shadow resolution", "${s.shadowRes}") { custom(); s.shadowRes = if (s.shadowRes == 1024) 2048 else 1024 }
        stepper("Grass & flowers", "${(s.decorDensity * 100).toInt()}%", { custom(); s.decorDensity = (s.decorDensity - 0.1f).coerceAtLeast(0f) }, { custom(); s.decorDensity = (s.decorDensity + 0.1f).coerceAtMost(1f) })
        col.addView(label("Grass density applies to newly loaded areas.", 11f, DIM))
        cycle("Water", if (s.waterQuality == 0) "Simple" else "Reflective waves") { custom(); s.waterQuality = 1 - s.waterQuality }
        cycle("Terrain detail", if (s.terrainDetail == 0) "Normal" else "High") { custom(); s.terrainDetail = 1 - s.terrainDetail }
        cycle("Clouds", if (s.clouds) "On" else "Off") { custom(); s.clouds = !s.clouds }
        cycle("Rain & snow particles", if (s.weatherParticles) "On" else "Off") { custom(); s.weatherParticles = !s.weatherParticles }
        cycle("Frame rate cap", "${s.fpsCap} FPS") { s.fpsCap = when (s.fpsCap) { 30 -> 60; 60 -> 90; 90 -> 120; else -> 30 } }
        stepper("Field of view", "${s.fov.toInt()}°", { s.fov = (s.fov - 5f).coerceAtLeast(55f) }, { s.fov = (s.fov + 5f).coerceAtMost(95f) })
        cycle("Show FPS counter", if (s.showFps) "On" else "Off") { s.showFps = !s.showFps }

        section("Controls")
        cycle("Control scheme", s.controls.label) { s.controls = ControlScheme.entries[(s.controls.ordinal + 1) % ControlScheme.entries.size] }
        col.addView(label("Controllers and keyboards work in every scheme. The scheme decides which on-screen controls and button prompts you see.", 11f, DIM))
        stepper("Look sensitivity", String.format("%.1f", s.lookSensitivity), { s.lookSensitivity = (s.lookSensitivity - 0.1f).coerceAtLeast(0.2f) }, { s.lookSensitivity = (s.lookSensitivity + 0.1f).coerceAtMost(4f) })
        cycle("Invert look Y", if (s.invertY) "On" else "Off") { s.invertY = !s.invertY }
        cycle("Auto sprint (touch)", if (s.autoSprint) "On" else "Off") { s.autoSprint = !s.autoSprint }
        stepper("Touch button opacity", "${(s.touchOpacity * 100).toInt()}%", { s.touchOpacity = (s.touchOpacity - 0.1f).coerceAtLeast(0.2f) }, { s.touchOpacity = (s.touchOpacity + 0.1f).coerceAtMost(1f) })
        cycle("Vibration", if (s.vibration) "On" else "Off") { s.vibration = !s.vibration }

        section("Audio & Gameplay")
        stepper("Volume", "${(s.masterVolume * 100).toInt()}%", { s.masterVolume = (s.masterVolume - 0.1f).coerceAtLeast(0f) }, { s.masterVolume = (s.masterVolume + 0.1f).coerceAtMost(1f) })
        cycle("Day length", "${s.dayLengthMinutes} min") { s.dayLengthMinutes = when (s.dayLengthMinutes) { 20 -> 30; 30 -> 45; 45 -> 60; else -> 20 } }

        centeredPanel(o, ScrollView(act).apply { addView(col) }, 0.7f, 0.94f)
        show(o, "settings") { s.save(act); callbacks.onSettingsChanged(); back() }
    }
}
