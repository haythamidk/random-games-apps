package scrapline.sim

/** Placeable things. Ids match `ItemDef.places`. 0 means "no entity". */
object EntityKind {
    const val NONE = 0
    const val WORKBENCH = 1
    const val CRATE = 2
    const val WALL = 3
    const val CAMPFIRE = 4
    const val SMELTER = 5
    const val DRILL = 6
    const val BELT = 7
    const val SENSOR = 8
    const val TURRET = 9
    const val LAMP = 10
    const val SWITCH = 11
    const val COUNT = 12
}

enum class EntityUse { NONE, CRAFT, OPEN, TOGGLE }

class EntityDef(
    val name: String,
    /** The kit item that places this entity and that dismantling returns. */
    val kit: Int,
    /** Height of the targetable volume in metres. */
    val height: Float,
    /** Whether the player collides with it. */
    val solid: Boolean,
    /** Slots of storage, 0 for none. */
    val storage: Int = 0,
    /** Labels for the storage slots, shown in the machine panel. */
    val slotNames: Array<String>? = null,
    /** What the context button does when aiming at it. */
    val use: EntityUse = EntityUse.NONE,
    /** Radius in metres within which it restores Warmth, 0 for none. */
    val heat: Float = 0f,
    /** True if it is warm whenever it exists; otherwise only while running. */
    val alwaysHot: Boolean = false,
    /** Logic input and output port names. Entities with any ports can be wired. */
    val ins: Array<String> = emptyArray(),
    val outs: Array<String> = emptyArray(),
    /** True if it gets a logic node as soon as it is placed (so it shows on the circuit board). */
    val autoNode: Boolean = false,
)

val ENTITY_DEFS = arrayOf(
    EntityDef("", 0, 0f, false),
    EntityDef("Workbench", Item.KIT_WORKBENCH, 1.0f, true, use = EntityUse.CRAFT),
    EntityDef("Crate", Item.KIT_CRATE, 0.8f, true, storage = 16, use = EntityUse.OPEN),
    EntityDef("Wall", Item.KIT_WALL, 2.4f, true),
    EntityDef("Campfire", Item.KIT_CAMPFIRE, 0.5f, false, heat = 6f, alwaysHot = true),
    EntityDef("Smelter", Item.KIT_SMELTER, 1.5f, true, storage = 3, slotNames = arrayOf("Ore", "Coal", "Out"),
        use = EntityUse.OPEN, heat = 5f, ins = arrayOf("run"), outs = arrayOf("busy")),
    EntityDef("Drill", Item.KIT_DRILL, 1.3f, true, storage = 1, slotNames = arrayOf("Out"),
        use = EntityUse.OPEN, ins = arrayOf("run"), outs = arrayOf("busy")),
    EntityDef("Belt", Item.KIT_BELT, 0.45f, false, ins = arrayOf("run"), outs = arrayOf("moving")),
    EntityDef("Sensor", Item.KIT_SENSOR, 1.5f, false, outs = arrayOf("seen", "range"), autoNode = true),
    EntityDef("Turret", Item.KIT_TURRET, 1.4f, true, ins = arrayOf("fire"), outs = arrayOf("firing"), autoNode = true),
    EntityDef("Lamp", Item.KIT_LAMP, 2.3f, false, ins = arrayOf("on"), autoNode = true),
    EntityDef("Switch", Item.KIT_SWITCH, 1.1f, false, use = EntityUse.TOGGLE, outs = arrayOf("on"), autoNode = true),
)

const val MAX_ENTITIES = 2048
/** Item slots along one belt tile. Slot 0 is the entry end, the last slot the exit end. */
const val BELT_SLOTS = 4

/** Unit step on the build grid for each quarter turn: 0 = -Z, 1 = -X, 2 = +Z, 3 = +X. */
val DIR_X = intArrayOf(0, -1, 0, 1)
val DIR_Z = intArrayOf(-1, 0, 1, 0)

