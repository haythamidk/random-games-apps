package com.worldwake.game

import com.worldwake.game.engine.INSTANCE_FLOATS
import com.worldwake.game.engine.Mat4
import com.worldwake.game.engine.MeshData
import com.worldwake.game.engine.NoiseImage
import com.worldwake.game.engine.PI_F
import com.worldwake.game.engine.ProceduralTextures
import com.worldwake.game.engine.smoothstep
import com.worldwake.game.game.BuildType
import com.worldwake.game.game.GameState
import com.worldwake.game.game.POD_CELL
import com.worldwake.game.game.POD_FLOOR
import com.worldwake.game.game.Placed
import com.worldwake.game.game.Settings
import com.worldwake.game.render.Models
import com.worldwake.game.world.CHUNK_SIZE
import com.worldwake.game.world.ChunkManager
import com.worldwake.game.world.HarvestRegistry
import com.worldwake.game.world.Planet
import com.worldwake.game.world.PoiKind
import com.worldwake.game.world.PropMeshes
import com.worldwake.game.world.PropType
import com.worldwake.game.world.WorldGen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Dumps a real frame's worth of geometry, textures and uniforms so the shaders can be run in WebGL off-device.
 * Set WW_DUMP to a folder; WW_STAGE, WW_TOD, WW_X, WW_Z, WW_YAW, WW_PITCH and WW_TAG choose the view.
 * The draw list mirrors WorldRenderer (without shadows and particles).
 */
class SceneDumpTest {
    private lateinit var dir: File
    private val meshIds = HashMap<MeshData, String>()
    private var bufCount = 0

    private fun floats(a: FloatArray, n: Int): String {
        val name = "b${bufCount++}.f32"
        val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putFloat(a[i])
        File(dir, name).writeBytes(bb.array())
        return name
    }

    private fun ints(a: IntArray, n: Int): String {
        val name = "b${bufCount++}.u32"
        val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putInt(a[i])
        File(dir, name).writeBytes(bb.array())
        return name
    }

    private val meshes = JSONObject()

    private fun mesh(m: MeshData): String = meshIds.getOrPut(m) {
        val id = "m" + meshIds.size
        meshes.put(id, JSONObject().put("v", floats(m.vertices, m.vertexCount * m.stride)).put("i", ints(m.indices, m.indexCount)).put("stride", m.stride).put("n", m.indexCount))
        id
    }

    private fun arr(vararg v: Float) = JSONArray().also { for (x in v) it.put(x.toDouble()) }
    private fun arr(v: FloatArray, n: Int) = JSONArray().also { for (i in 0 until n) it.put(v[i].toDouble()) }

