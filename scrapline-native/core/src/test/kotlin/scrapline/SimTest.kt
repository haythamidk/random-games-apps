package scrapline

import scrapline.sim.*
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SEED = 20261111

private fun Sim.run(ticks: Int) = repeat(ticks) { tick() }

private fun Sim.events(code: Int): Int {
    var n = 0
    for (i in 0 until eventCount) if (events[i * 3] == code) n++
    return n
}

private fun Sim.put(kind: Int, cx: Int, cz: Int, rot: Int = 0): Int {
    val id = entities.place(kind, cx, cz, rot, world.heightAt(cx + 0.5f, cz + 0.5f))
    assertTrue(id > 0, "could not place kind $kind at $cx,$cz")
    return id
}

private fun Sim.firstNode(kind: Int): Int = (0 until nodes.count).first { nodes.kind[it] == kind }

/** Stands the player 1.6 m south of a node, looking straight at its middle. */
private fun Sim.faceNode(i: Int) {
    player.x = nodes.x[i]
    player.z = nodes.z[i] + 1.6f
    player.y = world.heightAt(player.x, player.z)
    player.yaw = 0f
    player.pitch = -0.35f
    updateTarget()
}

/** Aims the player at an entity from two metres south of it. */
private fun Sim.aimAt(id: Int) {
    player.x = entities.cx[id] + 0.5f
    player.z = entities.cz[id] + 2.5f
    player.y = world.heightAt(player.x, player.z)
    player.yaw = 0f
    player.pitch = -0.55f
    updateTarget()
    assertEquals(TargetType.ENTITY, target.type)
    assertEquals(id, target.id)
}

private fun Sim.hold(ticks: Int) {
    actHeld = true
    run(ticks)
    actHeld = false
    tick()
}

private fun Sim.press() {
    actPressed = true
    tick()
}

private fun Sim.oreCell(): Pair<Int, Int> {
    val d = nodes.deposits.first { it.item == Item.IRON_ORE }
    return d.x.toInt() to d.z.toInt()
}

private fun Sim.beltItems(): Int {
    var n = 0
    for (id in 1 until entities.high) if (entities.kind[id] == EntityKind.BELT) {
        for (s in 0 until BELT_SLOTS) if (entities.belt[id * BELT_SLOTS + s] != 0) n++
    }
    return n
}

class InventoryTest {
    @Test fun addStacksAndReportsOverflow() {
        val c = Container(2)
        assertEquals(30, c.add(Item.WOOD, 130))
        assertEquals(100, c.count(Item.WOOD))
        assertEquals(1, Container(3).apply { add(Item.AXE, 2) }.counts[1])
    }

    @Test fun moveMergesSwapsAndRefusesPartialSwap() {
        val c = Container(4)
        c.add(Item.WOOD, 40); c.ids[1] = Item.WOOD; c.counts[1] = 30
        assertEquals(20, moveSlot(c, 0, c, 1, 40))
        assertEquals(50, c.counts[1])
        c.ids[2] = Item.STONE; c.counts[2] = 7
        assertEquals(0, moveSlot(c, 0, c, 2, 5))
        assertEquals(20, moveSlot(c, 0, c, 2, 20))
        assertEquals(Item.STONE, c.ids[0])
    }

    @Test fun craftRollsBackWhenOutputDoesNotFit() {
        val c = Container(2)
        c.add(Item.WOOD, 50); c.add(Item.STONE, 50)
        val axe = RECIPES.first { it.outItem == Item.AXE }
        assertFalse(craft(c, axe))
        assertEquals(50, c.count(Item.WOOD))
        val d = Container(4).apply { add(Item.WOOD, 5); add(Item.STONE, 2) }
        assertTrue(craft(d, axe))
        assertEquals(1, d.count(Item.AXE))
        assertEquals(2, d.count(Item.WOOD))
    }
}

class WorldTest {
    @Test fun worldIsDeterministicWithAHighRim() {
        val a = World(42)
        assertTrue(a.heights.contentEquals(World(42).heights))
        assertFalse(a.heights.contentEquals(World(43).heights))
        val w = World(SEED)
        assertTrue(w.heightAt(4f, 128f) - w.heightAt(128f, 128f) > 15f)
    }

