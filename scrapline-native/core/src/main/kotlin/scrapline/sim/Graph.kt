package scrapline.sim

import kotlin.math.max

/*
 * The wiring graph behind the visual programming system.
 *
 * Everything lives in fixed-size arrays allocated once, so wiring up a base
 * never allocates and never grows the heap.
 *
 * Evaluation is double buffered: nodes read last tick's outputs and write next
 * tick's, so evaluation order is irrelevant, feedback loops are well defined,
 * and each logic node adds exactly one tick of delay. Pure logic nodes are
 * evaluated only when one of their inputs changed (or they asked to run again),
 * so an idle circuit costs nothing.
 */

const val MAX_NODES = 1024
const val MAX_WIRES = 4096
/** Input ports and output ports per node. */
const val PORTS = 4
/** Logic ticks per second; the sim steps the graph on every second 20 Hz tick. */
const val LOGIC_HZ = 10

object NodeType {
    const val FREE = 0
    /** Backed by a world entity; the host reads sensors into it and drives actuators from it. */
    const val DEVICE = 1
    const val AND = 2
    const val OR = 3
    const val NOT = 4
    /** Output follows the input after `param0` ticks; shorter blips are swallowed. */
    const val DELAY = 5
    /** Square wave with a period of `param0` ticks while `run` is high (or unwired). */
    const val TIMER = 6
}

class ParamDef(val label: String, val min: Int, val max: Int, val step: Int, val initial: Int, val unit: String)

class LogicDef(val name: String, val ins: Array<String>, val outs: Array<String>, val param: ParamDef? = null)

val LOGIC_DEFS = arrayOf(
    LogicDef("", emptyArray(), emptyArray()),
    LogicDef("Device", emptyArray(), emptyArray()),
    LogicDef("AND", arrayOf("a", "b"), arrayOf("out")),
    LogicDef("OR", arrayOf("a", "b"), arrayOf("out")),
    LogicDef("NOT", arrayOf("in"), arrayOf("out")),
    LogicDef("Delay", arrayOf("in"), arrayOf("out"), ParamDef("Wait", 2, 100, 2, 10, "ticks")),
    LogicDef("Timer", arrayOf("run"), arrayOf("pulse"), ParamDef("Period", 2, 200, 2, 20, "ticks")),
)

/** Reads sensors and drives actuators for Device nodes. Implemented by the sim. */
interface LogicHost {
    /** Write the entity's current output values to out[base until base + PORTS]. */
    fun sense(entity: Int, node: Int, out: IntArray, base: Int)
    /** Apply the entity's input values; read them with `inputValue` / `inputWired`. */
    fun drive(entity: Int, node: Int)
}

fun wireSrcNode(w: Int) = w and 0x3ff
fun wireSrcPort(w: Int) = (w ushr 10) and 3
fun wireDstNode(w: Int) = (w ushr 12) and 0x3ff
fun wireDstPort(w: Int) = (w ushr 22) and 3
private fun pack(sn: Int, sp: Int, dn: Int, dp: Int) = sn or (sp shl 10) or (dn shl 12) or (dp shl 22)

class Graph {
    /** One past the highest node id ever used. Node 0 is never used. */
    var high = 1
    val type = IntArray(MAX_NODES)
    val entity = IntArray(MAX_NODES)
    /** Two settings per node. */
    val param = IntArray(MAX_NODES * 2)
    /** Two words of private state per node. */
    val state = IntArray(MAX_NODES * 2)
    /** Position on the circuit board. */
    val bx = IntArray(MAX_NODES)
    val by = IntArray(MAX_NODES)
    /** Output values visible this tick / being written for next tick, PORTS per node. */
    val outCur = IntArray(MAX_NODES * PORTS)
    val outNext = IntArray(MAX_NODES * PORTS)
    /** Packed wires: srcNode | srcPort << 10 | dstNode << 12 | dstPort << 22. */
    val wires = IntArray(MAX_WIRES)
    var wireCount = 0
    // Adjacency in CSR form, rebuilt only after an edit.
    private val inStart = IntArray(MAX_NODES * PORTS + 1)
    private val inList = IntArray(MAX_WIRES)
    private val outStart = IntArray(MAX_NODES * PORTS + 1)
    private val outList = IntArray(MAX_WIRES)
    var csrStale = true
    private var queue = IntArray(MAX_NODES)
    var nextQueue = IntArray(MAX_NODES)
    var nextLen = 0
    val queued = BooleanArray(MAX_NODES)
    /** Pure logic nodes evaluated in the last step; exposed for tests and the debug overlay. */
    var lastEvals = 0
    /** Bumped on every structural edit so the UI knows to redraw. */
    var version = 0

