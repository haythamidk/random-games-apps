package com.worldwake.game

import com.worldwake.game.engine.Coplanar
import com.worldwake.game.engine.MeshData
import com.worldwake.game.game.BuildType
import com.worldwake.game.render.Models
import com.worldwake.game.world.Structures
import org.junit.Assert.assertEquals
import org.junit.Test

/** No model may ship with two faces in the same place: that is what flickers on a phone. */
class FlickerTest {
    private fun check(name: String, m: MeshData?): Int {
        if (m == null) return 0
        val r = Coplanar.lift(m, apply = false)
        if (r.pairs > 0) println("still overlapping: $name ${r.pairs}")
        return r.pairs
    }

    @Test
    fun noModelHasFacesFightingForTheSamePlace() {
        var bad = 0
        for (t in BuildType.entries) { val m = Models.build(t); bad += check("$t solid", m.solid) + check("$t glow", m.glow) + check("$t spin", m.spin) }
        val base = Models.podBase(); bad += check("pod base", base.first) + check("pod base glow", base.second)
        for (k in 0 until 3) { val s = Models.podSide(k); bad += check("pod side $k", s.first) + check("pod side glow $k", s.second) }
        bad += check("ramp", Models.podRamp())
        val tool = Models.viewTool(); bad += check("tool", tool.first) + check("tool glow", tool.second)
        val st = Structures()
        for (g in st.geos) bad += check("site ${g.poi.kind}", g.solid) + check("site glow ${g.poi.kind}", g.glow)
        bad += check("watcher", st.watcherSolid)
        assertEquals(0, bad)
    }
}
