package scrapline.sim

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val TICK_HZ = 20
const val TICK_DT = 1f / TICK_HZ

const val HOTBAR_SIZE = 6
const val INVENTORY_SIZE = 24
const val VITAL_MAX = 1000

/** Day and night lengths in ticks: 14 minutes of light, 4 minutes of dark. */
const val DAY_TICKS = 14 * 60 * TICK_HZ
const val NIGHT_TICKS = 4 * 60 * TICK_HZ
const val CYCLE_TICKS = DAY_TICKS + NIGHT_TICKS
/** Length of the dusk and dawn fades. */
private const val TWILIGHT_TICKS = 30 * TICK_HZ

/** How far the player can reach to gather, use and build, metres. */
const val REACH = 4.5f
private const val RAY_STEP = 0.15f
private const val PLAYER_RADIUS = 0.3f

private const val SWING_TICKS_HAND = 14
private const val SWING_TICKS_TOOL = 5
private const val SWING_CHARGE_COST = 3
private const val DISMANTLE_TICKS = 18
private const val HIT_TICKS = 8
private const val HIT_DAMAGE_HAND = 14
private const val HIT_DAMAGE_TOOL = 34
/** Default detection radius of a proximity sensor, metres. */
const val SENSOR_RANGE = 8
/** The night warning fires this many ticks before dusk. */
private const val DUSK_WARNING_TICKS = 60 * TICK_HZ

/** Survival gauges, each an integer 0..VITAL_MAX. */
class Vitals {
    var health = VITAL_MAX
    var charge = VITAL_MAX
    var warmth = VITAL_MAX
}

object TargetType {
    const val NONE = 0
    const val NODE = 1
    const val ENTITY = 2
    const val GROUND = 3
    const val STRAY = 4
}

/** What the reticle is on. Reused every frame; never reallocated. */
class Target {
    var type = TargetType.NONE
    /** Node index, entity id or stray index. */
    var id = 0
    /** Build cell under the reticle (valid for GROUND and ENTITY). */
    var cx = 0
    var cz = 0
    /** For GROUND: whether the selected kit may be placed on this cell. */
    var buildable = false
}

/** Progress of the held action (gathering a node, dismantling an entity, hitting a stray). */
class Work {
    var type = 0
    var id = 0
    var progress = 0
    var need = 0
    /** Set once a "can't do that" event was sent for this press, to avoid spamming. */
    var warned = false
}

/** Event codes pushed to `Sim.events` as [code, a, b] triples for the view and UI. */
object Ev {
    /** a = item id, b = amount */
    const val GATHER = 1
    /** a = node index */
    const val SWING = 2
    const val NODE_GONE = 3
    /** a = entity id, b = kind */
    const val PLACED = 4
    const val REMOVED = 5
    const val BAG_FULL = 6
    /** a = item id */
    const val USED = 7
    /** a = node kind */
    const val NEED_TOOL = 8
    const val DIED = 9
    /** a = source entity id */
    const val LINK_START = 10
    /** a = source entity id, b = target entity id */
    const val LINKED = 11
    const val UNLINKED = 12
    /** a = reason: 0 not wireable, 1 only receives, 2 only sends, 3 circuit is full */
    const val LINK_BAD = 13
    /** a = turret entity id, b = stray index */
    const val SHOT = 14
    /** a = stray index, b = 1 if a turret did it */
    const val STRAY_DOWN = 15
    const val BITE = 16
    const val DUSK = 17
    /** a = nights survived so far, b = 1 if the player lived through this one */
    const val DAWN = 18
    const val STRAY_HIT = 19
    const val NEED_DEPOSIT = 20
}

