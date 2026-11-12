package scrapline.ui

import com.badlogic.gdx.utils.Align
import scrapline.sim.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val NODE_W = 124f
private const val HEAD_H = 26f
private const val ROW_H = 26f
/** Ports are drawn small but can be hit from this far away, in screen units at any zoom. */
private const val PORT_DRAW = 8f
private const val PORT_HIT = 30f
private const val WIRE_HIT = 16f
private const val DRAG_SLOP = 8f
/** A dragged wire's free end floats this far above the finger so the finger never hides the target. */
private const val DRAG_LIFT = 48f
private const val ZOOM_MIN = 0.55f
private const val ZOOM_MAX = 1.7f
private const val GRID = 8

private val PALETTE = intArrayOf(NodeType.AND, NodeType.OR, NodeType.NOT, NodeType.DELAY, NodeType.TIMER)

private enum class Drag { IDLE, PAN, NODE, WIRE, PINCH }

/**
 * Full-screen circuit editor. Built for thumbs:
 * tap an output, then tap an input to wire them (dragging between them works too);
 * tap a wire or a node to act on it; one finger on empty space pans, two pinch.
 * It edits the live graph, so signals can be watched flowing while wiring.
 */
class Board(private val simOf: () -> Sim) {
    var isOpen = false
        private set
    private val sim get() = simOf()
    private var panX = 0f
    private var panY = 0f
    private var zoom = 1f
    private var w = 844f
    private var h = 390f
    private var time = 0f

    /** Output port chosen as the start of a new wire: node, port. -1 for none. */
    private var fromNode = -1
    private var fromPort = 0
    var selNode = 0
        private set
    var selWire = -1
        private set

    private var drag = Drag.IDLE
    private val ptrX = FloatArray(20)
    private val ptrY = FloatArray(20)
    private val ptrDown = BooleanArray(20)
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var dragPortNode = -1
    private var dragPortIndex = 0
    private var dragNode = 0
    private var dragDX = 0f
    private var dragDY = 0f
    private var wireX = 0f
    private var wireY = 0f
    private var pinchDist = 0f
    private var pinchZoom = 1f
    private var flashText = ""
    private var flashLeft = 0f

    // Buttons drawn last frame: x, y, w, h, action, argument.
    private val bx = FloatArray(24)
    private val by = FloatArray(24)
    private val bw = FloatArray(24)
    private val bh = FloatArray(24)
    private val bAct = IntArray(24)
    private val bArg = IntArray(24)
    private var buttons = 0
    private var pressedButton = -1

    // Result of the last port search.
    private var hitNode = 0
    private var hitPort = 0
    private var hitOutput = false

    /** Opens the board, centred on `entity` if given (it is added to the board if needed). */
    fun open(entity: Int = 0) {
        isOpen = true
        fromNode = -1
        selNode = 0
        selWire = -1
        drag = Drag.IDLE
        ptrDown.fill(false)
        val g = sim.graph
        var focus = 0
        if (entity != 0) {
            val def = ENTITY_DEFS[sim.entities.kind[entity]]
            if (def.ins.isNotEmpty() || def.outs.isNotEmpty()) focus = sim.ensureNode(entity)
        }
        if (focus != 0) {
            panX = g.bx[focus] + NODE_W / 2
            panY = g.by[focus] + 40f
            zoom = 1f
            selNode = focus
        } else {
            fit()
        }
    }

    fun close() {
        isOpen = false
    }

    /** Centres and zooms so every node is on screen. */
    private fun fit() {
        val g = sim.graph
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (n in 1 until g.high) {
            if (g.type[n] == NodeType.FREE) continue
            x0 = min(x0, g.bx[n].toFloat()); y0 = min(y0, g.by[n].toFloat())
            x1 = max(x1, g.bx[n] + NODE_W); y1 = max(y1, g.by[n] + nodeH(n))
        }
        if (x0 == Float.MAX_VALUE) {
            panX = 0f; panY = 0f; zoom = 1f
            return
        }
        panX = (x0 + x1) / 2
        panY = (y0 + y1) / 2
        zoom = min((w - 80f) / (x1 - x0 + 1f), (h - 190f) / (y1 - y0 + 1f)).coerceIn(ZOOM_MIN, 1.1f)
    }

