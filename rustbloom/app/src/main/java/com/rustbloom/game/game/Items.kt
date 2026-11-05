package com.rustbloom.game.game

enum class Category { RESOURCE, FOOD, MEDICAL, TOOL, WEAPON, AMMO, ARMOR, KEY }

enum class ToolKind { NONE, AXE, PICK, SPEAR, BOW, TORCH, WATERSKIN, ROD }

/**
 * Every item in the game. Food values: hunger, thirst, health restored when consumed.
 * Tools/weapons have [durability] uses and [damage] per hit. Armor gives [armor] (0..1 damage reduction) and [warmth].
 */
enum class Item(
    val displayName: String,
    val category: Category,
    val maxStack: Int,
    val color: Int,
    val desc: String,
    val hunger: Float = 0f,
    val thirst: Float = 0f,
    val heal: Float = 0f,
    val tool: ToolKind = ToolKind.NONE,
    val tier: Int = 0,
    val durability: Int = 0,
    val damage: Float = 0f,
    val armor: Float = 0f,
    val warmth: Float = 0f,
) {
    WOOD("Wood", Category.RESOURCE, 99, 0xff8a6642.toInt(), "Sturdy timber from felled trees."),
    STONE("Stone", Category.RESOURCE, 99, 0xff8d8d8d.toInt(), "Rough stone for tools and foundations."),
    FIBER("Plant Fiber", Category.RESOURCE, 99, 0xff9fb35a.toInt(), "Tough reeds twisted into cord."),
    RESIN("Resin", Category.RESOURCE, 50, 0xffd59a2c.toInt(), "Sticky sap from needle pines. Burns well."),
    HIDE("Hide", Category.RESOURCE, 50, 0xffa0744e.toInt(), "Animal hide, for leather and warm clothing."),
    SCRAP("Scrap Metal", Category.RESOURCE, 99, 0xff9a8270.toInt(), "Rusted plating salvaged from the old world."),
    WIRE("Copper Wire", Category.RESOURCE, 99, 0xffd2803c.toInt(), "Conductive filament torn from machines."),
    ALLOY("Lattice Alloy", Category.RESOURCE, 50, 0xffa7c4cf.toInt(), "Light, unbreakable alloy from Rustborn frames."),
    CORE("Pulse Core", Category.RESOURCE, 20, 0xff4fe3d2.toInt(), "A humming energy core from a machine's heart."),
    LENS("Optic Lens", Category.RESOURCE, 20, 0xff8fd8ff.toInt(), "A machine eye. Still focuses light."),
    CRYO_SHARD("Cryo Shard", Category.RESOURCE, 30, 0xffbfeaff.toInt(), "Frozen crystal that never melts."),
    HERB("Bloodmoss", Category.RESOURCE, 30, 0xffc23b4a.toInt(), "Medicinal moss from the Sunken Fen."),
    FUR("Thick Fur", Category.RESOURCE, 30, 0xff8a7058.toInt(), "Dense fur from wild animals. Very warm."),
    FEATHER("Feather", Category.RESOURCE, 50, 0xffe8e4d8.toInt(), "Fletching for straighter arrows."),
    MUSHROOM("Fen Cap", Category.FOOD, 20, 0xff7fc4b4.toInt(), "Glowing mushroom. Edible, mostly.", hunger = 7f, thirst = 2f),

    BERRIES("Redberries", Category.FOOD, 30, 0xffc8243c.toInt(), "Sweet and tart.", hunger = 7f, thirst = 4f),
    THORNFRUIT("Thornfruit", Category.FOOD, 20, 0xffe3893a.toInt(), "Juicy desert fruit.", hunger = 5f, thirst = 16f),
    RAW_MEAT("Raw Meat", Category.FOOD, 20, 0xffd0686a.toInt(), "Should really be cooked.", hunger = 10f, heal = -6f),
    COOKED_MEAT("Roast Meat", Category.FOOD, 20, 0xff9e5a32.toInt(), "Hot and filling.", hunger = 32f, heal = 4f),
    STEW("Hunter's Stew", Category.FOOD, 10, 0xffb7743a.toInt(), "A hearty meal for long journeys.", hunger = 55f, thirst = 25f, heal = 20f),
    RAW_FISH("Raw Fish", Category.FOOD, 20, 0xffa8c0c8.toInt(), "Slippery. Better grilled.", hunger = 8f, thirst = 2f, heal = -3f),
    COOKED_FISH("Grilled Fish", Category.FOOD, 20, 0xffd09a5a.toInt(), "Flaky and light.", hunger = 26f, thirst = 4f, heal = 6f),

    BANDAGE("Bandage", Category.MEDICAL, 10, 0xffe8e2d0.toInt(), "Stops bleeding. Restores health.", heal = 22f),
    SALVE("Herbal Salve", Category.MEDICAL, 10, 0xffd04a5a.toInt(), "Potent healing paste.", heal = 50f),

    STONE_AXE("Stone Axe", Category.TOOL, 1, 0xffa0a0a0.toInt(), "Chops trees faster.", tool = ToolKind.AXE, tier = 1, durability = 120, damage = 9f),
    STONE_PICK("Stone Pickaxe", Category.TOOL, 1, 0xff9a9a9a.toInt(), "Breaks rock and scrap.", tool = ToolKind.PICK, tier = 1, durability = 120, damage = 8f),
    SCRAP_AXE("Scrap Axe", Category.TOOL, 1, 0xffb08a6a.toInt(), "A heavy metal axe.", tool = ToolKind.AXE, tier = 2, durability = 300, damage = 14f),
    SCRAP_PICK("Scrap Pickaxe", Category.TOOL, 1, 0xffaa8a70.toInt(), "Mines faster and yields more.", tool = ToolKind.PICK, tier = 2, durability = 300, damage = 12f),
    TORCH("Torch", Category.TOOL, 1, 0xffffb24a.toInt(), "Light and warmth in the dark.", tool = ToolKind.TORCH, durability = 400, damage = 6f, warmth = 6f),
    WATERSKIN("Waterskin", Category.TOOL, 1, 0xff6aa8d8.toInt(), "Holds 5 drinks. Refill at any water.", tool = ToolKind.WATERSKIN, durability = 5),
    FISHING_ROD("Fishing Rod", Category.TOOL, 1, 0xffb89a6a.toInt(), "Cast into water, wait for a bite, then reel in.", tool = ToolKind.ROD, durability = 150, damage = 3f),

    WOOD_SPEAR("Wooden Spear", Category.WEAPON, 1, 0xffb08a5a.toInt(), "Sharpened hardwood.", tool = ToolKind.SPEAR, tier = 1, durability = 100, damage = 16f),
    SCRAP_SPEAR("Scrap Spear", Category.WEAPON, 1, 0xffb59276.toInt(), "Barbed metal tip.", tool = ToolKind.SPEAR, tier = 2, durability = 250, damage = 28f),
    ALLOY_SPEAR("Lattice Lance", Category.WEAPON, 1, 0xff9fe6ff.toInt(), "Humming alloy blade. Tears machines apart.", tool = ToolKind.SPEAR, tier = 3, durability = 600, damage = 46f),
    HUNTING_BOW("Hunting Bow", Category.WEAPON, 1, 0xff9c7444.toInt(), "Hold to draw, release to fire.", tool = ToolKind.BOW, tier = 1, durability = 200, damage = 24f),
    ALLOY_BOW("Lattice Bow", Category.WEAPON, 1, 0xff8ad7e8.toInt(), "Fast draw, heavy punch.", tool = ToolKind.BOW, tier = 3, durability = 600, damage = 44f),

    ARROW("Arrow", Category.AMMO, 50, 0xffcfb28a.toInt(), "Simple stone-tipped arrow.", damage = 1f),
    FIRE_ARROW("Fire Arrow", Category.AMMO, 30, 0xffff7a2c.toInt(), "Burns targets over time.", damage = 1.2f),
    SHOCK_ARROW("Shock Arrow", Category.AMMO, 30, 0xff6fd8ff.toInt(), "Stuns machines and deals bonus damage to them.", damage = 1.4f),

    HIDE_COAT("Hide Coat", Category.ARMOR, 1, 0xff9b6b44.toInt(), "Warm leather coat.", armor = 0.12f, warmth = 12f),
    FUR_COAT("Fur Coat", Category.ARMOR, 1, 0xff8a7058.toInt(), "Heavy fur. Laughs at blizzards.", armor = 0.16f, warmth = 20f),
    SCRAP_PLATE("Scrap Plating", Category.ARMOR, 1, 0xff8e8478.toInt(), "Heavy riveted armor.", armor = 0.28f, warmth = 3f),
    FROST_CLOAK("Frost Cloak", Category.ARMOR, 1, 0xffc9ecff.toInt(), "Lined with cryo shards that hold heat.", armor = 0.15f, warmth = 26f),
    ALLOY_HARNESS("Lattice Harness", Category.ARMOR, 1, 0xff9fd0e0.toInt(), "Woven alloy armor.", armor = 0.42f, warmth = 8f),

    SIGNAL_KEY("Signal Key", Category.KEY, 5, 0xff4fe3a0.toInt(), "Tuned to awaken a Spire."),
    OVERLOAD_CORE("Overload Core", Category.KEY, 1, 0xffff4f6a.toInt(), "Unstable. Built to silence the Crown."),
    ;

    val isConsumable get() = category == Category.FOOD || category == Category.MEDICAL
    val hasDurability get() = durability > 0
    val shortName: String get() = displayName.split(" ").let { if (it.size > 1) it[0].take(1) + it[1].take(1) else displayName.take(2) }.uppercase()
}