    @Test fun playerWalksJumpsAndStaysInside() {
        val sim = Sim(SEED)
        val intent = MoveIntent().apply { moveY = 1f }
        val z0 = sim.player.z
        repeat(60) { updatePlayer(sim.player, sim.world, intent, 1 / 60f) }
        assertTrue(z0 - sim.player.z > 3f)
        assertTrue(sim.player.onGround)
        intent.sprint = true
        repeat(60 * 60) { updatePlayer(sim.player, sim.world, intent, 1 / 60f) }
        assertTrue(sim.player.z >= 2f)
        assertTrue(sim.player.y < 20f, "climbed the rim: y=${sim.player.y}")
    }
}

class GameplayTest {
    @Test fun gathersByHandUntilNodeIsUsedUp() {
        val sim = Sim(SEED)
        sim.selected = 3
        val i = sim.firstNode(NodeKind.DEADWOOD)
        sim.faceNode(i)
        assertEquals(TargetType.NODE, sim.target.type)
        sim.hold(14)
        assertEquals(1, sim.inventory.count(Item.WOOD))
        sim.hold(14 * 20)
        assertEquals(NODE_DEFS[NodeKind.DEADWOOD].hp, sim.inventory.count(Item.WOOD))
        assertEquals(0, sim.nodes.hp[i])
        assertEquals(1, sim.events(Ev.NODE_GONE))
    }

    @Test fun oreNeedsAPick() {
        val sim = Sim(SEED)
        sim.selected = 3
        sim.faceNode(sim.firstNode(NodeKind.ORE))
        sim.hold(60)
        assertEquals(0, sim.inventory.count(Item.IRON_ORE))
        assertEquals(1, sim.events(Ev.NEED_TOOL))
        sim.inventory.ids[3] = Item.PICK; sim.inventory.counts[3] = 1
        sim.hold(6)
        assertEquals(1, sim.inventory.count(Item.IRON_ORE))
    }

    @Test fun placesAndPacksUpAKit() {
        val sim = Sim(SEED)
        sim.inventory.ids[5] = Item.KIT_CRATE; sim.inventory.counts[5] = 1
        sim.selected = 5
        sim.player.pitch = -0.6f
        sim.updateTarget()
        assertEquals(TargetType.GROUND, sim.target.type)
        assertTrue(sim.target.buildable)
        val cx = sim.target.cx
        val cz = sim.target.cz
        sim.press()
        val id = sim.entities.at(cx, cz)
        assertEquals(EntityKind.CRATE, sim.entities.kind[id])
        assertFalse(sim.canBuildAt(cx, cz))
        sim.entities.containers[id]!!.add(Item.COAL, 7)
        sim.updateTarget()
        sim.hold(18)
        assertEquals(0, sim.entities.at(cx, cz))
        assertEquals(1, sim.inventory.count(Item.KIT_CRATE))
        assertEquals(7, sim.inventory.count(Item.COAL))
    }

    @Test fun nightIsColdAndFireWarms() {
        val sim = Sim(SEED)
        sim.run(1200)
        assertEquals(VITAL_MAX, sim.vitals.warmth)
        sim.tick = DAY_TICKS + 700
        sim.strays.spawned = 99 // keep the night's strays out of a warmth test
        sim.run(1200)
        assertEquals(VITAL_MAX - 400, sim.vitals.warmth)
        sim.put(EntityKind.CAMPFIRE, 129, 128)
        assertTrue(sim.nearHeat())
        sim.run(100)
        assertEquals(VITAL_MAX, sim.vitals.warmth)
    }
}

class AutomationTest {
    @Test fun drillOnlyOnDepositAndExtracts() {
        val sim = Sim(SEED)
        val (cx, cz) = sim.oreCell()
        assertTrue(sim.canBuildAt(cx, cz, EntityKind.DRILL))
        assertFalse(sim.canBuildAt(128, 120, EntityKind.DRILL))
        val drill = sim.put(EntityKind.DRILL, cx, cz)
        sim.run(DRILL_TICKS * 3)
        assertEquals(3, sim.entities.containers[drill]!!.counts[0])
    }

    @Test fun smelterBurnsCoalForIngots() {
        val sim = Sim(SEED)
        val s = sim.put(EntityKind.SMELTER, 129, 126)
        val c = sim.entities.containers[s]!!
        c.ids[SMELTER_ORE] = Item.IRON_ORE; c.counts[SMELTER_ORE] = 5
        c.ids[SMELTER_COAL] = Item.COAL; c.counts[SMELTER_COAL] = 2
        sim.run(SMELT_TICKS * 5)
        assertEquals(5, c.counts[SMELTER_OUT])
        assertEquals(0, c.counts[SMELTER_COAL])
        assertEquals(FUEL_TICKS * 2 - SMELT_TICKS * 5, sim.entities.fuel[s])
    }

