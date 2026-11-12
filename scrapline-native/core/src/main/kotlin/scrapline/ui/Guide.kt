package scrapline.ui

import scrapline.sim.EntityKind
import scrapline.sim.Item
import scrapline.sim.NodeType
import scrapline.sim.Sim
import scrapline.sim.TICK_HZ
import scrapline.sim.wireDstNode

private class Step(val text: String, val done: (Sim, BooleanArray) -> Boolean)

/** The slice in order. Each step is judged from the world itself, so it survives saves and skipping ahead. */
private val STEPS = listOf(
    Step("Aim at deadwood or stone and hold the ringed button to gather") { s, _ ->
        s.inventory.count(Item.WOOD) >= 3 && s.inventory.count(Item.STONE) >= 3
    },
    Step("Open the bag and craft an axe and a pick") { s, _ ->
        s.inventory.count(Item.AXE) > 0 && s.inventory.count(Item.PICK) > 0
    },
    Step("Craft a campfire kit, select it and place it. The night is freezing without one") { _, has -> has[EntityKind.CAMPFIRE] },
    Step("Craft and place a workbench, then press Use on it") { _, has -> has[EntityKind.WORKBENCH] },
    Step("Find rust or black stained sand and pick ore and coal from the boulders around it") { s, _ ->
        s.inventory.count(Item.IRON_ORE) > 0 && s.inventory.count(Item.COAL) > 0
    },
    Step("Build a smelter at the bench, place it, and put ore and coal inside") { s, has ->
        has[EntityKind.SMELTER] && s.inventory.count(Item.IRON_INGOT) + s.inventory.count(Item.GEAR) + s.inventory.count(Item.CIRCUIT) > 0
    },
    Step("Automate: a drill on stained sand, then belts leading from it into a crate or the smelter") { _, has ->
        has[EntityKind.DRILL] && has[EntityKind.BELT]
    },
    Step("Build a Linker, a sensor and a turret. Place both, then press the Linker on the sensor and then on the turret") { s, _ ->
        val g = s.graph
        (0 until g.wireCount).any {
            val n = wireDstNode(g.wires[it])
            g.type[n] == NodeType.DEVICE && s.entities.kind[g.entity[n]] == EntityKind.TURRET
        }
    },
    Step("Stay near your turret and hold out until dawn") { s, _ -> s.nights > 0 },
)

private const val FINISHED = "You held a night. Keep building: try a NOT or a Timer on the circuit board"

/** One line of "what next". Re-evaluated about once a second. */
class Guide {
    var text = ""
        private set
    private var lastCheck = -1
    private val present = BooleanArray(EntityKind.COUNT)

    fun sync(sim: Sim) {
        val second = sim.tick / TICK_HZ
        if (second == lastCheck) return
        lastCheck = second
        present.fill(false)
        for (id in 1 until sim.entities.high) present[sim.entities.kind[id]] = true
        // The current step is the one after the furthest step already achieved.
        var next = 0
        for (i in STEPS.indices) if (STEPS[i].done(sim, present)) next = i + 1
        text = if (next < STEPS.size) STEPS[next].text else FINISHED
    }
}