class ItemStack(val item: Item, var count: Int, var durability: Int = item.durability) {
    fun copy() = ItemStack(item, count, durability)
}

/** 6 hotbar slots followed by backpack slots. */
class Inventory(val capacity: Int = 42) {
    val slots = arrayOfNulls<ItemStack>(capacity)
    /** Usable slots (Pack Mule unlocks the rest). */
    var size = 36
    var armor: ItemStack? = null

    fun count(item: Item): Int {
        var c = 0
        for (i in 0 until capacity) { val s = slots[i]; if (s != null && s.item == item) c += s.count }
        return c
    }

    fun has(item: Item, n: Int) = count(item) >= n

    /** Adds items; returns how many could not fit. */
    fun add(item: Item, n: Int, durability: Int = item.durability): Int {
        var left = n
        if (item.maxStack > 1) {
            for (s in slots) {
                if (left == 0) break
                if (s != null && s.item == item && s.count < item.maxStack) {
                    val take = minOf(left, item.maxStack - s.count)
                    s.count += take
                    left -= take
                }
            }
        }
        // prefer hotbar for tools/weapons, backpack for resources
        val order = if (item.category == Category.TOOL || item.category == Category.WEAPON || item.category == Category.AMMO) (0 until size) else ((6 until size) + (0 until 6))
        for (i in order) {
            if (left == 0) break
            if (slots[i] == null) {
                val take = minOf(left, item.maxStack)
                slots[i] = ItemStack(item, take, durability)
                left -= take
            }
        }
        return left
    }

