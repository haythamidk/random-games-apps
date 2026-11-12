package scrapline.sim

import kotlin.math.roundToInt

/** Ticks to smelt one ore, and how long one coal burns (four ingots' worth). */
const val SMELT_TICKS = 100
const val FUEL_TICKS = 400
/** Ticks for a drill to extract one item. */
const val DRILL_TICKS = 40
/** Belts advance one slot every this many ticks (a tile is BELT_SLOTS slots long). */
const val BELT_PERIOD = 3

const val SMELTER_ORE = 0
const val SMELTER_COAL = 1
const val SMELTER_OUT = 2

/** Cached belt processing order (downstream belts first), rebuilt only when entities change. */
class BeltOrder {
    val ids = IntArray(MAX_ENTITIES)
    var count = 0
    var version = -1
    private val visited = BooleanArray(MAX_ENTITIES)
    private val path = IntArray(MAX_ENTITIES)

    /**
     * Orders belts so each one is stepped after the belt it feeds. A full line then
     * advances in a single pass, and an item can never move two slots in one step.
     */
    fun rebuild(es: Entities) {
        visited.fill(false)
        count = 0
        for (id in 1 until es.high) {
            if (es.kind[id] != EntityKind.BELT || visited[id]) continue
            var n = 0
            var cur = id
            while (cur != 0 && es.kind[cur] == EntityKind.BELT && !visited[cur]) {
                visited[cur] = true
                path[n++] = cur
                cur = es.inFront(cur)
            }
            while (n > 0) ids[count++] = path[--n]
        }
        version = es.version
    }
}

/** Offers one item to whatever an entity faces. Returns true if it was taken. */
private fun pushForward(es: Entities, from: Int, item: Int): Boolean {
    val to = es.inFront(from)
    if (to == 0) return false
    if (es.kind[to] == EntityKind.BELT) {
        // Never push into a belt that would push straight back.
        if (es.inFront(to) == from || es.belt[to * BELT_SLOTS] != 0) return false
        es.belt[to * BELT_SLOTS] = item
        es.beltMoved[to * BELT_SLOTS] = 1
        return true
    }
    return acceptItem(es, to, item)
}

/** Puts one item into a machine or crate if it takes that item and has room. */
fun acceptItem(es: Entities, id: Int, item: Int): Boolean {
    val c = es.containers[id] ?: return false
    when (es.kind[id]) {
        EntityKind.SMELTER -> {
            val slot = when (item) {
                Item.IRON_ORE -> SMELTER_ORE
                Item.COAL -> SMELTER_COAL
                else -> return false
            }
            if (c.counts[slot] == 0) {
                c.ids[slot] = item
                c.counts[slot] = 1
                return true
            }
            if (c.ids[slot] != item || c.counts[slot] >= stackMax(item)) return false
            c.counts[slot]++
            return true
        }
        EntityKind.CRATE -> return c.add(item, 1) == 0
    }
    return false
}

private fun tickSmelter(es: Entities, id: Int) {
    val c = es.containers[id]!!
    val hasOre = c.counts[SMELTER_ORE] > 0 && c.ids[SMELTER_ORE] == Item.IRON_ORE
    val outRoom = c.counts[SMELTER_OUT] == 0 ||
        (c.ids[SMELTER_OUT] == Item.IRON_INGOT && c.counts[SMELTER_OUT] < stackMax(Item.IRON_INGOT))
    var running = false
    if (es.enabled[id] != 0 && hasOre && outRoom) {
        if (es.fuel[id] == 0 && c.counts[SMELTER_COAL] > 0 && c.ids[SMELTER_COAL] == Item.COAL) {
            c.removeFromSlot(SMELTER_COAL, 1)
            es.fuel[id] = FUEL_TICKS
        }
        if (es.fuel[id] > 0) {
            running = true
            es.fuel[id]--
            if (++es.progress[id] >= SMELT_TICKS) {
                es.progress[id] = 0
                c.removeFromSlot(SMELTER_ORE, 1)
                c.ids[SMELTER_OUT] = Item.IRON_INGOT
                c.counts[SMELTER_OUT]++
            }
        }
    }
    if (!hasOre) es.progress[id] = 0
    es.active[id] = if (running) 1 else 0
    if (c.counts[SMELTER_OUT] > 0 && pushForward(es, id, c.ids[SMELTER_OUT])) c.removeFromSlot(SMELTER_OUT, 1)
}

