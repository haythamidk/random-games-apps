package scrapline.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Align
import scrapline.sim.*
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val STICK_RADIUS = 56f
private const val SPRINT_THRESHOLD = 0.85f
/** Radians of camera turn per logical unit of drag at sensitivity 1. */
private const val LOOK_RATE = 0.0052f
/** Fraction of the screen width, from the left, that spawns the move stick. */
private const val STICK_ZONE = 0.45f
private const val LONG_PRESS = 0.38f
private const val TAP_SLOP = 10f
private const val SLOT = 54f
private const val GAP = 6f
private const val MOVE_HINT = "Tap a stack, then tap where it goes. Hold for half."

private const val H_HOTBAR = 1
private const val H_BAG = 2
private const val H_MENU = 3
private const val H_ACT = 4
private const val H_JUMP = 5
private const val H_CTX = 6
private const val H_CLOSE = 7
private const val H_SLOT = 8
private const val H_RECIPE = 9
private const val H_SENS_LESS = 10
private const val H_SENS_MORE = 11
private const val H_RESET = 12
private const val H_DAWN_OK = 13
private const val H_BACKDROP = 14
private const val H_RESPAWN = 15
private const val H_SHEET = 16

enum class Context(val label: String) { NONE(""), ROTATE("Rotate"), CRAFT("Use"), OPEN("Open"), TOGGLE("Flip"), CIRCUIT("Circuit") }

private enum class Mode { PLAY, CRAFT, STORAGE, MENU }

private val LINK_BAD = arrayOf(
    "That cannot be wired",
    "That only receives signals. Start from a sensor or switch",
    "That only sends signals. Pick something to drive",
    "The circuit is full",
)

interface UiHost {
    val sim: Sim
    var sensitivity: Float
    fun newWorld()
    /** Frame statistics for the debug overlay. */
    fun stats(): String
}

/**
 * Everything on screen that is not the 3D world, and all touch handling:
 * the move stick and look drag, the thumb buttons, HUD, bag and crafting,
 * machine panels, the menu, the circuit board and the cards.
 */
class GameUi(private val host: UiHost) {
    val canvas = Canvas()
    val board = Board { host.sim }
    private val guide = Guide()
    private val sim get() = host.sim

    // --- touch state
    private var stickPointer = -1
    private var stickX = 0f
    private var stickY = 0f
    private var moveX = 0f
    private var moveY = 0f
    private var lookPointer = -1
    private var actPointer = -1
    private var jumpPointer = -1
    private val lastX = FloatArray(20)
    private val lastY = FloatArray(20)
    private var lookDX = 0f
    private var lookDY = 0f
    private var jumpQueued = false
    private var pointersDown = 0
    /** True while the action button is held. */
    var act = false
        private set
    /** Latched on press so a quick tap between two frames is never lost. */
    var actTapped = false

    // --- tap targets registered while drawing the last frame
    private val hx = FloatArray(160)
    private val hy = FloatArray(160)
    private val hw = FloatArray(160)
    private val hh = FloatArray(160)
    private val hid = IntArray(160)
    private val harg = IntArray(160)
    private var hits = 0
    private var pressId = 0
    private var pressArg = 0
    private var pressPointer = -1
    private var pressX = 0f
    private var pressY = 0f
    private var pressTime = 0f
    private var pressMoved = false
    private var pressLong = false

    // --- screens
    private var mode = Mode.PLAY
    private var station = Station.HAND
    private var recipes: List<Recipe> = emptyList()
    private var storeEntity = 0
    private var store: Container? = null
    private var scroll = 0f
    private var heldFrom: Container? = null
    private var heldSlot = -1
    private var heldCount = 0
    private var status = MOVE_HINT
    private var resetArmed = false
    private var dawnText = ""
    private var showDawn = false
    var debug = false

    // --- transient feedback
    private var toast = ""
    private var toastLeft = 0f
    private var feedItem = 0
    private var feedCount = 0
    private var feedLeft = 0f
    private var hurt = 0f
    private var clock = ""
    private var clockSecond = -1
    private val numbers = Array(1000) { it.toString() }
    private val tint = Color()

    val modal get() = mode != Mode.PLAY || board.isOpen || showDawn || sim.dead
    val sheetOpen get() = mode == Mode.CRAFT || mode == Mode.STORAGE

    fun resize(w: Int, h: Int) = canvas.resize(w, h)

    // ------------------------------------------------------------ input

    private fun lx(screenX: Int) = screenX / canvas.scale
    private fun ly(screenY: Int) = screenY / canvas.scale