    @Test fun beltsCarryBackUpAndNeverLoop() {
        val sim = Sim(SEED)
        val b1 = sim.put(EntityKind.BELT, 130, 124, 0)
        sim.put(EntityKind.BELT, 130, 123, 0)
        sim.put(EntityKind.BELT, 130, 122, 3)
        val crate = sim.put(EntityKind.CRATE, 131, 122)
        assertTrue(loadBelt(sim.entities, b1, Item.STONE))
        sim.run(BELT_PERIOD * 11)
        assertEquals(0, sim.entities.containers[crate]!!.count(Item.STONE))
        sim.run(BELT_PERIOD * 2)
        assertEquals(1, sim.entities.containers[crate]!!.count(Item.STONE))

        val f1 = sim.put(EntityKind.BELT, 140, 124, 0)
        val f2 = sim.put(EntityKind.BELT, 140, 123, 2)
        loadBelt(sim.entities, f1, Item.WOOD); loadBelt(sim.entities, f2, Item.WOOD)
        sim.run(BELT_PERIOD * 20)
        assertEquals(Item.WOOD, sim.entities.belt[f1 * BELT_SLOTS + BELT_SLOTS - 1])
        assertEquals(Item.WOOD, sim.entities.belt[f2 * BELT_SLOTS + BELT_SLOTS - 1])
    }

    @Test fun fullChainRunsUnattended() {
        val sim = Sim(SEED)
        val (cx, cz) = sim.oreCell()
        val drill = sim.put(EntityKind.DRILL, cx, cz, 1)
        for (i in 1..3) sim.put(EntityKind.BELT, cx - i, cz, 1)
        val smelter = sim.put(EntityKind.SMELTER, cx - 4, cz, 1)
        sim.put(EntityKind.BELT, cx - 5, cz, 1)
        val crate = sim.put(EntityKind.CRATE, cx - 6, cz)
        val sc = sim.entities.containers[smelter]!!
        sc.ids[SMELTER_COAL] = Item.COAL; sc.counts[SMELTER_COAL] = 50
        sim.strays.spawned = 99
        sim.run(5 * 60 * 20)
        val ingots = sim.entities.containers[crate]!!.count(Item.IRON_INGOT)
        assertTrue(ingots > 50, "only $ingots ingots")
        val extracted = 5 * 60 * 20 / DRILL_TICKS
        val accounted = sim.entities.containers[drill]!!.counts[0] + sc.counts[SMELTER_ORE] + sim.beltItems() + ingots + sc.counts[SMELTER_OUT]
        assertTrue(accounted <= extracted)
    }
}

class LogicTest {
    private class Host(val g: Graph) : LogicHost {
        val sensors = HashMap<Int, Int>()
        val driven = HashMap<Int, Int>()
        override fun sense(entity: Int, node: Int, out: IntArray, base: Int) { out[base] = sensors[entity] ?: 0 }
        override fun drive(entity: Int, node: Int) { driven[entity] = if (g.inputWired(node, 0)) g.inputValue(node, 0) else -1 }
    }

    private fun gate(type: Int, a: Int, b: Int): Int {
        val g = Graph()
        val h = Host(g)
        val sa = g.addNode(NodeType.DEVICE, 1)
        val sb = g.addNode(NodeType.DEVICE, 2)
        val n = g.addNode(type)
        g.addWire(sa, 0, n, 0); g.addWire(sb, 0, n, 1)
        h.sensors[1] = a; h.sensors[2] = b
        g.step(h); g.step(h)
        return g.outCur[n * PORTS]
    }

    @Test fun truthTables() {
        assertEquals(listOf(0, 0, 0, 1), listOf(gate(NodeType.AND, 0, 0), gate(NodeType.AND, 1, 0), gate(NodeType.AND, 0, 1), gate(NodeType.AND, 1, 1)))
        assertEquals(listOf(0, 1, 1, 1), listOf(gate(NodeType.OR, 0, 0), gate(NodeType.OR, 1, 0), gate(NodeType.OR, 0, 1), gate(NodeType.OR, 1, 1)))
    }

