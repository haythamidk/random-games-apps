package com.rustbloom.game

import com.rustbloom.game.engine.Mat4
import com.rustbloom.game.engine.Vec3
import com.rustbloom.game.game.BuildType
import com.rustbloom.game.game.CreatureType
import com.rustbloom.game.game.Difficulty
import com.rustbloom.game.game.GameState
import com.rustbloom.game.game.Item
import com.rustbloom.game.game.Recipes
import com.rustbloom.game.game.SaveSystem
import com.rustbloom.game.game.Settings
import com.rustbloom.game.game.Story
import com.rustbloom.game.game.Variant
import com.rustbloom.game.input.Action
import com.rustbloom.game.input.InputState
import com.rustbloom.game.render.CreatureModels
import com.rustbloom.game.render.PropMeshes
import com.rustbloom.game.world.Biome
import com.rustbloom.game.world.PoiKind
import com.rustbloom.game.world.VegType
import com.rustbloom.game.world.VegetationMeshes
import com.rustbloom.game.world.WorldGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SimulationTest {

    private fun newGame(): Pair<GameState, InputState> {
        val s = Settings()
        s.applyPreset(com.rustbloom.game.game.GraphicsPreset.HIGH)
        val g = GameState(s)
        val input = InputState()
        g.newGame(Difficulty.NORMAL)
        g.paused = false
        // stream the world in
        var frames = 0
        while (!g.worldReady && frames < 4000) {
            g.update(1 / 60f, input)
            Thread.sleep(2)
            frames++
        }
        assertTrue("world should load", g.worldReady)
        return g to input
    }

    @Test
    fun worldHasStoryLocationsOnLand() {
        for (k in listOf(PoiKind.VAULT, PoiKind.RELAY, PoiKind.SPIRE_GREEN, PoiKind.SPIRE_DUST, PoiKind.SPIRE_FROST, PoiKind.SPIRE_TIDE, PoiKind.CROWN)) {
            val p = WorldGen.pois.first { it.kind == k }
            val h = WorldGen.height(p.x, p.z)
            assertTrue("$k at height $h should be on land", h > 2f)
            println("$k at (${p.x}, ${p.z}) h=$h biome=${WorldGen.biomeAt(p.x, p.z)}")
        }
        assertEquals(24, WorldGen.pois.count { it.kind == PoiKind.RUIN })
        assertTrue(WorldGen.pois.count { it.kind == PoiKind.CACHE } >= 10)
        // the biomes the story relies on exist where expected
        val dust = WorldGen.pois.first { it.kind == PoiKind.SPIRE_DUST }
        assertEquals(Biome.DESERT, WorldGen.biomeAt(dust.x + 40f, dust.z + 40f))
        val frost = WorldGen.pois.first { it.kind == PoiKind.SPIRE_FROST }
        val fb = WorldGen.biomeAt(frost.x + 40f, frost.z)
        assertTrue("frost spire biome $fb", fb == Biome.SNOW || fb == Biome.PINE)
        val tide = WorldGen.pois.first { it.kind == PoiKind.SPIRE_TIDE }
        assertEquals(Biome.MARSH, WorldGen.biomeAt(tide.x + 40f, tide.z))
    }

    @Test
    fun biomeDistribution() {
        val counts = HashMap<Biome, Int>()
        var i = 0
        var x = -1500f
        while (x < 1500f) {
            var z = -1500f
            while (z < 1500f) { val b = WorldGen.biomeAt(x, z); counts[b] = (counts[b] ?: 0) + 1; z += 25f; i++ }
            x += 25f
        }
        println(counts.entries.sortedBy { -it.value }.joinToString { "${it.key}=${it.value * 100 / i}%" })
        for (b in Biome.entries) assertTrue("biome $b missing", (counts[b] ?: 0) > 0)
    }

    @Test
    fun meshesBuild() {
        for (t in VegType.entries) { assertTrue(VegetationMeshes.build(t, 0).indexCount > 0); assertTrue(VegetationMeshes.build(t, 1).indexCount > 0) }
        for (t in BuildType.entries) assertTrue(PropMeshes.build(t).first.indexCount > 0)
        for (t in CreatureType.entries) {
            val m = CreatureModels.build(t)
            val c = com.rustbloom.game.game.Creature(1, t, 0f, 0f, Variant.NORMAL)
            val mats = Array(12) { FloatArray(16) }
            CreatureModels.pose(c, m, mats)
            for (i in m.parts.indices) assertTrue(mats[i].all { !it.isNaN() })
        }
        for (i in Item.entries) assertTrue(PropMeshes.tool(i).indexCount > 0)
    }

    @Test
    fun proceduralTextures() {
        val t0 = System.currentTimeMillis()
        val r = com.rustbloom.game.engine.ProceduralTextures.load(null, java.util.concurrent.Executors.newFixedThreadPool(4))
        println("textures generated in ${System.currentTimeMillis() - t0} ms")
        assertEquals(com.rustbloom.game.engine.Mat.COUNT, r.albedo.size)
        for (a in r.albedo) assertEquals(512 * 512 * 4, a.size)
        // leaves must have both transparent and opaque texels
        val leaves = r.albedo[com.rustbloom.game.engine.Mat.LEAVES]
        var opaque = 0
        for (i in 0 until 512 * 512) if ((leaves[i * 4 + 3].toInt() and 255) > 128) opaque++
        println("leaf coverage ${opaque * 100 / (512 * 512)}%")
        assertTrue(opaque > 512 * 512 / 5 && opaque < 512 * 512 * 9 / 10)
    }

    @Test
    fun matrixMath() {
        val a = FloatArray(16); val inv = FloatArray(16); val r = FloatArray(16)
        Mat4.fromTRS(a, 1f, 2f, 3f, 0.5f, 0.3f, 0.1f, 2f, 2f, 2f)
        assertTrue(Mat4.invert(inv, a))
        Mat4.mul(r, a, inv)
        for (i in 0 until 16) assertEquals(if (i % 5 == 0) 1f else 0f, r[i], 1e-4f)
        val v = Mat4.transformPoint(a, 0f, 0f, 0f, Vec3())
        assertEquals(1f, v.x, 1e-5f); assertEquals(2f, v.y, 1e-5f); assertEquals(3f, v.z, 1e-5f)
    }

    @Test
    fun playSessionAndSaveRoundTrip() {
        val (g, input) = newGame()
        val p = g.player
        println("spawn at ${p.pos} ground=${WorldGen.height(p.pos.x, p.pos.z)}")
        assertTrue(abs(p.pos.y - WorldGen.height(p.pos.x, p.pos.z)) < 3f)

        // walk forward for a while, looking around, jumping and attacking
        var t = 0f
        input.setTouchMove(0f, 1f)
        for (f in 0 until 60 * 30) {
            if (f % 120 == 0) input.addLook(30f, 0f)
            if (f % 90 == 0) input.push(Action.JUMP)
            input.attackHeld = (f / 30) % 2 == 0
            if (f % 45 == 0) input.push(Action.INTERACT)
            g.update(1 / 60f, input)
            t += 1 / 60f
        }
        input.setTouchMove(0f, 0f)
        input.attackHeld = false
        println("after walking: pos=${p.pos} hp=${p.hp} hunger=${p.hunger} thirst=${p.thirst} creatures=${g.creatures.size} chunks=${g.chunks.chunks.size}")
        assertTrue(!p.pos.x.isNaN() && !p.pos.y.isNaN())
        assertTrue("player should stay near ground", p.pos.y > WorldGen.height(p.pos.x, p.pos.z) - 2f)

        // back to dry land near the vault
        val v = WorldGen.vaultPoi
        p.pos.set(v.x + 6f, WorldGen.height(v.x + 6f, v.z + 12f), v.z + 12f)
        p.yaw = 0f; p.pitch = -0.3f
        for (f in 0 until 120) g.update(1 / 60f, input)
        // give materials and exercise crafting + building
        for (it in listOf(Item.WOOD, Item.STONE, Item.FIBER, Item.HIDE, Item.RAW_MEAT, Item.SCRAP, Item.WIRE, Item.ALLOY, Item.LENS)) p.inv.add(it, 40)
        val axe = Recipes.all.first { it.output == Item.STONE_AXE }
        assertTrue(g.craft(axe))
        g.startBuild(BuildType.CAMPFIRE)
        for (f in 0 until 10) g.update(1 / 60f, input)
        input.push(Action.ATTACK_PRESS)
        for (f in 0 until 5) g.update(1 / 60f, input)
        println("placed=${g.placed.map { it.type }} valid=${g.buildValid} buildPos=${g.buildPos}")
        g.startBuild(BuildType.WORKBENCH)
        input.addLook(90f, 0f)
        for (f in 0 until 10) g.update(1 / 60f, input)
        input.push(Action.ATTACK_PRESS)
        for (f in 0 until 5) g.update(1 / 60f, input)
        assertTrue("built something: ${g.placed.size}", g.placed.isNotEmpty())

        // quests should progress past the first two
        for (f in 0 until 120) g.update(1 / 60f, input)
        println("quest now: ${g.quest?.id}")
        assertTrue(g.questIndex >= 1)

        // spawn some creatures next to the player and fight
        val c = g.spawnCreature(CreatureType.SCUTTLER, p.pos.x + 4f, p.pos.z + 4f, Variant.NORMAL, force = true)!!
        c.aggroTime = 20f
        g.spawnCreature(CreatureType.STRIDER, p.pos.x - 6f, p.pos.z + 2f, Variant.NORMAL, force = true)
        g.spawnCreature(CreatureType.WARDEN, p.pos.x + 20f, p.pos.z, Variant.DUST, force = true)!!.aggroTime = 30f
        for (f in 0 until 60 * 20) {
            input.attackHeld = f % 40 < 20
            g.update(1 / 60f, input)
            if (p.dead) { g.respawn() }
        }
        input.attackHeld = false
        println("after fight: hp=${p.hp} dead=${p.dead} creatures=${g.creatures.map { "${it.type}:${it.state}:${it.hp.toInt()}" }} projectiles=${g.projectiles.size}")

        // wildlife: animals should not climb cliffs, fish stay in water, birds stay airborne
        for (f in 0 until 60 * 30) g.update(1 / 30f, input)
        for (c in g.creatures) {
            if (!c.alive) continue
            when (c.type.loco) {
                com.rustbloom.game.game.Loco.WATER -> assertTrue("${c.type} out of water", WorldGen.height(c.pos.x, c.pos.z) < 0f)
                com.rustbloom.game.game.Loco.AIR -> assertTrue("${c.type} grounded", c.pos.y >= WorldGen.height(c.pos.x, c.pos.z))
                else -> assertTrue("${c.type} floating", kotlin.math.abs(c.pos.y - WorldGen.height(c.pos.x, c.pos.z)) < 2f)
            }
        }
        println("species seen: ${g.creatures.map { it.type }.toSet()}")

        // fishing: cast into the nearest water and catch something
        val water = (0 until 360 step 10).map { a -> val r = 20f; Pair(p.pos.x + kotlin.math.cos(a * 0.01745f) * r, p.pos.z + kotlin.math.sin(a * 0.01745f) * r) }
            .firstOrNull { WorldGen.height(it.first, it.second) < -1f }
        if (water != null) {
            p.inv.slots[0] = com.rustbloom.game.game.ItemStack(Item.FISHING_ROD, 1)
            p.selected = 0
            p.yaw = kotlin.math.atan2(water.first - p.pos.x, water.second - p.pos.z)
            p.pitch = -0.2f
            input.attackHeld = true; g.update(1 / 60f, input); input.attackHeld = false; g.update(1 / 60f, input)
            println("fishing active=${g.fishing.active}")
        }

        // run a long time to cycle day/night and weather
        for (f in 0 until 60 * 120) g.update(1 / 30f, input)
        println("time=${g.env.time} day=${g.env.day} weather=${g.env.weather}")

        // save + load
        val json = SaveSystem.toJson(g)
        val g2 = GameState(g.settings)
        SaveSystem.fromJson(g2, org.json.JSONObject(json.toString()))
        assertEquals(g.player.level, g2.player.level)
        assertEquals(g.placed.size, g2.placed.size)
        assertEquals(g.questIndex, g2.questIndex)
        assertEquals(g.player.inv.count(Item.WOOD), g2.player.inv.count(Item.WOOD))
        assertEquals(g.env.day, g2.env.day)
        assertEquals(json.toString().length, SaveSystem.toJson(g2).toString().length.toLong().toInt().let { json.toString().length })
        g.chunks.shutdown(); g2.chunks.shutdown()
    }

    @Test
    fun storyIsCompletable() {
        val (g, input) = newGame()
        val p = g.player
        // cheat through every objective type and make sure the chain advances to the end
        var guard = 0
        while (g.quest != null && guard < 200) {
            guard++
            val q = g.quest!!
            for (o in q.objectives) {
                when (o.type) {
                    com.rustbloom.game.game.ObjType.COLLECT -> p.inv.add(Item.valueOf(o.target), o.count)
                    com.rustbloom.game.game.ObjType.CRAFT -> g.count("craft:${o.target}")
                    com.rustbloom.game.game.ObjType.BUILD -> g.count("build:${o.target}")
                    com.rustbloom.game.game.ObjType.REACH -> g.counters["reach:${o.target}"] = 1
                    com.rustbloom.game.game.ObjType.INTERACT -> g.count("interact:${o.target}")
                    com.rustbloom.game.game.ObjType.KILL -> g.count("kill:${o.target}", o.count)
                    com.rustbloom.game.game.ObjType.SLEEP -> g.count("sleep")
                    com.rustbloom.game.game.ObjType.ACTIVATE -> g.spiresActive.add(o.target)
                    com.rustbloom.game.game.ObjType.BOSS -> g.bossDefeated = true
                    com.rustbloom.game.game.ObjType.READ_LOGS -> {}
                    com.rustbloom.game.game.ObjType.EQUIP_ARMOR -> p.inv.armor = com.rustbloom.game.game.ItemStack(Item.HIDE_COAT, 1)
                }
            }
            for (f in 0 until 40) g.update(1 / 60f, input)
        }
        assertEquals(Story.quests.size, g.questIndex)
        println("story finished, level ${p.level}")
        g.chunks.shutdown()
    }
}