private fun tickDrill(sim: Sim, id: Int) {
    val es = sim.entities
    val c = es.containers[id]!!
    val item = sim.deposits[es.cz[id] * WORLD_SIZE + es.cx[id]].toInt()
    val room = c.counts[0] == 0 || (c.ids[0] == item && c.counts[0] < stackMax(item))
    var running = false
    if (es.enabled[id] != 0 && item != 0 && room) {
        running = true
        if (++es.progress[id] >= DRILL_TICKS) {
            es.progress[id] = 0
            c.ids[0] = item
            c.counts[0]++
        }
    }
    es.active[id] = if (running) 1 else 0
    if (c.counts[0] > 0 && pushForward(es, id, c.ids[0])) c.removeFromSlot(0, 1)
}

private fun stepBelt(es: Entities, id: Int) {
    val base = id * BELT_SLOTS
    val last = base + BELT_SLOTS - 1
    es.beltMoved.fill(0, base, base + BELT_SLOTS)
    if (es.enabled[id] == 0) {
        es.active[id] = 0
        return
    }
    var moved = false
    if (es.belt[last] != 0 && pushForward(es, id, es.belt[last])) {
        es.belt[last] = 0
        moved = true
    }
    for (s in last - 1 downTo base) {
        if (es.belt[s] != 0 && es.belt[s + 1] == 0) {
            es.belt[s + 1] = es.belt[s]
            es.belt[s] = 0
            es.beltMoved[s + 1] = 1
            moved = true
        }
    }
    es.active[id] = if (moved) 1 else 0
}

/** Runs every machine for one tick and, every BELT_PERIOD ticks, advances the belts. */
fun tickMachines(sim: Sim) {
    val es = sim.entities
    for (id in 1 until es.high) {
        when (es.kind[id]) {
            EntityKind.SMELTER -> tickSmelter(es, id)
            EntityKind.DRILL -> tickDrill(sim, id)
            EntityKind.LAMP -> es.active[id] = es.enabled[id]
        }
    }
    if (sim.tick % BELT_PERIOD == 0) {
        val order = sim.beltOrder
        if (order.version != es.version) order.rebuild(es)
        for (i in 0 until order.count) stepBelt(es, order.ids[i])
    }
}

/** Places an item on a belt's entry slot by hand. Returns true if there was room. */
fun loadBelt(es: Entities, id: Int, item: Int): Boolean {
    if (es.kind[id] != EntityKind.BELT || es.belt[id * BELT_SLOTS] != 0) return false
    es.belt[id * BELT_SLOTS] = item
    return true
}

/** Short status line for the machine panel. */
fun machineStatus(sim: Sim, id: Int): String {
    val es = sim.entities
    val kind = es.kind[id]
    val c = es.containers[id]
    if (es.enabled[id] == 0 && ENTITY_DEFS[kind].ins.isNotEmpty()) return "Switched off by its circuit"
    if (kind == EntityKind.SMELTER && c != null) {
        if (es.active[id] != 0) return "Smelting · ${(es.progress[id] * 100f / SMELT_TICKS).roundToInt()}%"
        if (c.counts[SMELTER_ORE] == 0) return "Needs iron ore"
        if (es.fuel[id] == 0 && c.counts[SMELTER_COAL] == 0) return "Needs coal"
        return "Output is full"
    }
    if (kind == EntityKind.DRILL && c != null) {
        if (sim.deposits[es.cz[id] * WORLD_SIZE + es.cx[id]].toInt() == 0) return "Not on a deposit"
        return if (es.active[id] != 0) "Drilling · ${(es.progress[id] * 100f / DRILL_TICKS).roundToInt()}%" else "Output is full"
    }
    return ""
}