/** Whole game state. Everything the view and UI show is read from here. */
class Sim(val seed: Int) {
    /** Fixed ticks elapsed since the world was created. Tick 0 is dawn. */
    var tick = 0
    val world = World(seed)
    val nodes = Nodes(world, seed)
    val entities = Entities()
    val player = Player(world)
    /** Slots 0 until HOTBAR_SIZE are the hotbar, the rest is the bag. */
    val inventory = Container(INVENTORY_SIZE)
    /** Selected hotbar slot. */
    var selected = 0
    val vitals = Vitals()
    val target = Target()
    val work = Work()
    /** Quarter turns applied to the next placed entity. */
    var buildRot = 0
    /** Input state, written by the main loop. */
    var actHeld = false
    var actPressed = false
    var sprinting = false
    var dead = false
    /** Flat [code, a, b] triples; the main loop clears it after the view and UI have read it. */
    val events = IntArray(256 * 3)
    var eventCount = 0

    val rng = Rng(seed xor 0xa11ce)
    /** Item id a drill extracts on each build cell, 0 where there is no deposit. */
    val deposits = ByteArray(WORLD_SIZE * WORLD_SIZE)
    val beltOrder = BeltOrder()
    val graph = Graph()
    val strays = Strays()
    /** Entity chosen as the source of the wire being made with the Linker, or 0. */
    var linkFrom = 0
    /** Nights lived through without dying. */
    var nights = 0
    /** Set if the player died during the current night. */
    var diedTonight = false

    /** Connects Device nodes in the logic graph to the entities they stand for. */
    private val host = object : LogicHost {
        override fun sense(entity: Int, node: Int, out: IntArray, base: Int) {
            val es = entities
            if (es.kind[entity] != EntityKind.SENSOR) {
                out[base] = es.active[entity]
                return
            }
            // Proximity sensor: nearest stray, or the player unless set to strays only.
            val x = es.cx[entity] + 0.5f
            val z = es.cz[entity] + 0.5f
            val range = (if (graph.param[node * 2] != 0) graph.param[node * 2] else SENSOR_RANGE).toFloat()
            var best = range
            for (i in 0 until MAX_STRAYS) {
                if (!strays.alive[i]) continue
                best = min(best, hypot(strays.x[i] - x, strays.z[i] - z))
            }
            if (graph.param[node * 2 + 1] == 0 && !dead) best = min(best, hypot(player.x - x, player.z - z))
            val seen = best < range
            out[base] = if (seen) 1 else 0
            out[base + 1] = if (seen) (best * 10).roundToInt() else 0
            es.active[entity] = if (seen) 1 else 0
        }

        override fun drive(entity: Int, node: Int) {
            val kind = entities.kind[entity]
            if (ENTITY_DEFS[kind].ins.isEmpty()) return
            // Machines and belts run unless a wire says otherwise; turrets and lamps need a signal.
            entities.enabled[entity] = when {
                graph.inputWired(node, 0) -> if (graph.inputValue(node, 0) > 0) 1 else 0
                needsSignal(kind) -> 0
                else -> 1
            }
        }
    }

    init {
        // Starting kit salvaged from the drop pod.
        inventory.add(Item.RATION, 2)
        inventory.add(Item.CELL, 1)
        for (d in nodes.deposits) {
            val r = ceil(d.radius).toInt()
            for (cz in floor(d.z).toInt() - r..floor(d.z).toInt() + r) for (cx in floor(d.x).toInt() - r..floor(d.x).toInt() + r) {
                if (!inBounds(cx, cz)) continue
                if (hypot(cx + 0.5f - d.x, cz + 0.5f - d.z) <= d.radius) deposits[cz * WORLD_SIZE + cx] = d.item.toByte()
            }
        }
    }

    fun emit(code: Int, a: Int = 0, b: Int = 0) {
        if (eventCount >= events.size / 3) return
        val o = eventCount++ * 3
        events[o] = code
        events[o + 1] = a
        events[o + 2] = b
    }

    /** Item id in the selected hotbar slot, or 0. */
    fun selectedItem(): Int = if (inventory.counts[selected] > 0) inventory.ids[selected] else 0

    fun strayDown(stray: Int, byTurret: Boolean) {
        emit(Ev.STRAY_DOWN, stray, if (byTurret) 1 else 0)
        // Wrecks near the player sometimes leave a usable circuit behind.
        val near = hypot(strays.x[stray] - player.x, strays.z[stray] - player.z) < 15f
        if (near && rng.next() < 0.5f && inventory.add(Item.CIRCUIT, 1) == 0) emit(Ev.GATHER, Item.CIRCUIT, 1)
    }

