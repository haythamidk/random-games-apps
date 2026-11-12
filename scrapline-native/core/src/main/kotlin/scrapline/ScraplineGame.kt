package scrapline

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import scrapline.sim.MoveIntent
import scrapline.sim.Sim
import scrapline.sim.TICK_DT
import scrapline.view.WorldRenderer
import kotlin.math.min

private const val MAX_FRAME_DT = 0.1f
private const val MAX_TICKS_PER_FRAME = 5
const val DEFAULT_SEED = 20261111

/**
 * The game: owns the simulation, runs it at a fixed 20 Hz, and draws it every frame.
 * `script`, when given, is called once per frame before the update; automated
 * runs use it to drive the game and take screenshots.
 */
class ScraplineGame(private val script: ((ScraplineGame, Int) -> Unit)? = null) : ApplicationAdapter() {
    lateinit var sim: Sim
        private set
    lateinit var world: WorldRenderer
        private set
    val intent = MoveIntent()
    private var acc = 0f
    private var time = 0f
    private var frame = 0

    override fun create() {
        sim = Sim(DEFAULT_SEED)
        world = WorldRenderer(sim)
        world.resize(Gdx.graphics.width, Gdx.graphics.height)
    }

    override fun resize(width: Int, height: Int) {
        world.resize(width, height)
    }

    override fun render() {
        val dt = min(MAX_FRAME_DT, Gdx.graphics.deltaTime)
        time += dt
        script?.invoke(this, frame++)

        sim.stepPlayer(intent, dt)
        intent.lookYaw = 0f
        intent.lookPitch = 0f
        intent.jump = false

        acc += dt
        var ticks = 0
        while (acc >= TICK_DT && ticks < MAX_TICKS_PER_FRAME) {
            sim.tick()
            acc -= TICK_DT
            ticks++
        }
        // If we fell too far behind, drop the backlog rather than spiral.
        if (ticks == MAX_TICKS_PER_FRAME) acc = 0f

        world.render(dt, time, acc / TICK_DT)
        sim.eventCount = 0
    }

    override fun dispose() {
        world.dispose()
    }
}
