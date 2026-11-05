package com.hadal.game

import com.hadal.game.game.CState
import com.hadal.game.game.Costs
import com.hadal.game.game.GameState
import com.hadal.game.game.Item
import com.hadal.game.game.SaveSystem
import com.hadal.game.game.Settings
import com.hadal.game.game.Species
import com.hadal.game.game.Upgrade
import com.hadal.game.input.Action
import com.hadal.game.input.InputState
import com.hadal.game.world.CHUNK_SIZE
import com.hadal.game.world.Chunk
import com.hadal.game.world.FloraType
import com.hadal.game.world.InteractKind
import com.hadal.game.world.PoiType
import com.hadal.game.world.SeaGen
import com.hadal.game.world.Structures
import com.hadal.game.world.Zone
import com.hadal.game.world.chunkKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class SimulationTest {
    private fun loadAround(g: GameState, x: Float, z: Float, r: Int = 2) {
        val pcx = floor(x / CHUNK_SIZE).toInt(); val pcz = floor(z / CHUNK_SIZE).toInt()
        for (dz in -r..r) for (dx in -r..r) {
            val k = chunkKey(pcx + dx, pcz + dz)
            if (g.chunks.chunks.containsKey(k)) continue
            val b = g.chunks.buildChunk(pcx + dx, pcz + dz, 2, true, 1f)
            val c = Chunk(pcx + dx, pcz + dz)
            c.nodes = b.nodes!!; c.nodesReady = true
            g.chunks.chunks[k] = c
        }
    }

    private fun newGame(): GameState {
        val g = GameState(Settings())
        g.newGame()
        loadAround(g, g.sub.pos.x, g.sub.pos.z)
        g.worldReady = true
        return g
    }

    private fun step(g: GameState, input: InputState, seconds: Float) {
        var t = 0f
        while (t < seconds) { g.update(1f / 30f, input); t += 1f / 30f }
    }

    @Test
    fun worldDepthsMatchProgression() {
        val st = SeaGen.poi(PoiType.STATION)
        assertTrue("station depth ${-st.y}", -st.y in 40f..80f)
        val relay = SeaGen.poi(PoiType.RELAY)
        assertTrue("relay depth ${-relay.y}", -relay.y < Costs.hullRating[0] - 10)
        val mer = SeaGen.poi(PoiType.MERIDIAN)
        assertTrue("meridian depth ${-mer.y}", -mer.y > Costs.hullRating[0] && -mer.y < Costs.hullRating[1] - 20)
        val cinder = SeaGen.poi(PoiType.CINDER)
        assertTrue("cinder depth ${-cinder.y}", -cinder.y > Costs.hullRating[2] - 250 && -cinder.y < Costs.hullRating[3] - 20)
        for (p in SeaGen.pois.filter { it.type == PoiType.BEACON }) assertTrue("${p.name} depth ${-p.y}", -p.y < Costs.hullRating[4] - 20)
        val choir = SeaGen.poi(PoiType.CHOIR)
        assertTrue("choir depth ${-choir.y}", -choir.y > Costs.hullRating[4] && -choir.y < Costs.hullRating[5] - 40)
        assertEquals(Zone.HADAL, SeaGen.zone(choir.x, choir.z))
        assertEquals(Zone.VENTS, SeaGen.zone(SeaGen.VENT_X, SeaGen.VENT_Z))
        // midnight plains are reachable with Mk2 and include 500 m
        val plains = SeaGen.height(-1500f, -900f)
        assertTrue("plains depth ${-plains}", -plains > 450f && -plains < Costs.hullRating[2])
        println("depths: relay ${-relay.y} meridian ${-mer.y} cinder ${-cinder.y} choir ${-choir.y} plains ${-plains} beacons " +
            SeaGen.pois.filter { it.type == PoiType.BEACON }.joinToString { "${-it.y}" })
    }

    @Test
    fun everyZoneHasResources() {
        val g = GameState(Settings())
        val places = mapOf(
            Zone.SHELF to (60f to 90f), Zone.TWILIGHT to (-500f to -300f), Zone.MIDNIGHT to (-1500f to -900f),
            Zone.VENTS to (SeaGen.VENT_X to SeaGen.VENT_Z), Zone.HADAL to (300f to SeaGen.trenchZ(300f) + 40f),
        )
        for ((zone, xz) in places) {
            loadAround(g, xz.first, xz.second, 2)
            val items = HashMap<Item, Int>()
            g.chunks.forEachNodeNear(xz.first, xz.second, 150f) { n -> n.type.item?.let { items[it] = (items[it] ?: 0) + 1 } }
            println("$zone ${SeaGen.zone(xz.first, xz.second)} -> $items")
            assertTrue("$zone has resources", items.isNotEmpty())
        }
    }

    @Test
    fun structuresAndInteractionsAreReachable() {
        Structures.buildAll()
        for (it in Structures.interacts) {
            val floor = SeaGen.height(it.x, it.z)
            assertTrue("${it.kind} ${it.poi.name} above floor (${it.y} vs $floor)", it.y > floor + 0.5f || it.kind == InteractKind.ECHO)
        }
        // the dock spot must not be inside any collider
        val dx = Structures.dockX; val dy = Structures.dockY - 3.5f; val dz = Structures.dockZ
        for (c in Structures.colliders) {
            val inside = dx > c.minX && dx < c.maxX && dy > c.minY && dy < c.maxY && dz > c.minZ && dz < c.maxZ
            assertTrue("dock blocked by collider", !inside)
        }
        assertTrue(SeaGen.echoStones.size >= 20)
    }

    @Test
    fun firstChapterPlaysThrough() {
        val g = newGame()
        val input = InputState()
        step(g, input, 1f)
        assertTrue(g.docked)
        input.push(Action.USE) // undock
        step(g, input, 1f)
        assertTrue("undocked", !g.docked)
        assertEquals(0, g.story.chapter)
        // swim around grabbing scrap and copper by moving next to nodes
        var guard = 0
        while ((g.story.collected[Item.SCRAP.ordinal] < 6 || g.story.collected[Item.COPPER.ordinal] < 2) && guard < 400) {
            guard++
            var target: com.hadal.game.world.Node? = null
            val want = if (g.story.collected[Item.SCRAP.ordinal] < 6) FloraType.SCRAP else FloraType.COPPER
            g.chunks.forEachNodeNear(g.sub.pos.x, g.sub.pos.z, 400f) { n -> if (n.type == want && target == null) target = n }
            if (target == null) { loadAround(g, g.sub.pos.x + 200f, g.sub.pos.z, 3); g.sub.pos.x += 200f; continue }
            val n = target!!
            g.sub.pos.set(n.x, n.y + 2.5f, n.z - 4f)
            g.sub.yaw = 0f; g.sub.pitch = -0.4f; g.sub.bodyYaw = 0f; g.sub.bodyPitch = -0.3f
            step(g, input, 0.1f)
            input.push(Action.USE)
            step(g, input, 0.2f)
        }
        assertTrue("collected scrap ${g.story.collected[Item.SCRAP.ordinal]}", g.story.collected[Item.SCRAP.ordinal] >= 6)
        // head home and dock
        g.sub.pos.set(Structures.dockX, Structures.dockY - 3.5f, Structures.dockZ)
        step(g, input, 0.2f)
        input.push(Action.USE)
        step(g, input, 0.5f)
        assertTrue("docked", g.docked)
        assertTrue("cargo unloaded", g.storage[Item.SCRAP.ordinal] >= 6)
        assertTrue("can afford hull mk1", g.storageHas(Costs.upgrade(Upgrade.HULL, 0)))
        assertTrue(g.craftUpgrade(Upgrade.HULL))
        step(g, input, 0.5f)
        assertEquals("chapter advanced", 1, g.story.chapter)
        assertEquals(Costs.hullRating[1].toFloat(), g.sub.rating)
    }

    @Test
    fun fullStoryCanComplete() {
        val g = newGame()
        val input = InputState()
        step(g, input, 0.5f)
        g.undock()
        // grant materials generously, then jump through each objective the way a player would reach it
        fun stock() { for (i in Item.entries) g.storage[i.ordinal] += 200 }
        var guard = 0
        while (!g.story.finished && guard < 300) {
            guard++
            stock()
            val ch = g.story.chapter; val stp = g.story.step
            val text = g.story.objectiveText(g)
            when {
                text.startsWith("Undock") -> g.undock()
                text.startsWith("Grab") || text.startsWith("Mine") || text.startsWith("Collect") -> {
                    for (i in Item.entries) g.story.collected[i.ordinal] += 20
                }
                text.startsWith("Return") || text.startsWith("Dock") -> { if (!g.docked) g.dock() }
                text.startsWith("Fabricate") -> { val u = Upgrade.HULL; g.craftUpgrade(u) }
                text.startsWith("Reach the Shelf") -> { g.undock(); moveTo(g, SeaGen.poi(PoiType.RELAY)) }
                text.startsWith("Repair the relay") -> { g.undock(); g.cargo[Item.COPPER.ordinal] = 4; interact(g, input, InteractKind.RELAY) }
                text.startsWith("Find the wreck") -> { g.undock(); moveTo(g, SeaGen.poi(PoiType.MERIDIAN)) }
                text.startsWith("Recover the Meridian") || text.startsWith("Recover Dr.") -> {
                    g.undock()
                    val type = if (text.contains("Meridian")) PoiType.MERIDIAN else PoiType.CINDER
                    for (it in Structures.interacts.filter { it.kind == InteractKind.LOG && it.poi.type == type }) interactAt(g, input, it)
                }
                text.startsWith("Scan") -> { g.biodex.addAll(Species.entries.take(5)) }
                text.startsWith("Descend below") -> { g.undock(); g.sub.pos.set(-1500f, SeaGen.height(-1500f, -900f) + 5f, -900f); step(g, input, 0.2f) }
                text.startsWith("Find Cinder") -> { g.undock(); moveTo(g, SeaGen.poi(PoiType.CINDER)) }
                text.startsWith("Reactivate") -> {
                    g.undock()
                    for (it in Structures.interacts.filter { it.kind == InteractKind.BEACON }) interactAt(g, input, it)
                }
                text.startsWith("Descend to the Choir") -> { g.undock(); moveTo(g, SeaGen.poi(PoiType.CHOIR)) }
                text.startsWith("Touch the Choir") -> {
                    interactAt(g, input, Structures.interacts.first { it.kind == InteractKind.CHOIR })
                    assertTrue("ending requested", g.endingRequested)
                    g.chooseEnding(2)
                }
                else -> throw AssertionError("unhandled objective: $text")
            }
            step(g, input, 0.3f)
            if (g.dead) g.respawn()
            if (ch == g.story.chapter && stp == g.story.step) {
                // one more nudge for steps that complete on the next update
                step(g, input, 0.5f)
            }
        }
        assertTrue("story finished (stuck at ${g.story.chapter}/${g.story.step}: ${g.story.objectiveText(g)})", g.story.finished)
    }

    private fun moveTo(g: GameState, p: com.hadal.game.world.Poi) {
        g.sub.pos.set(p.x, p.y + 30f, p.z)
        g.sub.hull = g.sub.maxHull
    }

    private fun interact(g: GameState, input: InputState, kind: InteractKind) = interactAt(g, input, Structures.interacts.first { it.kind == kind })

    private fun interactAt(g: GameState, input: InputState, it: com.hadal.game.world.Interact) {
        loadAround(g, it.x, it.z, 1)
        g.sub.pos.set(it.x, it.y, it.z)
        g.sub.hull = g.sub.maxHull
        // clear harvestables so the arm targets the interaction
        g.chunks.forEachNodeNear(it.x, it.z, 12f) { n -> if (n.type.harvestable) n.alive = false }
        step(g, input, 0.05f)
        g.sub.pos.set(it.x, it.y, it.z)
        input.push(Action.USE)
        step(g, input, 0.1f)
    }

    @Test
    fun saveRoundTrip() {
        val g = newGame()
        g.storage[Item.PEARL.ordinal] = 7
        g.upgrades[Upgrade.SONAR.ordinal] = 2
        g.story.chapter = 3; g.story.step = 1
        g.story.logsFound.add(2); g.story.echoes.add(5)
        g.biodex.add(Species.ANGLER)
        val json = SaveSystem.toJson(g)
        val h = GameState(Settings())
        SaveSystem.apply(h, org.json.JSONObject(json.toString()))
        assertEquals(7, h.storage[Item.PEARL.ordinal])
        assertEquals(2, h.upgrades[Upgrade.SONAR.ordinal])
        assertEquals(3, h.story.chapter); assertEquals(1, h.story.step)
        assertTrue(h.story.logsFound.contains(2) && h.story.echoes.contains(5))
        assertTrue(h.biodex.contains(Species.ANGLER))
        assertTrue(h.docked)
        assertEquals(Costs.sonarRange[2], h.sub.sonarRange)
    }

    @Test
    fun ecologyBehaves() {
        val g = newGame()
        val input = InputState()
        g.undock()
        g.story.chapter = 4
        val x = -1500f; val z = -900f
        loadAround(g, x, z, 2)
        g.sub.pos.set(x, SeaGen.height(x, z) + 12f, z)
        g.sub.rating = 2000f
        g.story.forcePale = true
        var paleSeen = false
        var minClear = 99f
        for (i in 0 until 30 * 240) {
            g.sub.oxygen = g.sub.maxOxygen; g.sub.power = g.sub.maxPower; g.sub.hull = g.sub.maxHull
            g.update(1f / 30f, input)
            if (g.ecology.pale != null) paleSeen = true
            for (c in g.ecology.list) {
                val clear = c.pos.y - SeaGen.height(c.pos.x, c.pos.z)
                if (c.species != Species.ISOPOD && c.species != Species.VENT_CRAB) minClear = minOf(minClear, clear)
                assertTrue("${c.species} above surface", c.pos.y < -2f)
                assertTrue("${c.species} position finite", c.pos.x.isFinite() && c.pos.y.isFinite())
            }
        }
        println("creatures: ${g.ecology.list.groupingBy { it.species }.eachCount()} paleSeen=$paleSeen minClear=$minClear encounters=${g.ecology.paleEncounters}")
        assertTrue("the Pale appeared", paleSeen)
        assertTrue("creatures spawned", g.ecology.list.isNotEmpty() || g.ecology.paleEncounters > 0)
        assertTrue("creatures stay off the floor ($minClear)", minClear > 0.9f)
        // pulse drives everything hostile away
        g.ecology.pulse(100f)
        for (c in g.ecology.list) if (c.species.hostile && c.species != Species.MOON_JELLY && c.pos.dist(g.sub.pos) < 100f)
            assertTrue("${c.species} ${c.state}", c.state == CState.STUNNED || c.state == CState.RETREAT)
    }

    @Test
    fun pressureKillsAndRespawnRecovers() {
        val g = newGame()
        val input = InputState()
        g.undock()
        g.cargo[Item.SCRAP.ordinal] = 5
        val x = -1500f; val z = -900f
        loadAround(g, x, z, 1)
        g.sub.pos.set(x, SeaGen.height(x, z) + 6f, z)
        step(g, input, 120f)
        assertTrue("imploded at depth", g.dead)
        g.respawn()
        assertTrue(!g.dead && g.docked)
        assertEquals(0, g.cargo[Item.SCRAP.ordinal])
    }
}
