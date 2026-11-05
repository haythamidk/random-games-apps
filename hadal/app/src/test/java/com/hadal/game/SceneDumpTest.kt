package com.hadal.game

import com.hadal.game.engine.MeshData
import com.hadal.game.engine.NoiseImage
import com.hadal.game.engine.ProceduralTextures
import com.hadal.game.game.Species
import com.hadal.game.render.Models
import com.hadal.game.world.ChunkManager
import com.hadal.game.world.FloraMeshes
import com.hadal.game.world.FloraType
import com.hadal.game.world.HarvestRegistry
import com.hadal.game.world.SeaGen
import com.hadal.game.world.Structures
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Dumps real world geometry and textures so the GLSL shaders can be rendered in a WebGL2 harness for visual
 * checks. Only runs when HADAL_DUMP is set (HADAL_X / HADAL_Z choose the spot).
 */
class SceneDumpTest {
    private fun floats(f: File, a: FloatArray, n: Int) {
        val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN); for (i in 0 until n) bb.putFloat(a[i]); f.writeBytes(bb.array())
    }
    private fun ints(f: File, a: IntArray, n: Int) {
        val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN); for (i in 0 until n) bb.putInt(a[i]); f.writeBytes(bb.array())
    }
    private fun mesh(out: File, name: String, m: MeshData) {
        floats(File(out, "$name.v"), m.vertices, m.vertexCount * m.stride); ints(File(out, "$name.i"), m.indices, m.indexCount)
    }

    @Test
    fun dump() {
        val dir = System.getenv("HADAL_DUMP") ?: return
        val out = File(dir).apply { mkdirs() }
        if (!File(out, "albedo.bin").exists()) {
            val tex = ProceduralTextures.load(null, java.util.concurrent.Executors.newFixedThreadPool(4))
            DataOutputStream(File(out, "albedo.bin").outputStream()).use { o -> for (a in tex.albedo) o.write(a) }
            DataOutputStream(File(out, "normal.bin").outputStream()).use { o -> for (a in tex.normals) o.write(a) }
            File(out, "noise.bin").writeBytes(NoiseImage.generate(256, 99))
            File(out, "caustics.bin").writeBytes(ProceduralTextures.caustics(256))
            for (t in FloraType.entries) mesh(out, "flora_${t.name}", FloraMeshes.get(t))
            mesh(out, "sub", Models.sub())
            for (s in Species.entries) mesh(out, "cr_${s.name}", Models.creature(s))
            mesh(out, "seg_PALE", Models.segment(Species.PALE))
            mesh(out, "echo", Structures.echoStoneMesh())
        }
        val x0 = System.getenv("HADAL_X")?.toFloat() ?: 0f
        val z0 = System.getenv("HADAL_Z")?.toFloat() ?: 20f
        val tag = System.getenv("HADAL_TAG") ?: "scene"
        val sd = File(out, tag).apply { mkdirs() }
        val cm = ChunkManager(HarvestRegistry())
        val meta = JSONObject()
        val chunks = JSONArray()
        val inst = HashMap<FloraType, ArrayList<Float>>()
        val pcx = floor(x0 / 64f).toInt(); val pcz = floor(z0 / 64f).toInt()
        var k = 0
        for (dz in -2..2) for (dx in -2..2) {
            val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz))
            val b = cm.buildChunk(pcx + dx, pcz + dz, if (dist <= 1) 0 else 1, true, 1f)
            floats(File(sd, "t$k.v"), b.terrain.vertices, b.terrain.vertexCount * b.terrain.stride)
            ints(File(sd, "t$k.i"), b.terrain.indices, b.terrain.indexCount)
            chunks.put(JSONObject().put("id", k).put("idx", b.terrain.indexCount))
            for (n in b.nodes ?: emptyList()) {
                if (dist > 1 && (n.type == FloraType.SEAGRASS || n.type == FloraType.CORAL_BRANCH || n.type == FloraType.TUBEWORMS || n.type == FloraType.ANEMONE)) continue
                inst.getOrPut(n.type) { ArrayList() }.addAll(listOf(n.x, n.y, n.z, n.yaw, n.scale, n.tint, n.type.sway, (n.key and 1023).toFloat()))
            }
            k++
        }
        meta.put("chunks", chunks)
        val fl = JSONArray()
        for ((t, l) in inst) { floats(File(sd, "inst_${t.name}"), l.toFloatArray(), l.size); fl.put(JSONObject().put("name", t.name).put("count", l.size / 8)) }
        meta.put("flora", fl)
        Structures.buildAll()
        val st = JSONArray()
        for ((i, g) in Structures.geos.withIndex()) {
            val dx = g.poi.x - x0; val dz = g.poi.z - z0
            if (dx * dx + dz * dz > 200f * 200f) continue
            mesh(sd, "s$i", g.mesh)
            val fx = JSONArray()
            for (f in g.fixtures) fx.put(JSONArray(listOf(f.x, f.y, f.z, f.range, f.r, f.g, f.b).map { it.toDouble() }))
            st.put(JSONObject().put("id", i).put("fixtures", fx))
        }
        meta.put("structs", st)
        meta.put("floor", SeaGen.height(x0, z0).toDouble())
        meta.put("pos", JSONArray(listOf(x0.toDouble(), z0.toDouble())))
        File(sd, "meta.json").writeText(meta.toString())
        println("dumped $tag at $x0,$z0 floor ${SeaGen.height(x0, z0)}")
    }
}