    private fun hitAt(x: Float, y: Float): Int {
        for (i in hits - 1 downTo 0) if (x >= hx[i] && x <= hx[i] + hw[i] && y >= hy[i] && y <= hy[i] + hh[i]) return i
        return -1
    }

    fun touchDown(screenX: Int, screenY: Int, pointer: Int) {
        if (pointer >= lastX.size) return
        val x = lx(screenX)
        val y = ly(screenY)
        lastX[pointer] = x
        lastY[pointer] = y
        pointersDown++
        if (pointersDown == 3 && !board.isOpen) debug = !debug
        if (board.isOpen) {
            board.down(x, y, pointer)
            return
        }
        val h = hitAt(x, y)
        if (h >= 0) {
            when (hid[h]) {
                H_ACT -> {
                    actPointer = pointer
                    act = true
                    actTapped = true
                }
                H_JUMP -> {
                    jumpPointer = pointer
                    jumpQueued = true
                }
                else -> if (pressPointer == -1) {
                    pressId = hid[h]; pressArg = harg[h]; pressPointer = pointer
                    pressX = x; pressY = y; pressTime = 0f; pressMoved = false; pressLong = false
                }
            }
            return
        }
        if (modal) return
        if (stickPointer == -1 && x < canvas.width * STICK_ZONE) {
            stickPointer = pointer
            stickX = x
            stickY = y
            moveX = 0f
            moveY = 0f
        } else if (lookPointer == -1) {
            lookPointer = pointer
        }
    }

    fun touchDragged(screenX: Int, screenY: Int, pointer: Int) {
        if (pointer >= lastX.size) return
        val x = lx(screenX)
        val y = ly(screenY)
        val dx = x - lastX[pointer]
        val dy = y - lastY[pointer]
        lastX[pointer] = x
        lastY[pointer] = y
        if (board.isOpen) {
            board.move(x, y, pointer)
            return
        }
        when (pointer) {
            stickPointer -> {
                var sx = x - stickX
                var sy = y - stickY
                val d = hypot(sx, sy)
                if (d > STICK_RADIUS) {
                    sx *= STICK_RADIUS / d
                    sy *= STICK_RADIUS / d
                }
                moveX = sx / STICK_RADIUS
                moveY = -sy / STICK_RADIUS
            }
            // The action and jump buttons also steer, so the right thumb never has to lift to aim.
            lookPointer, actPointer, jumpPointer -> {
                lookDX += dx
                lookDY += dy
            }
            pressPointer -> {
                if (hypot(x - pressX, y - pressY) > TAP_SLOP) pressMoved = true
                if (pressMoved && (pressId == H_RECIPE || pressId == H_SHEET) && mode == Mode.CRAFT) scroll -= dy
            }
        }
    }

    fun touchUp(screenX: Int, screenY: Int, pointer: Int) {
        pointersDown = max(0, pointersDown - 1)
        if (board.isOpen) {
            board.up(lx(screenX), ly(screenY), pointer)
            releaseGameplay(pointer)
            return
        }
        if (pointer == pressPointer) {
            pressPointer = -1
            if (!pressMoved && !pressLong) tap(pressId, pressArg)
        }
        releaseGameplay(pointer)
    }

    private fun releaseGameplay(pointer: Int) {
        if (pointer == stickPointer) {
            stickPointer = -1
            moveX = 0f
            moveY = 0f
        }
        if (pointer == lookPointer) lookPointer = -1
        if (pointer == actPointer) {
            actPointer = -1
            act = false
        }
        if (pointer == jumpPointer) jumpPointer = -1
    }

    /** Desktop helpers: keyboard movement and mouse look are fed in through these. */
    fun addLook(dx: Float, dy: Float) {
        lookDX += dx
        lookDY += dy
    }

    fun setKeys(x: Float, y: Float, sprint: Boolean, jump: Boolean, actKey: Boolean) {
        if (stickPointer == -1) {
            moveX = if (sprint) x else x * 0.8f
            moveY = if (sprint) y else y * 0.8f
        }
        if (jump) jumpQueued = true
        if (actPointer == -1) {
            if (actKey && !act) actTapped = true
            act = actKey
        }
    }

    /** Writes this frame's intent and clears accumulated look deltas and edges. */
    fun consume(out: MoveIntent) {
        val blocked = modal
        out.moveX = if (blocked) 0f else moveX
        out.moveY = if (blocked) 0f else moveY
        out.sprint = !blocked && hypot(moveX, moveY) > SPRINT_THRESHOLD
        out.jump = !blocked && (jumpQueued || jumpPointer != -1)
        out.lookYaw = if (blocked) 0f else -lookDX * LOOK_RATE * host.sensitivity
        out.lookPitch = if (blocked) 0f else -lookDY * LOOK_RATE * host.sensitivity
        lookDX = 0f
        lookDY = 0f
        jumpQueued = false
    }