    // ------------------------------------------------------------ day / night

    /** 1 in full daylight, 0 at night, fading through dusk and dawn. */
    fun daylight(): Float {
        val t = tick % CYCLE_TICKS
        if (t < DAY_TICKS) {
            val toDusk = DAY_TICKS - t
            if (toDusk < TWILIGHT_TICKS) return toDusk.toFloat() / TWILIGHT_TICKS
            if (t < TWILIGHT_TICKS) return 0.35f + 0.65f * t / TWILIGHT_TICKS
            return 1f
        }
        val toDawn = CYCLE_TICKS - t
        return if (toDawn < TWILIGHT_TICKS) 0.35f * (1f - toDawn.toFloat() / TWILIGHT_TICKS) else 0f
    }

    /** Position through the whole cycle, 0..1, for placing the sun. */
    fun cyclePhase(): Float = (tick % CYCLE_TICKS).toFloat() / CYCLE_TICKS

    fun isNight(): Boolean = tick % CYCLE_TICKS >= DAY_TICKS

    /** Ticks until the next change between day and night. */
    fun ticksToPhaseChange(): Int {
        val t = tick % CYCLE_TICKS
        return if (t < DAY_TICKS) DAY_TICKS - t else CYCLE_TICKS - t
    }

    // ------------------------------------------------------------ logic helpers

    /** Gives an entity a node in the logic graph if it has none. Returns the node id, or 0 if the graph is full. */
    fun ensureNode(entity: Int): Int {
        val es = entities
        if (es.node[entity] != 0) return es.node[entity]
        val id = graph.addNode(NodeType.DEVICE, entity)
        if (id == 0) return 0
        // Lay new devices out on the circuit board in rows of five.
        graph.bx[id] = ((id - 1) % 5) * 150
        graph.by[id] = ((id - 1) / 5) * 110
        if (es.kind[entity] == EntityKind.SENSOR) graph.param[id * 2] = SENSOR_RANGE
        es.node[entity] = id
        return id
    }

    /** Flips a switch entity. */
    fun toggleSwitch(entity: Int) {
        if (entities.kind[entity] == EntityKind.SWITCH) entities.active[entity] = entities.active[entity] xor 1
    }

    // ------------------------------------------------------------ per frame

    /**
     * Moves the player for one rendered frame, keeps them out of solid things and
     * refreshes what the reticle is on.
     */
    fun stepPlayer(intent: MoveIntent, dt: Float) {
        if (dead) {
            intent.moveX = 0f
            intent.moveY = 0f
            intent.jump = false
        }
        if (vitals.charge <= 0) intent.sprint = false
        sprinting = intent.sprint && (intent.moveX != 0f || intent.moveY != 0f)
        updatePlayer(player, world, intent, dt)
        collide()
        updateTarget()
    }

