package com.rustbloom.game.render

import android.opengl.GLES30.*
import com.rustbloom.game.engine.Frustum
import com.rustbloom.game.engine.GLUtil
import com.rustbloom.game.engine.Mat4
import com.rustbloom.game.engine.Mesh
import com.rustbloom.game.engine.MeshData
import com.rustbloom.game.engine.NoiseImage
import com.rustbloom.game.engine.Shader
import com.rustbloom.game.engine.Shaders
import com.rustbloom.game.engine.ShadowMap
import com.rustbloom.game.engine.Textures
import com.rustbloom.game.engine.Vec3
import com.rustbloom.game.engine.clamp
import com.rustbloom.game.game.BuildType
import com.rustbloom.game.game.CState
import com.rustbloom.game.game.CreatureType
import com.rustbloom.game.game.GameState
import com.rustbloom.game.game.Item
import com.rustbloom.game.game.ProjKind
import com.rustbloom.game.game.ToolKind
import com.rustbloom.game.game.Variant
import com.rustbloom.game.world.CHUNK_SIZE
import com.rustbloom.game.world.Chunk
import com.rustbloom.game.world.PoiKind
import com.rustbloom.game.world.VegType
import com.rustbloom.game.world.VegetationMeshes
import com.rustbloom.game.world.WorldGen
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** CPU-side geometry prepared off the GL thread so the first frame stays fast. */
object RenderAssets {
    @Volatile var ready = false
        private set
    lateinit var noise: ByteArray
    lateinit var veg: Array<Array<MeshData>>
    lateinit var creatures: Map<CreatureType, CreatureModel>
    lateinit var builds: Map<BuildType, Pair<MeshData, MeshData?>>
    lateinit var tools: Map<Item, MeshData>
    lateinit var misc: Map<String, MeshData>

    @Synchronized fun prepare() {
        if (ready) return
        noise = NoiseImage.generate(256, 99)
        veg = Array(VegType.entries.size) { i -> Array(2) { l -> VegetationMeshes.build(VegType.entries[i], l) } }
        creatures = CreatureType.entries.associateWith { CreatureModels.build(it) }
        builds = BuildType.entries.associateWith { PropMeshes.build(it) }
        tools = Item.entries.associateWith { PropMeshes.tool(it) }
        misc = mapOf(
            "arm" to PropMeshes.arm(), "arrow" to PropMeshes.arrow(), "nock" to PropMeshes.arrowNocked(),
            "bolt" to PropMeshes.bolt(), "cube" to PropMeshes.cube(), "sky" to PropMeshes.skyTriangle(),
            "particles" to PropMeshes.particles(3000),
        )
        ready = true
    }
}

/** Renders the world. All methods run on the GL thread. */
class WorldRenderer(private val game: GameState) {
    private lateinit var lit: Shader
    private lateinit var inst: Shader
    private lateinit var terrain: Shader
    private lateinit var water: Shader
    private lateinit var sky: Shader
    private lateinit var depth: Shader
    private lateinit var depthInst: Shader
    private lateinit var particle: Shader
    private lateinit var unlit: Shader

    private var noiseTex = 0
    private var shadow: ShadowMap? = null
    private val vegMeshes = Array(VegType.entries.size) { arrayOfNulls<Mesh>(2) }
    private val structSolid = ArrayList<Mesh>()
    private val structGlow = ArrayList<Mesh?>()
    private val creatureModels = HashMap<CreatureType, CreatureModel>()
    private val creatureMeshes = HashMap<CreatureType, List<Mesh>>()
    private val buildMeshes = HashMap<BuildType, Pair<Mesh, Mesh?>>()
    private val toolMeshes = HashMap<Item, Mesh>()
    private lateinit var armMesh: Mesh
    private lateinit var arrowMesh: Mesh
    private lateinit var nockMesh: Mesh
    private lateinit var boltMesh: Mesh
    private lateinit var cubeMesh: Mesh
    private lateinit var skyMesh: Mesh
    private lateinit var particleMesh: Mesh
    private val particleCount = 3000

    private var width = 1
    private var height = 1
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val invVp = FloatArray(16)
    private val invView = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private val lightVp = FloatArray(16)
    private val model = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val frustum = Frustum()
    private val camPos = Vec3()
    private val partMats = Array(12) { FloatArray(16) }
    private val lights = FloatArray(16)
    private val lightColors = FloatArray(12)
    private var time = 0f
    private var shadowMode = 0
    private var shadowTexel = 1f / 2048f
    var initialized = false
        private set

    // shadow volume parameters
    private val shadowHalf = 48f

