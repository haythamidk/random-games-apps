package com.worldwake.game.game

enum class ItemKind { RAW, PART, SUPPLY, UPGRADE }
enum class Icon { NUGGET, SHARD, CUBE, PLATE, BAR, PANE, CHIP, GEAR, CELL, LENS, ORB, FLASK, CAPSULE, FOOD, POD, TANK, PACK, BOOT, JET, SCAN, LAMP }

enum class Item(val label: String, val color: Int, val kind: ItemKind, val icon: Icon, val desc: String) {
    FERRITE("Ferrite", 0xFF6B6370.toInt(), ItemKind.RAW, Icon.NUGGET, "Dark iron ore. Found all over the Frost Flats."),
    SILICA("Silica", 0xFFE6E4D8.toInt(), ItemKind.RAW, Icon.SHARD, "Pale glassy splinters. Melts into glass."),
    ICE("Ice", 0xFFA9DDF7.toInt(), ItemKind.RAW, Icon.CUBE, "Old ice. Water and oxygen both start here."),
    COBALT("Cobalt", 0xFF3460E0.toInt(), ItemKind.RAW, Icon.NUGGET, "Blue ore used in circuits and oxygen capsules."),
    MAGNESIUM("Magnesium", 0xFFEDEEF5.toInt(), ItemKind.RAW, Icon.NUGGET, "Light white metal. Goes into alloy."),
    TITANIUM("Titanium", 0xFF9DA3B3.toInt(), ItemKind.RAW, Icon.PLATE, "Tough grey plates."),
    ALUMINIUM("Aluminium", 0xFFDDBFCB.toInt(), ItemKind.RAW, Icon.NUGGET, "Found among the Slate Spires."),
    SULFUR("Sulfur", 0xFFF5DA35.toInt(), ItemKind.RAW, Icon.SHARD, "Yellow crystals from the Brimstone Vents."),
    LUMEN("Lumen", 0xFF4DF7EC.toInt(), ItemKind.RAW, Icon.SHARD, "A crystal from the Shards that holds light."),
    OSMIUM("Osmium", 0xFF2C7F88.toInt(), ItemKind.RAW, Icon.ORB, "Very dense metal from the North Terraces."),
    PYRE("Pyre Ore", 0xFFFF6C82.toInt(), ItemKind.RAW, Icon.NUGGET, "Warm to the touch. Fuel for fusion."),

    ALLOY("Alloy", 0xFFB9C0CC.toInt(), ItemKind.PART, Icon.BAR, "Structural metal."),
    GLASS("Glass", 0xFFBFEFF2.toInt(), ItemKind.PART, Icon.PANE, "Clear panes."),
    CIRCUIT("Circuit", 0xFF46C47A.toInt(), ItemKind.PART, Icon.CHIP, "Logic for machines."),
    MOTOR("Motor", 0xFFE0A050.toInt(), ItemKind.PART, Icon.GEAR, "Anything that spins needs one."),
    CELL("Power Cell", 0xFFF2E05A.toInt(), ItemKind.PART, Icon.CELL, "Stores a useful amount of charge."),
    LENS("Lumen Lens", 0xFF7FF2FF.toInt(), ItemKind.PART, Icon.LENS, "Focuses light and heat."),
    CORE("Fusion Core", 0xFFFF9BD0.toInt(), ItemKind.PART, Icon.ORB, "A small bottled star."),

    WATER("Water Flask", 0xFF58B7F0.toInt(), ItemKind.SUPPLY, Icon.FLASK, "Drink to restore water."),
    O2_CAP("Oxygen Capsule", 0xFF9AF0FF.toInt(), ItemKind.SUPPLY, Icon.CAPSULE, "Refills most of your tank anywhere."),
    RATION("Ration", 0xFFD9A066.toInt(), ItemKind.SUPPLY, Icon.FOOD, "Charter-issue food. Tastes like the box."),
    GOURD("Glowgourd", 0xFFFF7E8E.toInt(), ItemKind.SUPPLY, Icon.FOOD, "Grown in a planter. Much better than rations."),
    SPORE("Spore Pod", 0xFFE04F86.toInt(), ItemKind.SUPPLY, Icon.POD, "Native spores. Needed for anything that grows."),

    TANK_2("Oxygen Tank II", 0xFF9AF0FF.toInt(), ItemKind.UPGRADE, Icon.TANK, "Bigger tank."),
    TANK_3("Oxygen Tank III", 0xFF9AF0FF.toInt(), ItemKind.UPGRADE, Icon.TANK, "Much bigger tank."),
    TANK_4("Oxygen Tank IV", 0xFF9AF0FF.toInt(), ItemKind.UPGRADE, Icon.TANK, "The biggest tank."),
    PACK_2("Backpack II", 0xFFE0C090.toInt(), ItemKind.UPGRADE, Icon.PACK, "Carry more."),
    PACK_3("Backpack III", 0xFFE0C090.toInt(), ItemKind.UPGRADE, Icon.PACK, "Carry a lot more."),
    PACK_4("Backpack IV", 0xFFE0C090.toInt(), ItemKind.UPGRADE, Icon.PACK, "Carry almost everything."),
    BOOTS("Stride Boots", 0xFFB0B8FF.toInt(), ItemKind.UPGRADE, Icon.BOOT, "Run faster."),
    JETPACK("Jetpack", 0xFFFFB060.toInt(), ItemKind.UPGRADE, Icon.JET, "Hold jump in the air to fly for a while."),
    SCANNER("Ore Scanner", 0xFF7FF2FF.toInt(), ItemKind.UPGRADE, Icon.SCAN, "Marks nearby ore on your visor."),
    LAMP("Suit Lamp", 0xFFFFF2B0.toInt(), ItemKind.UPGRADE, Icon.LAMP, "A much brighter light at night."),
    ;