    // ------------------------------------------------------------ geometry

    private fun ins(n: Int): Array<String> {
        val g = sim.graph
        return if (g.type[n] == NodeType.DEVICE) ENTITY_DEFS[sim.entities.kind[g.entity[n]]].ins else LOGIC_DEFS[g.type[n]].ins
    }

    private fun outs(n: Int): Array<String> {
        val g = sim.graph
        return if (g.type[n] == NodeType.DEVICE) ENTITY_DEFS[sim.entities.kind[g.entity[n]]].outs else LOGIC_DEFS[g.type[n]].outs
    }

    private fun nodeName(n: Int): String {
        val g = sim.graph
        return if (g.type[n] == NodeType.DEVICE) ENTITY_DEFS[sim.entities.kind[g.entity[n]]].name else LOGIC_DEFS[g.type[n]].name
    }

    private fun nodeH(n: Int) = HEAD_H + ROW_H * max(1, max(ins(n).size, outs(n).size)) + 6f
    private fun sx(wx: Float) = (wx - panX) * zoom + w / 2
    private fun sy(wy: Float) = (wy - panY) * zoom + h / 2
    private fun portX(n: Int, output: Boolean) = sx(sim.graph.bx[n] + (if (output) NODE_W else 0f))
    private fun portY(n: Int, port: Int) = sy(sim.graph.by[n] + HEAD_H + ROW_H * port + ROW_H / 2)

    /** Screen position of a port, in logical units; used by automated checks. */
    fun portPosition(node: Int, port: Int, output: Boolean): Pair<Float, Float> = portX(node, output) to portY(node, port)

    /**
     * Finds the nearest port within the hit radius and stores it in hitNode/hitPort/hitOutput.
     * `want`: 0 = any, 1 = outputs only, 2 = inputs only.
     */
    private fun portAt(x: Float, y: Float, want: Int): Boolean {
        val g = sim.graph
        var best = PORT_HIT * PORT_HIT
        var found = false
        for (n in 1 until g.high) {
            if (g.type[n] == NodeType.FREE) continue
            for (side in 0..1) {
                val output = side == 1
                if ((want == 1 && !output) || (want == 2 && output)) continue
                val count = if (output) outs(n).size else ins(n).size
                for (p in 0 until count) {
                    val dx = x - portX(n, output)
                    val dy = y - portY(n, p)
                    val d = dx * dx + dy * dy
                    if (d < best) {
                        best = d
                        hitNode = n; hitPort = p; hitOutput = output
                        found = true
                    }
                }
            }
        }
        return found
    }

    private fun nodeAt(x: Float, y: Float): Int {
        val g = sim.graph
        for (n in g.high - 1 downTo 1) {
            if (g.type[n] == NodeType.FREE) continue
            val x0 = sx(g.bx[n].toFloat())
            val y0 = sy(g.by[n].toFloat())
            if (x >= x0 && x <= x0 + NODE_W * zoom && y >= y0 && y <= y0 + nodeH(n) * zoom) return n
        }
        return 0
    }

