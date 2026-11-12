package scrapline.sim

enum class Station { HAND, WORKBENCH }

class Recipe(val id: Int, val station: Station, val inputs: Array<IntArray>, val outItem: Int, val outCount: Int)

private fun r(station: Station, out: Int, n: Int, vararg inputs: Int): (Int) -> Recipe = { id ->
    Recipe(id, station, Array(inputs.size / 2) { intArrayOf(inputs[it * 2], inputs[it * 2 + 1]) }, out, n)
}

val RECIPES: List<Recipe> = listOf(
    r(Station.HAND, Item.AXE, 1, Item.WOOD, 3, Item.STONE, 2),
    r(Station.HAND, Item.PICK, 1, Item.WOOD, 2, Item.STONE, 3),
    r(Station.HAND, Item.KIT_CAMPFIRE, 1, Item.WOOD, 4, Item.STONE, 4),
    r(Station.HAND, Item.KIT_WORKBENCH, 1, Item.WOOD, 8, Item.STONE, 4),
    r(Station.WORKBENCH, Item.KIT_CRATE, 1, Item.WOOD, 6),
    r(Station.WORKBENCH, Item.KIT_WALL, 2, Item.STONE, 4, Item.WOOD, 1),
    r(Station.WORKBENCH, Item.KIT_SMELTER, 1, Item.STONE, 12),
    r(Station.WORKBENCH, Item.GEAR, 1, Item.IRON_INGOT, 2),
    r(Station.WORKBENCH, Item.CIRCUIT, 1, Item.IRON_INGOT, 1, Item.COAL, 1),
    r(Station.WORKBENCH, Item.KIT_BELT, 4, Item.IRON_INGOT, 1, Item.GEAR, 1),
    r(Station.WORKBENCH, Item.KIT_DRILL, 1, Item.IRON_INGOT, 4, Item.GEAR, 2),
    r(Station.WORKBENCH, Item.LINKER, 1, Item.CIRCUIT, 1, Item.WOOD, 1),
    r(Station.WORKBENCH, Item.KIT_SENSOR, 1, Item.CIRCUIT, 1, Item.IRON_INGOT, 1),
    r(Station.WORKBENCH, Item.KIT_SWITCH, 1, Item.CIRCUIT, 1),
    r(Station.WORKBENCH, Item.KIT_LAMP, 1, Item.IRON_INGOT, 1, Item.COAL, 1),
    r(Station.WORKBENCH, Item.KIT_TURRET, 1, Item.CIRCUIT, 2, Item.IRON_INGOT, 4, Item.GEAR, 2),
).mapIndexed { id, make -> make(id) }

/** Recipes available at a station. A workbench also offers everything craftable by hand. */
fun recipesFor(station: Station): List<Recipe> =
    if (station == Station.WORKBENCH) RECIPES else RECIPES.filter { it.station == Station.HAND }

fun hasInputs(c: Container, r: Recipe): Boolean = r.inputs.all { c.count(it[0]) >= it[1] }

/**
 * Crafts once: consumes the inputs and adds the output. If the output does not
 * fit even after the inputs are gone, everything is put back and it fails.
 */
fun craft(c: Container, r: Recipe): Boolean {
    if (!hasInputs(c, r)) return false
    for (i in r.inputs) c.remove(i[0], i[1])
    val left = c.add(r.outItem, r.outCount)
    if (left > 0) {
        c.remove(r.outItem, r.outCount - left)
        for (i in r.inputs) c.add(i[0], i[1])
        return false
    }
    return true
}