    // ------------------------------------------------------------ actions

    fun context(): Context {
        val held = ITEMS[sim.selectedItem()]
        val t = sim.target
        return when {
            held.kind == ItemKind.LINKER -> Context.CIRCUIT
            held.kind == ItemKind.KIT -> Context.ROTATE
            t.type == TargetType.ENTITY -> when (ENTITY_DEFS[sim.entities.kind[t.id]].use) {
                EntityUse.CRAFT -> Context.CRAFT
                EntityUse.OPEN -> Context.OPEN
                EntityUse.TOGGLE -> Context.TOGGLE
                EntityUse.NONE -> Context.NONE
            }
            else -> Context.NONE
        }
    }

    fun openCraft(at: Station) {
        dropHeld()
        mode = Mode.CRAFT
        station = at
        recipes = recipesFor(at)
        scroll = 0f
    }

    fun openStorage(entity: Int) {
        val c = sim.entities.containers[entity] ?: return
        dropHeld()
        mode = Mode.STORAGE
        storeEntity = entity
        store = c
    }

    fun closeSheet() {
        dropHeld()
        mode = Mode.PLAY
        store = null
    }

    private fun tap(id: Int, arg: Int) {
        when (id) {
            H_HOTBAR -> select(arg)
            H_BAG -> openCraft(Station.HAND)
            H_MENU -> {
                mode = Mode.MENU
                resetArmed = false
            }
            H_CTX -> {
                val t = sim.target
                val entity = if (t.type == TargetType.ENTITY) t.id else 0
                when (context()) {
                    Context.ROTATE -> sim.buildRot = (sim.buildRot + 1) and 3
                    Context.CRAFT -> openCraft(Station.WORKBENCH)
                    Context.OPEN -> if (entity != 0) openStorage(entity)
                    Context.TOGGLE -> if (entity != 0) sim.toggleSwitch(entity)
                    Context.CIRCUIT -> board.open(entity)
                    Context.NONE -> {}
                }
            }
            H_CLOSE, H_BACKDROP -> if (mode == Mode.MENU) mode = Mode.PLAY else closeSheet()
            H_SLOT -> tapSlot(if (arg shr 8 == 0) sim.inventory else store ?: return, arg and 255, false)
            H_RECIPE -> tryCraft(recipes[arg])
            H_SENS_LESS -> host.sensitivity = ((host.sensitivity - 0.1f) * 10).roundToInt().coerceIn(4, 20) / 10f
            H_SENS_MORE -> host.sensitivity = ((host.sensitivity + 0.1f) * 10).roundToInt().coerceIn(4, 20) / 10f
            H_RESET -> if (resetArmed) {
                mode = Mode.PLAY
                host.newWorld()
            } else resetArmed = true
            H_DAWN_OK -> showDawn = false
            H_RESPAWN -> sim.respawn()
        }
    }

    fun select(i: Int) {
        sim.selected = i
        if (sim.inventory.counts[i] > 0) {
            val def = ITEMS[sim.inventory.ids[i]]
            showToast(def.name + when (def.kind) {
                ItemKind.KIT -> " · aim at the ground and press the action button"
                ItemKind.CONSUMABLE -> " · press the action button to use"
                ItemKind.LINKER -> " · press on a sensor or switch, then on what it should drive"
                else -> ""
            })
        }
    }

    private fun tapSlot(c: Container, slot: Int, half: Boolean) {
        val from = heldFrom
        if (from == null || half) {
            val have = c.counts[slot]
            if (have == 0) return
            heldFrom = c
            heldSlot = slot
            heldCount = if (half) max(1, ceil(have / 2f).toInt()) else have
            status = "Moving $heldCount × ${ITEMS[c.ids[slot]].name}"
        } else if (from === c && heldSlot == slot) {
            dropHeld()
        } else {
            moveSlot(from, heldSlot, c, slot, heldCount)
            dropHeld()
        }
    }

    private fun dropHeld() {
        heldFrom = null
        heldSlot = -1
        heldCount = 0
        status = MOVE_HINT
    }

    private fun tryCraft(r: Recipe) {
        dropHeld()
        val name = ITEMS[r.outItem].name
        status = when {
            !hasInputs(sim.inventory, r) -> "Not enough materials for $name."
            craft(sim.inventory, r) -> "Made $name."
            else -> "Bag is full."
        }
    }

    fun showToast(text: String) {
        toast = text
        toastLeft = 2.2f
    }

    /** The toast currently on screen, for automated checks. */
    fun toastText(): String = if (toastLeft > 0f) toast else ""