    /** Schedules a node for evaluation on the next logic tick. */
    fun markDirty(node: Int) {
        if (queued[node] || type[node] <= NodeType.DEVICE) return
        queued[node] = true
        nextQueue[nextLen++] = node
    }

    /** Adds a node. Returns its id, or 0 if the graph is full. */
    fun addNode(t: Int, ent: Int = 0, x: Int = 0, y: Int = 0): Int {
        var id = 0
        for (i in 1 until high) if (type[i] == NodeType.FREE) { id = i; break }
        if (id == 0) {
            if (high >= MAX_NODES) return 0
            id = high++
        }
        type[id] = t
        entity[id] = ent
        param[id * 2] = LOGIC_DEFS[t].param?.initial ?: 0
        param[id * 2 + 1] = 0
        state[id * 2] = 0
        state[id * 2 + 1] = 0
        bx[id] = x
        by[id] = y
        for (p in 0 until PORTS) {
            outCur[id * PORTS + p] = 0
            outNext[id * PORTS + p] = 0
        }
        queued[id] = false
        markDirty(id)
        version++
        return id
    }

    /** Removes a node and every wire attached to it. */
    fun removeNode(id: Int) {
        if (id <= 0 || type[id] == NodeType.FREE) return
        for (i in wireCount - 1 downTo 0) {
            val w = wires[i]
            if (wireSrcNode(w) == id || wireDstNode(w) == id) removeWireAt(i)
        }
        type[id] = NodeType.FREE
        entity[id] = 0
        version++
    }

    fun findWire(sn: Int, sp: Int, dn: Int, dp: Int): Int {
        val w = pack(sn, sp, dn, dp)
        for (i in 0 until wireCount) if (wires[i] == w) return i
        return -1
    }

    /** Connects an output port to an input port. Returns false if it already exists or cannot be made. */
    fun addWire(sn: Int, sp: Int, dn: Int, dp: Int): Boolean {
        if (wireCount >= MAX_WIRES || sn == dn) return false
        if (type[sn] == NodeType.FREE || type[dn] == NodeType.FREE) return false
        if (findWire(sn, sp, dn, dp) != -1) return false
        wires[wireCount++] = pack(sn, sp, dn, dp)
        csrStale = true
        markDirty(dn)
        version++
        return true
    }

    fun removeWireAt(index: Int) {
        if (index < 0 || index >= wireCount) return
        val dn = wireDstNode(wires[index])
        wires[index] = wires[--wireCount]
        csrStale = true
        markDirty(dn)
        version++
    }

    private fun rebuildCsr() {
        val slots = MAX_NODES * PORTS
        // Pass 1: count wires per port, then turn the counts into end offsets.
        inStart.fill(0)
        outStart.fill(0)
        for (i in 0 until wireCount) {
            val w = wires[i]
            inStart[wireDstNode(w) * PORTS + wireDstPort(w)]++
            outStart[wireSrcNode(w) * PORTS + wireSrcPort(w)]++
        }
        var inSum = 0
        var outSum = 0
        for (s in 0 until slots) {
            inSum += inStart[s]
            outSum += outStart[s]
            inStart[s] = inSum
            outStart[s] = outSum
        }
        // Pass 2: place each wire by walking its port's end offset down. When done,
        // every entry has become that port's start offset.
        for (i in 0 until wireCount) {
            val w = wires[i]
            val a = wireDstNode(w) * PORTS + wireDstPort(w)
            val b = wireSrcNode(w) * PORTS + wireSrcPort(w)
            inList[--inStart[a]] = (wireSrcNode(w) shl 2) or wireSrcPort(w)
            outList[--outStart[b]] = (wireDstNode(w) shl 2) or wireDstPort(w)
        }
        inStart[slots] = wireCount
        outStart[slots] = wireCount
        csrStale = false
    }

