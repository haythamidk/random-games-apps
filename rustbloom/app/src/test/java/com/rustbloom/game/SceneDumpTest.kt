package com.rustbloom.game

import com.rustbloom.game.engine.MeshData
import com.rustbloom.game.engine.NoiseImage
import com.rustbloom.game.engine.ProceduralTextures
import com.rustbloom.game.game.Environment
import com.rustbloom.game.world.ChunkManager
import com.rustbloom.game.world.HarvestRegistry
import com.rustbloom.game.world.StructureBuilder
import com.rustbloom.game.world.VegType
import com.rustbloom.game.world.VegetationMeshes
import com.rustbloom.game.world.WorldGen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Dumps real world geometry, textures and lighting to disk so the GLSL shaders can be rendered in a WebGL2
 * harness for visual checks. Only runs when RUSTBLOOM_DUMP is set.
 */
class SceneDumpTest {
    private fun floats(f: File, a: FloatArray, n: Int) {
        val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putFloat(a[i])
        f.writeBytes(bb.array())
    }
    private fun ints(f: File, a: IntArray, n: Int) {
        val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putInt(a[i])
        f.writeBytes(bb.array())
    }

    @Test
    fun dump() {
        val dir = System.getenv("RUSTBLOOM_DUMP") ?: return
        val out = File(dir).apply { mkdirs() }
        val tex = ProceduralTextures.load(null, java.util.concurrent.Executors.newFixedThreadPool(4))
        DataOutputStream(File(out, "albedo.bin").outputStream()).use { o -> for (a in tex.albedo) o.write(a) }
        DataOutputStream(File(out, "normal.bin").outputStream()).use { o -> for (a in tex.normals) o.write(a) }
        File(out, "noise.bin").writeBytes(NoiseImage.generate(256, 99))

        val cx0 = System.getenv("RB_X")?.toFloat() ?: (WorldGen.vaultPoi.x + 4f)
        val cz0 = System.getenv("RB_Z")?.toFloat() ?: (WorldGen.vaultPoi.z + 14f)
        val cm = ChunkManager(HarvestRegistry())
        val meta = JSONObject()
        val chunks = JSONArray()
        val inst = HashMap<VegType, ArrayList<Float>>()
        val pcx = floor(cx0 / 64f).toInt(); val pcz = floor(cz0 / 64f).toInt()
        var k = 0
        for (dz in -3..3) for (dx in -3..3) {
            val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz))
            val b = cm.buildChunk(pcx + dx, pcz + dz, if (dist <= 1) 0 else 1, true, 0.8f)
            floats(File(out, "t$k.v"), b.terrain.vertices, b.terrain.vertexCount * b.terrain.stride)
            ints(File(out, "t$k.i"), b.terrain.indices, b.terrain.indexCount)
            val cj = JSONObject().put("id", k).put("idx", b.terrain.indexCount)
            if (b.water != null) {
                floats(File(out, "w$k.v"), b.water!!.vertices, b.water!!.vertexCount * 10)
                ints(File(out, "w$k.i"), b.water!!.indices, b.water!!.indexCount)
                cj.put("widx", b.water!!.indexCount)
            }
            chunks.put(cj)
            for (n in (b.nodes ?: emptyList()) + (b.decor ?: emptyList())) {
                if (n.type.decorative && dist > 1) continue
                val l = inst.getOrPut(n.type) { ArrayList() }
                l.addAll(listOf(n.x, n.y, n.z, n.yaw, n.scale, n.tint, n.type.sway, 0f))
            }
            k++
        }
        meta.put("chunks", chunks)
        val veg = JSONArray()
        for ((t, l) in inst) {
            val m: MeshData = VegetationMeshes.build(t, 0)
            floats(File(out, "veg_${t.name}.v"), m.vertices, m.vertexCount * 10)
            ints(File(out, "veg_${t.name}.i"), m.indices, m.indexCount)
            floats(File(out, "veg_${t.name}.inst"), l.toFloatArray(), l.size)
            veg.put(JSONObject().put("name", t.name).put("idx", m.indexCount).put("count", l.size / 8).put("trans", t.translucency))
        }
        meta.put("veg", veg)
        // structures near the camera
        val sb = StructureBuilder().buildAll()
        val st = JSONArray()
        for ((i, g) in sb.geos.withIndex()) {
            val dx = g.poi.x - cx0; val dz = g.poi.z - cz0
            if (dx * dx + dz * dz > 250f * 250f) continue
            floats(File(out, "s$i.v"), g.solid.vertices, g.solid.vertexCount * 10)
            ints(File(out, "s$i.i"), g.solid.indices, g.solid.indexCount)
            st.put(JSONObject().put("id", i).put("idx", g.solid.indexCount))
        }
        meta.put("structs", st)
        val envs = JSONArray()
        for (t in floatArrayOf(10f, 17.6f, 21.5f)) {
            val e = Environment(); e.time = t; e.computeLighting()
            fun arr(a: FloatArray) = JSONArray(a.map { it.toDouble() })
            envs.put(JSONObject().put("time", t.toDouble()).put("sunDir", arr(e.sunDir)).put("lightDir", arr(e.lightDir)).put("sunColor", arr(e.sunColor))
                .put("skyAmb", arr(e.skyAmb)).put("groundAmb", arr(e.groundAmb)).put("zenith", arr(e.zenith)).put("horizon", arr(e.horizon))
                .put("sunset", arr(e.sunset)).put("fogColor", arr(e.fogColor)).put("fogDensity", e.fogDensity.toDouble()).put("exposure", e.exposure.toDouble())
                .put("night", e.nightFactor.toDouble()).put("golden", e.golden.toDouble()))
        }
        meta.put("envs", envs)
        meta.put("cam", JSONArray(listOf(cx0.toDouble(), (WorldGen.height(cx0, cz0) + 1.65f).toDouble(), cz0.toDouble())))
        File(out, "meta.json").writeText(meta.toString())
        println("dumped scene to $out")
    }
}