    fun init() {
        lit = Shader(Shaders.litVS, Shaders.litFS, "lit")
        inst = Shader(Shaders.instVS, Shaders.instFS, "inst")
        terrain = Shader(Shaders.terrainVS, Shaders.terrainFS, "terrain")
        water = Shader(Shaders.waterVS, Shaders.waterFS, "water")
        sky = Shader(Shaders.skyVS, Shaders.skyFS, "sky")
        depth = Shader(Shaders.depthVS, Shaders.depthFS, "depth")
        depthInst = Shader(Shaders.depthInstVS, Shaders.depthFS, "depthInst")
        particle = Shader(Shaders.particleVS, Shaders.particleFS, "particle")
        unlit = Shader(Shaders.unlitVS, Shaders.unlitFS, "unlit")

        RenderAssets.prepare()
        val a = RenderAssets
        noiseTex = Textures.createRGBA(256, a.noise)

        for (t in VegType.entries) {
            vegMeshes[t.ordinal][0] = Mesh(a.veg[t.ordinal][0])
            vegMeshes[t.ordinal][1] = Mesh(a.veg[t.ordinal][1])
        }
        structSolid.clear(); structGlow.clear()
        for (g in game.structures.geos) {
            structSolid.add(Mesh(g.solid))
            structGlow.add(g.glow?.let { Mesh(it) })
        }
        for (t in CreatureType.entries) {
            val m = a.creatures[t]!!
            creatureModels[t] = m
            creatureMeshes[t] = m.parts.map { Mesh(it.mesh) }
        }
        for (t in BuildType.entries) {
            val (s, g) = a.builds[t]!!
            buildMeshes[t] = Pair(Mesh(s), g?.let { Mesh(it) })
        }
        for (i in Item.entries) toolMeshes[i] = Mesh(a.tools[i]!!)
        armMesh = Mesh(a.misc["arm"]!!)
        arrowMesh = Mesh(a.misc["arrow"]!!)
        nockMesh = Mesh(a.misc["nock"]!!)
        boltMesh = Mesh(a.misc["bolt"]!!)
        cubeMesh = Mesh(a.misc["cube"]!!)
        skyMesh = Mesh(a.misc["sky"]!!)
        particleMesh = Mesh(a.misc["particles"]!!)

        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glFrontFace(GL_CCW)
        initialized = true
        GLUtil.checkError("renderer init")
    }

    fun resize(w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
    }

    private fun ensureShadow() {
        val s = game.settings
        shadowMode = s.shadowQuality
        if (shadowMode == 0) return
        val want = s.shadowRes
        if (shadow?.size != want) {
            shadow?.release()
            shadow = ShadowMap(want)
        }
        shadowTexel = 1f / want
    }

    // ------------------------------------------------------------------ frame

    fun render(dt: Float) {
        if (!initialized) return
        time += dt
        val g = game
        val s = g.settings
        val p = g.player
        val env = g.env
        ensureShadow()

        // camera
        val bob = sin(p.headBob * 2f) * 0.045f * p.bobAmount
        val sway = cos(p.headBob) * 0.03f * p.bobAmount
        val shake = g.shake
        val sx = if (shake > 0) (sin(time * 61f) * 0.05f * shake) else 0f
        val sy = if (shake > 0) (cos(time * 53f) * 0.05f * shake) else 0f
        val yaw = p.yaw + sx
        val pitch = p.pitch + sy
        camPos.set(p.pos.x + cos(p.yaw) * sway, p.pos.y + p.eyeHeight + bob, p.pos.z - sin(p.yaw) * sway)
        val cp = cos(pitch)
        val dx = sin(yaw) * cp; val dy = sin(pitch); val dz = cos(yaw) * cp
        val zoom = if (p.drawing) 1f - 0.22f * p.drawAmount else 1f
        val far = s.viewDistance * CHUNK_SIZE + 160f
        Mat4.perspective(proj, s.fov * zoom, width.toFloat() / height, 0.08f, far)
        Mat4.lookAt(view, camPos.x, camPos.y, camPos.z, camPos.x + dx, camPos.y + dy, camPos.z + dz, 0f, 1f, 0f)
        Mat4.mul(vp, proj, view)
        Mat4.invert(invVp, vp)
        Mat4.invert(invView, view)
        frustum.update(vp)

        collectLights(p.selectedTool() == ToolKind.TORCH)

        // ---- shadow pass
        if (shadowMode > 0 && shadow != null && env.lightDir[1] > 0.05f) {
            computeLightMatrix()
            renderShadows()
        } else if (shadowMode > 0) {
            // light below horizon: no shadows this frame
            Mat4.identity(lightVp)
        }
        val useShadows = shadowMode > 0 && shadow != null && env.lightDir[1] > 0.05f

        // ---- main pass
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
        glDepthMask(true)
        glClearColor(env.fogColor[0], env.fogColor[1], env.fogColor[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_CULL_FACE)
        glDisable(GL_BLEND)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, noiseTex)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, if (useShadows) shadow!!.texture else 0)
        glActiveTexture(GL_TEXTURE0)

