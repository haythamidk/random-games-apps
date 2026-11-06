package com.hadal.game.game

enum class Item(val title: String, val desc: String, val color: Int) {
    SCRAP("Scrap Metal", "Bent plating and pipe from old wrecks. The station fabricator melts it into anything.", 0xFF9A8A78.toInt()),
    COPPER("Copper Ore", "Green veined ore from shelf and slope rocks. Wiring, motors, batteries.", 0xFF4FB3A0.toInt()),
    NODULE("Manganese Nodule", "Black lumps that took millions of years to grow on the midnight plains.", 0xFF4A4642.toInt()),
    CRYSTAL("Thermal Crystal", "Grows only around the black smokers. Still warm. Stores heat like a battery.", 0xFFFF9A3C.toInt()),
    PEARL("Abyssal Pearl", "Pale pearl from a giant clam. Valuable and strangely luminous.", 0xFFE8F4FF.toInt()),
    BIOSAMPLE("Bio Sample", "Glowing tissue from bioluminescent pods. Used for lights, filters and medicine.", 0xFF4CFFD0.toInt()),
    ALLOY("Pale Alloy", "Featherlight metal from the trench. Nobody made it. It hums when you hold it.", 0xFF9FD4FF.toInt()),
    FLARE("Flare", "Burns bright for a minute. Most things in the dark will chase it instead of you.", 0xFFFF5A3C.toInt()),
    REPAIR_KIT("Repair Kit", "Patches and sealant. Restores 40 hull in the field.", 0xFFF2C14E.toInt()),
    DATA_CORE("Data Core", "A crew data core. Return it to the station to read it.", 0xFF7CE0FF.toInt());
}

/** Upgradable sub and station systems. Each level has a cost; level 0 is the starting state. */
enum class Upgrade(val title: String, val desc: String, val maxLevel: Int) {
    HULL("Hull Plating", "Raises the safe depth rating.", 5),
    BATTERY("Battery Cells", "More power for thrusters, lamps and sonar.", 4),
    THRUSTERS("Thrusters", "Faster cruising and boosting.", 3),
    LAMP("Floodlamp", "Longer, wider beam.", 3),
    SONAR("Sonar", "Longer range and a quieter ping.", 3),
    CARGO("Cargo Hold", "Carry more materials per dive.", 3),
    OXYGEN("Oxygen Tanks", "Stay down longer.", 3),
    FLARES("Flare Launcher", "Launch flares that distract predators.", 1),
    PULSE("Shock Pulse", "Electric discharge that drives attackers away.", 2),
    THERMAL("Thermal Tap", "Recharge power by hovering near hydrothermal vents.", 1),
    SILENT("Silent Running", "Dampened thrusters make far less noise.", 1),
    SCANNER("Bio Scanner", "Scan faster and from further away.", 2),
    ARMOR("Armor Plating", "The hull takes 15% less damage from bites and impacts per level.", 2),
    STEALTH("Stealth Coating", "Quieter thrusters and harder for predators to notice.", 2),
    HOLD("Deep Hold", "Eight more cargo slots per level.", 2);

    /** Upgrades that only become available once an outpost has been built. */
    val needsOutpost: Boolean get() = this == ARMOR || this == STEALTH || this == HOLD
}

