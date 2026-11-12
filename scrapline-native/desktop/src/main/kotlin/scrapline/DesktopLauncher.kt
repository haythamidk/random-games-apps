package scrapline

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.ScreenUtils
import scrapline.sim.DAY_TICKS
import scrapline.sim.EntityKind
import scrapline.sim.Item

/**
 * Desktop entry point. With no arguments it opens a playable window. With
 * `--shots <dir>` it runs a fixed tour of scenes, saves a PNG of each and exits;
 * that is how the look is checked on a machine with no screen.
 */
fun main(args: Array<String>) {
    val shotDir = args.indexOf("--shots").let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Scrapline")
        setWindowedMode(1688, 780)
        useVsync(shotDir == null)
        setForegroundFPS(60)
    }
    Lwjgl3Application(ScraplineGame(if (shotDir != null) tour(shotDir) else null), config)
}

private fun savePng(path: String) {
    val w = Gdx.graphics.backBufferWidth
    val h = Gdx.graphics.backBufferHeight
    val pixels = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true)
    val pixmap = Pixmap(w, h, Pixmap.Format.RGBA8888)
    pixmap.pixels.put(pixels).rewind()
    PixmapIO.writePNG(Gdx.files.absolute(path), pixmap)
    pixmap.dispose()
}

/** Scenes for screenshots: each sets up the world, waits a few frames, then saves an image of the previous frame. */
private fun tour(dir: String): (ScraplineGame, Int) -> Unit {
    val scenes = listOf<Pair<String, (ScraplineGame) -> Unit>>(
        "01-morning" to { g ->
            g.sim.tick = 2400
            g.sim.player.yaw = 0.5f
            g.sim.player.pitch = -0.05f
        },
        "02-dunes" to { g ->
            g.sim.player.x = 150f; g.sim.player.z = 150f
            g.sim.player.yaw = 2.2f
            g.sim.player.pitch = -0.08f
            g.sim.tick = 7000
        },
        "03-base" to { g ->
            val s = g.sim
            s.tick = 9000
            s.player.x = 128.5f; s.player.z = 132f; s.player.yaw = 0f; s.player.pitch = -0.2f
            fun put(k: Int, cx: Int, cz: Int, rot: Int = 0) = s.entities.place(k, cx, cz, rot, s.world.heightAt(cx + 0.5f, cz + 0.5f))
            put(EntityKind.WORKBENCH, 126, 127); put(EntityKind.CRATE, 125, 128); put(EntityKind.CAMPFIRE, 128, 129)
            put(EntityKind.SMELTER, 130, 127, 1)
            for (i in 0..3) { val b = put(EntityKind.BELT, 131 + i, 127, 1); s.entities.belt[b * 4 + 1] = Item.IRON_ORE }
            put(EntityKind.DRILL, 135, 127, 1)
            for (i in 0..4) put(EntityKind.WALL, 124 + i, 124)
            put(EntityKind.TURRET, 131, 130); put(EntityKind.SENSOR, 132, 131)
            val lamp = put(EntityKind.LAMP, 124, 129); s.entities.enabled[lamp] = 1
            put(EntityKind.SWITCH, 127, 125)
            s.inventory.ids[0] = Item.PICK; s.inventory.counts[0] = 1; s.selected = 0
        },
        "04-sunset" to { g ->
            g.sim.tick = DAY_TICKS - 260
            g.sim.player.yaw = -1.75f
            g.sim.player.pitch = 0.02f
        },
        "05-night" to { g ->
            g.sim.tick = DAY_TICKS + 900
            g.sim.strays.spawned = 99
            g.sim.vitals.warmth = 1000
            g.sim.player.yaw = 0.3f
            g.sim.player.pitch = -0.15f
            scrapline.sim.spawnStray(g.sim, 127f, 122f)
        },
    )
    val framesPerScene = 12
    return { game, frame ->
        val index = frame / framesPerScene
        val step = frame % framesPerScene
        if (step == 0) {
            if (index > 0) savePng("$dir/${scenes[index - 1].first}.png")
            if (index >= scenes.size) Gdx.app.exit() else scenes[index].second(game)
        }
    }
}