    private fun entityName(id: Int) = ENTITY_DEFS[sim.entities.kind[id]].name.ifEmpty { "it" }

    private fun handleEvents() {
        for (i in 0 until sim.eventCount) {
            val a = sim.events[i * 3 + 1]
            val b = sim.events[i * 3 + 2]
            when (sim.events[i * 3]) {
                Ev.GATHER -> {
                    if (a != feedItem) {
                        feedItem = a
                        feedCount = 0
                    }
                    feedCount += b
                    feedLeft = 1.6f
                }
                Ev.BAG_FULL -> showToast("Bag is full")
                Ev.NEED_TOOL -> showToast("${NODE_DEFS[a].name} needs a pick")
                Ev.USED -> showToast("Used ${ITEMS[a].name}")
                Ev.REMOVED -> showToast("Packed up ${ENTITY_DEFS[b].name}")
                Ev.LINK_START -> showToast("From ${entityName(a)}. Now press on what it should drive")
                Ev.LINKED -> showToast("${entityName(a)} now drives ${entityName(b)}")
                Ev.UNLINKED -> showToast("Unwired ${entityName(a)} from ${entityName(b)}")
                Ev.LINK_BAD -> showToast(LINK_BAD.getOrElse(a) { LINK_BAD[0] })
                Ev.NEED_DEPOSIT -> showToast("Drills only work on stained sand: an ore or coal deposit")
                Ev.DUSK -> showToast("One minute to nightfall. Strays wake in the dark")
                Ev.STRAY_DOWN -> if (b == 1) showToast("Turret took down a stray")
                Ev.BITE -> hurt = 0.45f
                Ev.DAWN -> if (b == 1) {
                    dawnText = if (a == 1) "First night survived. The strays shut down at sunrise. The next night brings more of them."
                    else "$a nights survived. Each one brings more strays than the last."
                    showDawn = true
                }
            }
        }
    }

    /** Called once per frame before drawing. */
    fun update(dt: Float) {
        handleEvents()
        guide.sync(sim)
        toastLeft -= dt
        feedLeft -= dt
        if (feedLeft <= 0f) feedItem = 0
        hurt = max(0f, hurt - dt)
        if (pressPointer != -1 && !pressLong && !pressMoved) {
            pressTime += dt
            if (pressTime >= LONG_PRESS && pressId == H_SLOT) {
                pressLong = true
                tapSlot(if (pressArg shr 8 == 0) sim.inventory else store ?: sim.inventory, pressArg and 255, true)
            }
        }
        // A machine or crate can be packed up while its panel is open.
        if (mode == Mode.STORAGE && sim.entities.containers[storeEntity] !== store) closeSheet()
        val from = heldFrom
        if (from != null && from.counts[heldSlot] == 0) dropHeld()
        val second = ceil(sim.ticksToPhaseChange() / TICK_HZ.toFloat()).toInt()
        if (second != clockSecond) {
            clockSecond = second
            clock = "${if (sim.isNight()) "Dawn" else "Night"} in ${second / 60}:${(second % 60).toString().padStart(2, '0')}"
        }
        board.update(dt)
    }

    // ------------------------------------------------------------ drawing

    private fun hit(id: Int, arg: Int, x: Float, y: Float, w: Float, h: Float) {
        if (hits >= hid.size) return
        hx[hits] = x; hy[hits] = y; hw[hits] = w; hh[hits] = h; hid[hits] = id; harg[hits] = arg
        hits++
    }

    private fun num(n: Int) = if (n in 0..999) numbers[n] else n.toString()

    private fun slot(c: Container, index: Int, x: Float, y: Float, selected: Boolean, held: Boolean, accent: Boolean) {
        val k = canvas
        val line = if (selected || held) Ui.AMBER else if (accent) tint.set(Ui.AMBER).apply { a = 0.45f } else Ui.LINE
        k.panel(x, y, SLOT, SLOT, 10f, if (held) tint.set(Ui.AMBER).apply { a = 0.3f } else Ui.SLOT, line, if (selected) 2.5f else 1.5f)
        if (c.counts[index] > 0) {
            drawIcon(k, c.ids[index], x + 9f, y + 9f, 36f)
            if (c.counts[index] > 1) k.text(num(c.counts[index]), x + 4f, y + SLOT - 17f, Type.SMALL, Ui.BONE, Align.right, SLOT - 9f)
        }
    }