    private fun wireAt(x: Float, y: Float): Int {
        val g = sim.graph
        var best = -1
        var bestD = WIRE_HIT
        for (i in 0 until g.wireCount) {
            val wv = g.wires[i]
            val x0 = portX(wireSrcNode(wv), true); val y0 = portY(wireSrcNode(wv), wireSrcPort(wv))
            val x3 = portX(wireDstNode(wv), false); val y3 = portY(wireDstNode(wv), wireDstPort(wv))
            val k = max(30f, abs(x3 - x0) * 0.5f)
            var d = Float.MAX_VALUE
            for (s in 0..16) {
                val t = s / 16f
                val u = 1 - t
                val cx = u * u * u * x0 + 3 * u * u * t * (x0 + k) + 3 * u * t * t * (x3 - k) + t * t * t * x3
                val cy = u * u * u * y0 + 3 * u * u * t * y0 + 3 * u * t * t * y3 + t * t * t * y3
                d = min(d, hypot(x - cx, y - cy))
            }
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    // ------------------------------------------------------------ input

    private fun buttonAt(x: Float, y: Float): Int {
        for (i in buttons - 1 downTo 0) if (x >= bx[i] && x <= bx[i] + bw[i] && y >= by[i] && y <= by[i] + bh[i]) return i
        return -1
    }

    fun down(x: Float, y: Float, pointer: Int) {
        ptrDown[pointer] = true
        ptrX[pointer] = x
        ptrY[pointer] = y
        val count = ptrDown.count { it }
        if (count == 2) {
            drag = Drag.PINCH
            pinchDist = pointerSpread()
            pinchZoom = zoom
            return
        }
        if (count > 2) return
        downX = x
        downY = y
        moved = false
        pressedButton = buttonAt(x, y)
        if (pressedButton >= 0) {
            drag = Drag.IDLE
            return
        }
        if (portAt(x, y, 1)) {
            drag = Drag.WIRE
            dragPortNode = hitNode
            dragPortIndex = hitPort
            wireX = x
            wireY = y - DRAG_LIFT
            return
        }
        val node = nodeAt(x, y)
        if (node != 0 && !portAt(x, y, 2)) {
            val g = sim.graph
            drag = Drag.NODE
            dragNode = node
            dragDX = (x - sx(g.bx[node].toFloat())) / zoom
            dragDY = (y - sy(g.by[node].toFloat())) / zoom
        } else {
            drag = Drag.PAN
        }
    }

    private fun pointerSpread(): Float {
        var a = -1
        for (i in ptrDown.indices) {
            if (!ptrDown[i]) continue
            if (a == -1) a = i else return hypot(ptrX[a] - ptrX[i], ptrY[a] - ptrY[i])
        }
        return 1f
    }

    fun move(x: Float, y: Float, pointer: Int) {
        if (!ptrDown[pointer]) return
        val px = ptrX[pointer]
        val py = ptrY[pointer]
        ptrX[pointer] = x
        ptrY[pointer] = y
        if (drag == Drag.PINCH) {
            zoomTo(pinchZoom * pointerSpread() / max(1f, pinchDist))
            return
        }
        if (!moved && hypot(x - downX, y - downY) < DRAG_SLOP) return
        moved = true
        when (drag) {
            Drag.PAN -> {
                panX -= (x - px) / zoom
                panY -= (y - py) / zoom
            }
            Drag.NODE -> {
                val g = sim.graph
                g.bx[dragNode] = (((x - w / 2) / zoom + panX - dragDX) / GRID).roundToInt() * GRID
                g.by[dragNode] = (((y - h / 2) / zoom + panY - dragDY) / GRID).roundToInt() * GRID
            }
            Drag.WIRE -> {
                fromNode = dragPortNode
                fromPort = dragPortIndex
                wireX = x
                wireY = y - DRAG_LIFT
            }
            else -> {}
        }
    }

    fun up(x: Float, y: Float, pointer: Int) {
        if (!ptrDown[pointer]) return
        ptrDown[pointer] = false
        val mode = drag
        if (mode == Drag.PINCH) {
            if (ptrDown.none { it }) drag = Drag.IDLE
            return
        }
        drag = Drag.IDLE
        if (pressedButton >= 0) {
            val b = pressedButton
            pressedButton = -1
            if (buttonAt(x, y) == b) press(bAct[b], bArg[b])
            return
        }
        if (mode == Drag.WIRE && moved) {
            // Dropped a dragged wire: connect if its lifted end is on an input.
            if (fromNode != -1 && portAt(wireX, wireY, 2)) connect(fromNode, fromPort, hitNode, hitPort)
            fromNode = -1
            return
        }
        if (!moved) tap(x, y)
    }

    private fun tap(x: Float, y: Float) {
        if (portAt(x, y, 0)) {
            val n = hitNode
            val p = hitPort
            if (hitOutput) {
                // Tapping the chosen output again cancels.
                if (fromNode == n && fromPort == p) fromNode = -1 else {
                    fromNode = n
                    fromPort = p
                }
                selNode = 0
                selWire = -1
            } else if (fromNode != -1) {
                connect(fromNode, fromPort, n, p)
                fromNode = -1
            } else {
                // An input on its own: select the first wire into it so it can be removed.
                val g = sim.graph
                selWire = (0 until g.wireCount).firstOrNull { wireDstNode(g.wires[it]) == n && wireDstPort(g.wires[it]) == p } ?: -1
                selNode = 0
            }
            return
        }
        fromNode = -1
        val node = nodeAt(x, y)
        if (node != 0) {
            selNode = node
            selWire = -1
            return
        }
        selNode = 0
        selWire = wireAt(x, y)
    }

    private fun connect(sn: Int, sp: Int, dn: Int, dp: Int) {
        val g = sim.graph
        if (sn == dn) {
            flash("A node cannot feed itself directly.")
            return
        }
        val existing = g.findWire(sn, sp, dn, dp)
        if (existing >= 0) {
            selWire = existing
            selNode = 0
            return
        }
        if (!g.addWire(sn, sp, dn, dp)) flash("The circuit is full.")
        selWire = -1
    }

    private fun addLogic(type: Int) {
        val g = sim.graph
        val id = g.addNode(type, 0, ((panX - 48f) / GRID).roundToInt() * GRID, ((panY - 30f) / GRID).roundToInt() * GRID)
        if (id == 0) {
            flash("The circuit is full.")
            return
        }
        // Nudge if it would land exactly on another node.
        for (n in 1 until g.high) {
            if (n != id && g.type[n] != NodeType.FREE && g.bx[n] == g.bx[id] && g.by[n] == g.by[id]) {
                g.bx[id] += GRID * 3
                g.by[id] += GRID * 3
            }
        }
        selNode = id
        selWire = -1
        fromNode = -1
    }

    private fun zoomTo(z: Float) {
        zoom = z.coerceIn(ZOOM_MIN, ZOOM_MAX)
    }

    private fun flash(text: String) {
        flashText = text
        flashLeft = 2.2f
    }

    private fun paramOf(n: Int): ParamDef? {
        val g = sim.graph
        val sensor = g.type[n] == NodeType.DEVICE && sim.entities.kind[g.entity[n]] == EntityKind.SENSOR
        return if (sensor) SENSOR_PARAM else LOGIC_DEFS[g.type[n]].param
    }

    private fun press(action: Int, arg: Int) {
        val g = sim.graph
        val n = selNode
        when (action) {
            A_DONE -> close()
            A_ADD -> addLogic(arg)
            A_REMOVE_WIRE -> {
                g.removeWireAt(selWire)
                selWire = -1
            }
            A_REMOVE_NODE -> {
                g.removeNode(n)
                selNode = 0
            }
            A_LESS, A_MORE -> paramOf(n)?.let { p ->
                val v = if (g.param[n * 2] != 0) g.param[n * 2] else p.initial
                g.param[n * 2] = (v + (if (action == A_MORE) p.step else -p.step)).coerceIn(p.min, p.max)
            }
            A_MODE -> g.param[n * 2 + 1] = g.param[n * 2 + 1] xor 1
        }
    }

    fun update(dt: Float) {
        time += dt
        flashLeft -= dt
    }

    // ------------------------------------------------------------ drawing

    private fun button(k: Canvas, x: Float, y: Float, bwid: Float, label: String, action: Int, arg: Int = 0, accent: Boolean = false, solid: Boolean = false) {
        if (solid) k.rrect(x, y, bwid, 44f, 22f, Ui.AMBER)
        else k.panel(x, y, bwid, 44f, 22f, Ui.PANEL_SOLID, if (accent) Ui.AMBER else Ui.LINE)
        k.text(label, x, y + 14f, Type.BODY, if (solid) Ui.INK else Ui.BONE, Align.center, bwid)
        if (buttons < bAct.size) {
            bx[buttons] = x; by[buttons] = y; bw[buttons] = bwid; bh[buttons] = 44f; bAct[buttons] = action; bArg[buttons] = arg
            buttons++
        }
    }

    /** Redraws the board. Called every frame while open so live signals are visible. */
    fun draw(k: Canvas) {
        w = k.width
        h = k.height
        val g = sim.graph
        if (selNode != 0 && g.type[selNode] == NodeType.FREE) selNode = 0
        if (selWire >= g.wireCount) selWire = -1
        buttons = 0
        k.rect(0f, 0f, w, h, Ui.INK)

        // Dot grid, to give panning something to move against.
        val step = 40f * zoom
        var gy = ((h / 2 - panY * zoom) % step + step) % step
        while (gy < h) {
            var gx = ((w / 2 - panX * zoom) % step + step) % step
            while (gx < w) {
                k.rect(gx - 1f, gy - 1f, 2f, 2f, Ui.LINE)
                gx += step
            }
            gy += step
        }

        // Wires under nodes.
        for (i in 0 until g.wireCount) {
            val wv = g.wires[i]
            val live = g.outCur[wireSrcNode(wv) * PORTS + wireSrcPort(wv)] > 0
            val sel = i == selWire
            k.curve(
                portX(wireSrcNode(wv), true), portY(wireSrcNode(wv), wireSrcPort(wv)),
                portX(wireDstNode(wv), false), portY(wireDstNode(wv), wireDstPort(wv)),
                (if (sel) 5f else if (live) 4f else 3f) * max(0.8f, zoom), if (sel) Ui.CORAL else if (live) Ui.AMBER else Ui.OFF,
            )
        }
        if (drag == Drag.WIRE && moved && fromNode != -1) {
            k.curve(portX(fromNode, true), portY(fromNode, fromPort), wireX, wireY, 3f, Ui.TEAL)
            k.circle(wireX, wireY, 7f, Ui.TEAL)
        }

        val pulse = 0.5f + 0.5f * sin(time * 6f)
        var any = false
        for (n in 1 until g.high) {
            if (g.type[n] == NodeType.FREE) continue
            any = true
            val x = sx(g.bx[n].toFloat())
            val y = sy(g.by[n].toFloat())
            val nw = NODE_W * zoom
            val nh = nodeH(n) * zoom
            val device = g.type[n] == NodeType.DEVICE
            k.panel(x, y, nw, nh, 9f * zoom, if (device) DEVICE_FILL else Ui.PANEL_SOLID, if (n == selNode) Ui.AMBER else Ui.LINE, if (n == selNode) 2.5f else 1.5f)
            k.text(nodeName(n), x, y + 6f * zoom, Type.BODY, if (device) Ui.TEAL else Ui.BONE, Align.center, nw)
            val ins = ins(n)
            val outs = outs(n)
            val r = PORT_DRAW * max(0.8f, zoom)
            for (p in ins.indices) {
                val px = portX(n, false)
                val py = portY(n, p)
                // Inputs pulse while a wire is being made, to show where it can go.
                if (fromNode != -1 && fromNode != n) k.ring(px, py, r + 5f + pulse * 5f, 2f, Ui.TEAL, Ui.INK)
                k.ring(px, py, r, 2f, Ui.BONE, Ui.INK)
                k.text(ins[p], px + 13f * zoom, py - 6f, Type.SMALL, Ui.MUTED)
            }
            for (p in outs.indices) {
                val px = portX(n, true)
                val py = portY(n, p)
                val chosen = fromNode == n && fromPort == p
                val live = g.outCur[n * PORTS + p] > 0
                val c = if (chosen) Ui.TEAL else if (live) Ui.AMBER else Ui.BONE
                k.ring(px, py, r, 2f, c, if (chosen || live) c else Ui.INK)
                k.text(outs[p], px - 13f * zoom - 60f, py - 6f, Type.SMALL, Ui.MUTED, Align.right, 60f)
            }
        }

        // Top bar.
        k.text("Circuit", 14f + k.safeLeft, 20f, Type.TITLE, Ui.BONE)
        val hint = when {
            flashLeft > 0f -> flashText
            !any -> "Nothing to wire yet. Place a sensor, switch, lamp or turret first."
            fromNode != -1 -> "Now tap the input it should drive."
            else -> "Tap an output, then an input. Tap a wire or node to change it."
        }
        hintText = hint
        k.text(hint, 82f + k.safeLeft, 23f, Type.SMALL, Ui.MUTED)
        button(k, w - 14f - 80f - k.safeRight, 8f, 80f, "Done", A_DONE, solid = true)

        // Selection bar.
        var bxp = 0f
        val barY = h - 108f
        val n = selNode
        if (selWire >= 0) {
            button(k, (w - 130f) / 2, barY, 130f, "Remove wire", A_REMOVE_WIRE, accent = true)
        } else if (n != 0) {
            val p = paramOf(n)
            val removable = g.type[n] > NodeType.DEVICE
            val sensor = p === SENSOR_PARAM
            val total = (if (p != null) 48f + 8f + 120f + 8f + 48f + 8f else 0f) + (if (sensor) 170f + 8f else 0f) + (if (removable) 96f else 0f) + 70f
            bxp = (w - total) / 2
            k.text(nodeName(n), bxp, barY + 14f, Type.BODY, Ui.BONE, Align.center, 62f)
            bxp += 70f
            if (p != null) {
                val v = if (g.param[n * 2] != 0) g.param[n * 2] else p.initial
                button(k, bxp, barY, 48f, "−", A_LESS, accent = true)
                paramText = if (p.unit == "m") "${p.label} $v m" else "${p.label} ${String.format("%.1f", v / LOGIC_HZ.toFloat())} s"
                k.text(paramText, bxp + 56f, barY + 14f, Type.BODY, Ui.BONE, Align.center, 120f)
                button(k, bxp + 184f, barY, 48f, "+", A_MORE, accent = true)
                bxp += 240f
            }
            if (sensor) {
                button(k, bxp, barY, 170f, if (g.param[n * 2 + 1] == 1) "Sees strays only" else "Sees strays and you", A_MODE, accent = true)
                bxp += 178f
            }
            if (removable) button(k, bxp, barY, 96f, "Remove", A_REMOVE_NODE, accent = true)
        }

        // Palette.
        val pw = 84f
        val total = PALETTE.size * pw + (PALETTE.size - 1) * 8f
        for ((i, type) in PALETTE.withIndex()) {
            button(k, (w - total) / 2 + i * (pw + 8f), h - 54f, pw, "+ ${LOGIC_DEFS[type].name}", A_ADD, type)
        }
    }

    /** Text currently shown in the hint line and the setting readout; read by automated checks. */
    var hintText = ""
        private set
    var paramText = ""
        private set

    /** Centre of the first button with this action and argument, in logical units; null if not shown. */
    fun locate(action: Int, arg: Int = 0): Pair<Float, Float>? {
        for (i in 0 until buttons) if (bAct[i] == action && bArg[i] == arg) return (bx[i] + bw[i] / 2) to (by[i] + bh[i] / 2)
        return null
    }

    companion object {
        const val A_DONE = 1
        const val A_ADD = 2
        const val A_REMOVE_WIRE = 3
        const val A_REMOVE_NODE = 4
        const val A_LESS = 5
        const val A_MORE = 6
        const val A_MODE = 7
        private val SENSOR_PARAM = ParamDef("Range", 2, 20, 1, SENSOR_RANGE, "m")
        private val DEVICE_FILL = com.badlogic.gdx.graphics.Color.valueOf("2B3A36FF")
    }
}