    fun remove(item: Item, n: Int): Boolean {
        if (!has(item, n)) return false
        var left = n
        // take from backpack first, then hotbar
        for (i in (capacity - 1) downTo 0) {
            val s = slots[i] ?: continue
            if (s.item != item) continue
            val take = minOf(left, s.count)
            s.count -= take
            left -= take
            if (s.count <= 0) slots[i] = null
            if (left == 0) break
        }
        return true
    }

    fun swap(a: Int, b: Int) {
        val t = slots[a]; slots[a] = slots[b]; slots[b] = t
    }

    fun freeSlots(): Int = (0 until size).count { slots[it] == null }

    fun clear() { for (i in slots.indices) slots[i] = null; armor = null }
}

enum class Station(val displayName: String) { HAND("By Hand"), CAMPFIRE("Campfire"), WORKBENCH("Workbench"), TINKER("Tinker Bench") }

class Recipe(val output: Item, val amount: Int, val station: Station, val inputs: List<Pair<Item, Int>>, val requiresQuest: String? = null)

object Recipes {
    private fun r(out: Item, amount: Int, station: Station, vararg inputs: Pair<Item, Int>, quest: String? = null) =
        Recipe(out, amount, station, inputs.toList(), quest)

    val all = listOf(
        r(Item.STONE_AXE, 1, Station.HAND, Item.WOOD to 3, Item.STONE to 2, Item.FIBER to 2),
        r(Item.STONE_PICK, 1, Station.HAND, Item.WOOD to 3, Item.STONE to 3, Item.FIBER to 2),
        r(Item.WOOD_SPEAR, 1, Station.HAND, Item.WOOD to 4, Item.STONE to 1, Item.FIBER to 2),
        r(Item.TORCH, 1, Station.HAND, Item.WOOD to 2, Item.FIBER to 2),
        r(Item.BANDAGE, 1, Station.HAND, Item.FIBER to 4),
        r(Item.ARROW, 5, Station.HAND, Item.WOOD to 2, Item.STONE to 1, Item.FIBER to 1),
        r(Item.ARROW, 10, Station.HAND, Item.WOOD to 3, Item.STONE to 1, Item.FEATHER to 2),
        r(Item.FISHING_ROD, 1, Station.HAND, Item.WOOD to 3, Item.FIBER to 5),

        r(Item.COOKED_MEAT, 1, Station.CAMPFIRE, Item.RAW_MEAT to 1),
        r(Item.COOKED_FISH, 1, Station.CAMPFIRE, Item.RAW_FISH to 1),
        r(Item.STEW, 1, Station.CAMPFIRE, Item.RAW_MEAT to 2, Item.BERRIES to 3, Item.MUSHROOM to 2),

        r(Item.HUNTING_BOW, 1, Station.WORKBENCH, Item.WOOD to 5, Item.FIBER to 4, Item.HIDE to 1),
        r(Item.WATERSKIN, 1, Station.WORKBENCH, Item.HIDE to 2, Item.FIBER to 2),
        r(Item.HIDE_COAT, 1, Station.WORKBENCH, Item.HIDE to 5, Item.FIBER to 4),
        r(Item.FUR_COAT, 1, Station.WORKBENCH, Item.FUR to 5, Item.HIDE to 2, Item.FIBER to 3),
        r(Item.SCRAP_AXE, 1, Station.WORKBENCH, Item.WOOD to 3, Item.SCRAP to 4, Item.WIRE to 1),
        r(Item.SCRAP_PICK, 1, Station.WORKBENCH, Item.WOOD to 3, Item.SCRAP to 4, Item.WIRE to 1),
        r(Item.SCRAP_SPEAR, 1, Station.WORKBENCH, Item.WOOD to 4, Item.SCRAP to 5, Item.WIRE to 2),
        r(Item.FIRE_ARROW, 5, Station.WORKBENCH, Item.WOOD to 2, Item.STONE to 1, Item.FIBER to 1, Item.RESIN to 1),
        r(Item.SALVE, 1, Station.WORKBENCH, Item.HERB to 3, Item.FIBER to 2),
        r(Item.SCRAP_PLATE, 1, Station.WORKBENCH, Item.SCRAP to 8, Item.WIRE to 4, Item.HIDE to 2),
        r(Item.SIGNAL_KEY, 1, Station.WORKBENCH, Item.WIRE to 4, Item.ALLOY to 2, Item.LENS to 1),

        r(Item.SHOCK_ARROW, 5, Station.TINKER, Item.WOOD to 2, Item.WIRE to 2, Item.ALLOY to 1),
        r(Item.ALLOY_SPEAR, 1, Station.TINKER, Item.WOOD to 2, Item.ALLOY to 6, Item.WIRE to 3, Item.CORE to 1),
        r(Item.ALLOY_BOW, 1, Station.TINKER, Item.WOOD to 3, Item.ALLOY to 5, Item.WIRE to 4, Item.CORE to 1),
        r(Item.FROST_CLOAK, 1, Station.TINKER, Item.HIDE to 6, Item.CRYO_SHARD to 3, Item.FIBER to 4),
        r(Item.ALLOY_HARNESS, 1, Station.TINKER, Item.ALLOY to 10, Item.WIRE to 6, Item.CORE to 2, Item.HIDE to 3),
        r(Item.OVERLOAD_CORE, 1, Station.TINKER, Item.CORE to 4, Item.CRYO_SHARD to 4, Item.ALLOY to 6, Item.LENS to 2),
    )