    fun draw() {
        val k = canvas
        val w = k.width
        val h = k.height
        val sl = k.safeLeft
        val sr = k.safeRight
        hits = 0
        k.begin()

        if (board.isOpen) {
            board.draw(k)
            k.end()
            return
        }

        // --- hurt flash
        if (hurt > 0f) {
            tint.set(Ui.CORAL).a = hurt / 0.45f * 0.55f
            k.rect(0f, 0f, w, 26f, tint); k.rect(0f, h - 26f, w, 26f, tint)
            k.rect(0f, 26f, 26f, h - 52f, tint); k.rect(w - 26f, 26f, 26f, h - 52f, tint)
        }

        // --- vitals
        k.panel(14f + sl, 10f, 168f, 78f, 12f, Ui.PANEL)
        gauge(26f + sl, 20f, sim.vitals.health, Ui.CORAL)
        gauge(26f + sl, 36f, sim.vitals.charge, Ui.TEAL)
        gauge(26f + sl, 52f, sim.vitals.warmth, Ui.AMBER)
        k.text(clock, 26f + sl, 67f, Type.SMALL, if (sim.isNight()) Ui.TEAL else Ui.MUTED)

        // --- next step
        if (guide.text.isNotEmpty() && mode == Mode.PLAY && !sim.dead && !showDawn) {
            val gw = min(w * 0.44f, 400f)
            val gh = k.textHeight(guide.text, Type.SMALL, gw - 24f) + 14f
            k.panel((w - gw) / 2, 10f, gw, gh, 11f, Ui.PANEL)
            k.text(guide.text, (w - gw) / 2 + 12f, 17f, Type.SMALL, Ui.BONE, Align.center, gw - 24f)
        }

        // --- reticle and target
        k.circle(w / 2, h / 2, 4f, Ui.INK)
        k.circle(w / 2, h / 2, 2.6f, Ui.BONE)
        val name = targetName()
        if (name.isNotEmpty() && !modal) {
            val tw = k.textWidth(name, Type.SMALL) + 18f
            k.panel(w / 2 - tw / 2, h / 2 + 14f, tw, 19f, 9.5f, Ui.PANEL)
            k.text(name, w / 2 - tw / 2, h / 2 + 17.5f, Type.SMALL, Ui.BONE, Align.center, tw)
            if (sim.work.need > 0) {
                k.rrect(w / 2 - 36f, h / 2 + 38f, 72f, 5f, 2.5f, Ui.DIM)
                k.rrect(w / 2 - 36f, h / 2 + 38f, 72f * sim.work.progress / sim.work.need, 5f, 2.5f, Ui.AMBER)
            }
        }
        if (feedItem != 0 && !modal) {
            val text = "+$feedCount ${ITEMS[feedItem].name}"
            val tw = k.textWidth(text, Type.BODY) + 20f
            k.panel(w / 2 - tw / 2, h / 2 - 48f, tw, 22f, 11f, Ui.PANEL)
            k.text(text, w / 2 - tw / 2, h / 2 - 44f, Type.BODY, Ui.TEAL, Align.center, tw)
        }

        // --- hotbar
        val barW = HOTBAR_SIZE * SLOT + (HOTBAR_SIZE - 1) * GAP
        val barX = (w - barW) / 2
        for (i in 0 until HOTBAR_SIZE) {
            val x = barX + i * (SLOT + GAP)
            val sel = i == sim.selected
            val y = h - 8f - SLOT - (if (sel) 5f else 0f)
            slot(sim.inventory, i, x, y, sel, false, false)
            hit(H_HOTBAR, i, x, y, SLOT, SLOT + 8f)
        }
        if (toastLeft > 0f && toast.isNotEmpty()) {
            val tw = min(k.textWidth(toast, Type.BODY) + 24f, w * 0.7f)
            val th = k.textHeight(toast, Type.BODY, tw - 24f) + 10f
            k.panel((w - tw) / 2, h - 80f - th, tw, th, 11f, Ui.PANEL)
            k.text(toast, (w - tw) / 2 + 12f, h - 80f - th + 5f, Type.BODY, Ui.BONE, Align.center, tw - 24f)
        }

        // --- thumb buttons
        val ax = w - 26f - 39f - sr
        val ay = h - 40f - 39f
        k.ring(ax, ay, 39f, 2f, if (act) Ui.AMBER else tint.set(Ui.AMBER).apply { a = 0.75f }, if (act) tint.set(Ui.AMBER).apply { a = 0.35f } else Ui.PANEL)
        k.ring(ax, ay, 14f, 2.5f, Ui.AMBER, Ui.PANEL_SOLID)
        k.circle(ax, ay, 5.5f, Ui.AMBER)
        hit(H_ACT, 0, ax - 44f, ay - 44f, 88f, 88f)
        val jx = w - 118f - 29f - sr
        val jy = h - 22f - 29f
        k.ring(jx, jy, 29f, 1.5f, Ui.LINE, if (jumpPointer != -1) tint.set(Ui.AMBER).apply { a = 0.35f } else Ui.PANEL)
        k.line(jx - 9f, jy + 1f, jx, jy - 9f, 2.5f, Ui.BONE); k.line(jx, jy - 9f, jx + 9f, jy + 1f, 2.5f, Ui.BONE)
        k.line(jx - 9f, jy + 9f, jx, jy - 1f, 2.5f, Ui.BONE); k.line(jx, jy - 1f, jx + 9f, jy + 9f, 2.5f, Ui.BONE)
        hit(H_JUMP, 0, jx - 33f, jy - 33f, 66f, 66f)

        val ctx = context()
        if (ctx != Context.NONE && !modal) {
            val cw = 96f
            k.rrect(w - 22f - cw - sr, h - 180f, cw, 48f, 24f, Ui.AMBER)
            k.text(ctx.label, w - 22f - cw - sr, h - 164f, Type.TITLE, Ui.INK, Align.center, cw)
            hit(H_CTX, 0, w - 22f - cw - sr, h - 184f, cw, 56f)
        }

        val bx = w - 14f - 48f - sr
        k.panel(bx, 10f, 48f, 48f, 12f, Ui.PANEL, Ui.LINE)
        k.rrect(bx + 14f, 28f, 20f, 18f, 3f, Ui.BONE); k.rrect(bx + 16f, 30f, 16f, 14f, 2f, Ui.PANEL_SOLID)
        k.ring(bx + 24f, 27f, 6f, 2f, Ui.BONE, Ui.PANEL_SOLID); k.rect(bx + 16f, 28f, 16f, 6f, Ui.PANEL_SOLID)
        k.rrect(bx + 14f, 28f, 20f, 3f, 1f, Ui.BONE)
        hit(H_BAG, 0, bx - 2f, 8f, 52f, 52f)
        val mx = bx - 56f
        k.panel(mx, 10f, 48f, 48f, 12f, Ui.PANEL, Ui.LINE)
        for (i in 0 until 3) k.rrect(mx + 14f, 25f + i * 7.5f, 20f, 2.5f, 1.2f, Ui.BONE)
        hit(H_MENU, 0, mx - 2f, 8f, 52f, 52f)

        // --- move stick
        if (stickPointer != -1) {
            k.ring(stickX, stickY, 62f, 2f, Ui.LINE, tint.set(Ui.INK).apply { a = 0.3f })
            k.circle(stickX + moveX * STICK_RADIUS, stickY - moveY * STICK_RADIUS, 27f, tint.set(Ui.BONE).apply { a = 0.55f })
        }

        if (debug) {
            k.panel(14f + sl, 96f, 200f, 62f, 8f, Ui.PANEL)
            k.text(host.stats(), 22f + sl, 101f, Type.SMALL, Ui.BONE, Align.left, 190f)
        }
        k.flush()

        when (mode) {
            Mode.CRAFT, Mode.STORAGE -> drawSheet(w, h)
            Mode.MENU -> drawMenu(w, h)
            Mode.PLAY -> {}
        }
        if (sim.dead) card(w, h, "The cold and the strays won", "Your suit rebooted at the landing pad. Your bag and your base are untouched.", "Get up", H_RESPAWN)
        else if (showDawn) card(w, h, "You held the night", dawnText, "Keep building", H_DAWN_OK)
        k.end()
    }