    /** Pushes the player out of resource nodes and occupied build cells. */
    private fun collide() {
        val p = player
        for (c in 0 until 4) {
            // Sample the four buckets around the player; duplicates are harmless.
            val list = nodes.buckets[bucketIndex(p.x + (if (c and 1 != 0) 1.5f else -1.5f), p.z + (if (c and 2 != 0) 1.5f else -1.5f))]
            for (i in list) {
                if (nodes.hp[i] == 0) continue
                val def = NODE_DEFS[nodes.kind[i]]
                if (p.y > nodes.y[i] + def.height * nodes.scale[i]) continue
                val r = def.radius * nodes.scale[i] + PLAYER_RADIUS
                val dx = p.x - nodes.x[i]
                val dz = p.z - nodes.z[i]
                val d2 = dx * dx + dz * dz
                if (d2 < r * r && d2 > 1e-8f) {
                    val d = sqrt(d2)
                    p.x = nodes.x[i] + dx / d * r
                    p.z = nodes.z[i] + dz / d * r
                }
            }
        }

        val es = entities
        val pcx = floor(p.x).toInt()
        val pcz = floor(p.z).toInt()
        for (cz in pcz - 1..pcz + 1) for (cx in pcx - 1..pcx + 1) {
            val id = es.at(cx, cz)
            if (id == 0) continue
            val def = ENTITY_DEFS[es.kind[id]]
            if (!def.solid || p.y > es.y[id] + def.height) continue
            // Closest point on the cell's square to the player.
            val qx = p.x.coerceIn(cx.toFloat(), cx + 1f)
            val qz = p.z.coerceIn(cz.toFloat(), cz + 1f)
            val dx = p.x - qx
            val dz = p.z - qz
            val d2 = dx * dx + dz * dz
            if (d2 >= PLAYER_RADIUS * PLAYER_RADIUS) continue
            if (d2 > 1e-8f) {
                val d = sqrt(d2)
                p.x = qx + dx / d * PLAYER_RADIUS
                p.z = qz + dz / d * PLAYER_RADIUS
            } else {
                // Centre is inside the cell: leave through the nearest side.
                val l = p.x - cx
                val r = cx + 1 - p.x
                val t = p.z - cz
                val b = cz + 1 - p.z
                val m = min(min(l, r), min(t, b))
                when (m) {
                    l -> p.x = cx - PLAYER_RADIUS
                    r -> p.x = cx + 1 + PLAYER_RADIUS
                    t -> p.z = cz - PLAYER_RADIUS
                    else -> p.z = cz + 1 + PLAYER_RADIUS
                }
            }
        }
    }

    /** Marches a ray from the eye and records the first stray, node, entity or ground cell it meets. */
    fun updateTarget() {
        val p = player
        val t = target
        val cp = cos(p.pitch)
        val dx = -sin(p.yaw) * cp
        val dy = sin(p.pitch)
        val dz = -cos(p.yaw) * cp
        val ox = p.x
        val oy = p.y + EYE_HEIGHT
        val oz = p.z
        val es = entities

        t.type = TargetType.NONE
        t.id = 0
        t.buildable = false

        var px = ox
        var py = oy
        var pz = oz
        var d = RAY_STEP
        while (d <= REACH) {
            px = ox + dx * d
            py = oy + dy * d
            pz = oz + dz * d

            if (strays.count > 0) for (i in 0 until MAX_STRAYS) {
                if (!strays.alive[i]) continue
                val sx = px - strays.x[i]
                val sy = py - strays.y[i]
                val sz = pz - strays.z[i]
                if (sx * sx + sy * sy + sz * sz < 0.75f * 0.75f) {
                    t.type = TargetType.STRAY
                    t.id = i
                    return
                }
            }

            val cx = floor(px).toInt()
            val cz = floor(pz).toInt()
            val id = es.at(cx, cz)
            if (id != 0 && py <= es.y[id] + ENTITY_DEFS[es.kind[id]].height) {
                t.type = TargetType.ENTITY
                t.id = id
                t.cx = cx
                t.cz = cz
                return
            }

            var lastBucket = -1
            for (c in 0 until 4) {
                val b = bucketIndex(px + (if (c and 1 != 0) 1f else -1f), pz + (if (c and 2 != 0) 1f else -1f))
                if (b == lastBucket) continue
                lastBucket = b
                for (i in nodes.buckets[b]) {
                    if (nodes.hp[i] == 0) continue
                    val def = NODE_DEFS[nodes.kind[i]]
                    val s = nodes.scale[i]
                    if (py < nodes.y[i] - 0.2f || py > nodes.y[i] + def.height * s) continue
                    val r = def.radius * s + 0.12f
                    val ndx = px - nodes.x[i]
                    val ndz = pz - nodes.z[i]
                    if (ndx * ndx + ndz * ndz < r * r) {
                        t.type = TargetType.NODE
                        t.id = i
                        return
                    }
                }
            }

            if (py <= world.heightAt(px, pz)) break
            d += RAY_STEP
        }

        // Ground under the ray's end point (or where it went below the surface).
        t.type = TargetType.GROUND
        t.cx = floor(px).toInt()
        t.cz = floor(pz).toInt()
        t.buildable = canBuildAt(t.cx, t.cz, ITEMS[selectedItem()].places)
    }

