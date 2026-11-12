package scrapline

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import scrapline.sim.MoveIntent
import scrapline.sim.Sim
import scrapline.sim.TICK_DT
import scrapline.sim.deserialize
import scrapline.sim.serialize
import scrapline.ui.GameUi
import scrapline.ui.UiHost
import scrapline.view.WorldRenderer
import kotlin.math.min

private const val MAX_FRAME_DT = 0.1f
private const val MAX_TICKS_PER_FRAME = 5
private const val AUTOSAVE_SECONDS = 30f
private const val PREFS = "scrapline"
const val DEFAULT_SEED = 20261111

/**
 * The game: owns the simulation, runs it at a fixed 20 Hz, and draws it every frame.
 *
 * `script`, when given, is called once per frame before the update; automated
 * runs use it to drive the game with synthetic touches and take screenshots.
 * `fresh` ignores any saved game and never writes one.
 */
class ScraplineGame(
    private val script: ((ScraplineGame, Int) -> Unit)? = null,
    private val fresh: Boolean = false,
) : ApplicationAdapter(), UiHost {
    override lateinit var sim: Sim
        private set
    lateinit var world: WorldRenderer
        private set
    lateinit var ui: GameUi
        private set
    override var sensitivity = 1f
        set(value) {
            field = value
            if (!fresh) Gdx.app.getPreferences(PREFS).putFloat("sensitivity", value).flush()
        }
    private val intent = MoveIntent()
    private var acc = 0f
    private var time = 0f
    private var frame = 0
    private var sinceSave = 0f
    private var tickMs = 0f
    private var mouseLook = false

    override fun create() {
        val prefs = Gdx.app.getPreferences(PREFS)
        sim = (if (fresh) null else deserialize(prefs.getString("save", null))) ?: Sim(DEFAULT_SEED)
        if (!fresh) sensitivity = prefs.getFloat("sensitivity", 1f).coerceIn(0.4f, 2f)
        world = WorldRenderer(sim)
        ui = GameUi(this)
        resize(Gdx.graphics.width, Gdx.graphics.height)
        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun touchDown(x: Int, y: Int, pointer: Int, button: Int): Boolean {
                // On desktop the right mouse button looks around instead of touching the screen.
                if (button == Input.Buttons.RIGHT) mouseLook = true else ui.touchDown(x, y, pointer)
                return true
            }

            override fun touchDragged(x: Int, y: Int, pointer: Int): Boolean {
                if (mouseLook) ui.addLook(Gdx.input.deltaX / ui.canvas.scale, Gdx.input.deltaY / ui.canvas.scale)
                else ui.touchDragged(x, y, pointer)
                return true
            }

            override fun touchUp(x: Int, y: Int, pointer: Int, button: Int): Boolean {
                if (button == Input.Buttons.RIGHT) mouseLook = false else ui.touchUp(x, y, pointer)
                return true
            }

            override fun keyDown(keycode: Int): Boolean {
                when (keycode) {
                    Input.Keys.F3 -> ui.debug = !ui.debug
                    in Input.Keys.NUM_1..Input.Keys.NUM_6 -> ui.select(keycode - Input.Keys.NUM_1)
                }
                return true
            }
        }
    }

    override fun resize(width: Int, height: Int) {
        world.resize(width, height)
        ui.resize(width, height)
        ui.canvas.safeLeft = Gdx.graphics.safeInsetLeft / ui.canvas.scale
        ui.canvas.safeRight = Gdx.graphics.safeInsetRight / ui.canvas.scale
    }

    override fun newWorld() {
        world.dispose()
        sim = Sim(DEFAULT_SEED)
        world = WorldRenderer(sim)
        world.resize(Gdx.graphics.width, Gdx.graphics.height)
        save()
    }

    fun save() {
        if (fresh) return
        Gdx.app.getPreferences(PREFS).putString("save", serialize(sim)).flush()
    }

    /** Replaces the running game with a saved one; used by automated checks. */
    fun load(data: String): Boolean {
        val loaded = deserialize(data) ?: return false
        world.dispose()
        sim = loaded
        world = WorldRenderer(sim)
        world.resize(Gdx.graphics.width, Gdx.graphics.height)
        return true
    }

    override fun stats(): String =
        "${Gdx.graphics.framesPerSecond} fps   draws ${world.drawCalls}   tris ${world.triangles}\n" +
            "tick ${String.format("%.2f", tickMs)} ms   heap ${Gdx.app.javaHeap / 1048576} MB"

    override fun pause() = save()

    override fun render() {
        val dt = min(MAX_FRAME_DT, Gdx.graphics.deltaTime)
        time += dt
        script?.invoke(this, frame++)
        pollKeyboard()

        ui.consume(intent)
        sim.actHeld = ui.act && !ui.modal
        if (ui.actTapped && !ui.modal) sim.actPressed = true
        ui.actTapped = false
        sim.stepPlayer(intent, dt)

        acc += dt
        var ticks = 0
        val t0 = System.nanoTime()
        while (acc >= TICK_DT && ticks < MAX_TICKS_PER_FRAME) {
            sim.tick()
            acc -= TICK_DT
            ticks++
        }
        // If we fell too far behind, drop the backlog rather than spiral.
        if (ticks == MAX_TICKS_PER_FRAME) acc = 0f
        if (ticks > 0) tickMs = (System.nanoTime() - t0) / 1e6f / ticks

        ui.update(dt)
        world.render(dt, time, acc / TICK_DT)
        ui.draw()
        sim.eventCount = 0

        sinceSave += dt
        if (sinceSave >= AUTOSAVE_SECONDS) {
            sinceSave = 0f
            save()
        }
    }

    /** Desktop convenience: WASD, shift, space and E alongside the touch controls. */
    private fun pollKeyboard() {
        val i = Gdx.input
        val x = (if (i.isKeyPressed(Input.Keys.D)) 1f else 0f) - (if (i.isKeyPressed(Input.Keys.A)) 1f else 0f)
        val y = (if (i.isKeyPressed(Input.Keys.W)) 1f else 0f) - (if (i.isKeyPressed(Input.Keys.S)) 1f else 0f)
        ui.setKeys(x, y, i.isKeyPressed(Input.Keys.SHIFT_LEFT), i.isKeyJustPressed(Input.Keys.SPACE), i.isKeyPressed(Input.Keys.E))
    }

    override fun dispose() {
        save()
        world.dispose()
        ui.dispose()
    }
}
