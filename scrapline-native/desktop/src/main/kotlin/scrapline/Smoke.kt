package scrapline

import com.badlogic.gdx.Gdx
import scrapline.sim.*
import scrapline.ui.Board
import scrapline.ui.GameUi
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Plays the game from the outside, one step per rendered frame, using only
 * what a player has: touches on the screen. State is read directly from the
 * simulation to check the results.
 */
class Smoke(private val dir: String) {
    var passed = 0
        private set
    var failed = 0
        private set
    private var steps: Iterator<Unit>? = null
    private lateinit var g: ScraplineGame

    fun frame(game: ScraplineGame, @Suppress("UNUSED_PARAMETER") frame: Int) {
        g = game
        val it = steps ?: script().iterator().also { steps = it }
        if (it.hasNext()) it.next() else Gdx.app.exit()
    }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) passed++ else failed++
        println("${if (ok) "PASS" else "FAIL"}  $name${if (detail.isNotEmpty()) "  ($detail)" else ""}")
    }

    private val sim get() = g.sim
    private val ui get() = g.ui
    private val scale get() = ui.canvas.scale
    private fun px(logical: Float) = (logical * scale).toInt()

    private suspend fun SequenceScope<Unit>.frames(n: Int) = repeat(n) { yield(Unit) }

    private suspend fun SequenceScope<Unit>.seconds(s: Float) {
        var left = s
        while (left > 0f) {
            yield(Unit)
            left -= Gdx.graphics.deltaTime.coerceAtMost(0.1f)
        }
    }

    private suspend fun SequenceScope<Unit>.shot(name: String) {
        frames(2)
        savePng("$dir/$name.png")
    }

    private suspend fun SequenceScope<Unit>.tapPx(x: Int, y: Int, pointer: Int = 5) {
        ui.touchDown(x, y, pointer)
        frames(2)
        ui.touchUp(x, y, pointer)
        frames(2)
    }

    /** Taps a HUD or sheet control by its id. Fails the check if it is not on screen. */
    private suspend fun SequenceScope<Unit>.tap(id: Int, arg: Int = 0): Boolean {
        frames(1)
        val at = ui.locate(id, arg)
        if (at == null) {
            check("control $id/$arg is on screen", false)
            return false
        }
        tapPx(at.first, at.second)
        return true
    }

    /** Drags the recipe list up by `units`, the way a thumb would. */
    private suspend fun SequenceScope<Unit>.scrollRecipes(units: Float) {
        frames(1)
        val at = ui.locate(GameUi.RECIPE, 2) ?: return
        ui.touchDown(at.first, at.second, 6)
        for (k in 1..8) {
            ui.touchDragged(at.first, at.second - px(units * k / 8f), 6)
            frames(1)
        }
        ui.touchUp(at.first, at.second - px(units), 6)
        frames(2)
    }

    private suspend fun SequenceScope<Unit>.holdAct(s: Float) {
        val at = ui.locate(GameUi.ACT)!!
        ui.touchDown(at.first, at.second, 7)
        seconds(s)
        ui.touchUp(at.first, at.second, 7)
        frames(3)
    }

    private suspend fun SequenceScope<Unit>.tapBoard(action: Int, arg: Int = 0) {
        frames(1)
        val at = ui.board.locate(action, arg)
        if (at == null) check("board button $action/$arg is shown", false) else tapPx(px(at.first), px(at.second))
    }

    private suspend fun SequenceScope<Unit>.tapPort(node: Int, port: Int, output: Boolean) {
        val at = ui.board.portPosition(node, port, output)
        tapPx(px(at.first), px(at.second))
    }

    private fun setBag(vararg items: Int) {
        sim.inventory.clear()
        for (k in 0 until items.size / 2) {
            sim.inventory.ids[k] = items[k * 2]
            sim.inventory.counts[k] = items[k * 2 + 1]
        }
    }

    /** Puts the player on the ground at a spot, facing `yaw`, standing still. */
    private fun stand(x: Float, z: Float, yaw: Float) {
        val p = sim.player
        p.x = x; p.z = z; p.y = sim.world.heightAt(x, z)
        p.vx = 0f; p.vy = 0f; p.vz = 0f
        p.onGround = true
        p.yaw = yaw
    }

    /** Stands south of a build cell and tilts the view until the reticle is on it. */
    private suspend fun SequenceScope<Unit>.aimCell(cx: Int, cz: Int, wantEntity: Boolean = false): Boolean {
        val p = sim.player
        stand(cx + 0.5f, cz + 3.2f, 0f)
        var lo = -1.25f
        var hi = -0.15f
        repeat(14) {
            p.pitch = (lo + hi) / 2
            frames(2)
            val t = sim.target
            val hit = t.type == TargetType.ENTITY || t.type == TargetType.GROUND
            if (hit && t.cx == cx && t.cz == cz && (t.type == TargetType.ENTITY) == wantEntity) return true
            if (!hit || t.cz < cz) hi = p.pitch else lo = p.pitch
        }
        return false
    }

    private suspend fun SequenceScope<Unit>.placeAt(hotSlot: Int, cx: Int, cz: Int, rot: Int = 0): Int {
        tap(GameUi.HOTBAR, hotSlot)
        sim.buildRot = rot
        val aimed = aimCell(cx, cz)
        tap(GameUi.ACT)
        frames(4)
        return if (aimed) sim.entities.at(cx, cz) else 0
    }

    private fun script() = sequence {
        frames(20)
        shot("01-start")

        // ---------------- movement: both thumbs at once
        val p0x = sim.player.x
        val p0z = sim.player.z
        val yaw0 = sim.player.yaw
        ui.touchDown(px(150f), px(290f), 0)
        ui.touchDown(px(560f), px(120f), 1)
        for (i in 1..10) {
            ui.touchDragged(px(150f), px(290f - i * 4f), 0)
            ui.touchDragged(px(560f + i * 9f), px(120f), 1)
            frames(1)
        }
        seconds(1.5f)
        shot("02-moving")
        val moved = hypot(sim.player.x - p0x, sim.player.z - p0z)
        check("left stick moves the player", moved > 1.5f, "%.2f m".format(moved))
        check("right drag turns the camera", abs(sim.player.yaw - yaw0) > 0.2f, "%.2f rad".format(sim.player.yaw - yaw0))
        ui.touchUp(px(150f), px(250f), 0)
        ui.touchUp(px(650f), px(120f), 1)
        frames(20)
        val sx = sim.player.x
        frames(10)
        check("player stops when the stick is released", abs(sim.player.x - sx) < 0.05f)
        val y0 = sim.player.y
        val jump = ui.locate(GameUi.JUMP)!!
        ui.touchDown(jump.first, jump.second, 3)
        seconds(0.22f)
        check("jump button lifts the player", sim.player.y - y0 > 0.3f, "%.2f m".format(sim.player.y - y0))
        ui.touchUp(jump.first, jump.second, 3)
        seconds(0.9f)

        // ---------------- gather by hand
        tap(GameUi.HOTBAR, 3)
        check("tapping a hotbar slot selects it", sim.selected == 3)
        val tree = (0 until sim.nodes.count).first { sim.nodes.kind[it] == NodeKind.DEADWOOD }
        stand(sim.nodes.x[tree], sim.nodes.z[tree] + 1.6f, 0f); sim.player.pitch = -0.35f
        frames(5)
        check("reticle is on the tree", sim.target.type == TargetType.NODE && sim.target.id == tree)
        holdAct(1.8f)
        shot("03-gather")
        check("holding the action button gathers by hand", sim.inventory.count(Item.WOOD) >= 2, "${sim.inventory.count(Item.WOOD)} wood")

        // ---------------- craft
        sim.inventory.add(Item.WOOD, 60)
        sim.inventory.add(Item.STONE, 30)
        tap(GameUi.BAG)
        check("bag opens", ui.sheetOpen)
        tap(GameUi.RECIPE, 0)
        tap(GameUi.RECIPE, 3)
        tap(GameUi.RECIPE, 2)
        check("recipes craft into the bag", sim.inventory.count(Item.AXE) == 1 && sim.inventory.count(Item.KIT_WORKBENCH) == 1 && sim.inventory.count(Item.KIT_CAMPFIRE) == 1)
        shot("04-crafting")
        val woodSlot = (0 until INVENTORY_SIZE).first { sim.inventory.ids[it] == Item.WOOD }
        val had = sim.inventory.counts[woodSlot]
        tap(GameUi.SLOT_TARGET, woodSlot)
        tap(GameUi.SLOT_TARGET, 20)
        check("tap, tap moves a whole stack", sim.inventory.counts[woodSlot] == 0 && sim.inventory.counts[20] == had)
        val at = ui.locate(GameUi.SLOT_TARGET, 20)!!
        ui.touchDown(at.first, at.second, 5)
        seconds(0.6f)
        ui.touchUp(at.first, at.second, 5)
        frames(2)
        tap(GameUi.SLOT_TARGET, 22)
        check("hold picks up half a stack", sim.inventory.counts[20] + sim.inventory.counts[22] == had && abs(sim.inventory.counts[20] - sim.inventory.counts[22]) <= 1,
            "${sim.inventory.counts[20]} / ${sim.inventory.counts[22]}")
        tap(GameUi.CLOSE)
        check("bag closes", !ui.sheetOpen)

        // ---------------- build, use the bench, store in a crate
        setBag(Item.KIT_WORKBENCH, 1, Item.WOOD, 30, Item.STONE, 20, Item.AXE, 1)
        tap(GameUi.HOTBAR, 0)
        aimCell(128, 133)
        frames(3)
        shot("05-ghost")
        check("context button offers Rotate while holding a kit", ui.context().label == "Rotate")
        tap(GameUi.CTX)
        check("rotate turns the next placement", sim.buildRot == 1)
        tap(GameUi.ACT)
        frames(4)
        val bench = sim.entities.at(128, 133)
        check("a quick tap on the action button places the kit", bench != 0 && sim.entities.kind[bench] == EntityKind.WORKBENCH && sim.entities.rot[bench] == 1)
        aimCell(128, 133, true)
        check("context button offers Use on the workbench", ui.context().label == "Use")
        tap(GameUi.CTX)
        check("the lower bench recipes start off screen", ui.locate(GameUi.RECIPE, 4) == null)
        scrollRecipes(150f)
        check("dragging the list scrolls without crafting", ui.locate(GameUi.RECIPE, 4) != null && sim.inventory.count(Item.KIT_CAMPFIRE) == 0)
        tap(GameUi.RECIPE, 4)
        check("bench recipes craft", sim.inventory.count(Item.KIT_CRATE) == 1)
        shot("06-workbench")
        tap(GameUi.CLOSE)
        val crateSlot = (0 until INVENTORY_SIZE).first { sim.inventory.ids[it] == Item.KIT_CRATE }
        val crate = placeAt(crateSlot, 126, 133)
        check("crate is placed", crate != 0)
        aimCell(126, 133, true)
        check("context button offers Open on the crate", ui.context().label == "Open")
        tap(GameUi.CTX)
        tap(GameUi.SLOT_TARGET, 2)
        tap(GameUi.SLOT_TARGET, (1 shl 8) or 0)
        check("items move from the bag into the crate", sim.entities.containers[crate]!!.count(Item.STONE) == 20)
        shot("07-crate")
        tap(GameUi.CLOSE)
        tap(GameUi.HOTBAR, 5)
        holdAct(1.3f)
        check("holding the action button packs an entity up", sim.entities.at(126, 133) == 0 && sim.inventory.count(Item.KIT_CRATE) == 1 && sim.inventory.count(Item.STONE) == 20)

        // ---------------- smelter through the machine panel
        setBag(Item.KIT_SMELTER, 1, Item.IRON_ORE, 6, Item.COAL, 3)
        val smelter = placeAt(0, 124, 133)
        check("smelter is placed", smelter != 0)
        aimCell(124, 133, true)
        tap(GameUi.CTX)
        check("machine panel says what it needs", machineStatus(sim, smelter) == "Needs iron ore")
        tap(GameUi.SLOT_TARGET, 1); tap(GameUi.SLOT_TARGET, (1 shl 8) or 0)
        tap(GameUi.SLOT_TARGET, 2); tap(GameUi.SLOT_TARGET, (1 shl 8) or 1)
        seconds(0.6f)
        check("smelter starts once it has ore and coal", machineStatus(sim, smelter).startsWith("Smelting"), machineStatus(sim, smelter))
        shot("08-smelter")
        seconds(5.2f)
        check("smelter turns ore into ingots", sim.entities.containers[smelter]!!.counts[SMELTER_OUT] >= 1)
        tap(GameUi.CLOSE)

        // ---------------- drill, belts, crate on the ore deposit
        val dep = sim.nodes.deposits.first { it.item == Item.IRON_ORE }
        val dx = dep.x.toInt()
        val dz = dep.z.toInt()
        setBag(Item.KIT_DRILL, 2, Item.KIT_BELT, 3, Item.KIT_CRATE, 1)
        tap(GameUi.HOTBAR, 0)
        aimCell(124, 135)
        tap(GameUi.ACT)
        frames(4)
        check("a drill cannot go on plain sand, and says why", sim.entities.at(124, 135) == 0 && ui.toastText().contains("deposit"), ui.toastText())
        // The line runs toward -X (rot 1), away from the ore boulders that ring the deposit.
        val drill = placeAt(0, dx, dz, 1)
        var belts = 0
        for (k in 1..3) if (placeAt(1, dx - k, dz, 1) != 0) belts++
        val lineCrate = placeAt(2, dx - 4, dz)
        check("drill, belts and crate are placed in a line", drill != 0 && belts == 3 && lineCrate != 0, "$belts belts")
        seconds(4f)
        shot("09-belts")
        seconds(2.5f)
        check("drill, belts and crate run unattended", lineCrate != 0 && sim.entities.containers[lineCrate]!!.count(Item.IRON_ORE) >= 1)

        // ---------------- Linker in the world
        setBag(Item.KIT_SWITCH, 1, Item.KIT_LAMP, 1, Item.LINKER, 1, Item.KIT_SENSOR, 1, Item.KIT_TURRET, 1)
        val sw = placeAt(0, 122, 136)
        val lamp = placeAt(1, 124, 136)
        check("switch and lamp are placed", sw != 0 && lamp != 0)
        tap(GameUi.HOTBAR, 2)
        aimCell(122, 136, true)
        tap(GameUi.ACT)
        frames(3)
        check("first Linker press picks the source", ui.toastText().startsWith("From Switch"), ui.toastText())
        aimCell(124, 136, true)
        tap(GameUi.ACT)
        frames(3)
        check("second Linker press wires source to target", sim.graph.wireCount == 1 && ui.toastText().contains("Switch now drives Lamp"), ui.toastText())
        tap(GameUi.HOTBAR, 5)
        aimCell(122, 136, true)
        check("context button offers Flip on the switch", ui.context().label == "Flip")
        tap(GameUi.CTX)
        seconds(0.4f)
        check("flipping the switch lights the lamp", sim.entities.active[lamp] == 1)

        // ---------------- circuit board
        tap(GameUi.HOTBAR, 2)
        check("context button offers Circuit with the Linker in hand", ui.context().label == "Circuit")
        tap(GameUi.CTX)
        frames(3)
        check("circuit board opens", ui.board.isOpen)
        val swNode = sim.entities.node[sw]
        val lampNode = sim.entities.node[lamp]
        tapPort(lampNode, 0, false)
        tapBoard(Board.A_REMOVE_WIRE)
        check("a wire can be selected and removed", sim.graph.wireCount == 0)
        tapBoard(Board.A_ADD, NodeType.NOT)
        val not = (sim.graph.high - 1 downTo 1).firstOrNull { sim.graph.type[it] == NodeType.NOT } ?: 0
        check("palette adds a logic node", not != 0)
        sim.graph.bx[not] = sim.graph.bx[lampNode] + 150
        sim.graph.by[not] = sim.graph.by[lampNode]
        frames(2)
        tapPort(swNode, 0, true)
        frames(2)
        val hint = ui.board.hintText
        tapPort(not, 0, false)
        check("tap an output then an input makes a wire", sim.graph.wireCount == 1 && hint.contains("tap the input"), hint)
        // Drag wiring: the wire's end floats 48 units above the finger.
        val from = ui.board.portPosition(not, 0, true)
        val to = ui.board.portPosition(lampNode, 0, false)
        ui.touchDown(px(from.first), px(from.second), 9)
        for (k in 1..8) {
            ui.touchDragged(px(from.first + (to.first - from.first) * k / 8f), px(from.second + (to.second + 48f - from.second) * k / 8f), 9)
            frames(1)
        }
        shot("10-board-drag")
        ui.touchUp(px(to.first), px(to.second + 48f), 9)
        frames(3)
        check("dragging from an output to an input makes a wire", sim.graph.wireCount == 2)
        seconds(0.5f)
        check("the lamp now does the opposite of the switch", sim.entities.active[sw] == 1 && sim.entities.active[lamp] == 0)
        tapBoard(Board.A_ADD, NodeType.DELAY)
        frames(2)
        val before = ui.board.paramText
        tapBoard(Board.A_MORE)
        frames(2)
        check("a node setting can be stepped", before == "Wait 1.0 s" && ui.board.paramText == "Wait 1.2 s", "$before -> ${ui.board.paramText}")
        tapBoard(Board.A_REMOVE_NODE)
        check("a logic node can be removed", (1 until sim.graph.high).none { sim.graph.type[it] == NodeType.DELAY })
        shot("11-board")
        tapBoard(Board.A_DONE)
        check("circuit board closes", !ui.board.isOpen)

        // ---------------- the night
        val sensor = placeAt(3, 126, 136)
        val turret = placeAt(4, 128, 136)
        check("sensor and turret are placed", sensor != 0 && turret != 0)
        tap(GameUi.HOTBAR, 2)
        aimCell(126, 136, true); tap(GameUi.ACT); frames(3)
        aimCell(128, 136, true); tap(GameUi.ACT); frames(3)
        check("sensor is wired to the turret", ui.toastText().contains("Sensor now drives Turret"), ui.toastText())
        tap(GameUi.HOTBAR, 5)
        sim.tick = DAY_TICKS + 95
        sim.vitals.health = VITAL_MAX
        stand(127.5f, 138.5f, sim.player.yaw); sim.player.pitch = -0.1f
        var downed = false
        var sawStray = false
        var waited = 0f
        while (waited < 60f && !downed) {
            seconds(0.5f)
            waited += 0.5f
            sim.vitals.warmth = VITAL_MAX
            if (sim.strays.count > 0 && !sawStray) {
                sawStray = true
                // Look toward it for the picture.
                val i = (0 until MAX_STRAYS).first { sim.strays.alive[it] }
                sim.player.yaw = kotlin.math.atan2(sim.player.x - sim.strays.x[i], sim.player.z - sim.strays.z[i])
            }
            if (sawStray && waited % 2f == 0f && !downed) savePng("$dir/12-night.png")
            downed = ui.toastText().contains("Turret took down a stray")
        }
        check("strays come at night", sawStray)
        check("the wired turret destroys a stray on its own", downed)
        check("the player was never touched", sim.vitals.health == VITAL_MAX, "${sim.vitals.health}")
        sim.tick = CYCLE_TICKS - 30
        seconds(2.5f)
        shot("13-dawn")
        check("surviving to dawn counts the night", sim.nights == 1 && ui.locate(GameUi.DAWN_OK) != null)
        tap(GameUi.DAWN_OK)
        check("strays are gone at dawn", sim.strays.count == 0)

        // ---------------- save and load
        val entities = (1 until sim.entities.high).count { sim.entities.kind[it] != 0 }
        val wires = sim.graph.wireCount
        val ok = g.load(serialize(sim))
        frames(5)
        check("factory and circuits survive a save and load",
            ok && (1 until sim.entities.high).count { sim.entities.kind[it] != 0 } == entities && sim.graph.wireCount == wires && wires == 3 && sim.nights == 1,
            "$entities entities, $wires wires")
        sim.tick = 5000
        stand(127.5f, 142f, 0f); sim.player.pitch = -0.12f
        shot("14-base")
        println("INFO  ${g.stats().replace("\n", "   ")}")
        println("\n$passed/${passed + failed} checks passed")
    }
}