    /** Whether an entity (of `kind`, if given) may be placed on a build cell. */
    fun canBuildAt(cx: Int, cz: Int, kind: Int = 0): Boolean {
        if (cx < 3 || cz < 3 || cx > WORLD_SIZE - 4 || cz > WORLD_SIZE - 4) return false
        if (entities.at(cx, cz) != 0) return false
        if (kind == EntityKind.DRILL && deposits[cz * WORLD_SIZE + cx].toInt() == 0) return false

        val h0 = world.heightAt(cx.toFloat(), cz.toFloat())
        val h1 = world.heightAt(cx + 1f, cz.toFloat())
        val h2 = world.heightAt(cx.toFloat(), cz + 1f)
        val h3 = world.heightAt(cx + 1f, cz + 1f)
        if (max(max(h0, h1), max(h2, h3)) - min(min(h0, h1), min(h2, h3)) > 0.7f) return false

        val mx = cx + 0.5f
        val mz = cz + 0.5f
        if (abs(player.x - mx) < 0.5f + PLAYER_RADIUS && abs(player.z - mz) < 0.5f + PLAYER_RADIUS) return false

        var lastBucket = -1
        for (c in 0 until 4) {
            val b = bucketIndex(mx + (if (c and 1 != 0) 2f else -2f), mz + (if (c and 2 != 0) 2f else -2f))
            if (b == lastBucket) continue
            lastBucket = b
            for (i in nodes.buckets[b]) {
                if (nodes.hp[i] == 0) continue
                val r = NODE_DEFS[nodes.kind[i]].radius * nodes.scale[i] + 0.6f
                val dx = nodes.x[i] - mx
                val dz = nodes.z[i] - mz
                if (dx * dx + dz * dz < r * r) return false
            }
        }
        return true
    }

    // ------------------------------------------------------------ fixed tick

    /** Advances the fixed-rate simulation by one tick. Must not allocate. */
    fun tick() {
        tick++
        if (dead) {
            actPressed = false
            return
        }
        val wasNight = isNight() || tick % CYCLE_TICKS == 0
        tickActions()
        tickMachines(this)
        if (tick % 2 == 0) graph.step(host)
        tickThreat(wasNight)
        tickVitals()
    }

    /** Runs the strays and turrets, and announces dusk and dawn. */
    private fun tickThreat(wasNight: Boolean) {
        val phase = tick % CYCLE_TICKS
        if (phase == DAY_TICKS - DUSK_WARNING_TICKS) emit(Ev.DUSK)
        if (phase == DAY_TICKS) diedTonight = false
        if (phase == 0 && wasNight && tick > 0) {
            if (!diedTonight) nights++
            emit(Ev.DAWN, nights, if (diedTonight) 0 else 1)
        }
        tickStrays(this, phase >= DAY_TICKS, tick / CYCLE_TICKS)
    }