    val isUpgrade get() = kind == ItemKind.UPGRADE
}

class Cost(val item: Item, val n: Int)

fun costs(vararg p: Pair<Item, Int>): Array<Cost> = Array(p.size) { Cost(p[it].first, p[it].second) }

/** A fabricator recipe. [hand] recipes also work from the suit menu with no fabricator nearby. */
class Recipe(val out: Item, val count: Int, val cost: Array<Cost>, val stage: Int = 0, val hand: Boolean = false, val needs: Item? = null)

object Recipes {
    val all: List<Recipe> = listOf(
        Recipe(Item.WATER, 2, costs(Item.ICE to 1), hand = true),
        Recipe(Item.O2_CAP, 1, costs(Item.ICE to 1, Item.COBALT to 1), hand = true),
        Recipe(Item.ALLOY, 1, costs(Item.FERRITE to 2, Item.MAGNESIUM to 1)),
        Recipe(Item.GLASS, 1, costs(Item.SILICA to 2)),
        Recipe(Item.CIRCUIT, 1, costs(Item.SILICA to 1, Item.COBALT to 2)),
        Recipe(Item.MOTOR, 1, costs(Item.ALLOY to 1, Item.CIRCUIT to 1, Item.TITANIUM to 1), stage = 1),
        Recipe(Item.CELL, 1, costs(Item.SULFUR to 2, Item.COBALT to 1, Item.ALLOY to 1), stage = 2),
        Recipe(Item.LENS, 1, costs(Item.GLASS to 1, Item.LUMEN to 2), stage = 2),
        Recipe(Item.CORE, 1, costs(Item.OSMIUM to 2, Item.PYRE to 2, Item.CIRCUIT to 1, Item.LENS to 1), stage = 5),
        Recipe(Item.TANK_2, 1, costs(Item.ALLOY to 2, Item.COBALT to 2)),
        Recipe(Item.TANK_3, 1, costs(Item.ALUMINIUM to 3, Item.GLASS to 2, Item.CELL to 1), stage = 2, needs = Item.TANK_2),
        Recipe(Item.TANK_4, 1, costs(Item.OSMIUM to 3, Item.CORE to 1), stage = 5, needs = Item.TANK_3),
        Recipe(Item.PACK_2, 1, costs(Item.ALLOY to 3, Item.SILICA to 2)),
        Recipe(Item.PACK_3, 1, costs(Item.ALUMINIUM to 3, Item.TITANIUM to 2, Item.MOTOR to 1), stage = 2, needs = Item.PACK_2),
        Recipe(Item.PACK_4, 1, costs(Item.OSMIUM to 3, Item.CELL to 2, Item.CORE to 1), stage = 5, needs = Item.PACK_3),
        Recipe(Item.LAMP, 1, costs(Item.GLASS to 1, Item.CIRCUIT to 1), stage = 0),
        Recipe(Item.BOOTS, 1, costs(Item.ALLOY to 2, Item.MOTOR to 1), stage = 1),
        Recipe(Item.SCANNER, 1, costs(Item.CIRCUIT to 2, Item.LENS to 1), stage = 2),
        Recipe(Item.JETPACK, 1, costs(Item.CELL to 2, Item.MOTOR to 2, Item.ALUMINIUM to 3), stage = 3),
    )
}

/** Items carried by the player, a crate or a machine: counts per item with a total capacity. */
class Inventory(var capacity: Int) {
    val counts = IntArray(Item.entries.size)
    var total = 0
        private set

    fun count(i: Item) = counts[i.ordinal]
    val free get() = capacity - total

    /** Adds up to [n]; returns how many fit. */
    fun add(i: Item, n: Int = 1): Int {
        val k = minOf(n, free).coerceAtLeast(0)
        counts[i.ordinal] += k
        total += k
        return k
    }

    fun remove(i: Item, n: Int = 1): Boolean {
        if (counts[i.ordinal] < n) return false
        counts[i.ordinal] -= n
        total -= n
        return true
    }

    fun has(cost: Array<Cost>): Boolean {
        for (c in cost) if (counts[c.item.ordinal] < c.n) return false
        return true
    }

    fun pay(cost: Array<Cost>): Boolean {
        if (!has(cost)) return false
        for (c in cost) remove(c.item, c.n)
        return true
    }

    fun clear() { counts.fill(0); total = 0 }

    fun set(i: Item, n: Int) { total += n - counts[i.ordinal]; counts[i.ordinal] = n }
}
