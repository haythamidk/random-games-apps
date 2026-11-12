package scrapline

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration

/** Android entry point: full screen, landscape, screen kept on. */
class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            useWakelock = true
            useAccelerometer = false
            useCompass = false
            numSamples = 0
        }
        initialize(ScraplineGame(), config)
    }
}