    private fun tickActions() {
        val inv = inventory
        val def = ITEMS[selectedItem()]
        val pressed = actPressed
        actPressed = false

        // A kit places on the ground; aimed at an existing entity it falls through
        // to the hold-to-pack-up behaviour below, so a misplaced piece is easy to undo.
        if (def.kind == ItemKind.KIT && target.type != TargetType.ENTITY) {
            resetWork()
            if (pressed && target.type == TargetType.GROUND) {
                val cx = target.cx
                val cz = target.cz
                if (canBuildAt(cx, cz, def.places)) {
                    val id = entities.place(def.places, cx, cz, buildRot, world.heightAt(cx + 0.5f, cz + 0.5f))
                    if (id != 0) {
                        if (needsSignal(def.places)) entities.enabled[id] = 0
                        if (ENTITY_DEFS[def.places].autoNode) ensureNode(id)
                        inv.removeFromSlot(selected, 1)
                        emit(Ev.PLACED, id, def.places)
                    }
                } else if (def.places == EntityKind.DRILL && canBuildAt(cx, cz)) {
                    emit(Ev.NEED_DEPOSIT)
                }
            }
            return
        }

        if (def.kind == ItemKind.LINKER) {
            resetWork()
            if (pressed) tickLinker()
            return
        }
        linkFrom = 0

        if (def.kind == ItemKind.CONSUMABLE) {
            resetWork()
            if (pressed) {
                vitals.health = min(VITAL_MAX, vitals.health + def.health)
                vitals.charge = min(VITAL_MAX, vitals.charge + def.charge)
                inv.removeFromSlot(selected, 1)
                emit(Ev.USED, def.id)
            }
            return
        }

        if (!actHeld) {
            resetWork()
            work.warned = false
            return
        }

        val t = target
        val w = work
        if (t.type != w.type || t.id != w.id) {
            w.type = t.type
            w.id = t.id
            w.progress = 0
            w.need = 0
        }

        // The target is refreshed once per frame, but several ticks can run in one
        // frame, so never trust it to still exist.
        when {
            t.type == TargetType.NODE && nodes.hp[t.id] > 0 -> {
                val nd = NODE_DEFS[nodes.kind[t.id]]
                val fits = def.gathers and nd.gatherClass != 0
                if (nd.needsTool && !fits) {
                    warnOnce(Ev.NEED_TOOL, nodes.kind[t.id])
                    w.progress = 0
                    return
                }
                if (inv.space(nd.item) == 0) {
                    warnOnce(Ev.BAG_FULL)
                    w.progress = 0
                    return
                }
                val powered = fits && vitals.charge > 0
                w.need = if (powered) SWING_TICKS_TOOL else SWING_TICKS_HAND
                if (++w.progress >= w.need) {
                    w.progress = 0
                    nodes.hp[t.id]--
                    inv.add(nd.item, 1)
                    if (powered) vitals.charge = max(0, vitals.charge - SWING_CHARGE_COST)
                    emit(Ev.GATHER, nd.item, 1)
                    emit(Ev.SWING, t.id)
                    if (nodes.hp[t.id] == 0) emit(Ev.NODE_GONE, t.id)
                }
            }
            t.type == TargetType.STRAY && strays.alive[t.id] -> {
                w.need = HIT_TICKS
                if (++w.progress >= w.need) {
                    w.progress = 0
                    emit(Ev.STRAY_HIT, t.id)
                    if (damageStray(this, t.id, if (def.kind == ItemKind.TOOL) HIT_DAMAGE_TOOL else HIT_DAMAGE_HAND)) strayDown(t.id, false)
                }
            }
            t.type == TargetType.ENTITY && entities.kind[t.id] != 0 -> {
                w.need = DISMANTLE_TICKS
                if (++w.progress >= w.need) {
                    w.progress = 0
                    dismantle(t.id)
                }
            }
            else -> {
                w.progress = 0
                w.need = 0
            }
        }
    }

    private fun resetWork() {
        work.type = 0
        work.id = 0
        work.progress = 0
        work.need = 0
    }

    private fun warnOnce(code: Int, a: Int = 0) {
        if (work.warned) return
        work.warned = true
        emit(code, a)
    }

    /** Returns an entity to the bag as its kit, emptying its storage and belt into the bag first. */
    private fun dismantle(id: Int) {
        val es = entities
        val kind = es.kind[id]
        val def = ENTITY_DEFS[kind]
        val inv = inventory
        val store = es.containers[id]
        if (store != null) for (i in 0 until store.size) {
            if (store.counts[i] == 0) continue
            val left = inv.add(store.ids[i], store.counts[i])
            store.removeFromSlot(i, store.counts[i] - left)
            if (left > 0) {
                warnOnce(Ev.BAG_FULL)
                return
            }
        }
        if (kind == EntityKind.BELT) for (s in id * BELT_SLOTS until (id + 1) * BELT_SLOTS) {
            if (es.belt[s] == 0) continue
            if (inv.add(es.belt[s], 1) > 0) {
                warnOnce(Ev.BAG_FULL)
                return
            }
            es.belt[s] = 0
        }
        if (inv.space(def.kit) == 0) {
            warnOnce(Ev.BAG_FULL)
            return
        }
        inv.add(def.kit, 1)
        if (es.node[id] != 0) graph.removeNode(es.node[id])
        if (linkFrom == id) linkFrom = 0
        es.remove(id)
        emit(Ev.REMOVED, id, kind)
    }