    fun canCraft(r: Recipe, inv: Inventory): Boolean = r.inputs.all { (it, n) -> inv.has(it, n) }
}

enum class BuildType(
    val displayName: String,
    val cost: List<Pair<Item, Int>>,
    val desc: String,
    val grid: Boolean,
    val sx: Float, val sy: Float, val sz: Float,
    val solid: Boolean = true,
) {
    FOUNDATION("Foundation", listOf(Item.WOOD to 6, Item.STONE to 4), "A 3x3 floor tile. Snaps to grid.", true, 3f, 0.4f, 3f),
    WALL("Wall", listOf(Item.WOOD to 6), "Snaps to foundation edges.", true, 3f, 3f, 0.25f),
    DOORWAY("Doorway", listOf(Item.WOOD to 5), "A wall with an opening.", true, 3f, 3f, 0.25f, solid = false),
    ROOF("Roof", listOf(Item.WOOD to 5, Item.FIBER to 4), "Shelter from rain and cold.", true, 3f, 0.25f, 3f),
    CAMPFIRE("Campfire", listOf(Item.WOOD to 5, Item.STONE to 3), "Cook food, stay warm, keep machines wary.", false, 1.4f, 0.6f, 1.4f, solid = false),
    WORKBENCH("Workbench", listOf(Item.WOOD to 10, Item.STONE to 5), "Unlocks advanced crafting.", false, 2f, 1f, 1f),
    BED("Bed", listOf(Item.WOOD to 8, Item.FIBER to 6, Item.HIDE to 2), "Sleep through the night. Sets your respawn point.", false, 1.2f, 0.6f, 2.2f),
    CHEST("Storage Chest", listOf(Item.WOOD to 12, Item.SCRAP to 2), "Stores 24 stacks.", false, 1.2f, 0.9f, 0.8f),
    TORCH_POST("Torch Post", listOf(Item.WOOD to 2, Item.FIBER to 1, Item.RESIN to 1), "Lights up your base.", false, 0.3f, 2.2f, 0.3f, solid = false),
    RAIN_CATCHER("Rain Catcher", listOf(Item.WOOD to 6, Item.FIBER to 4, Item.HIDE to 2), "Collects clean water when it rains.", false, 1.4f, 1.4f, 1.4f),
    SPIKES("Spike Barricade", listOf(Item.WOOD to 8), "Damages machines that push through.", false, 3f, 1.4f, 0.8f),
    TINKER_BENCH("Tinker Bench", listOf(Item.STONE to 15, Item.SCRAP to 10, Item.WIRE to 6), "Work machine parts into alloy gear.", false, 2.2f, 1.2f, 1.2f),
    SHOCK_PYLON("Shock Pylon", listOf(Item.ALLOY to 4, Item.WIRE to 3, Item.CORE to 1), "Zaps hostile machines that come close.", false, 0.8f, 2.6f, 0.8f),
    BOUNTY_BOARD("Bounty Board", listOf(Item.WOOD to 6, Item.FIBER to 2), "Take daily hunting and gathering contracts.", false, 2f, 2.2f, 0.3f),
}