    /** Whether anything is wired to an input port. */
    fun inputWired(node: Int, port: Int): Boolean {
        if (csrStale) rebuildCsr()
        val s = node * PORTS + port
        return inStart[s + 1] > inStart[s]
    }

    /** Value arriving at an input port: the highest value among everything wired to it, 0 if unwired. */
    fun inputValue(node: Int, port: Int): Int {
        if (csrStale) rebuildCsr()
        val s = node * PORTS + port
        var v = 0
        for (i in inStart[s] until inStart[s + 1]) {
            val src = inList[i]
            v = max(v, outCur[(src shr 2) * PORTS + (src and 3)])
        }
        return v
    }

    private fun evalLogic(n: Int) {
        val o = n * PORTS
        when (type[n]) {
            NodeType.AND -> outNext[o] = if (inputValue(n, 0) > 0 && inputValue(n, 1) > 0) 1 else 0
            NodeType.OR -> outNext[o] = if (inputValue(n, 0) > 0 || inputValue(n, 1) > 0) 1 else 0
            NodeType.NOT -> outNext[o] = if (inputValue(n, 0) > 0) 0 else 1
            NodeType.DELAY -> {
                // state0 = ticks left before the pending value is released, state1 = pending value.
                val v = if (inputValue(n, 0) > 0) 1 else 0
                if (v != state[n * 2 + 1]) {
                    state[n * 2 + 1] = v
                    state[n * 2] = max(1, param[n * 2])
                }
                if (state[n * 2] > 0) {
                    if (--state[n * 2] == 0) outNext[o] = state[n * 2 + 1] else markDirty(n)
                }
            }
            NodeType.TIMER -> {
                val run = !inputWired(n, 0) || inputValue(n, 0) > 0
                if (run) {
                    val period = max(2, param[n * 2])
                    outNext[o] = if (state[n * 2] < period / 2f) 1 else 0
                    state[n * 2] = (state[n * 2] + 1) % period
                    markDirty(n)
                } else {
                    state[n * 2] = 0
                    outNext[o] = 0
                }
            }
        }
    }

    /** Publishes a node's new outputs and schedules whatever is wired to the ones that changed. */
    private fun commit(n: Int) {
        val o = n * PORTS
        for (p in 0 until PORTS) {
            val v = outNext[o + p]
            if (v == outCur[o + p]) continue
            outCur[o + p] = v
            for (i in outStart[o + p] until outStart[o + p + 1]) markDirty(outList[i] shr 2)
        }
    }

    /** Advances the whole graph by one logic tick. Does not allocate. */
    fun step(host: LogicHost) {
        if (csrStale) rebuildCsr()

        // Take this tick's work list; nodes re-queued during evaluation go to the next one.
        val work = nextQueue
        val workLen = nextLen
        nextQueue = queue
        nextLen = 0
        queue = work
        for (i in 0 until workLen) queued[work[i]] = false

        // 1. Devices publish what they sense.
        for (n in 1 until high) if (type[n] == NodeType.DEVICE) host.sense(entity[n], n, outNext, n * PORTS)
        // 2. Logic nodes whose inputs changed compute their next outputs from current values.
        for (i in 0 until workLen) if (type[work[i]] > NodeType.DEVICE) evalLogic(work[i])
        lastEvals = workLen
        // 3. Everything flips over at once.
        for (n in 1 until high) if (type[n] == NodeType.DEVICE) commit(n)
        for (i in 0 until workLen) commit(work[i])
        // 4. Devices act on the values now visible at their inputs.
        for (n in 1 until high) if (type[n] == NodeType.DEVICE) host.drive(entity[n], n)
    }

    /** After loading: adjacency must be rebuilt and every logic node re-evaluated once. */
    fun afterLoad() {
        csrStale = true
        nextLen = 0
        queued.fill(false)
        for (n in 1 until high) if (type[n] > NodeType.DEVICE) {
            queued[n] = true
            nextQueue[nextLen++] = n
        }
        version++
    }
}