fun inBounds(cx: Int, cz: Int): Boolean = cx >= 0 && cz >= 0 && cx < WORLD_SIZE && cz < WORLD_SIZE

/**
 * Placed entities, struct-of-arrays indexed by entity id (1..MAX_ENTITIES-1).
 * Every entity occupies exactly one 1 m build cell.
 */
class Entities {
    /** One past the highest id ever used. */
    var high = 1
    val kind = IntArray(MAX_ENTITIES)
    val cx = IntArray(MAX_ENTITIES)
    val cz = IntArray(MAX_ENTITIES)
    /** Quarter turns, 0..3. The entity faces DIR_X/DIR_Z[rot]. */
    val rot = IntArray(MAX_ENTITIES)
    /** Ground height under the entity. */
    val y = FloatArray(MAX_ENTITIES)
    val containers = arrayOfNulls<Container>(MAX_ENTITIES)
    /** Entity id occupying each build cell (0 = empty), index = cz * WORLD_SIZE + cx. */
    val grid = ShortArray(WORLD_SIZE * WORLD_SIZE)

    /** Work done on the current item, ticks. */
    val progress = IntArray(MAX_ENTITIES)
    /** Burn time left, ticks. */
    val fuel = IntArray(MAX_ENTITIES)
    /** Driven by the logic graph: 1 = allowed to run / fire / light. */
    val enabled = IntArray(MAX_ENTITIES)
    /** 1 while the entity did work this tick (running, moving, firing, lit, switched on). */
    val active = IntArray(MAX_ENTITIES)
    /** Logic node id for this entity, 0 if it has none. */
    val node = IntArray(MAX_ENTITIES)
    /** Item id in each belt slot, index = id * BELT_SLOTS + slot. */
    val belt = IntArray(MAX_ENTITIES * BELT_SLOTS)
    /** 1 for belt slots whose item arrived in the last belt step (for smooth drawing). */
    val beltMoved = IntArray(MAX_ENTITIES * BELT_SLOTS)
    /** Bumped whenever an entity is placed or removed; lets caches know to rebuild. */
    var version = 0

    fun at(cx: Int, cz: Int): Int = if (inBounds(cx, cz)) grid[cz * WORLD_SIZE + cx].toInt() else 0

    /** The entity in the cell an entity faces, or 0. */
    fun inFront(id: Int): Int = at(cx[id] + DIR_X[rot[id]], cz[id] + DIR_Z[rot[id]])

    /** Places an entity on an empty cell. Returns its id, or 0 if the cell is taken or the table is full. */
    fun place(k: Int, x: Int, z: Int, r: Int, groundY: Float): Int {
        if (!inBounds(x, z) || grid[z * WORLD_SIZE + x].toInt() != 0) return 0
        var id = 0
        for (i in 1 until high) if (kind[i] == 0) { id = i; break }
        if (id == 0) {
            if (high >= MAX_ENTITIES) return 0
            id = high++
        }
        kind[id] = k
        cx[id] = x
        cz[id] = z
        rot[id] = r and 3
        y[id] = groundY
        progress[id] = 0
        fuel[id] = 0
        enabled[id] = 1
        active[id] = 0
        node[id] = 0
        belt.fill(0, id * BELT_SLOTS, (id + 1) * BELT_SLOTS)
        beltMoved.fill(0, id * BELT_SLOTS, (id + 1) * BELT_SLOTS)
        val storage = ENTITY_DEFS[k].storage
        containers[id] = if (storage > 0) Container(storage) else null
        grid[z * WORLD_SIZE + x] = id.toShort()
        version++
        return id
    }

    fun remove(id: Int) {
        if (id <= 0 || kind[id] == 0) return
        grid[cz[id] * WORLD_SIZE + cx[id]] = 0
        kind[id] = 0
        containers[id] = null
        node[id] = 0
        active[id] = 0
        version++
    }
}