    private fun gauge(x: Float, y: Float, value: Int, c: Color) {
        val k = canvas
        k.circle(x + 4f, y + 4.5f, 4.5f, c)
        val gx = x + 16f
        k.rrect(gx, y, 128f, 9f, 3f, tint.set(Ui.BONE).apply { a = 0.14f })
        val fill = 128f * value / VITAL_MAX
        if (fill > 1f) k.rrect(gx, y, fill, 9f, 3f, c)
        // Ten segments cut into the bar so it reads at a glance.
        for (i in 1 until 10) k.rect(gx + i * 12.8f - 1f, y, 2f, 9f, Ui.PANEL_SOLID)
    }

    private fun targetName(): String {
        val t = sim.target
        return when (t.type) {
            TargetType.NODE -> NODE_DEFS[sim.nodes.kind[t.id]].name
            TargetType.ENTITY -> ENTITY_DEFS[sim.entities.kind[t.id]].name
            TargetType.STRAY -> "Stray"
            else -> ""
        }
    }

    private fun sheetFrame(w: Float, h: Float, sw: Float, sh: Float, title: String, sub: String): Float {
        val k = canvas
        k.rect(0f, 0f, w, h, Ui.DIM)
        hit(H_BACKDROP, 0, 0f, 0f, w, h)
        val x = (w - sw) / 2
        val y = h - sh
        k.panel(x, y, sw, sh + 20f, 16f, Ui.PANEL_SOLID, Ui.LINE)
        hit(H_SHEET, 0, x, y, sw, sh)
        k.text(title, x + 14f, y + 13f, Type.TITLE, Ui.BONE)
        k.text(sub, x + 22f + k.textWidth(title, Type.TITLE), y + 16f, Type.SMALL, Ui.MUTED)
        // Close button.
        val cx = x + sw - 30f
        k.line(cx - 7f, y + 15f, cx + 7f, y + 29f, 2.2f, Ui.MUTED)
        k.line(cx + 7f, y + 15f, cx - 7f, y + 29f, 2.2f, Ui.MUTED)
        hit(H_CLOSE, 0, cx - 22f, y, 44f, 44f)
        return x
    }

