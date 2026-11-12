package scrapline.sim

enum class ItemKind { RESOURCE, PART, TOOL, KIT, CONSUMABLE, LINKER }

/** Resource node classes a tool can work on (bitmask). */
const val GATHER_WOOD = 1
const val GATHER_ROCK = 2

/** Item ids. 0 always means "empty slot". */
object Item {
    const val NONE = 0
    const val WOOD = 1
    const val STONE = 2
    const val IRON_ORE = 3
    const val COAL = 4
    const val IRON_INGOT = 5
    const val GEAR = 6
    const val CIRCUIT = 7
    const val AXE = 8
    const val PICK = 9
    const val RATION = 10
    const val CELL = 11
    const val KIT_WORKBENCH = 12
    const val KIT_CRATE = 13
    const val KIT_WALL = 14
    const val KIT_CAMPFIRE = 15
    const val KIT_SMELTER = 16
    const val KIT_DRILL = 17
    const val KIT_BELT = 18
    const val KIT_SENSOR = 19
    const val KIT_TURRET = 20
    const val KIT_LAMP = 21
    const val KIT_SWITCH = 22
    const val LINKER = 23
}

class ItemDef(
    val id: Int,
    val name: String,
    /** Maximum count per slot; 1 for tools. */
    val stack: Int,
    val kind: ItemKind,
    /** For kits: the entity kind this item becomes when placed. */
    val places: Int = 0,
    /** For tools: bitmask of node classes it gathers quickly. */
    val gathers: Int = 0,
    /** Consumable effect in vitals units (0..1000 scale). */
    val health: Int = 0,
    val charge: Int = 0,
)

val ITEMS: Array<ItemDef> = arrayOf(
    ItemDef(Item.NONE, "", 0, ItemKind.RESOURCE),
    ItemDef(Item.WOOD, "Deadwood", 50, ItemKind.RESOURCE),
    ItemDef(Item.STONE, "Stone", 50, ItemKind.RESOURCE),
    ItemDef(Item.IRON_ORE, "Iron Ore", 50, ItemKind.RESOURCE),
    ItemDef(Item.COAL, "Coal", 50, ItemKind.RESOURCE),
    ItemDef(Item.IRON_INGOT, "Iron Ingot", 50, ItemKind.PART),
    ItemDef(Item.GEAR, "Gear", 30, ItemKind.PART),
    ItemDef(Item.CIRCUIT, "Circuit", 30, ItemKind.PART),
    ItemDef(Item.AXE, "Axe", 1, ItemKind.TOOL, gathers = GATHER_WOOD),
    ItemDef(Item.PICK, "Pick", 1, ItemKind.TOOL, gathers = GATHER_ROCK),
    ItemDef(Item.RATION, "Ration Pack", 10, ItemKind.CONSUMABLE, health = 350),
    ItemDef(Item.CELL, "Charge Cell", 10, ItemKind.CONSUMABLE, charge = 500),
    ItemDef(Item.KIT_WORKBENCH, "Workbench Kit", 5, ItemKind.KIT, places = EntityKind.WORKBENCH),
    ItemDef(Item.KIT_CRATE, "Crate Kit", 10, ItemKind.KIT, places = EntityKind.CRATE),
    ItemDef(Item.KIT_WALL, "Wall Kit", 30, ItemKind.KIT, places = EntityKind.WALL),
    ItemDef(Item.KIT_CAMPFIRE, "Campfire Kit", 5, ItemKind.KIT, places = EntityKind.CAMPFIRE),
    ItemDef(Item.KIT_SMELTER, "Smelter Kit", 5, ItemKind.KIT, places = EntityKind.SMELTER),
    ItemDef(Item.KIT_DRILL, "Drill Kit", 5, ItemKind.KIT, places = EntityKind.DRILL),
    ItemDef(Item.KIT_BELT, "Belt Kit", 50, ItemKind.KIT, places = EntityKind.BELT),
    ItemDef(Item.KIT_SENSOR, "Sensor Kit", 10, ItemKind.KIT, places = EntityKind.SENSOR),
    ItemDef(Item.KIT_TURRET, "Turret Kit", 5, ItemKind.KIT, places = EntityKind.TURRET),
    ItemDef(Item.KIT_LAMP, "Lamp Kit", 10, ItemKind.KIT, places = EntityKind.LAMP),
    ItemDef(Item.KIT_SWITCH, "Switch Kit", 10, ItemKind.KIT, places = EntityKind.SWITCH),
    ItemDef(Item.LINKER, "Linker", 1, ItemKind.LINKER),
)

fun stackMax(id: Int): Int = ITEMS[id].stack