    @Test fun directWireIsInstantAndEachNodeAddsATick() {
        val g = Graph()
        val h = Host(g)
        val s = g.addNode(NodeType.DEVICE, 1)
        val direct = g.addNode(NodeType.DEVICE, 2)
        val n1 = g.addNode(NodeType.NOT)
        val n2 = g.addNode(NodeType.NOT)
        val via = g.addNode(NodeType.DEVICE, 3)
        g.addWire(s, 0, direct, 0); g.addWire(s, 0, n1, 0); g.addWire(n1, 0, n2, 0); g.addWire(n2, 0, via, 0)
        repeat(4) { g.step(h) }
        h.sensors[1] = 1
        g.step(h)
        assertEquals(1, h.driven[2]); assertEquals(0, h.driven[3])
        g.step(h); assertEquals(0, h.driven[3])
        g.step(h); assertEquals(1, h.driven[3])
    }

    @Test fun delayTimerLatchAndIdle() {
        val g = Graph()
        val h = Host(g)
        val s = g.addNode(NodeType.DEVICE, 1)
        val d = g.addNode(NodeType.DELAY)
        g.param[d * 2] = 4
        g.addWire(s, 0, d, 0)
        g.step(h)
        h.sensors[1] = 1
        val seen = (0 until 7).map { g.step(h); g.outCur[d * PORTS] }
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1), seen)

        val g2 = Graph()
        val h2 = Host(g2)
        val t = g2.addNode(NodeType.TIMER)
        g2.param[t * 2] = 4
        assertEquals(listOf(1, 1, 0, 0, 1, 1, 0, 0), (0 until 8).map { g2.step(h2); g2.outCur[t * PORTS] })

        val g3 = Graph()
        val h3 = Host(g3)
        val src = g3.addNode(NodeType.DEVICE, 1)
        val or = g3.addNode(NodeType.OR)
        val keep = g3.addNode(NodeType.OR)
        g3.addWire(src, 0, or, 0); g3.addWire(or, 0, keep, 0); g3.addWire(keep, 0, or, 1)
        repeat(3) { g3.step(h3) }
        h3.sensors[1] = 1
        repeat(4) { g3.step(h3) }
        h3.sensors[1] = 0
        repeat(10) { g3.step(h3) }
        assertEquals(1, g3.outCur[or * PORTS])
        assertEquals(0, g3.lastEvals)
    }

    @Test fun removingANodeDropsItsWiresAndFreesItsId() {
        val g = Graph()
        val a = g.addNode(NodeType.OR)
        val b = g.addNode(NodeType.NOT)
        val c = g.addNode(NodeType.AND)
        g.addWire(a, 0, b, 0); g.addWire(b, 0, c, 0); g.addWire(a, 0, c, 1)
        assertFalse(g.addWire(a, 0, b, 0))
        g.removeNode(b)
        assertEquals(1, g.wireCount)
        assertEquals(b, g.addNode(NodeType.DELAY))
    }
}

class WiringAndNightTest {
    private fun withLinker() = Sim(SEED).apply {
        inventory.ids[5] = Item.LINKER; inventory.counts[5] = 1
        selected = 5
    }

    @Test fun linkerWiresSwitchToLampAndBack() {
        val sim = withLinker()
        val sw = sim.put(EntityKind.SWITCH, 120, 120)
        val lamp = sim.put(EntityKind.LAMP, 124, 120)
        sim.entities.enabled[lamp] = 0
        sim.aimAt(sw); sim.press()
        assertEquals(sw, sim.linkFrom)
        sim.aimAt(lamp); sim.press()
        assertEquals(1, sim.graph.wireCount)
        sim.run(4)
        assertEquals(0, sim.entities.active[lamp])
        sim.toggleSwitch(sw)
        sim.run(4)
        assertEquals(1, sim.entities.active[lamp])
        sim.aimAt(sw); sim.press(); sim.aimAt(lamp); sim.press()
        assertEquals(0, sim.graph.wireCount)
        sim.aimAt(lamp); sim.press()
        assertEquals(1, sim.events(Ev.LINK_BAD))
    }

    @Test fun sensorSeesPlayerAndNotInverts() {
        val sim = Sim(SEED)
        val sensor = sim.put(EntityKind.SENSOR, 120, 120)
        val lamp = sim.put(EntityKind.LAMP, 121, 120)
        sim.entities.enabled[lamp] = 0
        val not = sim.graph.addNode(NodeType.NOT)
        sim.graph.addWire(sim.ensureNode(sensor), 0, not, 0)
        sim.graph.addWire(not, 0, sim.ensureNode(lamp), 0)
        sim.player.x = 140f; sim.player.z = 120.5f
        sim.run(6)
        assertEquals(1, sim.entities.active[lamp])
        sim.player.x = 122f
        sim.run(6)
        assertEquals(0, sim.entities.active[lamp])
        assertEquals(15, sim.graph.outCur[sim.entities.node[sensor] * PORTS + 1])
    }