object Costs {
    /** Materials for upgrading [u] from level [lvl] to lvl+1. */
    fun upgrade(u: Upgrade, lvl: Int): Map<Item, Int> = when (u) {
        Upgrade.HULL -> when (lvl) {
            0 -> mapOf(Item.SCRAP to 6, Item.COPPER to 2)
            1 -> mapOf(Item.SCRAP to 10, Item.COPPER to 6, Item.BIOSAMPLE to 2)
            2 -> mapOf(Item.SCRAP to 14, Item.NODULE to 10, Item.COPPER to 6)
            3 -> mapOf(Item.NODULE to 14, Item.CRYSTAL to 8, Item.PEARL to 2)
            else -> mapOf(Item.ALLOY to 10, Item.CRYSTAL to 10, Item.PEARL to 4, Item.NODULE to 10)
        }
        Upgrade.BATTERY -> when (lvl) {
            0 -> mapOf(Item.COPPER to 5, Item.SCRAP to 4)
            1 -> mapOf(Item.COPPER to 8, Item.NODULE to 6)
            2 -> mapOf(Item.CRYSTAL to 6, Item.NODULE to 8, Item.COPPER to 6)
            else -> mapOf(Item.CRYSTAL to 10, Item.ALLOY to 4)
        }
        Upgrade.THRUSTERS -> when (lvl) {
            0 -> mapOf(Item.SCRAP to 8, Item.COPPER to 4)
            1 -> mapOf(Item.NODULE to 8, Item.COPPER to 8)
            else -> mapOf(Item.CRYSTAL to 6, Item.ALLOY to 3)
        }
        Upgrade.LAMP -> when (lvl) {
            0 -> mapOf(Item.BIOSAMPLE to 3, Item.COPPER to 3)
            1 -> mapOf(Item.BIOSAMPLE to 6, Item.CRYSTAL to 2)
            else -> mapOf(Item.PEARL to 2, Item.CRYSTAL to 4, Item.BIOSAMPLE to 6)
        }
        Upgrade.SONAR -> when (lvl) {
            0 -> mapOf(Item.COPPER to 6, Item.SCRAP to 4)
            1 -> mapOf(Item.COPPER to 8, Item.NODULE to 5, Item.BIOSAMPLE to 2)
            else -> mapOf(Item.ALLOY to 3, Item.CRYSTAL to 4)
        }
        Upgrade.CARGO -> when (lvl) {
            0 -> mapOf(Item.SCRAP to 10)
            1 -> mapOf(Item.SCRAP to 12, Item.NODULE to 6)
            else -> mapOf(Item.NODULE to 12, Item.ALLOY to 2)
        }
        Upgrade.OXYGEN -> when (lvl) {
            0 -> mapOf(Item.SCRAP to 5, Item.BIOSAMPLE to 3)
            1 -> mapOf(Item.BIOSAMPLE to 6, Item.COPPER to 6)
            else -> mapOf(Item.BIOSAMPLE to 8, Item.PEARL to 2)
        }
        Upgrade.FLARES -> mapOf(Item.SCRAP to 6, Item.BIOSAMPLE to 4)
        Upgrade.PULSE -> if (lvl == 0) mapOf(Item.COPPER to 10, Item.NODULE to 6) else mapOf(Item.CRYSTAL to 8, Item.ALLOY to 2)
        Upgrade.THERMAL -> mapOf(Item.CRYSTAL to 6, Item.COPPER to 8)
        Upgrade.SILENT -> mapOf(Item.NODULE to 10, Item.BIOSAMPLE to 6, Item.PEARL to 1)
        Upgrade.SCANNER -> if (lvl == 0) mapOf(Item.COPPER to 4, Item.BIOSAMPLE to 4) else mapOf(Item.PEARL to 2, Item.CRYSTAL to 3)
        Upgrade.ARMOR -> if (lvl == 0) mapOf(Item.ALLOY to 4, Item.SCRAP to 20, Item.NODULE to 8) else mapOf(Item.ALLOY to 8, Item.CRYSTAL to 6, Item.PEARL to 2)
        Upgrade.STEALTH -> if (lvl == 0) mapOf(Item.BIOSAMPLE to 8, Item.NODULE to 8, Item.COPPER to 10) else mapOf(Item.PEARL to 3, Item.CRYSTAL to 6, Item.BIOSAMPLE to 10)
        Upgrade.HOLD -> if (lvl == 0) mapOf(Item.SCRAP to 20, Item.NODULE to 10, Item.COPPER to 10) else mapOf(Item.NODULE to 16, Item.ALLOY to 4, Item.CRYSTAL to 4)
    }

    val flare = mapOf(Item.SCRAP to 1, Item.BIOSAMPLE to 1)
    val repairKit = mapOf(Item.SCRAP to 3, Item.COPPER to 1)

    /** Depth rating in meters for each hull level. */
    val hullRating = intArrayOf(160, 420, 700, 1000, 1300, 1800)
    val batteryCap = floatArrayOf(100f, 150f, 210f, 290f, 400f)
    val oxygenCap = floatArrayOf(240f, 360f, 520f, 720f)
    val cargoCap = intArrayOf(16, 26, 38, 54)
    val thrust = floatArrayOf(6.2f, 7.6f, 9.2f, 11f)
    val lampRange = floatArrayOf(34f, 44f, 56f, 70f)
    val sonarRange = floatArrayOf(90f, 140f, 200f, 280f)
}

/** Cosmetic station comforts: the cozy part of the game. Purely decorative, unlocked with materials. */
enum class Decor(val title: String, val desc: String, val cost: Map<Item, Int>) {
    LAMPS("Warm Lamps", "Soft amber lights along the corridor.", mapOf(Item.SCRAP to 3, Item.BIOSAMPLE to 1)),
    PLANTS("Hydroponic Garden", "Basil, mint and a stubborn tomato.", mapOf(Item.BIOSAMPLE to 4, Item.SCRAP to 2)),
    AQUARIUM("Aquarium", "Little glowing fish keep you company.", mapOf(Item.BIOSAMPLE to 6, Item.SCRAP to 6, Item.PEARL to 1)),
    RUG("Woven Rug", "Made from old cargo netting. Warm under bare feet.", mapOf(Item.SCRAP to 4)),
    RECORD("Record Player", "Someone left a crate of old jazz records.", mapOf(Item.COPPER to 4, Item.SCRAP to 4)),
    STARMAP("Star Chart", "A reminder that there is a sky up there.", mapOf(Item.PEARL to 1, Item.COPPER to 2)),
    BUNK("Proper Bunk", "Replaces the cot. Quilts and a reading light.", mapOf(Item.SCRAP to 8, Item.BIOSAMPLE to 2)),
    KETTLE("Kettle", "Tea at 60 meters below the surface.", mapOf(Item.COPPER to 3)),
    CHIMES("Pearl Chimes", "They ring softly when the station creaks.", mapOf(Item.PEARL to 3)),
    ECHO_SHELF("Echo Shelf", "Display case for every Echo Stone you recover.", mapOf(Item.ALLOY to 2, Item.SCRAP to 6));
}
