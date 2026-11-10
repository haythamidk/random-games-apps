package com.worldwake.game

import com.worldwake.game.game.BuildType
import com.worldwake.game.game.Cat
import com.worldwake.game.game.GameState
import com.worldwake.game.game.Item
import com.worldwake.game.game.Recipes
import com.worldwake.game.game.SaveSystem
import com.worldwake.game.game.Settings
import com.worldwake.game.game.Story
import com.worldwake.game.input.Action
import com.worldwake.game.input.InputState
import com.worldwake.game.world.Biome
import com.worldwake.game.world.Planet
import com.worldwake.game.world.PoiKind
import com.worldwake.game.world.WorldGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationTest {

    @Test
    fun worldIsSane() {
        val l = WorldGen.lander
        assertTrue("lander on dry ground", l.y > 1f)
        for (p in WorldGen.pois) {
            assertTrue("poi ${p.kind} ${p.id} dry: ${p.y}", p.y > 0.8f)
            assertTrue("poi inside the basin", p.x * p.x + p.z * p.z < WorldGen.RIM_R * WorldGen.RIM_R)
        }
        assertEquals(WorldGen.LOG_COUNT, WorldGen.pois.count { it.logIndex >= 0 })
        assertEquals(WorldGen.LOG_COUNT, Story.logs.size)
        assertEquals(WorldGen.LOG_COUNT, Story.logReplies.size)
        val seen = HashSet<Biome>()
        var water = 0; var n = 0
        var x = -1100f
        while (x <= 1100f) {
            var z = -1100f
            while (z <= 1100f) {
                if (x * x + z * z < 1050f * 1050f) {
                    seen.add(WorldGen.biomeAt(x, z)); n++
                    val h = WorldGen.height(x, z)
                    assertTrue(h.isFinite())
                    if (h < 0f) water++
                }
                z += 25f
            }
            x += 25f
        }
        println("biomes: $seen, lake share: ${water * 100 / n}%")
        assertTrue(seen.containsAll(listOf(Biome.FLATS, Biome.SPIRES, Biome.SHARDS, Biome.VENTS, Biome.HIGHLAND, Biome.SINK)))
        assertTrue("some lakes but mostly land", water * 100 / n in 4..30)
        // the vault island stays above the final water line
        assertTrue(WorldGen.height(WorldGen.SINK_X, WorldGen.SINK_Z) > 2f)
        // every stage has text
        assertEquals(Planet.LAST + 1, Planet.stageNames.size)
        assertEquals(Planet.LAST + 1, Story.stageLines.size)
        // every recipe and blueprint can be paid for with items that exist somewhere
        for (r in Recipes.all) assertTrue(r.cost.isNotEmpty())
        for (b in BuildType.entries) assertTrue(b.label, b.cost.isNotEmpty() && b.stage in 0..Planet.LAST)
    }

    /**
     * A model of an ordinary player: about two and a half hours of play a day, the rest of the day at the
     * offline rate. They add one machine of the best kind they have every so often and keep the power up.
     */
    @Test
    fun pacingIsAroundFiveDays() {
        val counts = IntArray(BuildType.entries.size)
        var wi = 0.0
        var t = 0.0
        val activePerDay = 2.5 * 3600
        var buildClock = 0.0
        var cat = 0
        val cats = listOf(Cat.HEAT, Cat.PRESSURE, Cat.OXYGEN, Cat.LIFE)
        val reached = DoubleArray(Planet.LAST + 1)
        var stage = 0
        fun rate(): Double {
            var gen = 0f; var use = 0f; var r = 0.0
            for (b in BuildType.entries) { val c = counts[b.ordinal]; if (b.power > 0) gen += b.power * c else use -= b.power * c; r += b.wake * c }
            return r * (if (use <= 0f) 1f else minOf(1f, gen / use))
        }
        val dtv = 30.0
        while (stage < Planet.LAST && t < 30 * 86400.0) {
            val dayTime = t % 86400.0
            val active = dayTime < activePerDay
            if (active) {
                buildClock += dtv
                // gathering gets slower as recipes get deeper
                val every = 420.0 + stage * 150.0
                if (buildClock >= every) {
                    buildClock -= every
                    var gen = 0f; var use = 0f
                    for (b in BuildType.entries) { val c = counts[b.ordinal]; if (b.power > 0) gen += b.power * c else use -= b.power * c }
                    val best = cats.map { c -> BuildType.entries.filter { it.cat == c && it.stage <= stage && it.isMachine }.maxByOrNull { it.wake } }
                    val pick = best[cat % cats.size] ?: best.filterNotNull().maxByOrNull { it.wake }
                    cat++
                    if (pick != null && use - pick.power > gen) {
                        val g = BuildType.entries.filter { it.cat == Cat.POWER && it.stage <= stage }.maxByOrNull { it.power }!!
                        counts[g.ordinal]++
                    } else if (pick != null) counts[pick.ordinal]++
                }
            }
            wi += rate() * dtv * (if (active) 1.0 else 0.2)
            t += dtv
            while (stage < Planet.LAST && wi >= Planet.thresholds[stage + 1]) { stage++; reached[stage] = t }
        }
        for (i in 1..Planet.LAST) println(String.format("stage %2d %-13s day %.2f", i, Planet.stageNames[i], reached[i] / 86400.0))
        println("machines built: " + counts.sum())
        val days = reached[Planet.LAST] / 86400.0
        assertTrue("finished ($days days)", stage == Planet.LAST)
        assertTrue("planet takes around 4-5 days, was $days", days in 3.6..5.6)
    }

    @Test
    fun playTheOpening() {
        val g = GameState(Settings().also { it.viewDistance = 2 })
        val input = InputState()
        g.newGame()
        g.paused = false
        var guard = 0
        while (!g.worldReady && guard++ < 600) { g.update(0.016f, input); Thread.sleep(10) }
        assertTrue("world loads", g.worldReady)
        val p = g.player
        assertTrue("standing on the ground", kotlin.math.abs(p.pos.y - WorldGen.height(p.pos.x, p.pos.z)) < 1.5f)

        // walk forward for a while: air drains, position changes
        val x0 = p.pos.x; val z0 = p.pos.z
        input.setPadMove(0f, 1f)
        // move away from the lander's free air first
        repeat(1200) { g.update(0.016f, input) }
        input.setPadMove(0f, 0f)
        assertTrue("moved", p.pos.distXZ(x0, z0) > 20f)
        val o2a = p.o2
        repeat(300) { g.update(0.016f, input) }
        assertTrue("air drains outside", p.o2 < o2a)

        // build a spindle, a coil and a bore by aiming at the ground
        p.inv.add(Item.FERRITE, 12); p.inv.add(Item.MAGNESIUM, 4); p.inv.add(Item.SILICA, 2); p.inv.add(Item.COBALT, 2)
        p.pitch = -0.5f
        for (t in listOf(BuildType.WIND, BuildType.HEATER_1, BuildType.DRILL_1)) {
            var tries = 0
            while (g.placed.none { it.type == t } && tries++ < 40) {
                g.startBuild(t)
                p.yaw += 0.45f
                g.update(0.016f, input)
                input.push(Action.PLACE)
                g.update(0.016f, input)
            }
            assertTrue("placed $t (${g.buildWhy})", g.placed.any { it.type == t })
        }
        g.buildType = null
        assertEquals(2f, g.powerGen, 0.01f)
        assertEquals(2f, g.powerUse, 0.01f)
        val before = g.wi
        repeat(600) { g.update(0.05f, input) }
        assertTrue("the planet is warming", g.wi > before + 2.0)

        // offline progress runs at a fifth of the pace
        val w0 = g.wi
        val gained = g.applyOffline(1000.0)
        assertEquals(g.rate * 200.0, gained, 0.01)
        assertEquals(w0 + gained, g.wi, 0.01)

        // a pod gives shelter
        p.inv.add(Item.FERRITE, 8); p.inv.add(Item.SILICA, 4); p.inv.add(Item.MAGNESIUM, 4)
        var tries = 0
        while (g.pods.isEmpty() && tries++ < 60) {
            g.startBuild(BuildType.POD)
            p.yaw += 0.4f
            g.update(0.016f, input)
            input.push(Action.PLACE)
            g.update(0.016f, input)
        }
        assertTrue("pod placed (${g.buildWhy})", g.pods.isNotEmpty())
        val pod = g.pods.values.first()
        p.pos.set((pod.gx + 0.5f) * 6f, pod.y + 0.2f, (pod.gz + 0.5f) * 6f)
        p.vel.set(0f, 0f, 0f)
        p.o2 = 20f
        repeat(120) { g.update(0.016f, input) }
        assertTrue("indoors", p.indoors)
        assertTrue("air refills in a pod", p.o2 > 60f)
        // walls hold: push hard against the window walls from inside
        for (dir in 0 until 4) {
            p.pos.set((pod.gx + 0.5f) * 6f, pod.y + 0.2f, (pod.gz + 0.5f) * 6f)
            if (g.sideState(pod, dir) != 0) continue
            p.yaw = when (dir) { 0 -> 1.5708f; 1 -> 0f; 2 -> -1.5708f; else -> 3.1416f }
            input.setPadMove(0f, 1f)
            repeat(200) { g.update(0.016f, input) }
            input.setPadMove(0f, 0f)
            assertTrue("wall $dir holds", g.podAt(p.pos.x, p.pos.z) === pod)
        }

        // save and load round trip
        val json = SaveSystem.toJson(g).toString()
        val g2 = GameState(Settings())
        SaveSystem.apply(g2, org.json.JSONObject(json))
        assertEquals(g.placed.size, g2.placed.size)
        assertEquals(g.wi, g2.wi, g.wi * 0.01 + 1.0)
        assertEquals(1, g2.pods.size)
        assertEquals(p.inv.count(Item.FERRITE), g2.player.inv.count(Item.FERRITE))
        assertEquals(g.powerGen, g2.powerGen, 0.01f)
    }

    @Test
    fun stagesAdvanceAndUnlock() {
        val g = GameState(Settings())
        g.newGame()
        assertTrue(g.unlocked(BuildType.WIND))
        assertTrue(!g.unlocked(BuildType.SOLAR))
        assertEquals(0f, Planet.stageOf(0.0), 1e-6f)
        assertEquals(Planet.LAST.toFloat(), Planet.stageOf(5e9), 1e-6f)
        var prev = -1f
        for (k in 0..200) { val s = Planet.stageOf(Math.pow(10.0, k / 20.0)); assertTrue(s >= prev); prev = s }
        val v = Planet.Visuals()
        Planet.visuals(0f, v); assertEquals(1f, v.frost, 1e-6f); assertTrue(v.waterLevel < -50f); assertEquals(0f, v.trees, 1e-6f)
        Planet.visuals(11f, v); assertEquals(0f, v.frost, 1e-6f); assertEquals(0f, v.waterLevel, 1e-6f); assertEquals(1f, v.trees, 1e-6f); assertTrue(v.breathable)
        // the vault and log sites exist
        assertEquals(6, WorldGen.pois.count { it.kind == PoiKind.OUTPOST })
        assertEquals(38, WorldGen.pois.count { it.kind == PoiKind.WATCHER })
    }

    private fun ready(g: GameState, input: InputState) {
        g.newGame(); g.paused = false
        var guard = 0
        while (!g.worldReady && guard++ < 800) { g.update(0.016f, input); Thread.sleep(10) }
        assertTrue("world loads", g.worldReady)
    }

    @Test
    fun pickUpOreCraftAndSurviveDeath() {
        val g = GameState(Settings().also { it.viewDistance = 2 })
        val input = InputState()
        var died = 0
        g.ui = object : com.worldwake.game.game.GameUi {
            override fun onPlayerDied() { died++ }
            override fun showLog(title: String, body: String) {}
            override fun openStorage(title: String, inv: com.worldwake.game.game.Inventory) {}
            override fun openFabricator() {}
            override fun refreshMenus() {}
            override fun toast(text: String) {}
            override fun vibrate(ms: Long) {}
            override fun onEnding() {}
        }
        ready(g, input)
        val p = g.player
        // walk up to the nearest ore and pick it up through the real targeting code
        var best: com.worldwake.game.world.Node? = null; var bd = 1e9f
        g.chunks.forEachNodeNear(p.pos.x, p.pos.z, 120f) { n ->
            val d = (n.x - p.pos.x) * (n.x - p.pos.x) + (n.z - p.pos.z) * (n.z - p.pos.z)
            if (n.type.isOre && d < bd) { bd = d; best = n }
        }
        val ore = best!!
        p.pos.set(ore.x - 1.2f, WorldGen.height(ore.x - 1.2f, ore.z), ore.z)
        p.vel.set(0f, 0f, 0f)
        p.yaw = 1.5708f; p.pitch = -0.9f
        repeat(3) { g.update(0.016f, input) }
        assertEquals(com.worldwake.game.game.TargetKind.ORE, g.targetKind)
        assertTrue(g.prompt.startsWith("Pick up"))
        input.push(Action.INTERACT)
        g.update(0.016f, input)
        assertEquals(1, p.inv.count(ore.type.item!!))
        assertTrue(!ore.alive)

        // field recipe works anywhere, fabricator recipes need a fabricator
        p.inv.add(Item.ICE, 2); p.inv.add(Item.FERRITE, 4); p.inv.add(Item.MAGNESIUM, 2)
        p.pos.set(WorldGen.lander.x + 60f, WorldGen.height(WorldGen.lander.x + 60f, WorldGen.lander.z), WorldGen.lander.z)
        assertTrue(g.craft(Recipes.all.first { it.out == Item.WATER }))
        assertEquals(2, p.inv.count(Item.WATER))
        assertTrue("no fabricator out here", !g.craft(Recipes.all.first { it.out == Item.ALLOY }))
        p.pos.set(WorldGen.lander.x + 5f, WorldGen.height(WorldGen.lander.x + 5f, WorldGen.lander.z), WorldGen.lander.z)
        assertTrue("the lander has a fabricator", g.craft(Recipes.all.first { it.out == Item.ALLOY }))

        // running out of air kills you, and you wake up back at shelter with nothing lost
        p.pos.set(WorldGen.lander.x + 200f, WorldGen.height(WorldGen.lander.x + 200f, WorldGen.lander.z), WorldGen.lander.z)
        p.o2 = 0.5f
        repeat(900) { g.update(0.033f, input) }
        assertTrue("blacked out", p.dead && died == 1)
        val carried = p.inv.total
        g.respawn()
        assertTrue(!p.dead && p.health > 0f)
        assertEquals(carried, p.inv.total)
        assertTrue(p.pos.distXZ(WorldGen.lander.x, WorldGen.lander.z) < 12f)
    }

    @Test
    fun everyStageRunsAndEndsAtTheVault() {
        val g = GameState(Settings().also { it.viewDistance = 2 })
        val input = InputState()
        var ending = 0
        g.ui = object : com.worldwake.game.game.GameUi {
            override fun onPlayerDied() {}
            override fun showLog(title: String, body: String) {}
            override fun openStorage(title: String, inv: com.worldwake.game.game.Inventory) {}
            override fun openFabricator() {}
            override fun refreshMenus() {}
            override fun toast(text: String) {}
            override fun vibrate(ms: Long) {}
            override fun onEnding() { ending++ }
        }
        ready(g, input)
        val p = g.player
        input.setPadMove(0.3f, 1f)
        for (k in 0..Planet.LAST) {
            g.heat = Planet.thresholds[k] + (if (k < Planet.LAST) (Planet.thresholds[k + 1] - Planet.thresholds[k]) * 0.5 else 1.0)
            repeat(400) {
                p.yaw += 0.01f
                p.o2 = p.o2Max; p.water = 100f; p.food = 100f
                g.update(0.05f, input)
            }
            assertEquals(k, g.stageReached)
            assertTrue(g.objective.isNotEmpty())
        }
        input.setPadMove(0f, 0f)
        assertTrue(g.env.v.breathable)
        assertTrue(BuildType.entries.all { g.unlocked(it) })
        // the door opens for a finished world
        val v = WorldGen.vault
        p.pos.set(v.x, WorldGen.height(v.x, v.z + 11f), v.z + 11f)
        p.vel.set(0f, 0f, 0f)
        p.yaw = 3.1416f; p.pitch = 0.05f
        repeat(3) { g.update(0.016f, input) }
        assertEquals("Enter the vault", g.prompt)
        input.push(Action.INTERACT)
        g.update(0.016f, input)
        assertEquals(1, ending)
        assertTrue(g.ended)
    }
}