    @Test fun wiredTurretKillsAStrayAndUnwiredDoesNothing() {
        val sim = Sim(SEED)
        sim.tick = DAY_TICKS + 5
        val turret = sim.put(EntityKind.TURRET, 128, 122)
        sim.entities.enabled[turret] = 0
        val sensor = sim.put(EntityKind.SENSOR, 127, 122)
        sim.ensureNode(turret); sim.ensureNode(sensor)
        sim.player.x = 128.5f; sim.player.z = 160f
        val i = spawnStray(sim, 128.5f, 122f - TURRET_RANGE + 3f)
        sim.strays.spawned = 99
        sim.run(30)
        assertEquals(STRAY_HP, sim.strays.hp[i])
        sim.graph.addWire(sim.entities.node[sensor], 0, sim.entities.node[turret], 0)
        sim.run(120)
        assertFalse(sim.strays.alive[i])
        assertEquals(4, sim.events(Ev.SHOT))
        assertEquals(VITAL_MAX, sim.vitals.health)
    }

    @Test fun straysHuntAtNightAndDawnCountsTheNight() {
        val sim = Sim(SEED)
        sim.tick = DAY_TICKS + 5
        val i = spawnStray(sim, sim.player.x + 12f, sim.player.z)
        val d0 = hypot(sim.strays.x[i] - sim.player.x, sim.strays.z[i] - sim.player.z)
        sim.run(40)
        assertTrue(hypot(sim.strays.x[i] - sim.player.x, sim.strays.z[i] - sim.player.z) < d0 - 5f)
        sim.run(80)
        assertTrue(sim.vitals.health < VITAL_MAX)

        val s2 = Sim(SEED)
        s2.tick = CYCLE_TICKS - 3
        repeat(5) { spawnStray(s2, 30f + it, 30f) }
        s2.run(5)
        assertEquals(0, s2.strays.count)
        assertEquals(1, s2.nights)
        assertEquals(1, s2.events(Ev.DAWN))
    }
}

class SaveTest {
    @Test fun roundTripsAFactory() {
        val sim = Sim(SEED)
        val (cx, cz) = sim.oreCell()
        val drill = sim.put(EntityKind.DRILL, cx, cz, 1)
        val belt = sim.put(EntityKind.BELT, cx - 1, cz, 1)
        val sw = sim.put(EntityKind.SWITCH, 120, 120)
        val lamp = sim.put(EntityKind.LAMP, 122, 120)
        sim.entities.enabled[lamp] = 0
        sim.entities.remove(sim.put(EntityKind.WALL, 110, 110))
        val g = sim.graph
        val not = g.addNode(NodeType.NOT)
        val delay = g.addNode(NodeType.DELAY)
        g.param[delay * 2] = 6
        g.addWire(sim.ensureNode(sw), 0, not, 0); g.addWire(not, 0, delay, 0); g.addWire(delay, 0, sim.ensureNode(lamp), 0)
        sim.toggleSwitch(sw)
        sim.inventory.add(Item.GEAR, 9)
        sim.run(DRILL_TICKS * 2 + 7)

        val copy = assertNotNull(deserialize(serialize(sim)))
        val ces = copy.entities
        val cDrill = ces.at(cx, cz)
        val cBelt = ces.at(cx - 1, cz)
        assertEquals(sim.entities.progress[drill], ces.progress[cDrill])
        for (s in 0 until BELT_SLOTS) assertEquals(sim.entities.belt[belt * BELT_SLOTS + s], ces.belt[cBelt * BELT_SLOTS + s])
        assertEquals(3, copy.graph.wireCount)
        assertEquals(6, copy.graph.param[delay * 2])
        assertEquals(9, copy.inventory.count(Item.GEAR))
        val cSw = ces.at(120, 120)
        val cLamp = ces.at(122, 120)
        sim.run(40); copy.run(40)
        assertEquals(sim.entities.active[lamp], ces.active[cLamp])
        sim.toggleSwitch(sw); copy.toggleSwitch(cSw)
        sim.run(40); copy.run(40)
        assertEquals(1, sim.entities.active[lamp])
        assertEquals(1, ces.active[cLamp])
    }

    @Test fun rejectsBadData() {
        assertNull(deserialize(null))
        assertNull(deserialize("not a save"))
        assertNull(deserialize(serialize(Sim(SEED)).dropLast(40)))
    }
}