    private fun drawSheet(w: Float, h: Float) {
        val k = canvas
        val sw = min(770f, w * 0.97f)
        val sh = 306f
        val title = if (mode == Mode.STORAGE) ENTITY_DEFS[sim.entities.kind[storeEntity]].name else if (station == Station.WORKBENCH) "Workbench" else "Bag"
        val x = sheetFrame(w, h, sw, sh, title, status)
        val y = h - sh
        val gridW = 6 * SLOT + 5 * GAP
        val bagX = x + sw - 14f - gridW
        val top = y + 54f

        // Right pane: the player's bag. The first row mirrors the hotbar.
        for (i in 0 until INVENTORY_SIZE) {
            val sx = bagX + (i % 6) * (SLOT + GAP)
            val sy = top + (i / 6) * (SLOT + GAP) + (if (i >= 6) 6f else 0f)
            slot(sim.inventory, i, sx, sy, false, heldFrom === sim.inventory && heldSlot == i, i < HOTBAR_SIZE)
            hit(H_SLOT, i, sx, sy, SLOT, SLOT)
        }

        val leftX = x + 14f
        val leftW = bagX - leftX - 16f
        val c = store
        if (mode == Mode.STORAGE && c != null) {
            val names = ENTITY_DEFS[sim.entities.kind[storeEntity]].slotNames
            k.text(if (names != null) "INSIDE THE MACHINE" else "STORED HERE", leftX, y + 44f, Type.SMALL, Ui.MUTED)
            val perRow = if (names != null) c.size else 4
            val pitchX = SLOT + (if (names != null) 12f else GAP)
            for (i in 0 until c.size) {
                val sx = leftX + (i % perRow) * pitchX
                val sy = top + 8f + (i / perRow) * (SLOT + GAP)
                slot(c, i, sx, sy, false, heldFrom === c && heldSlot == i, false)
                hit(H_SLOT, (1 shl 8) or i, sx, sy, SLOT, SLOT)
                if (names != null) k.text(names[i], sx, sy + SLOT + 3f, Type.SMALL, Ui.MUTED, Align.center, SLOT)
            }
            if (names != null) {
                k.text(machineStatus(sim, storeEntity), leftX, top + 92f, Type.BODY, Ui.AMBER)
                k.text("It pushes finished items onto a belt placed in front of it. Belts pointing into it feed it.", leftX, top + 114f, Type.SMALL, Ui.MUTED, Align.left, leftW)
            }
        } else {
            k.text(if (station == Station.WORKBENCH) "BUILD AT THE BENCH" else "CRAFT BY HAND", leftX, y + 44f, Type.SMALL, Ui.MUTED)
            val listY = top + 6f
            val listH = sh - 66f
            val rowH = 54f
            scroll = scroll.coerceIn(0f, max(0f, recipes.size * (rowH + 6f) - listH))
            // Only the recipe list scrolls; clip it to its pane.
            k.flush()
            k.shapes.flush()
            Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_SCISSOR_TEST)
            Gdx.gl.glScissor((leftX * k.scale).toInt(), ((h - listY - listH) * k.scale).toInt(), (leftW * k.scale).toInt(), (listH * k.scale).toInt())
            for ((i, r) in recipes.withIndex()) {
                val ry = listY + i * (rowH + 6f) - scroll
                if (ry + rowH < listY || ry > listY + listH) continue
                val ready = hasInputs(sim.inventory, r)
                k.panel(leftX + 2f, ry, leftW - 4f, rowH, 10f, Ui.SLOT, if (ready) tint.set(Ui.AMBER).apply { a = 0.7f } else Ui.LINE)
                drawIcon(k, r.outItem, leftX + 10f, ry + 8f, 38f)
                val label = ITEMS[r.outItem].name + if (r.outCount > 1) " ×${r.outCount}" else ""
                k.text(label, leftX + 58f, ry + 9f, Type.BODY, if (ready) Ui.BONE else Ui.MUTED)
                var cx = leftX + 58f
                for (input in r.inputs) {
                    val have = sim.inventory.count(input[0])
                    drawIcon(k, input[0], cx, ry + 29f, 17f)
                    val text = "${min(have, 999)}/${input[1]}"
                    k.text(text, cx + 19f, ry + 31f, Type.SMALL, if (have < input[1]) Ui.CORAL else Ui.MUTED)
                    cx += 26f + k.textWidth(text, Type.SMALL) + 8f
                }
                val visibleTop = max(ry, listY)
                val visibleBottom = min(ry + rowH, listY + listH)
                if (visibleBottom - visibleTop > 12f) hit(H_RECIPE, i, leftX, visibleTop, leftW, visibleBottom - visibleTop)
            }
            k.flush()
            k.shapes.flush()
            Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_SCISSOR_TEST)
        }
    }

    private fun drawMenu(w: Float, h: Float) {
        val k = canvas
        val sw = 380f
        val sh = 210f
        val x = sheetFrame(w, h, sw, sh, "Menu", "")
        val y = h - sh
        k.text("Look sensitivity", x + 14f, y + 58f, Type.BODY, Ui.BONE)
        chip(x + sw - 170f, y + 46f, 48f, "−", H_SENS_LESS)
        k.text(String.format("%.1f", host.sensitivity), x + sw - 118f, y + 58f, Type.BODY, Ui.BONE, Align.center, 52f)
        chip(x + sw - 62f, y + 46f, 48f, "+", H_SENS_MORE)
        k.text("The game saves by itself every half minute and whenever you leave the app.", x + 14f, y + 102f, Type.SMALL, Ui.MUTED, Align.left, sw - 28f)
        val by = y + 146f
        k.panel(x + 14f, by, sw - 28f, 46f, 10f, if (resetArmed) Ui.CORAL else Ui.PANEL_SOLID, Ui.CORAL)
        k.text(if (resetArmed) "Tap again to erase this world" else "Start a new world", x + 14f, by + 15f, Type.BODY, if (resetArmed) Ui.INK else Ui.CORAL, Align.center, sw - 28f)
        hit(H_RESET, 0, x + 14f, by, sw - 28f, 46f)
    }

    private fun chip(x: Float, y: Float, w: Float, label: String, id: Int) {
        canvas.panel(x, y, w, 40f, 20f, Ui.SLOT, Ui.LINE)
        canvas.text(label, x, y + 12f, Type.BODY, Ui.BONE, Align.center, w)
        hit(id, 0, x, y - 2f, w, 44f)
    }

    private fun card(w: Float, h: Float, title: String, body: String, button: String, id: Int) {
        val k = canvas
        k.rect(0f, 0f, w, h, Ui.DIM)
        val cw = 340f
        val bodyH = k.textHeight(body, Type.BODY, cw - 36f)
        val ch = bodyH + 122f
        val x = (w - cw) / 2
        val y = (h - ch) / 2
        k.panel(x, y, cw, ch, 14f, Ui.PANEL_SOLID, Ui.LINE)
        k.text(title, x, y + 18f, Type.TITLE, Ui.BONE, Align.center, cw)
        k.text(body, x + 18f, y + 46f, Type.BODY, Ui.MUTED, Align.center, cw - 36f)
        k.rrect(x + 18f, y + ch - 62f, cw - 36f, 46f, 10f, Ui.AMBER)
        k.text(button, x + 18f, y + ch - 47f, Type.BODY, Ui.INK, Align.center, cw - 36f)
        hit(id, 0, x + 18f, y + ch - 62f, cw - 36f, 46f)
    }

    // ------------------------------------------------------------ test hooks

    /** Centre of the first tap target with this id and argument, in screen pixels; null if it is not on screen. */
    fun locate(id: Int, arg: Int = 0): Pair<Int, Int>? {
        for (i in hits - 1 downTo 0) if (hid[i] == id && harg[i] == arg) {
            return ((hx[i] + hw[i] / 2) * canvas.scale).toInt() to ((hy[i] + hh[i] / 2) * canvas.scale).toInt()
        }
        return null
    }

    fun dispose() = canvas.dispose()

    companion object Targets {
        const val HOTBAR = H_HOTBAR
        const val BAG = H_BAG
        const val MENU = H_MENU
        const val ACT = H_ACT
        const val JUMP = H_JUMP
        const val CTX = H_CTX
        const val CLOSE = H_CLOSE
        const val SLOT_TARGET = H_SLOT
        const val RECIPE = H_RECIPE
        const val RESET = H_RESET
        const val DAWN_OK = H_DAWN_OK
    }
}
