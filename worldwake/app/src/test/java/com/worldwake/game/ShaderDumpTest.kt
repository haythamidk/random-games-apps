package com.worldwake.game

import com.worldwake.game.engine.Shaders
import org.junit.Test
import java.io.File

/** Writes every shader to WW_DUMP so they can be compiled in a real GLSL ES 3.00 compiler off-device. */
class ShaderDumpTest {
    @Test
    fun dumpShaders() {
        val dir = System.getenv("WW_DUMP") ?: return
        val d = File(dir, "shaders"); d.mkdirs()
        for ((name, vs, fs) in Shaders.all()) {
            File(d, "$name.vert").writeText(vs)
            File(d, "$name.frag").writeText(fs)
        }
    }
}
