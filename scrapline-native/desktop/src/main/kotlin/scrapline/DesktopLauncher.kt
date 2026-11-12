package scrapline

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.ScreenUtils

/**
 * Desktop entry point. With no arguments it opens a playable window
 * (WASD, right-drag to look, E to act, or click the touch controls).
 * With `--smoke <dir>` it plays through the whole game loop with synthetic
 * touches, checks the results, saves screenshots to <dir> and exits with a
 * non-zero status if any check failed. That is how the native build is
 * verified on a machine with no screen.
 */
fun main(args: Array<String>) {
    val smokeDir = args.indexOf("--smoke").let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Scrapline")
        setWindowedMode(1688, 780)
        useVsync(smokeDir == null)
        setForegroundFPS(60)
    }
    if (smokeDir == null) {
        Lwjgl3Application(ScraplineGame(), config)
        return
    }
    val smoke = Smoke(smokeDir)
    Lwjgl3Application(ScraplineGame(smoke::frame, fresh = true), config)
    kotlin.system.exitProcess(if (smoke.failed == 0) 0 else 1)
}

fun savePng(path: String) {
    val w = Gdx.graphics.backBufferWidth
    val h = Gdx.graphics.backBufferHeight
    val pixels = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true)
    val pixmap = Pixmap(w, h, Pixmap.Format.RGBA8888)
    pixmap.pixels.put(pixels).rewind()
    PixmapIO.writePNG(Gdx.files.absolute(path), pixmap)
    pixmap.dispose()
}