        val sm = if (useShadows) shadowMode else 0
        drawTerrain(sm)
        drawVegetation(sm)
        drawStructures(sm)
        drawPlaced(sm)
        drawCreatures(sm)
        drawProjectiles(sm)
        drawSky()
        drawWater(sm)
        drawBeams()
        if (s.weatherParticles) drawWeather()
        drawBuildPreview()
        drawViewModel(sm)

        glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, intArrayOf(GL_DEPTH), 0)
    }

    private fun collectLights(torch: Boolean) {
        for (i in 0 until 16) lights[i] = 0f
        var n = 0
        val p = game.player
        val flick = 0.85f + 0.15f * sin(time * 17f) * sin(time * 7.3f)
        if (torch) {
            lights[0] = camPos.x + sin(p.yaw) * 0.6f; lights[1] = camPos.y; lights[2] = camPos.z + cos(p.yaw) * 0.6f; lights[3] = 15f
            lightColors[0] = 2.2f * flick; lightColors[1] = 1.3f * flick; lightColors[2] = 0.5f * flick
            n = 1
        }
        // nearest fires
        val cands = game.placed.filter { it.type == BuildType.CAMPFIRE || it.type == BuildType.TORCH_POST || it.type == BuildType.SHOCK_PYLON }
            .sortedBy { (it.x - p.pos.x) * (it.x - p.pos.x) + (it.z - p.pos.z) * (it.z - p.pos.z) }
        for (pl in cands) {
            if (n >= 4) break
            val d = sqrt((pl.x - p.pos.x) * (pl.x - p.pos.x) + (pl.z - p.pos.z) * (pl.z - p.pos.z))
            if (d > 60f) break
            val o = n * 4
            when (pl.type) {
                BuildType.CAMPFIRE -> {
                    lights[o] = pl.x; lights[o + 1] = pl.y + 0.8f; lights[o + 2] = pl.z; lights[o + 3] = 16f
                    lightColors[n * 3] = 2.6f * flick; lightColors[n * 3 + 1] = 1.3f * flick; lightColors[n * 3 + 2] = 0.45f * flick
                }
                BuildType.TORCH_POST -> {
                    lights[o] = pl.x; lights[o + 1] = pl.y + 2.2f; lights[o + 2] = pl.z; lights[o + 3] = 10f
                    lightColors[n * 3] = 1.8f * flick; lightColors[n * 3 + 1] = 1.0f * flick; lightColors[n * 3 + 2] = 0.35f * flick
                }
                else -> {
                    lights[o] = pl.x; lights[o + 1] = pl.y + 2.5f; lights[o + 2] = pl.z; lights[o + 3] = 6f
                    lightColors[n * 3] = 0.4f; lightColors[n * 3 + 1] = 0.9f; lightColors[n * 3 + 2] = 1.3f
                }
            }
            n++
        }
        // active spires and the boss cast colored light too
        if (n < 4) {
            for (poi in WorldGen.pois) {
                if (n >= 4) break
                if (!poi.kind.name.startsWith("SPIRE") || !game.spiresActive.contains(poi.kind.name)) continue
                val d = sqrt((poi.x - p.pos.x) * (poi.x - p.pos.x) + (poi.z - p.pos.z) * (poi.z - p.pos.z))
                if (d > 70f) continue
                val o = n * 4
                lights[o] = poi.x; lights[o + 1] = poi.y + 3f; lights[o + 2] = poi.z + 6f; lights[o + 3] = 26f
                lightColors[n * 3] = 0.4f; lightColors[n * 3 + 1] = 1.2f; lightColors[n * 3 + 2] = 1.0f
                n++
            }
        }
    }

    private fun computeLightMatrix() {
        val env = game.env
        val p = game.player
        val cx = p.pos.x + sin(p.yaw) * shadowHalf * 0.45f
        val cz = p.pos.z + cos(p.yaw) * shadowHalf * 0.45f
        val cy = p.pos.y
        val ld = env.lightDir
        val dist = 160f
        var ux = 0f; var uy = 1f; var uz = 0f
        if (abs(ld[1]) > 0.98f) { ux = 0f; uy = 0f; uz = 1f }
        Mat4.lookAt(lightView, cx + ld[0] * dist, cy + ld[1] * dist, cz + ld[2] * dist, cx, cy, cz, ux, uy, uz)
        Mat4.ortho(lightProj, -shadowHalf, shadowHalf, -shadowHalf, shadowHalf, 1f, dist * 2f)
        Mat4.mul(lightVp, lightProj, lightView)
        // snap to texels to avoid shimmering
        val size = shadow!!.size.toFloat()
        val ox = lightVp[12] * size * 0.5f
        val oy = lightVp[13] * size * 0.5f
        lightVp[12] += (ox.roundToInt() - ox) * 2f / size
        lightVp[13] += (oy.roundToInt() - oy) * 2f / size
    }

    private fun renderShadows() {
        val sm = shadow!!
        glBindFramebuffer(GL_FRAMEBUFFER, sm.fbo)
        glViewport(0, 0, sm.size, sm.size)
        glDepthMask(true)
        glClear(GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(1.6f, 3f)
        glDisable(GL_CULL_FACE)
        val p = game.player
        val r = shadowHalf * 1.6f
        depth.use()
        depth.m4("u_lightVP", lightVp)
        Mat4.identity(model)
        depth.m4("u_model", model)
        for (c in game.chunks.chunks.values) {
            val t = c.terrain ?: continue
            if (!nearXZ(c, p.pos.x, p.pos.z, r)) continue
            t.draw()
        }
        for (i in structSolid.indices) {
            val geo = game.structures.geos[i]
            val d = sqrt((geo.poi.x - p.pos.x) * (geo.poi.x - p.pos.x) + (geo.poi.z - p.pos.z) * (geo.poi.z - p.pos.z))
            if (d > r + geo.boundsRadius) continue
            structSolid[i].draw()
        }
        for (pl in game.placed) {
            if (abs(pl.x - p.pos.x) > r || abs(pl.z - p.pos.z) > r) continue
            Mat4.fromTRS(model, pl.x, pl.y, pl.z, pl.yaw, 0f, 0f, 1f, 1f, 1f)
            depth.m4("u_model", model)
            buildMeshes[pl.type]!!.first.draw()
        }
        for (c in game.creatures) {
            if (abs(c.pos.x - p.pos.x) > r || abs(c.pos.z - p.pos.z) > r) continue
            val m = creatureModels[c.type]!!
            val meshes = creatureMeshes[c.type]!!
            CreatureModels.pose(c, m, partMats)
            for (i in meshes.indices) { depth.m4("u_model", partMats[i]); meshes[i].draw() }
        }
        depthInst.use()
        depthInst.m4("u_lightVP", lightVp)
        for (t in VegType.entries) {
            if (!t.shadow) continue
            val mesh = vegMeshes[t.ordinal][1] ?: continue
            for (c in game.chunks.chunks.values) {
                val ib = c.inst[t.ordinal] ?: continue
                if (!nearXZ(c, p.pos.x, p.pos.z, r)) continue
                mesh.drawInstanced(ib)
            }
        }
        glDisable(GL_POLYGON_OFFSET_FILL)
        glEnable(GL_CULL_FACE)
    }

    private fun nearXZ(c: Chunk, x: Float, z: Float, r: Float): Boolean {
        val cx = clamp(x, c.x0, c.x0 + CHUNK_SIZE)
        val cz = clamp(z, c.z0, c.z0 + CHUNK_SIZE)
        return (cx - x) * (cx - x) + (cz - z) * (cz - z) < r * r
    }

    private fun setCommon(sh: Shader, shadowMode: Int) {
        val env = game.env
        sh.use()
        sh.m4("u_vp", vp)
        sh.m4("u_lightVP", lightVp)
        sh.f3("u_sunDir", env.lightDir)
        sh.f3("u_sunColor", env.sunColor)
        sh.f3("u_skyAmb", env.skyAmb)
        sh.f3("u_groundAmb", env.groundAmb)
        val under = game.player.underwater
        if (under) {
            sh.f3("u_fogColor", 0.02f, 0.09f, 0.11f)
            sh.f3("u_fogSunColor", 0.04f, 0.14f, 0.15f)
            sh.f1("u_fogDensity", 0.09f)
        } else {
            sh.f3("u_fogColor", env.fogColor)
            sh.f3("u_fogSunColor", env.fogSunColor)
            sh.f1("u_fogDensity", env.fogDensity)
        }
        sh.f1("u_fogHeightFalloff", 0.025f)
        sh.f1("u_viewDist", if (game.player.underwater) 60f else game.settings.viewDistance * CHUNK_SIZE)
        sh.f3("u_camPos", camPos.x, camPos.y, camPos.z)
        sh.f1("u_exposure", env.exposure)
        sh.i1("u_shadowMode", shadowMode)
        sh.i1("u_shadowMap", 1)
        sh.i1("u_noise", 0)
        sh.f1("u_shadowTexel", shadowTexel)
        sh.f4v("u_lights", lights, 4)
        sh.f3v("u_lightColors", lightColors, 4)
        sh.f1("u_flash", env.flash * 0.6f)
        sh.f1("u_time", time)
    }

    private fun chunkVisible(c: Chunk, extra: Float): Boolean =
        frustum.box(c.x0, c.minY - 2f, c.z0, c.x0 + CHUNK_SIZE, c.maxY + extra, c.z0 + CHUNK_SIZE)

    private fun drawTerrain(sm: Int) {
        val env = game.env
        setCommon(terrain, sm)
        terrain.i1("u_detail", 1)
        terrain.f1("u_wetness", env.wetness * 0.8f)
        val cold = WorldGen.coldness(game.player.pos.x, game.player.pos.z)
        terrain.f1("u_snowCover", if (env.snowing && env.rain > 0.2f) clamp(env.rain * cold, 0f, 0.6f) else 0f)
        for (c in game.chunks.chunks.values) {
            val t = c.terrain ?: continue
            if (!chunkVisible(c, 2f)) continue
            t.draw()
        }
    }

    private fun drawVegetation(sm: Int) {
        setCommon(inst, sm)
        inst.f1("u_wind", game.env.wind)
        val p = game.player
        val pcx = floor(p.pos.x / CHUNK_SIZE).toInt()
        val pcz = floor(p.pos.z / CHUNK_SIZE).toInt()
        glDisable(GL_CULL_FACE)
        for (t in VegType.entries) {
            inst.f1("u_translucency", t.translucency)
            val isDecor = t.decorative
            for (c in game.chunks.chunks.values) {
                val ib = c.inst[t.ordinal] ?: continue
                val cd = max(abs(c.cx - pcx), abs(c.cz - pcz))
                if (isDecor && cd > 1) continue
                if (!t.isTree && !isDecor && cd > 3) continue
                if (!chunkVisible(c, if (t.isTree) 14f else 3f)) continue
                val lod = if (cd <= 1 || (cd <= 2 && !t.isTree)) 0 else 1
                vegMeshes[t.ordinal][lod]!!.drawInstanced(ib)
            }
        }
        glEnable(GL_CULL_FACE)
    }

    private fun drawStructures(sm: Int) {
        setCommon(lit, sm)
        Mat4.identity(model)
        lit.m4("u_model", model)
        lit.f4("u_tint", 1f, 1f, 1f, 0f)
        lit.f1("u_hitFlash", 0f)
        lit.f1("u_alpha", 1f)
        lit.f3("u_emissiveColor", 1f, 1f, 1f)
        val p = game.player
        val maxD = game.settings.viewDistance * CHUNK_SIZE + 120f
        val enc = game.encounter
        for (i in structSolid.indices) {
            val geo = game.structures.geos[i]
            val poi = geo.poi
            val d = sqrt((poi.x - p.pos.x) * (poi.x - p.pos.x) + (poi.z - p.pos.z) * (poi.z - p.pos.z))
            if (d > maxD + geo.boundsRadius) continue
            if (!frustum.sphere(poi.x, poi.y + 10f, poi.z, geo.boundsRadius + 30f)) continue
            lit.f1("u_specular", 0.25f)
            lit.f1("u_emissiveScale", 0f)
            structSolid[i].draw()
            val glow = structGlow[i] ?: continue
            var e = 1f
            if (poi.kind.name.startsWith("SPIRE")) {
                e = if (game.spiresActive.contains(poi.kind.name)) 1f else 0.08f
                if (enc != null && enc.poiId == poi.id) e = 0.4f + 0.6f * (0.5f + 0.5f * sin(time * 6f))
            }
            if (poi.kind == PoiKind.CROWN) e = if (game.bossDefeated) 0.25f else 0.6f + 0.4f * sin(time * 2f)
            if (poi.kind == PoiKind.RUIN && game.logsRead.contains(poi.logIndex)) e = 0.15f
            if (poi.kind == PoiKind.CACHE && game.cachesOpened.contains(poi.id)) e = 0.05f
            lit.f1("u_emissiveScale", e)
            lit.f1("u_specular", 0f)
            glow.draw()
        }
    }

    private fun drawPlaced(sm: Int) {
        if (game.placed.isEmpty()) return
        setCommon(lit, sm)
        lit.f4("u_tint", 1f, 1f, 1f, 0f)
        lit.f1("u_hitFlash", 0f)
        lit.f1("u_alpha", 1f)
        lit.f3("u_emissiveColor", 1f, 1f, 1f)
        lit.f1("u_specular", 0.1f)
        val p = game.player
        val maxD = game.settings.viewDistance * CHUNK_SIZE
        for (pl in game.placed) {
            if (abs(pl.x - p.pos.x) > maxD || abs(pl.z - p.pos.z) > maxD) continue
            if (!frustum.sphere(pl.x, pl.y + 1f, pl.z, 3f)) continue
            Mat4.fromTRS(model, pl.x, pl.y, pl.z, pl.yaw, 0f, 0f, 1f, 1f, 1f)
            lit.m4("u_model", model)
            lit.f1("u_emissiveScale", 0f)
            val (solid, glow) = buildMeshes[pl.type]!!
            solid.draw()
            if (glow != null) {
                val flick = if (pl.type == BuildType.CAMPFIRE || pl.type == BuildType.TORCH_POST) 0.75f + 0.25f * sin(time * 13f + pl.id) else 1f
                if (pl.type == BuildType.CAMPFIRE) {
                    Mat4.fromTRS(model, pl.x, pl.y, pl.z, pl.yaw + time * 0.7f, 0f, 0f, 1f, flick * 1.1f, 1f)
                    lit.m4("u_model", model)
                }
                lit.f1("u_emissiveScale", flick)
                glow.draw()
            }
        }
    }

    private fun drawCreatures(sm: Int) {
        setCommon(lit, sm)
        lit.f1("u_alpha", 1f)
        val p = game.player
        for (c in game.creatures) {
            val dx = c.pos.x - p.pos.x; val dz = c.pos.z - p.pos.z
            if (dx * dx + dz * dz > 200f * 200f) continue
            if (!frustum.sphere(c.pos.x, c.pos.y + c.type.height * 0.5f, c.pos.z, c.type.height + c.type.radius)) continue
            val m = creatureModels[c.type]!!
            val meshes = creatureMeshes[c.type]!!
            CreatureModels.pose(c, m, partMats)
            lit.f1("u_specular", m.specular)
            lit.f1("u_hitFlash", c.hitFlash * 0.6f + (if (c.burn > 0) 0.25f + 0.15f * sin(time * 20f) else 0f))
            if (c.variant != Variant.NORMAL) {
                val t = c.variant.tint
                lit.f4("u_tint", ((t shr 16) and 255) / 255f, ((t shr 8) and 255) / 255f, (t and 255) / 255f, 0.35f)
            } else lit.f4("u_tint", 1f, 1f, 1f, 0f)
            // eye/core glow reflects state
            if (!c.alive) {
                lit.f3("u_emissiveColor", 0f, 0f, 0f); lit.f1("u_emissiveScale", 0f)
            } else if (c.type.machine) {
                val hostile = c.state == CState.CHASE || c.state == CState.WINDUP || c.state == CState.RECOVER || c.type == CreatureType.COLOSSUS
                when {
                    c.stun > 0 -> lit.f3("u_emissiveColor", 0.6f, 0.9f, 1.4f)
                    hostile -> lit.f3("u_emissiveColor", 1.6f, 0.25f, 0.15f)
                    c.state == CState.ALERT || c.state == CState.FLEE -> lit.f3("u_emissiveColor", 1.5f, 1.1f, 0.3f)
                    else -> lit.f3("u_emissiveColor", 1f, 1f, 1f)
                }
                lit.f1("u_emissiveScale", if (c.type == CreatureType.COLOSSUS) 1f + 0.3f * sin(time * 4f) else 0.8f)
            } else {
                lit.f3("u_emissiveColor", 1f, 1f, 1f); lit.f1("u_emissiveScale", 0f)
            }
            for (i in meshes.indices) {
                lit.m4("u_model", partMats[i])
                meshes[i].draw()
            }
        }
        lit.f1("u_hitFlash", 0f)
        lit.f4("u_tint", 1f, 1f, 1f, 0f)
    }

    private fun drawProjectiles(sm: Int) {
        if (game.projectiles.isEmpty()) return
        setCommon(lit, sm)
        lit.f4("u_tint", 1f, 1f, 1f, 0f)
        lit.f1("u_alpha", 1f)
        lit.f1("u_specular", 0.2f)
        for (pr in game.projectiles) {
            val v = pr.vel
            val yaw = atan2(v.x, v.z)
            val pitch = -atan2(v.y, sqrt(v.x * v.x + v.z * v.z))
            Mat4.fromTRS(model, pr.pos.x, pr.pos.y, pr.pos.z, yaw, pitch, 0f, 1f, 1f, 1f)
            lit.m4("u_model", model)
            if (pr.kind == ProjKind.BOLT) {
                lit.f3("u_emissiveColor", 1.4f, 0.8f, 0.4f); lit.f1("u_emissiveScale", 1f)
                boltMesh.draw()
            } else {
                when (pr.kind) {
                    ProjKind.FIRE_ARROW -> { lit.f3("u_emissiveColor", 1.5f, 0.6f, 0.2f); lit.f4("u_tint", 1f, 0.5f, 0.2f, 0.5f) }
                    ProjKind.SHOCK_ARROW -> { lit.f3("u_emissiveColor", 0.5f, 1f, 1.5f); lit.f4("u_tint", 0.5f, 0.9f, 1f, 0.5f) }
                    else -> { lit.f3("u_emissiveColor", 0f, 0f, 0f); lit.f4("u_tint", 1f, 1f, 1f, 0f) }
                }
                lit.f1("u_emissiveScale", 0f)
                arrowMesh.draw()
            }
        }
        lit.f4("u_tint", 1f, 1f, 1f, 0f)
    }

    private fun drawSky() {
        val env = game.env
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        sky.use()
        sky.m4("u_invViewProj", invVp)
        sky.f3("u_camPos", camPos.x, camPos.y, camPos.z)
        sky.f3("u_sunDir", env.sunDir)
        sky.f3("u_moonDir", env.moonDir)
        sky.f3("u_skyZenith", env.zenith)
        sky.f3("u_skyHorizon", env.horizon)
        sky.f3("u_sunColor", env.sunColor)
        sky.f1("u_night", env.nightFactor)
        sky.f1("u_cloudCover", env.clouds)
        sky.f1("u_time", time)
        sky.f1("u_exposure", env.exposure)
        sky.f1("u_flash", env.flash * 0.5f)
        sky.i1("u_noise", 0)
        sky.i1("u_clouds", if (game.settings.clouds) 1 else 0)
        skyMesh.draw()
        glDepthMask(true)
        glEnable(GL_CULL_FACE)
    }

    private fun drawWater(sm: Int) {
        val env = game.env
        setCommon(water, sm)
        water.f3("u_skyHorizon", env.horizon)
        water.f3("u_skyZenith", env.zenith)
        water.i1("u_waterQuality", game.settings.waterQuality)
        water.f1("u_rain", env.rain)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        for (c in game.chunks.chunks.values) {
            val w = c.water ?: continue
            if (!frustum.box(c.x0, -2f, c.z0, c.x0 + CHUNK_SIZE, 1f, c.z0 + CHUNK_SIZE)) continue
            w.draw()
        }
        glDepthMask(true)
        glEnable(GL_CULL_FACE)
        glDisable(GL_BLEND)
    }

    private fun drawBeams() {
        if (game.beams.isEmpty()) return
        unlit.use()
        unlit.m4("u_vp", vp)
        unlit.f1("u_fadeHeight", 0f)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE)
        glDepthMask(false)
        for (b in game.beams) {
            val dx = b.to.x - b.from.x; val dy = b.to.y - b.from.y; val dz = b.to.z - b.from.z
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            val yaw = atan2(dx, dz)
            val pitch = -atan2(dy, sqrt(dx * dx + dz * dz))
            Mat4.fromTRS(model, (b.from.x + b.to.x) / 2, (b.from.y + b.to.y) / 2, (b.from.z + b.to.z) / 2, yaw, pitch, 0f, 0.08f, 0.08f, len)
            unlit.m4("u_model", model)
            val c = b.color
            unlit.f4("u_color", ((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, clamp(b.ttl * 4f, 0f, 1f))
            cubeMesh.draw()
        }
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun drawWeather() {
        val env = game.env
        if (env.rain < 0.05f || game.player.underwater) return
        val snow = env.snowing
        particle.use()
        particle.m4("u_vp", vp)
        particle.f3("u_camPos", camPos.x, camPos.y, camPos.z)
        particle.f1("u_time", time)
        particle.f1("u_mode", if (snow) 1f else 0f)
        particle.f3("u_windDir", env.wind * 1.5f, 0f, env.wind * 0.6f)
        particle.f1("u_pointScale", height * 0.012f)
        particle.f3("u_color", if (snow) 0.95f else 0.65f, if (snow) 0.97f else 0.72f, if (snow) 1f else 0.82f)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        val count = (particleCount * env.rain).toInt() * 2
        if (snow) particleMesh.drawArrays(GL_POINTS, count / 2) else particleMesh.drawArrays(GL_LINES, count)
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun drawBuildPreview() {
        val t = game.buildType ?: return
        val bp = game.buildPos
        unlit.use()
        unlit.m4("u_vp", vp)
        unlit.f1("u_fadeHeight", 0f)
        Mat4.fromTRS(model, bp.x, bp.y, bp.z, game.rotPreview * 1.5707964f, 0f, 0f, 1f, 1f, 1f)
        unlit.m4("u_model", model)
        if (game.buildValid) unlit.f4("u_color", 0.6f, 1.6f, 0.7f, 0.45f) else unlit.f4("u_color", 1.8f, 0.5f, 0.4f, 0.45f)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        buildMeshes[t]!!.first.draw()
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private val local = FloatArray(16)

    private fun drawViewModel(sm: Int) {
        val p = game.player
        if (p.dead || game.buildType != null || !game.started) return
        glClear(GL_DEPTH_BUFFER_BIT)
        setCommon(lit, sm)
        lit.f4("u_tint", 1f, 1f, 1f, 0f)
        lit.f1("u_hitFlash", 0f)
        lit.f1("u_alpha", 1f)
        lit.f1("u_specular", 0.15f)
        lit.f3("u_emissiveColor", 1f, 1f, 1f)
        lit.f1("u_emissiveScale", 1f)
        // viewmodel uses a fixed FOV
        val vproj = tmpM
        Mat4.perspective(vproj, 60f, width.toFloat() / height, 0.03f, 10f)
        val vvp = FloatArray(16)
        Mat4.mul(vvp, vproj, view)
        lit.m4("u_vp", vvp)

        val stack = p.selectedStack()
        val tool = stack?.item?.tool ?: ToolKind.NONE
        val bobX = sin(p.headBob) * 0.012f * p.bobAmount
        val bobY = abs(cos(p.headBob)) * 0.012f * p.bobAmount
        var ox = 0.3f + bobX
        var oy = -0.3f + bobY
        var oz = -0.55f
        var yaw = 0.15f
        var pitch = 0.1f
        var roll = 0f
        if (p.swingTime >= 0f) {
            val t = clamp(p.swingTime / p.swingDuration, 0f, 1f)
            val sw = sin(t * Math.PI.toFloat())
            if (tool == ToolKind.SPEAR) {
                oz -= sw * 0.35f; oy += sw * 0.04f
            } else {
                pitch -= sw * 1.3f
                yaw += sw * 0.5f
                ox -= sw * 0.12f
                oy += sw * 0.06f
            }
        }
        if (tool == ToolKind.BOW) {
            ox = 0.05f + bobX; oy = -0.18f + bobY; oz = -0.5f
            yaw = 0f; pitch = 0f; roll = 0.25f - p.drawAmount * 0.15f
        }
        if (tool == ToolKind.SPEAR && p.swingTime < 0f) { pitch = 0.05f; yaw = 0.04f }
        Mat4.fromTRS(local, ox, oy, oz, yaw, pitch, roll, 1f, 1f, 1f)
        Mat4.mul(model, invView, local)
        if (stack != null) {
            lit.m4("u_model", model)
            toolMeshes[stack.item]!!.draw()
            if (tool == ToolKind.BOW && p.drawing) {
                Mat4.fromTRS(local, ox, oy, oz + 0.12f + p.drawAmount * 0.2f, yaw, pitch, roll, 1f, 1f, 1f)
                Mat4.mul(model, invView, local)
                lit.m4("u_model", model)
                nockMesh.draw()
            }
        }
        // the arm
        if (tool == ToolKind.BOW) Mat4.fromTRS(local, ox + 0.02f, oy - 0.04f, oz + 0.12f, 0f, 0f, roll, 1f, 1f, 1f)
        else Mat4.fromTRS(local, ox, oy, oz, yaw, pitch, roll, 1f, 1f, 1f)
        Mat4.mul(model, invView, local)
        lit.m4("u_model", model)
        armMesh.draw()
        if (tool == ToolKind.BOW && p.drawing) {
            // drawing hand
            Mat4.fromTRS(local, 0.12f, -0.2f, -0.3f + p.drawAmount * 0.18f, -0.3f, 0f, 0f, 1f, 1f, 1f)
            Mat4.mul(model, invView, local)
            lit.m4("u_model", model)
            armMesh.draw()
        }
    }
}
