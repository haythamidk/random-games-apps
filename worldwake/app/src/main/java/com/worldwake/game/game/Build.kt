package com.worldwake.game.game

enum class Cat(val label: String) {
    BASE("Base"), POWER("Power"), HEAT("Heat"), PRESSURE("Pressure"), OXYGEN("Oxygen"), LIFE("Life"), TOOLS("Tools"),
}

/** Edge length of one pod cell on the base grid. */
const val POD_CELL = 6f
const val POD_HEIGHT = 3.1f

/**
 * Everything the player can build. [power] is kW made (positive) or used (negative); heat/press/oxy/bio are
 * Wake Index per second at full power. [stage] is the planet stage that unlocks the blueprint.
 */
enum class BuildType(
    val label: String,
    val cat: Cat,
    val cost: Array<Cost>,
    val stage: Int,
    val power: Float = 0f,
    val heat: Double = 0.0,
    val press: Double = 0.0,
    val oxy: Double = 0.0,
    val bio: Double = 0.0,
    val radius: Float = 1.2f,
    val height: Float = 2.5f,
    val indoor: Boolean = false,
    val storage: Int = 0,
    val desc: String = "",
) {
    POD("Living Pod", Cat.BASE, costs(Item.FERRITE to 4, Item.SILICA to 2, Item.MAGNESIUM to 2), 0, radius = 3f, height = POD_HEIGHT,
        desc = "A pressurised room. Refills oxygen. Pods placed side by side join up."),
    FABRICATOR("Fabricator", Cat.BASE, costs(Item.FERRITE to 3, Item.COBALT to 2, Item.SILICA to 1), 0, radius = 0.8f, height = 1.6f, indoor = true,
        desc = "Makes parts and suit upgrades. Goes inside a pod."),
    LOCKER("Locker", Cat.BASE, costs(Item.FERRITE to 3), 0, radius = 0.6f, height = 2f, indoor = true, storage = 40,
        desc = "Indoor storage."),
    PLANTER("Planter", Cat.BASE, costs(Item.ALLOY to 2, Item.GLASS to 2, Item.SPORE to 1, Item.ICE to 2), 2, radius = 0.8f, height = 1.4f, indoor = true, storage = 6,
        desc = "Grows glowgourds to eat, and now and then a spore pod."),

    WIND("Wind Spindle", Cat.POWER, costs(Item.FERRITE to 2, Item.MAGNESIUM to 1), 0, power = 2f, radius = 0.9f, height = 5f,
        desc = "A little power from the thin wind."),
    SOLAR("Solar Leaf", Cat.POWER, costs(Item.GLASS to 2, Item.CIRCUIT to 1, Item.ALLOY to 1), 1, power = 8f, radius = 1.6f, height = 3f,
        desc = "Unfolds toward the sun."),
    THERMAL("Thermal Tap", Cat.POWER, costs(Item.ALLOY to 3, Item.MOTOR to 2, Item.CELL to 2), 4, power = 45f, radius = 1.8f, height = 4f,
        desc = "Drinks heat from below the crust."),
    FUSION("Fusion Seed", Cat.POWER, costs(Item.CORE to 2, Item.ALLOY to 4, Item.LENS to 2, Item.OSMIUM to 2), 6, power = 400f, radius = 2.4f, height = 5f,
        desc = "A caged star. Powers a whole field of machines."),

    HEATER_1("Ember Coil", Cat.HEAT, costs(Item.FERRITE to 2, Item.SILICA to 1, Item.COBALT to 1), 0, power = -1f, heat = 0.05, radius = 0.8f, height = 2f,
        desc = "A glowing coil. A start."),
    HEATER_2("Furnace Tower", Cat.HEAT, costs(Item.ALLOY to 3, Item.CIRCUIT to 1, Item.SULFUR to 2), 2, power = -3f, heat = 0.6, radius = 1.2f, height = 4f,
        desc = "Burns sulfur slowly and steadily."),
    HEATER_3("Magma Lance", Cat.HEAT, costs(Item.MOTOR to 2, Item.CELL to 2, Item.ALUMINIUM to 3, Item.LENS to 1), 4, power = -12f, heat = 8.0, radius = 1.6f, height = 6f,
        desc = "Pulls molten rock up a pipe and lets it glow."),
    HEATER_4("Helios Mirror", Cat.HEAT, costs(Item.LENS to 3, Item.CORE to 1, Item.ALUMINIUM to 4, Item.MOTOR to 2), 6, power = -50f, heat = 100.0, radius = 2.4f, height = 7f,
        desc = "A dish that folds sunlight onto the ground."),
    HEATER_5("Sunwell", Cat.HEAT, costs(Item.CORE to 3, Item.OSMIUM to 4, Item.LENS to 4, Item.ALLOY to 6), 8, power = -220f, heat = 1500.0, radius = 3f, height = 9f,
        desc = "Warm enough to feel through the suit."),

    DRILL_1("Vent Bore", Cat.PRESSURE, costs(Item.FERRITE to 3, Item.MAGNESIUM to 1), 0, power = -1f, press = 0.05, radius = 0.9f, height = 3f,
        desc = "Cracks the crust and lets old gas out."),
    DRILL_2("Deep Bore", Cat.PRESSURE, costs(Item.ALLOY to 2, Item.TITANIUM to 1, Item.CIRCUIT to 1), 1, power = -3f, press = 0.5, radius = 1.2f, height = 4.5f,
        desc = "Reaches the gas pockets under the frost."),
    DRILL_3("Breather", Cat.PRESSURE, costs(Item.MOTOR to 2, Item.TITANIUM to 3, Item.ALLOY to 2), 3, power = -12f, press = 5.0, radius = 1.6f, height = 6f,
        desc = "A tall stack that exhales all day."),
    DRILL_4("Mantle Bore", Cat.PRESSURE, costs(Item.CELL to 2, Item.MOTOR to 3, Item.ALUMINIUM to 4, Item.CORE to 1), 5, power = -50f, press = 60.0, radius = 2.2f, height = 8f,
        desc = "Goes down a very long way."),
    DRILL_5("Skyforge", Cat.PRESSURE, costs(Item.CORE to 2, Item.OSMIUM to 4, Item.MOTOR to 4), 7, power = -200f, press = 700.0, radius = 3f, height = 10f,
        desc = "Makes weather."),

    OXY_1("Spore Vat", Cat.OXYGEN, costs(Item.GLASS to 2, Item.SPORE to 1, Item.ICE to 2), 1, power = -2f, oxy = 0.3, radius = 0.9f, height = 2f,
        desc = "Native spores in meltwater. They breathe out oxygen."),
    OXY_2("Algae Column", Cat.OXYGEN, costs(Item.GLASS to 3, Item.SPORE to 2, Item.MOTOR to 1, Item.ALLOY to 2), 3, power = -8f, oxy = 4.0, radius = 1.1f, height = 4.5f,
        desc = "A tall tube of red algae."),
    OXY_3("Bloom Engine", Cat.OXYGEN, costs(Item.LENS to 2, Item.SPORE to 3, Item.CELL to 2, Item.GLASS to 4), 5, power = -30f, oxy = 40.0, radius = 1.8f, height = 5f,
        desc = "Force-grows blooms under lumen light."),
    OXY_4("Canopy Engine", Cat.OXYGEN, costs(Item.CORE to 2, Item.SPORE to 5, Item.LENS to 4), 8, power = -150f, oxy = 900.0, radius = 2.6f, height = 8f,
        desc = "A machine that thinks it is a forest."),

    BIO_1("Moss Seeder", Cat.LIFE, costs(Item.SPORE to 3, Item.MOTOR to 2, Item.ALLOY to 3, Item.ICE to 2), 6, power = -30f, bio = 80.0, radius = 1.4f, height = 3f,
        desc = "Throws spores across the wet ground."),
    BIO_2("Meadow Seeder", Cat.LIFE, costs(Item.SPORE to 5, Item.CELL to 2, Item.LENS to 2, Item.ALUMINIUM to 4), 7, power = -80f, bio = 500.0, radius = 1.8f, height = 4f,
        desc = "Spreads grass and lantern flowers."),
    BIO_3("Grove Seeder", Cat.LIFE, costs(Item.SPORE to 8, Item.CORE to 2, Item.MOTOR to 4), 8, power = -200f, bio = 2500.0, radius = 2.2f, height = 6f,
        desc = "Plants trees faster than you can count them."),
    BIO_4("Mote Hive", Cat.LIFE, costs(Item.SPORE to 10, Item.CORE to 3, Item.LUMEN to 4, Item.GLASS to 4), 9, power = -300f, bio = 6000.0, radius = 2f, height = 5f,
        desc = "Home for the little lights."),
    BIO_5("Chorus Tower", Cat.LIFE, costs(Item.SPORE to 12, Item.CORE to 5, Item.LUMEN to 6, Item.OSMIUM to 6), 10, power = -500f, oxy = 5000.0, bio = 15000.0, radius = 2.6f, height = 12f,
        desc = "It sings on the frequency the ground hums at."),

    EXTRACTOR("Ore Extractor", Cat.TOOLS, costs(Item.ALLOY to 2, Item.MOTOR to 1, Item.CIRCUIT to 1), 2, power = -4f, radius = 1.4f, height = 3.5f, storage = 12,
        desc = "Digs up whatever ore is under it. Empty it now and then."),
    COLLECTOR("Rain Catcher", Cat.TOOLS, costs(Item.GLASS to 2, Item.ALLOY to 2, Item.MOTOR to 1), 4, power = -2f, radius = 1.2f, height = 3f, storage = 8,
        desc = "Fills water flasks from the air."),
    CRATE("Crate", Cat.TOOLS, costs(Item.FERRITE to 3), 0, radius = 0.7f, height = 1f, storage = 30,
        desc = "Outdoor storage."),
    BEACON("Beacon", Cat.TOOLS, costs(Item.FERRITE to 1, Item.SILICA to 1, Item.COBALT to 1), 0, radius = 0.4f, height = 4f,
        desc = "A light and a marker on your compass."),
    ;

    val isMachine get() = heat + press + oxy + bio > 0.0
    val wake get() = heat + press + oxy + bio
}

class Placed(val id: Int, val type: BuildType, var x: Float, var y: Float, var z: Float, var yaw: Float) {
    var inv: Inventory? = if (type.storage > 0) Inventory(type.storage) else null
    var timer = 0f
    var ore: Item? = null
    /** Pods: grid cell and the side that carries the door (0 +x, 1 +z, 2 -x, 3 -z). */
    var gx = 0
    var gz = 0
    var door = 0
    /** Game time when built, drives the build-in animation. */
    var born = 0.0
    /** Id of the pod a piece of furniture stands in, or -1. */
    var podId = -1
}