    @Test
    fun dumpScene() {
        val out = System.getenv("WW_DUMP") ?: return
        val tag = System.getenv("WW_TAG") ?: "scene"
        dir = File(out, tag); dir.mkdirs()
        val stage = (System.getenv("WW_STAGE") ?: "0").toFloat()
        val tod = (System.getenv("WW_TOD") ?: "0.38").toFloat()
        val l = WorldGen.lander
        val px = (System.getenv("WW_X") ?: (l.x + 14f).toString()).toFloat()
        val pz = (System.getenv("WW_Z") ?: (l.z + 20f).toString()).toFloat()
        val yaw = (System.getenv("WW_YAW") ?: "3.6").toFloat()
        val pitch = (System.getenv("WW_PITCH") ?: "-0.02").toFloat()
        val radius = (System.getenv("WW_R") ?: "4").toInt()
        val withBase = (System.getenv("WW_BASE") ?: "1") == "1"

        // textures (shared by every scene)
        val texDir = File(out, "tex"); texDir.mkdirs()
        if (!File(texDir, "albedo.bin").exists()) {
            val t = ProceduralTextures.load(null, null)
            DataOutputStream(File(texDir, "albedo.bin").outputStream().buffered()).use { o -> for (a in t.albedo) o.write(a) }
            DataOutputStream(File(texDir, "normal.bin").outputStream().buffered()).use { o -> for (a in t.normals) o.write(a) }
            File(texDir, "noise.bin").writeBytes(NoiseImage.generate(256, 99))
        }

        val g = GameState(Settings())
        val env = g.env
        env.timeOfDay = tod
        repeat(400) { env.update(1f, stage, 1e9f, false) }
        val v = env.v
        val py = WorldGen.height(px, pz)

        // camera
        val aspect = 2340f / 1080f
        val proj = FloatArray(16); val view = FloatArray(16); val vp = FloatArray(16); val invVp = FloatArray(16); val invView = FloatArray(16)
        val cx = px; val cy = py + 1.62f; val cz = pz
        val cp = cos(pitch)
        Mat4.perspective(proj, 70f, aspect, 0.08f, radius * CHUNK_SIZE + 260f)
        Mat4.lookAt(view, cx, cy, cz, cx + sin(yaw) * cp, cy + sin(pitch), cz + cos(yaw) * cp, 0f, 1f, 0f)
        Mat4.mul(vp, proj, view); Mat4.invert(invVp, vp); Mat4.invert(invView, view)
        val ident = FloatArray(16); Mat4.identity(ident)

        val common = JSONObject()
        common.put("u_vp", arr(*vp)); common.put("u_lightVP0", arr(*ident)); common.put("u_lightVP1", arr(*ident))
        common.put("u_sunDir", arr(*env.lightDir)); common.put("u_trueSunDir", arr(*env.sunDir)); common.put("u_sunColor", arr(*env.sunColor))
        common.put("u_skyAmb", arr(*env.skyAmb)); common.put("u_groundAmb", arr(*env.groundAmb)); common.put("u_zenith", arr(*env.zenith))
        common.put("u_horizon", arr(*env.horizon)); common.put("u_sunset", arr(*env.sunset))
        common.put("u_underwater", arr(0f)); common.put("u_waterFog", arr(*Planet.waterFog)); common.put("u_frost", arr(v.frost)); common.put("u_frostColor", arr(*Planet.frostColor))
        common.put("u_fogDensity", arr(env.fogDensity)); common.put("u_fogHeightFalloff", arr(0.02f)); common.put("u_viewDist", arr(radius * CHUNK_SIZE))
        common.put("u_camPos", arr(cx, cy, cz)); common.put("u_exposure", arr(env.exposure)); common.put("u_hdr", arr(1f)); common.put("u_outScale", arr(1f))
        common.put("u_shadowMode", arr(0f)); common.put("u_shadow0", arr(1f)); common.put("u_shadow1", arr(4f)); common.put("u_shadowTexel0", arr(1f / 2048)); common.put("u_shadowTexel1", arr(1f / 2048))
        common.put("u_noise", arr(0f)); common.put("u_albedoArr", arr(2f)); common.put("u_normalArr", arr(3f)); common.put("u_scene", arr(5f)); common.put("u_texQuality", arr(2f))
        common.put("u_lights", arr(*FloatArray(16))); common.put("u_lightColors", arr(*FloatArray(12))); common.put("u_flash", arr(0f)); common.put("u_time", arr(12.3f))

        val draws = JSONArray()
        fun draw(sh: String, m: String, u: JSONObject, inst: String? = null, instN: Int = 0, blend: Int = 0, cull: Boolean = true, depthWrite: Boolean = true, clearDepth: Boolean = false) {
            val d = JSONObject().put("sh", sh).put("mesh", m).put("u", u).put("blend", blend).put("cull", cull).put("dw", depthWrite).put("cd", clearDepth)
            if (inst != null) d.put("inst", inst).put("instN", instN)
            draws.put(d)
        }

        // ---- chunks
        val cm = ChunkManager(HarvestRegistry())
        val pcx = floor(px / CHUNK_SIZE).toInt(); val pcz = floor(pz / CHUNK_SIZE).toInt()
        class C(val dx: Int, val dz: Int, val b: com.worldwake.game.world.ChunkBuild)
        val chunks = ArrayList<C>()
        for (dz in -radius..radius) for (dx in -radius..radius) {
            if (dx * dx + dz * dz > (radius + 0.5f) * (radius + 0.5f)) continue
            val dist = max(abs(dx), abs(dz))
            val lod = if (dist <= 1) 0 else if (dist <= 3) 1 else 2
            chunks.add(C(dx, dz, cm.buildChunk(pcx + dx, pcz + dz, lod, true, 0.8f)))
        }
        val tu = JSONObject().put("u_wetness", arr(env.wetness * 0.8f)).put("u_moss", arr(v.moss)).put("u_grass", arr(v.grass)).put("u_waterLevel", arr(v.waterLevel)).put("u_waterTint", arr(*Planet.waterTint))
        for (c in chunks) draw("terrain", mesh(c.b.terrain), tu)

        // ---- props
        val propMesh = Array(PropType.entries.size) { i -> Array(2) { lod -> PropMeshes.build(PropType.entries[i], lod) } }
        for (t in PropType.entries) {
            val grow = v.grow(t.grow)
            if (grow <= 0.001f) continue
            val u = JSONObject().put("u_wind", arr(env.wind)).put("u_foliage", arr(1f)).put("u_grow", arr(grow)).put("u_translucency", arr(t.translucency))
                .put("u_frostTop", arr(t.frostTop)).put("u_glow", arr(t.glow * (1f + env.nightFactor * 0.7f)))
            if (t.isTree || t == PropType.BUSH) { u.put("u_tintA", arr(0.82f, 0.9f, 1f)); u.put("u_tintB", arr(1.18f, 1.04f, 0.9f)) }
            else { u.put("u_tintA", arr(0.86f, 0.86f, 0.9f)); u.put("u_tintB", arr(1.12f, 1.1f, 1.06f)) }
            val isDecor = t.range == 0
            u.put("u_fadeDist", arr(if (isDecor) 50f else 0f))
            for (c in chunks) {
                val cd = max(abs(c.dx), abs(c.dz))
                if (isDecor && cd > 1) continue
                if (t.range == 1 && cd > 3) continue
                val list = (c.b.nodes!!.filter { it.type == t && it.alive } + c.b.decor!!.filter { it.type == t })
                if (list.isEmpty()) continue
                val a = FloatArray(list.size * INSTANCE_FLOATS)
                for ((i, n) in list.withIndex()) {
                    val o = i * INSTANCE_FLOATS
                    a[o] = n.x; a[o + 1] = n.y; a[o + 2] = n.z; a[o + 3] = n.yaw
                    a[o + 4] = n.scale; a[o + 5] = n.tint; a[o + 6] = t.sway; a[o + 7] = n.grow
                }
                val lod = if (isDecor) (if (cd == 0) 0 else 1) else if (cd <= 1 || (cd <= 2 && t.range == 1)) 0 else 1
                draw("inst", mesh(propMesh[t.ordinal][lod]), u, floats(a, a.size), list.size, cull = false)
            }
        }

        fun lit(model: FloatArray, emissive: Float, spec: Float, frostTop: Float, texScale: Float, alpha: Float = 1f, glass: Float = 0f) =
            JSONObject().put("u_model", arr(*model)).put("u_tint", arr(1f, 1f, 1f, 0f)).put("u_hitFlash", arr(0f)).put("u_alpha", arr(alpha)).put("u_glass", arr(glass))
                .put("u_frostTop", arr(frostTop)).put("u_emissiveColor", arr(1f, 1f, 1f)).put("u_texScale", arr(texScale)).put("u_emissiveScale", arr(emissive)).put("u_specular", arr(spec))

        // ---- structures and watchers
        for (geo in g.structures.geos) {
            if (abs(geo.poi.x - px) > 400f || abs(geo.poi.z - pz) > 400f) continue
            draw("lit", mesh(geo.solid), lit(ident, 0f, 0.3f, if (geo.poi.kind == PoiKind.LANDER) 0.25f else 1f, 0.5f))
            geo.glow?.let { draw("lit", mesh(it), lit(ident, if (geo.poi.kind == PoiKind.VAULT) 0.04f + v.wake * 1.6f else 1f, 0f, 0f, 0.5f)) }
        }
        val sink = smoothstep(0.3f, 0.95f, v.frost) * 5.2f
        val m = FloatArray(16)
        for (i in g.watcherPois.indices) {
            val w = g.watcherPois[i]
            if (abs(w.x - px) > 300f || abs(w.z - pz) > 300f) continue
            Mat4.fromTRS(m, w.x, w.y - sink, w.z, kotlin.math.atan2(px - w.x, pz - w.z), 0f, 0f, 1f, 1f, 1f)
            draw("lit", mesh(g.structures.watcherSolid), lit(m, 0f, 0.15f, 0.9f, 0.5f))
            draw("lit", mesh(g.structures.watcherGlow), lit(m, 0.12f + env.nightFactor * 0.35f + v.wake * 1.4f, 0f, 0f, 0.5f))
            if (v.wake > 0.02f) {
                Mat4.fromTRS(m, w.x, w.y - sink, w.z, 0f, 0f, 0f, v.wake, v.wake, v.wake)
                m[13] = w.y - sink + 6.4f * (1f - v.wake)
                draw("lit", mesh(g.structures.watcherBloom), lit(m, 1.2f, 0.15f, 0f, 0.5f), cull = false)
            }
        }

        // ---- a small base in front of the camera
        val glass = ArrayList<Pair<String, JSONObject>>()
        if (withBase) {
            val fx = sin(yaw); val fz = cos(yaw)
            val rx = -cos(yaw); val rz = sin(yaw)
            var id = 1
            fun at(f: Float, r: Float) = floatArrayOf(px + fx * f + rx * r, pz + fz * f + rz * r)
            val layout = (System.getenv("WW_LAYOUT") ?: "early")
            val list = if (layout == "late") listOf(
                BuildType.HEATER_4 to at(34f, -18f), BuildType.DRILL_4 to at(42f, 6f), BuildType.OXY_3 to at(22f, 10f), BuildType.FUSION to at(30f, 22f),
                BuildType.BIO_2 to at(18f, -9f), BuildType.BIO_4 to at(26f, -3f), BuildType.OXY_4 to at(50f, -10f), BuildType.HEATER_5 to at(62f, 16f),
                BuildType.DRILL_5 to at(70f, -24f), BuildType.BIO_5 to at(56f, 34f), BuildType.SOLAR to at(14f, 6f), BuildType.BIO_3 to at(38f, -34f),
            ) else listOf(
                BuildType.WIND to at(13f, -7f), BuildType.WIND to at(15f, -10.5f), BuildType.HEATER_1 to at(10f, 3.5f), BuildType.HEATER_1 to at(12f, 5.5f),
                BuildType.DRILL_1 to at(16f, 8f), BuildType.DRILL_2 to at(22f, 3f), BuildType.SOLAR to at(19f, -5f), BuildType.OXY_1 to at(8.5f, -3.2f),
                BuildType.HEATER_2 to at(24f, -11f), BuildType.CRATE to at(6.5f, 2.2f), BuildType.BEACON to at(9f, -6f), BuildType.EXTRACTOR to at(27f, 10f),
                BuildType.THERMAL to at(32f, -3f), BuildType.DRILL_3 to at(36f, 9f), BuildType.OXY_2 to at(17f, 13f), BuildType.COLLECTOR to at(12f, 10f),
                BuildType.HEATER_3 to at(40f, -14f),
            )
            for ((t, p) in list) g.placed.add(Placed(id++, t, p[0], WorldGen.height(p[0], p[1]), p[1], yaw + PI_F + id).also { it.born = -100.0 })
            // two joined pods off to the left
            val pp = at(16f, 16f)
            val gx = floor(pp[0] / POD_CELL).toInt(); val gz = floor(pp[1] / POD_CELL).toInt()
            var hi = -999f
            for (i in -1..3) for (j in -1..1) hi = max(hi, WorldGen.height((gx + 0.5f) * POD_CELL + i * 3f, (gz + 0.5f) * POD_CELL + j * 3f))
            for (k in 0 until 2) {
                val pod = Placed(id++, BuildType.POD, (gx + k + 0.5f) * POD_CELL, hi + 0.1f, (gz + 0.5f) * POD_CELL, 0f)
                pod.gx = gx + k; pod.gz = gz; pod.door = if (k == 0) 3 else 1; pod.born = -100.0
                g.placed.add(pod); g.pods[g.cellKey(pod.gx, pod.gz)] = pod
            }
            g.recalc()
            val models = BuildType.entries.associateWith { Models.build(it) }
            val base = Models.podBase(); val sides = Array(3) { Models.podSide(it) }; val ramp = Models.podRamp()
            for (pl in g.placed) {
                if (pl.type == BuildType.POD) {
                    val pcx2 = (pl.gx + 0.5f) * POD_CELL; val pcz2 = (pl.gz + 0.5f) * POD_CELL
                    Mat4.fromTRS(m, pcx2, pl.y, pcz2, 0f, 0f, 0f, 1f, 1f, 1f)
                    draw("lit", mesh(base.first), lit(m, 0f, 0.35f, 0.5f, 0.9f)); draw("lit", mesh(base.second), lit(m, 1f, 0.35f, 0f, 0.9f))
                    for (sd in 0 until 4) {
                        val st = g.sideState(pl, sd)
                        Mat4.fromTRS(m, pcx2, pl.y, pcz2, -sd * PI_F * 0.5f, 0f, 0f, 1f, 1f, 1f)
                        draw("lit", mesh(sides[st].first), lit(m, 0f, 0.35f, 0f, 0.9f))
                        sides[st].second?.let { draw("lit", mesh(it), lit(m, 1f, 0.35f, 0f, 0.9f)) }
                        sides[st].third?.let { glass.add(Pair(mesh(it), lit(m, 0f, 1f, 0f, 1f, 0.16f, 1f))) }
                        if (st == 1) {
                            val drop = g.rampDrop(pl)
                            Mat4.fromTRS(m, pcx2, pl.y + POD_FLOOR * (1f - drop), pcz2, -sd * PI_F * 0.5f, 0f, 0f, 1f, drop, 1f)
                            draw("lit", mesh(ramp), lit(m, 0f, 0.35f, 0f, 0.9f), cull = false)
                        }
                    }
                    continue
                }
                val ms = models[pl.type]!!
                Mat4.fromTRS(m, pl.x, pl.y, pl.z, pl.yaw, 0f, 0f, 1f, 1f, 1f)
                draw("lit", mesh(ms.solid), lit(m, 0.5f, 0.35f, 0.35f, 0.9f))
                ms.glow?.let { draw("lit", mesh(it), lit(m, 1.3f, 0.35f, 0f, 0.9f)) }
                ms.spin?.let { draw("lit", mesh(it), lit(m, 1f, 0.35f, 0f, 0.9f), cull = false) }
                ms.glass?.let { glass.add(Pair(mesh(it), lit(m, 0f, 1f, 0f, 1f, 0.16f, 1f))) }
            }
        }

        // ---- sky
        draw("sky", mesh(Models.skyTriangle()), JSONObject().put("u_invViewProj", arr(*invVp)).put("u_moonDir", arr(*env.moonDir)).put("u_night", arr(env.nightFactor))
            .put("u_starVis", arr(env.starVis)).put("u_giantVis", arr(env.giantVis)).put("u_aurora", arr(env.aurora)).put("u_cloudCover", arr(env.clouds)).put("u_clouds", arr(2f)),
            cull = false, depthWrite = false)

        // ---- water
        if (v.waterLevel > -50f) {
            val wu = JSONObject().put("u_waterQuality", arr(1f)).put("u_refract", arr(0f)).put("u_rain", arr(env.rain)).put("u_waveScale", arr(0.6f + env.wind * 0.8f))
                .put("u_waterLevel", arr(v.waterLevel)).put("u_waterAbsorb", arr(*Planet.waterAbsorb)).put("u_waterScatter", arr(*Planet.waterScatter))
                .put("u_waterGlow", arr(env.nightFactor * 0.35f + v.wake * 0.25f))
            for (c in chunks) c.b.water?.let { if (c.b.minY <= v.waterLevel + 0.2f) draw("water", mesh(it), wu, blend = 1, cull = false, depthWrite = false) }
        }
        for ((mid, u) in glass) draw("lit", mid, u, blend = 1, cull = false, depthWrite = false)

        // ---- first person tool
        val tool = Models.viewTool()
        val tproj = FloatArray(16); val vvp = FloatArray(16); val local = FloatArray(16)
        Mat4.perspective(tproj, 58f, aspect, 0.03f, 10f); Mat4.mul(vvp, tproj, view)
        Mat4.fromTRS(local, 0.22f, -0.17f, -0.46f, -0.08f, 0.16f, 0f, 1f, 1f, 1f); Mat4.mul(m, invView, local)
        draw("lit", mesh(tool.first), lit(m, 0f, 0.3f, 0f, 2.2f).put("u_vp", arr(*vvp)), clearDepth = true)
        draw("lit", mesh(tool.second), lit(m, 0.8f, 0.3f, 0f, 2.2f).put("u_vp", arr(*vvp)))

        val post = JSONObject().put("u_grade", arr(1f, 1f, 1f)).put("u_saturation", arr(1.04f - 0.18f * env.nightFactor)).put("u_contrast", arr(0.2f)).put("u_vignette", arr(0.5f))
            .put("u_bloomStrength", arr(0.6f * 0.13f)).put("u_waterFog", arr(*Planet.waterFog))
            .put("u_visor", if (v.breathable) arr(0f, 0f, 0f) else arr(0.03f + 0.27f * v.frost, 0.05f + 0.29f * v.frost, 0.07f + 0.39f * v.frost))
        val root = JSONObject().put("common", common).put("meshes", meshes).put("draws", draws).put("post", post).put("clear", arr(*env.fogColor))
        File(dir, "scene.json").writeText(root.toString())
        println("dumped $tag: ${draws.length()} draws, ${meshIds.size} meshes, stage $stage, ground $py")
    }
}