    /**
     * One press of the Linker. The first press on something that can send a signal
     * picks it as the source; the second press on something that can receive one
     * wires them together, or unwires them if they already were.
     */
    private fun tickLinker() {
        val t = target
        val es = entities
        if (t.type != TargetType.ENTITY) {
            linkFrom = 0
            return
        }
        val def = ENTITY_DEFS[es.kind[t.id]]
        if (linkFrom == 0 || es.kind[linkFrom] == 0) {
            if (def.outs.isEmpty()) {
                emit(Ev.LINK_BAD, if (def.ins.isNotEmpty()) 1 else 0)
                return
            }
            linkFrom = t.id
            emit(Ev.LINK_START, t.id)
            return
        }
        val from = linkFrom
        linkFrom = 0
        if (from == t.id) return
        if (def.ins.isEmpty()) {
            emit(Ev.LINK_BAD, 2)
            return
        }
        val a = ensureNode(from)
        val b = ensureNode(t.id)
        if (a == 0 || b == 0) {
            emit(Ev.LINK_BAD, 3)
            return
        }
        val existing = graph.findWire(a, 0, b, 0)
        when {
            existing >= 0 -> {
                graph.removeWireAt(existing)
                emit(Ev.UNLINKED, from, t.id)
            }
            graph.addWire(a, 0, b, 0) -> emit(Ev.LINKED, from, t.id)
            else -> emit(Ev.LINK_BAD, 3)
        }
    }

    /** True when the player stands within the heat radius of a fire or running machine. */
    fun nearHeat(): Boolean {
        val es = entities
        for (id in 1 until es.high) {
            val def = ENTITY_DEFS[es.kind[id]]
            if (def.heat == 0f || (!def.alwaysHot && es.active[id] == 0)) continue
            val dx = es.cx[id] + 0.5f - player.x
            val dz = es.cz[id] + 0.5f - player.z
            if (dx * dx + dz * dz < def.heat * def.heat) return true
        }
        return false
    }

    private fun tickVitals() {
        val v = vitals
        val night = isNight()

        // Warmth: the desert night is freezing; heat and daylight bring it back.
        if (nearHeat()) v.warmth = min(VITAL_MAX, v.warmth + 5)
        else if (night) {
            if (tick % 3 == 0) v.warmth = max(0, v.warmth - 1)
        } else if (tick % 4 == 0) v.warmth = min(VITAL_MAX, v.warmth + 1)

        // Charge: sprinting spends it; daylight slowly trickles it back.
        if (sprinting) {
            if (tick % 2 == 0) v.charge = max(0, v.charge - 1)
        } else if (!night && tick % 8 == 0) v.charge = min(VITAL_MAX, v.charge + 1)

        // Health: freezing hurts; staying warm slowly heals.
        if (v.warmth == 0) {
            if (tick % 2 == 0) v.health--
        } else if (v.warmth > 400 && v.health < VITAL_MAX && tick % 10 == 0) v.health++

        if (v.health <= 0) {
            v.health = 0
            dead = true
            diedTonight = true
            resetWork()
            emit(Ev.DIED)
        }
    }

    /** Puts the player back at the landing pad after dying. Inventory and base are kept. */
    fun respawn() {
        val p = player
        p.x = WORLD_SIZE / 2f
        p.z = WORLD_SIZE / 2f
        p.y = world.heightAt(p.x, p.z)
        p.vx = 0f
        p.vy = 0f
        p.vz = 0f
        p.onGround = true
        vitals.health = 600
        vitals.charge = max(vitals.charge, 400)
        vitals.warmth = 600
        dead = false
    }
}

fun needsSignal(kind: Int): Boolean = kind == EntityKind.TURRET || kind == EntityKind.LAMP
