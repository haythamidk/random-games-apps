package com.worldwake.game.render

import android.opengl.GLES30.*
import com.worldwake.game.engine.ATTR_COLOR
import com.worldwake.game.engine.ATTR_NORMAL
import com.worldwake.game.engine.ATTR_POS
import com.worldwake.game.engine.Frustum
import com.worldwake.game.engine.GLCaps
import com.worldwake.game.engine.GLUtil
import com.worldwake.game.engine.Mat4
import com.worldwake.game.engine.Mesh
import com.worldwake.game.engine.MsaaTarget
import com.worldwake.game.engine.MeshData
import com.worldwake.game.engine.NoiseImage
import com.worldwake.game.engine.PI_F
import com.worldwake.game.engine.ProceduralTextures
import com.worldwake.game.engine.RenderTarget
import com.worldwake.game.engine.Shader
import com.worldwake.game.engine.Shaders
import com.worldwake.game.engine.ShadowMap
import com.worldwake.game.engine.TextureArray
import com.worldwake.game.engine.Textures
import com.worldwake.game.engine.VERTEX_FLOATS
import com.worldwake.game.engine.Vec3
import com.worldwake.game.engine.clamp
import com.worldwake.game.engine.clamp01
import com.worldwake.game.engine.lerp
import com.worldwake.game.engine.smoothstep
import com.worldwake.game.game.BuildType
import com.worldwake.game.game.Cat
import com.worldwake.game.game.GameState
import com.worldwake.game.game.Item
import com.worldwake.game.game.POD_CELL
import com.worldwake.game.game.POD_FLOOR
import com.worldwake.game.game.Placed
import com.worldwake.game.world.CHUNK_SIZE
import com.worldwake.game.world.Chunk
import com.worldwake.game.world.Grow
import com.worldwake.game.world.Planet
import com.worldwake.game.world.PoiKind
import com.worldwake.game.world.PropMeshes
import com.worldwake.game.world.PropType
import com.worldwake.game.world.WorldGen
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** CPU-side geometry and textures prepared off the GL thread so the first frame stays fast. */
object RenderAssets {
    @Volatile var ready = false
        private set
    lateinit var noise: ByteArray
    lateinit var props: Array<Array<MeshData>>
    lateinit var models: Map<BuildType, ModelSet>
    lateinit var podBase: Pair<MeshData, MeshData>
    lateinit var podSides: Array<Triple<MeshData, MeshData?, MeshData?>>
    lateinit var podRamp: MeshData
    lateinit var tool: Pair<MeshData, MeshData>
    lateinit var misc: Map<String, MeshData>
    lateinit var textures: ProceduralTextures.Result

    @Synchronized fun prepare(cacheDir: File?, pool: ExecutorService?) {
        if (ready) return
        textures = ProceduralTextures.load(cacheDir, pool)
        noise = NoiseImage.generate(256, 99)
        props = Array(PropType.entries.size) { i -> Array(2) { l -> PropMeshes.build(PropType.entries[i], l) } }
        models = BuildType.entries.associateWith { Models.build(it) }
        podBase = Models.podBase()
        podSides = Array(3) { Models.podSide(it) }
        podRamp = Models.podRamp()
        tool = Models.viewTool()
        misc = mapOf("cube" to Models.cube(), "sky" to Models.skyTriangle(), "particles" to Models.particles(3000))
        ready = true
    }
}

private class GpuModel(val solid: Mesh, val glow: Mesh?, val spin: Mesh?, val glass: Mesh?, val set: ModelSet)

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
    private lateinit var fxShader: Shader
    private lateinit var unlit: Shader
    private lateinit var down: Shader
    private lateinit var up: Shader
    private lateinit var composite: Shader
    private lateinit var fxaa: Shader

    private var noiseTex = 0
    private var albedoArr: TextureArray? = null
    private var normalArr: TextureArray? = null
    private var shadow0: ShadowMap? = null
    private var shadow1: ShadowMap? = null
    private var dummyShadow: ShadowMap? = null
    private val propMeshes = Array(PropType.entries.size) { arrayOfNulls<Mesh>(2) }
    private val structSolid = ArrayList<Mesh>()
    private val structGlow = ArrayList<Mesh?>()
    private val models = HashMap<BuildType, GpuModel>()
    private lateinit var podBase: Mesh
    private lateinit var podBaseGlow: Mesh
    private val podSide = arrayOfNulls<Mesh>(3)
    private val podSideGlow = arrayOfNulls<Mesh>(3)
    private val podSideGlass = arrayOfNulls<Mesh>(3)
    private lateinit var podRamp: Mesh
    private lateinit var watcherSolid: Mesh
    private lateinit var watcherGlow: Mesh
    private lateinit var watcherBloom: Mesh
    private lateinit var toolMesh: Mesh
    private lateinit var toolGlow: Mesh
    private lateinit var cubeMesh: Mesh
    private lateinit var skyMesh: Mesh
    private lateinit var particleMesh: Mesh
    private val particleCount = 3000

    // dynamic sprite buffer
    private var fxVao = 0
    private var fxVbo = 0
    private val fxData = FloatArray(420 * VERTEX_FLOATS)
    private val fxBuf: FloatBuffer = ByteBuffer.allocateDirect(420 * VERTEX_FLOATS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var sceneRT: RenderTarget? = null
    private var msaaRT: MsaaTarget? = null
    private var msaaFailed = false
    private var refractRT: RenderTarget? = null
    private var ldrRT: RenderTarget? = null
    private val bloomRT = arrayOfNulls<RenderTarget>(4)
    private var hdrFormat = true
    private var outScale = 1f
    private var postActive = false
    private var dynScale = 1f
    private var dynTimer = 0f
    private var dynFrames = 0
    private var dynAccum = 0f
    private var dynGoodWindows = 0
    private var fpsTimer = 0f
    private var fpsFrames = 0

    private var width = 1
    private var height = 1
    private var sceneW = 1
    private var sceneH = 1
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val vvp = FloatArray(16)
    private val invVp = FloatArray(16)
    private val invView = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private val lightVp0 = FloatArray(16)
    private val lightVp1 = FloatArray(16)
    private val model = FloatArray(16)
    private val local = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val frustum = Frustum()
    private val camPos = Vec3()
    private val lights = FloatArray(16)
    private val lightColors = FloatArray(12)
    private var time = 0f
    private var shadowMode = 0
    var initialized = false
        private set

    fun init() {
        GLCaps.init()
        lit = Shader(Shaders.litVS, Shaders.litFS, "lit")
        inst = Shader(Shaders.instVS, Shaders.instFS, "inst")
        terrain = Shader(Shaders.terrainVS, Shaders.terrainFS, "terrain")
        water = Shader(Shaders.waterVS, Shaders.waterFS, "water")
        sky = Shader(Shaders.skyVS, Shaders.skyFS, "sky")
        depth = Shader(Shaders.depthVS, Shaders.depthFS, "depth")
        depthInst = Shader(Shaders.depthInstVS, Shaders.depthInstFS, "depthInst")
        particle = Shader(Shaders.particleVS, Shaders.particleFS, "particle")
        fxShader = Shader(Shaders.fxVS, Shaders.fxFS, "fx")
        unlit = Shader(Shaders.unlitVS, Shaders.unlitFS, "unlit")
        down = Shader(Shaders.postVS, Shaders.downFS, "down")
        up = Shader(Shaders.postVS, Shaders.upFS, "up")
        composite = Shader(Shaders.postVS, Shaders.compositeFS, "composite")
        fxaa = Shader(Shaders.postVS, Shaders.fxaaFS, "fxaa")
        hdrFormat = GLCaps.halfFloatTargets

        val a = RenderAssets
        noiseTex = Textures.createRGBA(256, a.noise)
        albedoArr = TextureArray(ProceduralTextures.SIZE, a.textures.albedo, 4f)
        normalArr = TextureArray(ProceduralTextures.NSIZE, a.textures.normals, 4f)
        dummyShadow = ShadowMap(16)

        for (t in PropType.entries) {
            propMeshes[t.ordinal][0] = Mesh(a.props[t.ordinal][0])
            propMeshes[t.ordinal][1] = Mesh(a.props[t.ordinal][1])
        }
        structSolid.clear(); structGlow.clear()
        for (g in game.structures.geos) {
            structSolid.add(Mesh(g.solid))
            structGlow.add(g.glow?.let { Mesh(it) })
        }
        for (t in BuildType.entries) {
            val m = a.models[t]!!
            models[t] = GpuModel(Mesh(m.solid), m.glow?.let { Mesh(it) }, m.spin?.let { Mesh(it) }, m.glass?.let { Mesh(it) }, m)
        }
        podBase = Mesh(a.podBase.first); podBaseGlow = Mesh(a.podBase.second)
        for (i in 0 until 3) {
            podSide[i] = Mesh(a.podSides[i].first)
            podSideGlow[i] = a.podSides[i].second?.let { Mesh(it) }
            podSideGlass[i] = a.podSides[i].third?.let { Mesh(it) }
        }
        podRamp = Mesh(a.podRamp)
        watcherSolid = Mesh(game.structures.watcherSolid)
        watcherGlow = Mesh(game.structures.watcherGlow)
        watcherBloom = Mesh(game.structures.watcherBloom)
        toolMesh = Mesh(a.tool.first); toolGlow = Mesh(a.tool.second)
        cubeMesh = Mesh(a.misc["cube"]!!)
        skyMesh = Mesh(a.misc["sky"]!!)
        particleMesh = Mesh(a.misc["particles"]!!)

        fxVao = GLUtil.genVao(); fxVbo = GLUtil.genBuffer()
        glBindVertexArray(fxVao)
        glBindBuffer(GL_ARRAY_BUFFER, fxVbo)
        glBufferData(GL_ARRAY_BUFFER, fxData.size * 4, null, GL_DYNAMIC_DRAW)
        val stride = VERTEX_FLOATS * 4
        glEnableVertexAttribArray(ATTR_POS); glVertexAttribPointer(ATTR_POS, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(ATTR_NORMAL); glVertexAttribPointer(ATTR_NORMAL, 3, GL_FLOAT, false, stride, 12)
        glEnableVertexAttribArray(ATTR_COLOR); glVertexAttribPointer(ATTR_COLOR, 4, GL_FLOAT, false, stride, 24)
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)

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
        releaseTargets()
    }

    private fun releaseTargets() {
        sceneRT?.release(); sceneRT = null
        msaaRT?.release(); msaaRT = null
        refractRT?.release(); refractRT = null
        ldrRT?.release(); ldrRT = null
        for (i in bloomRT.indices) { bloomRT[i]?.release(); bloomRT[i] = null }
    }

    private fun ensureTargets(): Boolean {
        val s = game.settings
        val scale = clamp(if (s.dynamicRes) min(dynScale, s.renderScale) else s.renderScale, 0.4f, 1f)
        val w = max(16, (width * scale).toInt()); val h = max(16, (height * scale).toInt())
        val wantMs = s.antiAlias >= 2 && !msaaFailed
        if (sceneRT != null && sceneW == w && sceneH == h && (ldrRT != null) == (s.antiAlias >= 1) && (msaaRT != null) == wantMs) return true
        releaseTargets()
        sceneW = w; sceneH = h
        var rt = RenderTarget(w, h, hdrFormat, true)
        if (!rt.complete && hdrFormat) {
            rt.release(); hdrFormat = false
            rt = RenderTarget(w, h, false, true)
        }
        if (!rt.complete) { rt.release(); return false }
        sceneRT = rt
        outScale = if (hdrFormat) 1f else 0.25f
        refractRT = RenderTarget(max(8, w / 2), max(8, h / 2), hdrFormat, false)
        var bw = w; var bh = h
        for (i in bloomRT.indices) {
            bw = max(4, bw / 2); bh = max(4, bh / 2)
            bloomRT[i] = RenderTarget(bw, bh, hdrFormat, false)
        }
        if (s.antiAlias >= 1) ldrRT = RenderTarget(width, height, false, false)
        if (wantMs) {
            // some phones cannot multisample a float target: fall back to FXAA alone and do not try again
            val ms = try { MsaaTarget(w, h, hdrFormat, 4) } catch (_: Throwable) { null }
            if (ms != null && ms.complete) msaaRT = ms else { ms?.release(); msaaFailed = true }
        }
        return true
    }

    private fun ensureShadow() {
        val s = game.settings
        shadowMode = s.shadowQuality
        if (shadowMode == 0) return
        val want = s.shadowRes
        if (shadow0?.size != want) { shadow0?.release(); shadow0 = ShadowMap(want) }
        if (shadowMode == 3) { if (shadow1?.size != want) { shadow1?.release(); shadow1 = ShadowMap(want) } }
    }

    private fun updateDynamicRes(dt: Float) {
        val s = game.settings
        fpsTimer += dt; fpsFrames++
        if (fpsTimer >= 1f) { game.fps = (fpsFrames / fpsTimer).roundToInt(); fpsTimer = 0f; fpsFrames = 0 }
        if (!s.dynamicRes || !postActive) { dynScale = s.renderScale; return }
        dynAccum += dt; dynFrames++; dynTimer += dt
        if (dynTimer < 1.5f) return
        val avg = dynAccum / dynFrames
        val target = 1f / s.fpsCap
        val old = dynScale
        if (avg > target * 1.12f) {
            dynScale = max(0.55f, dynScale - 0.05f)
            dynGoodWindows = 0
        } else if (avg < target * 1.04f) {
            dynGoodWindows++
            if (dynGoodWindows >= 3) { dynScale = min(s.renderScale, dynScale + 0.05f); dynGoodWindows = 0 }
        }
        if (dynScale > s.renderScale) dynScale = s.renderScale
        dynTimer = 0f; dynFrames = 0; dynAccum = 0f
        if (abs(old - dynScale) > 0.01f) releaseTargets()
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
        updateDynamicRes(dt)
        postActive = s.postFx && ensureTargets()
        if (!s.postFx && sceneRT != null) releaseTargets()
        val rw = if (postActive) sceneW else width
        val rh = if (postActive) sceneH else height

        val bob = sin(p.headBob * 2f) * 0.04f * p.bobAmount
        val sway = cos(p.headBob) * 0.025f * p.bobAmount
        val shake = g.shake
        val sx = if (shake > 0) (sin(time * 61f) * 0.05f * shake) else 0f
        val sy = if (shake > 0) (cos(time * 53f) * 0.05f * shake) else 0f
        val yaw = p.yaw + sx
        val pitch = p.pitch + sy
        camPos.set(p.pos.x + cos(p.yaw) * sway, p.pos.y + p.eyeHeight + bob, p.pos.z - sin(p.yaw) * sway)
        val cp = cos(pitch)
        val dx = sin(yaw) * cp; val dy = sin(pitch); val dz = cos(yaw) * cp
        val far = s.viewDistance * CHUNK_SIZE + 260f
        Mat4.perspective(proj, s.fov, rw.toFloat() / rh, 0.12f, far)
        Mat4.lookAt(view, camPos.x, camPos.y, camPos.z, camPos.x + dx, camPos.y + dy, camPos.z + dz, 0f, 1f, 0f)
        Mat4.mul(vp, proj, view)
        Mat4.invert(invVp, vp)
        Mat4.invert(invView, view)
        frustum.update(vp)
        System.arraycopy(vp, 0, g.viewProj, 0, 16)

        collectLights()

        val useShadows = shadowMode > 0 && shadow0 != null && env.lightDir[1] > 0.05f
        if (useShadows) {
            val near = if (shadowMode == 3) 30f else 48f
            computeLightMatrix(lightVp0, near, shadow0!!.size, 0.45f)
            renderShadows(shadow0!!, lightVp0, near * 1.6f, s.foliage >= 2)
            if (shadowMode == 3) {
                computeLightMatrix(lightVp1, 140f, shadow1!!.size, 0.6f)
                renderShadows(shadow1!!, lightVp1, 200f, false)
            } else System.arraycopy(lightVp0, 0, lightVp1, 0, 16)
        } else {
            Mat4.identity(lightVp0); Mat4.identity(lightVp1)
        }
        val sm = if (useShadows) shadowMode else 0

        if (postActive) (msaaRT?.bind() ?: sceneRT!!.bind()) else { glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, width, height) }
        glDepthMask(true)
        glClearColor(env.fogColor[0], env.fogColor[1], env.fogColor[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_CULL_FACE)
        glDisable(GL_BLEND)
        bindTextures(useShadows)

        drawTerrain(sm)
        drawProps(sm)
        drawStructures(sm)
        drawWatchers(sm)
        drawPlaced(sm)
        drawSky()
        val waterOn = env.v.waterLevel > -50f
        val refract = postActive && s.waterQuality > 0 && waterOn && hasVisibleWater()
        if (refract) copySceneForRefraction()
        if (waterOn) drawWater(sm, refract)
        drawGlass(sm)
        drawFx(rh)
        if (s.weatherParticles) drawWeather(rh)
        drawBuildPreview()
        drawViewModel(sm, rw, rh)

        if (postActive) {
            msaaRT?.let { ms ->
                ms.resolveTo(sceneRT!!)
                glBindFramebuffer(GL_READ_FRAMEBUFFER, ms.fbo)
                glInvalidateFramebuffer(GL_READ_FRAMEBUFFER, 2, intArrayOf(GL_COLOR_ATTACHMENT0, GL_DEPTH_ATTACHMENT), 0)
                glBindFramebuffer(GL_FRAMEBUFFER, sceneRT!!.fbo)
            }
            glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, intArrayOf(GL_DEPTH_ATTACHMENT), 0)
            postProcess()
        } else {
            glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, intArrayOf(GL_DEPTH), 0)
        }
    }

    private fun bindTextures(shadows: Boolean) {
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, noiseTex)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, if (shadows) shadow0!!.texture else dummyShadow!!.texture)
        glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, if (shadows && shadowMode == 3) shadow1!!.texture else dummyShadow!!.texture)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D_ARRAY, albedoArr!!.id)
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D_ARRAY, normalArr!!.id)
        glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, refractRT?.texture ?: noiseTex)
        glActiveTexture(GL_TEXTURE0)
    }

    private fun addLight(n: Int, x: Float, y: Float, z: Float, range: Float, r: Float, g: Float, b: Float): Int {
        if (n >= 4) return n
        val o = n * 4
        lights[o] = x; lights[o + 1] = y; lights[o + 2] = z; lights[o + 3] = range
        lightColors[n * 3] = r; lightColors[n * 3 + 1] = g; lightColors[n * 3 + 2] = b
        return n + 1
    }

    private fun collectLights() {
        for (i in 0 until 16) lights[i] = 0f
        var n = 0
        val p = game.player
        val env = game.env
        val dark = clamp01(env.nightFactor * 1.3f)
        // suit lamp: soft by default, strong with the lamp upgrade
        if (dark > 0.05f && game.started) {
            val k = dark * (if (p.has(Item.LAMP)) 1f else 0.42f)
            val r = if (p.has(Item.LAMP)) 26f else 15f
            n = addLight(n, camPos.x + sin(p.yaw) * 1.2f, camPos.y + 0.1f, camPos.z + cos(p.yaw) * 1.2f, r, 1.5f * k, 1.45f * k, 1.25f * k)
        }
        val pod = game.podAt(p.pos.x, p.pos.z)
        if (pod != null) n = addLight(n, (pod.gx + 0.5f) * POD_CELL, pod.y + 2.6f, (pod.gz + 0.5f) * POD_CELL, 9f, 1.0f, 0.95f, 0.85f)
        val l = WorldGen.lander
        if (abs(l.x - p.pos.x) < 40f && abs(l.z - p.pos.z) < 40f) n = addLight(n, l.x + sin(l.yaw) * 2.6f, l.y + 3f, l.z + cos(l.yaw) * 2.6f, 12f, 0.25f, 0.8f, 0.75f)
        // nearest glowing machines
        while (n < 4) {
            var bestD = 45f * 45f; var best: Placed? = null
            for (pl in game.placed) {
                val t = pl.type
                if (t.cat != Cat.HEAT && t != BuildType.BEACON && t != BuildType.FUSION && t != BuildType.BIO_4) continue
                val d = (pl.x - p.pos.x) * (pl.x - p.pos.x) + (pl.z - p.pos.z) * (pl.z - p.pos.z)
                if (d < bestD && !usedLight(pl.x, pl.z, n)) { bestD = d; best = pl }
            }
            val pl = best ?: break
            val h = pl.type.height
            n = when {
                pl.type.cat == Cat.HEAT -> addLight(n, pl.x, pl.y + h * 0.6f, pl.z, 7f + h * 1.6f, 1.9f, 0.8f, 0.35f)
                pl.type == BuildType.BEACON -> addLight(n, pl.x, pl.y + 3.9f, pl.z, 14f, 0.5f, 1.3f, 1.2f)
                pl.type == BuildType.FUSION -> addLight(n, pl.x, pl.y + 3f, pl.z, 18f, 1.6f, 1.1f, 1.5f)
                else -> addLight(n, pl.x, pl.y + 2.5f, pl.z, 12f, 1.6f, 1.2f, 0.6f)
            }
        }
    }

    private fun usedLight(x: Float, z: Float, n: Int): Boolean {
        for (i in 0 until n) if (lights[i * 4 + 3] > 0f && abs(lights[i * 4] - x) < 0.01f && abs(lights[i * 4 + 2] - z) < 0.01f) return true
        return false
    }

    private fun computeLightMatrix(out: FloatArray, half: Float, size: Int, forward: Float) {
        val env = game.env
        val p = game.player
        val cx = p.pos.x + sin(p.yaw) * half * forward
        val cz = p.pos.z + cos(p.yaw) * half * forward
        val cy = p.pos.y
        val ld = env.lightDir
        val dist = 220f
        var ux = 0f; var uy = 1f; var uz = 0f
        if (abs(ld[1]) > 0.98f) { ux = 0f; uy = 0f; uz = 1f }
        Mat4.lookAt(lightView, cx + ld[0] * dist, cy + ld[1] * dist, cz + ld[2] * dist, cx, cy, cz, ux, uy, uz)
        Mat4.ortho(lightProj, -half, half, -half, half, 1f, dist * 2f)
        Mat4.mul(out, lightProj, lightView)
        val sz = size.toFloat()
        val ox = out[12] * sz * 0.5f
        val oy = out[13] * sz * 0.5f
        out[12] += (ox.roundToInt() - ox) * 2f / sz
        out[13] += (oy.roundToInt() - oy) * 2f / sz
    }

    private fun placedMatrix(pl: Placed, out: FloatArray, spinAngle: Float = 0f) {
        val age = (game.time - pl.born).toFloat()
        val k = if (age < 0.5f) lerp(0.25f, 1f, smoothstep(0f, 0.5f, age)) else 1f
        Mat4.fromTRS(out, pl.x, pl.y, pl.z, pl.yaw + spinAngle, 0f, 0f, k, k, k)
    }

    private fun podSideMatrix(pod: Placed, side: Int, out: FloatArray) {
        Mat4.fromTRS(out, (pod.gx + 0.5f) * POD_CELL, pod.y, (pod.gz + 0.5f) * POD_CELL, -side * PI_F * 0.5f, 0f, 0f, 1f, 1f, 1f)
    }

    private fun renderShadows(sm: ShadowMap, lvp: FloatArray, r: Float, alphaFoliage: Boolean) {
        glBindFramebuffer(GL_FRAMEBUFFER, sm.fbo)
        glViewport(0, 0, sm.size, sm.size)
        glDepthMask(true)
        glClear(GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(1.6f, 3f)
        glDisable(GL_CULL_FACE)
        val p = game.player
        val v = game.env.v
        depth.use()
        depth.m4("u_lightVP", lvp)
        Mat4.identity(model)
        depth.m4("u_model", model)
        for (c in game.chunks.chunks.values) {
            val t = c.terrain ?: continue
            if (!nearXZ(c, p.pos.x, p.pos.z, r)) continue
            if (c.mainIdx > 0) t.drawFirst(c.mainIdx) else t.draw()
        }
        for (i in structSolid.indices) {
            val geo = game.structures.geos[i]
            val d = sqrt((geo.poi.x - p.pos.x) * (geo.poi.x - p.pos.x) + (geo.poi.z - p.pos.z) * (geo.poi.z - p.pos.z))
            if (d > r + geo.boundsRadius) continue
            structSolid[i].draw()
        }
        val sink = watcherSink()
        for (i in game.watcherPois.indices) {
            val w = game.watcherPois[i]
            if (abs(w.x - p.pos.x) > r || abs(w.z - p.pos.z) > r) continue
            Mat4.fromTRS(model, w.x, w.y - sink, w.z, game.watcherYaw[i], 0f, 0f, 1f, 1f, 1f)
            depth.m4("u_model", model)
            watcherSolid.draw()
        }
        for (pl in game.placed) {
            if (abs(pl.x - p.pos.x) > r + 6f || abs(pl.z - p.pos.z) > r + 6f) continue
            if (pl.type == BuildType.POD) {
                Mat4.fromTRS(model, (pl.gx + 0.5f) * POD_CELL, pl.y, (pl.gz + 0.5f) * POD_CELL, 0f, 0f, 0f, 1f, 1f, 1f)
                depth.m4("u_model", model)
                podBase.draw()
                for (sd in 0 until 4) {
                    podSideMatrix(pl, sd, model)
                    depth.m4("u_model", model)
                    podSide[game.sideState(pl, sd)]!!.draw()
                }
                continue
            }
            val m = models[pl.type]!!
            placedMatrix(pl, model)
            depth.m4("u_model", model)
            m.solid.draw()
            if (m.spin != null) {
                placedMatrix(pl, model, time * m.set.spinSpeed * game.efficiency)
                depth.m4("u_model", model)
                m.spin.draw()
            }
        }
        depthInst.use()
        depthInst.m4("u_lightVP", lvp)
        depthInst.i1("u_albedoArr", 2)
        depthInst.i1("u_alphaTest", if (alphaFoliage) 1 else 0)
        if (alphaFoliage) { glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D_ARRAY, albedoArr!!.id); glActiveTexture(GL_TEXTURE0) }
        for (t in PropType.entries) {
            if (!t.shadow) continue
            val grow = v.grow(t.grow)
            if (grow <= 0.001f) continue
            depthInst.f1("u_grow", grow)
            val mesh = propMeshes[t.ordinal][if (alphaFoliage) 0 else 1] ?: continue
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
        val s = game.settings
        sh.use()
        sh.m4("u_vp", vp)
        sh.m4("u_lightVP0", lightVp0)
        sh.m4("u_lightVP1", lightVp1)
        sh.f3("u_sunDir", env.lightDir)
        sh.f3("u_trueSunDir", env.sunDir)
        sh.f3("u_sunColor", env.sunColor)
        sh.f3("u_skyAmb", env.skyAmb)
        sh.f3("u_groundAmb", env.groundAmb)
        sh.f3("u_zenith", env.zenith)
        sh.f3("u_horizon", env.horizon)
        sh.f3("u_sunset", env.sunset)
        sh.i1("u_underwater", if (game.player.underwater) 1 else 0)
        sh.f3("u_waterFog", Planet.waterFog)
        sh.f1("u_frost", env.v.frost)
        sh.f3("u_frostColor", Planet.frostColor)
        sh.f1("u_fogDensity", env.fogDensity)
        sh.f1("u_fogHeightFalloff", 0.02f)
        sh.f1("u_viewDist", s.viewDistance * CHUNK_SIZE)
        sh.f3("u_camPos", camPos.x, camPos.y, camPos.z)
        sh.f1("u_exposure", env.exposure)
        sh.i1("u_hdr", if (postActive) 1 else 0)
        sh.f1("u_outScale", outScale)
        sh.i1("u_shadowMode", shadowMode)
        sh.i1("u_shadow0", 1)
        sh.i1("u_shadow1", 4)
        sh.f1("u_shadowTexel0", 1f / (shadow0?.size ?: 2048))
        sh.f1("u_shadowTexel1", 1f / (shadow1?.size ?: 2048))
        sh.i1("u_noise", 0)
        sh.i1("u_albedoArr", 2)
        sh.i1("u_normalArr", 3)
        sh.i1("u_scene", 5)
        sh.i1("u_texQuality", s.texQuality)
        sh.f4v("u_lights", lights, 4)
        sh.f3v("u_lightColors", lightColors, 4)
        sh.f1("u_flash", env.flash * 0.6f)
        sh.f1("u_time", time)
    }

    private fun chunkVisible(c: Chunk, extra: Float): Boolean =
        frustum.box(c.x0, c.minY - 2f, c.z0, c.x0 + CHUNK_SIZE, c.maxY + extra, c.z0 + CHUNK_SIZE)

    private fun drawTerrain(sm: Int) {
        val env = game.env
        val v = env.v
        setCommon(terrain, sm)
        terrain.f1("u_wetness", env.wetness * 0.8f)
        terrain.f1("u_moss", v.moss)
        terrain.f1("u_grass", v.grass)
        terrain.f1("u_waterLevel", v.waterLevel)
        terrain.f3("u_waterTint", Planet.waterTint)
        for (c in game.chunks.chunks.values) {
            val t = c.terrain ?: continue
            if (!chunkVisible(c, 2f)) continue
            t.draw()
        }
    }

    private fun drawProps(sm: Int) {
        val s = game.settings
        val v = game.env.v
        setCommon(inst, sm)
        inst.f1("u_wind", game.env.wind)
        inst.i1("u_foliage", if (s.foliage > 0 && s.texQuality > 0) 1 else 0)
        val p = game.player
        val pcx = floor(p.pos.x / CHUNK_SIZE).toInt()
        val pcz = floor(p.pos.z / CHUNK_SIZE).toInt()
        val grassChunks = if (s.grassDistance > 60f) 2 else 1
        val glowBoost = 1f + game.env.nightFactor * 0.7f
        glDisable(GL_CULL_FACE)
        for (t in PropType.entries) {
            val grow = v.grow(t.grow)
            if (grow <= 0.001f) continue
            inst.f1("u_grow", grow)
            inst.f1("u_translucency", t.translucency)
            inst.f1("u_frostTop", t.frostTop)
            inst.f1("u_glow", t.glow * glowBoost)
            if (t.isTree || t == PropType.BUSH) { inst.f3("u_tintA", 0.82f, 0.9f, 1.0f); inst.f3("u_tintB", 1.18f, 1.04f, 0.9f) }
            else { inst.f3("u_tintA", 0.86f, 0.86f, 0.9f); inst.f3("u_tintB", 1.12f, 1.1f, 1.06f) }
            val isDecor = t.range == 0
            inst.f1("u_fadeDist", if (isDecor) s.grassDistance else 0f)
            for (c in game.chunks.chunks.values) {
                val ib = c.inst[t.ordinal] ?: continue
                val cd = max(abs(c.cx - pcx), abs(c.cz - pcz))
                if (isDecor && cd > grassChunks) continue
                if (t.range == 1 && cd > 3) continue
                if (isDecor && !nearXZ(c, p.pos.x, p.pos.z, s.grassDistance)) continue
                if (!chunkVisible(c, if (t.range == 2) 20f else 3f)) continue
                val lod = if (isDecor) (if (cd == 0) 0 else 1) else if (cd <= 1 || (cd <= 2 && t.range == 1)) 0 else 1
                propMeshes[t.ordinal][lod]!!.drawInstanced(ib)
            }
        }
        glEnable(GL_CULL_FACE)
    }

    private fun litDefaults(texScale: Float) {
        lit.f4("u_tint", 1f, 1f, 1f, 0f)
        lit.f1("u_hitFlash", 0f)
        lit.f1("u_alpha", 1f)
        lit.f1("u_glass", 0f)
        lit.f1("u_frostTop", 0f)
        lit.f3("u_emissiveColor", 1f, 1f, 1f)
        lit.f1("u_texScale", texScale)
    }

    private fun drawStructures(sm: Int) {
        setCommon(lit, sm)
        Mat4.identity(model)
        lit.m4("u_model", model)
        litDefaults(0.5f)
        val p = game.player
        val maxD = game.settings.viewDistance * CHUNK_SIZE + 120f
        val wake = game.env.v.wake
        for (i in structSolid.indices) {
            val geo = game.structures.geos[i]
            val poi = geo.poi
            val d = sqrt((poi.x - p.pos.x) * (poi.x - p.pos.x) + (poi.z - p.pos.z) * (poi.z - p.pos.z))
            if (d > maxD + geo.boundsRadius) continue
            if (!frustum.sphere(poi.x, poi.y + 3f, poi.z, geo.boundsRadius + 6f)) continue
            lit.f1("u_specular", 0.3f)
            lit.f1("u_emissiveScale", 0f)
            lit.f1("u_frostTop", if (poi.kind == PoiKind.LANDER) 0.25f else 1f)
            structSolid[i].draw()
            val glow = structGlow[i] ?: continue
            val pulse = 0.82f + 0.18f * sin(time * 1.7f + poi.id)
            val e = when (poi.kind) {
                PoiKind.LANDER -> 1f
                PoiKind.VAULT -> 0.04f + wake * 1.6f * pulse
                PoiKind.SLATE, PoiKind.OUTPOST -> if (game.logsRead.contains(poi.logIndex)) 0.3f else pulse
                else -> if (game.lootInv[poi.id]?.total == 0) 0.12f else pulse
            }
            lit.f1("u_emissiveScale", e)
            lit.f1("u_frostTop", 0f)
            lit.f1("u_specular", 0f)
            glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(-1f, -2f)
            glow.draw()
            glDisable(GL_POLYGON_OFFSET_FILL)
        }
    }

    private fun watcherSink(): Float = smoothstep(0.3f, 0.95f, game.env.v.frost) * 5.2f

    private fun drawWatchers(sm: Int) {
        setCommon(lit, sm)
        litDefaults(0.5f)
        lit.f1("u_specular", 0.15f)
        val p = game.player
        val v = game.env.v
        val sink = watcherSink()
        val maxD = game.settings.viewDistance * CHUNK_SIZE + 60f
        for (i in game.watcherPois.indices) {
            val w = game.watcherPois[i]
            if (abs(w.x - p.pos.x) > maxD || abs(w.z - p.pos.z) > maxD) continue
            if (!frustum.sphere(w.x, w.y + 3f, w.z, 6f)) continue
            Mat4.fromTRS(model, w.x, w.y - sink, w.z, game.watcherYaw[i], 0f, 0f, 1f, 1f, 1f)
            lit.m4("u_model", model)
            lit.f1("u_emissiveScale", 0f)
            lit.f1("u_frostTop", 0.9f)
            watcherSolid.draw()
            lit.f1("u_frostTop", 0f)
            lit.f1("u_emissiveScale", 0.12f + game.env.nightFactor * 0.35f + v.wake * 1.4f)
            watcherGlow.draw()
            if (v.wake > 0.02f) {
                Mat4.fromTRS(model, w.x, w.y - sink, w.z, game.watcherYaw[i] + time * 0.05f, 0f, 0f, v.wake, v.wake, v.wake)
                // the crown opens from the top of the head, so scale it about that point
                model[13] = w.y - sink + 6.4f * (1f - v.wake)
                lit.m4("u_model", model)
                lit.f1("u_emissiveScale", 1.2f)
                glDisable(GL_CULL_FACE)
                watcherBloom.draw()
                glEnable(GL_CULL_FACE)
            }
        }
    }

    private fun drawPlaced(sm: Int) {
        if (game.placed.isEmpty()) return
        setCommon(lit, sm)
        litDefaults(0.9f)
        val p = game.player
        val maxD = game.settings.viewDistance * CHUNK_SIZE
        val eff = game.efficiency
        val flick = 0.9f + 0.1f * sin(time * 9f)
        for (pl in game.placed) {
            if (abs(pl.x - p.pos.x) > maxD || abs(pl.z - p.pos.z) > maxD) continue
            val t = pl.type
            if (t == BuildType.POD) {
                val cx = (pl.gx + 0.5f) * POD_CELL; val cz = (pl.gz + 0.5f) * POD_CELL
                if (!frustum.sphere(cx, pl.y + 1f, cz, 7f)) continue
                Mat4.fromTRS(model, cx, pl.y, cz, 0f, 0f, 0f, 1f, 1f, 1f)
                lit.m4("u_model", model)
                lit.f1("u_specular", 0.35f); lit.f1("u_emissiveScale", 0f); lit.f1("u_frostTop", 0.5f)
                podBase.draw()
                lit.f1("u_frostTop", 0f); lit.f1("u_emissiveScale", 1f)
                glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(-1f, -2f)
                podBaseGlow.draw()
                glDisable(GL_POLYGON_OFFSET_FILL)
                for (sd in 0 until 4) {
                    val st = game.sideState(pl, sd)
                    podSideMatrix(pl, sd, model)
                    lit.m4("u_model", model)
                    lit.f1("u_emissiveScale", 0f)
                    podSide[st]!!.draw()
                    podSideGlow[st]?.let { lit.f1("u_emissiveScale", 1f); it.draw() }
                    if (st == 1) {
                        Mat4.fromTRS(model, cx, pl.y + POD_FLOOR * (1f - game.rampDrop(pl)), cz, -sd * PI_F * 0.5f, 0f, 0f, 1f, game.rampDrop(pl), 1f)
                        lit.m4("u_model", model)
                        lit.f1("u_emissiveScale", 0f)
                        glDisable(GL_CULL_FACE)
                        podRamp.draw()
                        glEnable(GL_CULL_FACE)
                    }
                }
                continue
            }
            if (!frustum.sphere(pl.x, pl.y + t.height * 0.5f, pl.z, t.height + t.radius)) continue
            val m = models[t]!!
            placedMatrix(pl, model)
            lit.m4("u_model", model)
            lit.f1("u_emissiveScale", 0.5f)
            lit.f1("u_specular", 0.35f)
            lit.f1("u_frostTop", if (t.indoor) 0f else 0.35f)
            m.solid.draw()
            lit.f1("u_frostTop", 0f)
            if (m.glow != null) {
                glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(-1f, -2f)
                val on = if (t.power < 0f) (0.25f + 0.75f * eff) else 1f
                val bl = if (t == BuildType.BEACON) (0.4f + 0.6f * smoothstep(0.6f, 1f, sin(time * 2.2f + pl.id))) else if (t.cat == Cat.HEAT) flick else 1f
                lit.f1("u_emissiveScale", on * bl * 1.3f)
                m.glow.draw()
                glDisable(GL_POLYGON_OFFSET_FILL)
            }
            if (m.spin != null) {
                placedMatrix(pl, model, time * m.set.spinSpeed * (if (t.power < 0f) eff else 1f))
                lit.m4("u_model", model)
                lit.f1("u_emissiveScale", 1f)
                glDisable(GL_CULL_FACE)
                m.spin.draw()
                glEnable(GL_CULL_FACE)
            }
        }
    }

    private fun drawGlass(sm: Int) {
        if (game.placed.isEmpty()) return
        setCommon(lit, sm)
        litDefaults(1f)
        lit.f1("u_glass", 1f)
        lit.f1("u_alpha", 0.16f)
        lit.f1("u_specular", 1f)
        lit.f1("u_emissiveScale", 0f)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        val p = game.player
        val maxD = min(game.settings.viewDistance * CHUNK_SIZE, 160f)
        for (pl in game.placed) {
            if (abs(pl.x - p.pos.x) > maxD || abs(pl.z - p.pos.z) > maxD) continue
            if (pl.type == BuildType.POD) {
                if (!frustum.sphere((pl.gx + 0.5f) * POD_CELL, pl.y + 1f, (pl.gz + 0.5f) * POD_CELL, 7f)) continue
                for (sd in 0 until 4) {
                    val gm = podSideGlass[game.sideState(pl, sd)] ?: continue
                    podSideMatrix(pl, sd, model)
                    lit.m4("u_model", model)
                    gm.draw()
                }
                continue
            }
            val m = models[pl.type]!!
            val gm = m.glass ?: continue
            if (!frustum.sphere(pl.x, pl.y + pl.type.height * 0.5f, pl.z, pl.type.height + pl.type.radius)) continue
            placedMatrix(pl, model)
            lit.m4("u_model", model)
            gm.draw()
        }
        glEnable(GL_CULL_FACE)
        glDepthMask(true)
        glDisable(GL_BLEND)
        lit.f1("u_glass", 0f)
        lit.f1("u_alpha", 1f)
    }

    private fun drawSky() {
        val env = game.env
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        setCommon(sky, 0)
        sky.m4("u_invViewProj", invVp)
        sky.f3("u_moonDir", env.moonDir)
        sky.f1("u_night", env.nightFactor)
        sky.f1("u_starVis", env.starVis)
        sky.f1("u_giantVis", env.giantVis)
        sky.f1("u_aurora", env.aurora)
        sky.f1("u_cloudCover", env.clouds)
        sky.i1("u_clouds", if (!game.settings.clouds) 0 else if (game.settings.postFx) 2 else 1)
        skyMesh.draw()
        glDepthMask(true)
        glEnable(GL_CULL_FACE)
    }

    private fun hasVisibleWater(): Boolean {
        val lv = game.env.v.waterLevel
        for (c in game.chunks.chunks.values) {
            if (c.water == null) continue
            if (frustum.box(c.x0, lv - 1f, c.z0, c.x0 + CHUNK_SIZE, lv + 1f, c.z0 + CHUNK_SIZE)) return true
        }
        return false
    }

    private fun copySceneForRefraction() {
        val src = sceneRT!!; val dst = refractRT!!
        msaaRT?.resolveTo(src)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, src.fbo)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, dst.fbo)
        glBlitFramebuffer(0, 0, src.width, src.height, 0, 0, dst.width, dst.height, GL_COLOR_BUFFER_BIT, GL_LINEAR)
        if (msaaRT != null) msaaRT!!.bind() else { glBindFramebuffer(GL_FRAMEBUFFER, src.fbo); glViewport(0, 0, src.width, src.height) }
        glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, dst.texture); glActiveTexture(GL_TEXTURE0)
    }

    private fun drawWater(sm: Int, refract: Boolean) {
        val env = game.env
        val lv = env.v.waterLevel
        setCommon(water, sm)
        water.i1("u_waterQuality", game.settings.waterQuality)
        water.i1("u_refract", if (refract) 1 else 0)
        water.f1("u_rain", env.rain)
        water.f1("u_waveScale", 0.6f + env.wind * 0.8f)
        water.f1("u_waterLevel", lv)
        water.f3("u_waterAbsorb", Planet.waterAbsorb)
        water.f3("u_waterScatter", Planet.waterScatter)
        water.f1("u_waterGlow", env.nightFactor * 0.35f + env.v.wake * 0.25f)
        if (!refract) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        }
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        for (c in game.chunks.chunks.values) {
            val w = c.water ?: continue
            if (c.minY > lv + 0.2f) continue
            if (!frustum.box(c.x0, lv - 1f, c.z0, c.x0 + CHUNK_SIZE, lv + 1f, c.z0 + CHUNK_SIZE)) continue
            w.draw()
        }
        glDepthMask(true)
        glEnable(GL_CULL_FACE)
        glDisable(GL_BLEND)
    }

    private val outMul get() = if (postActive) outScale * game.env.exposure else 1f

    private fun drawFx(rh: Int) {
        val fx = game.fx
        var n = 0
        for (i in 0 until fx.max) {
            if (fx.life[i] <= 0f || fx.alpha[i] <= 0.002f) continue
            val o = n * VERTEX_FLOATS
            fxData[o] = fx.x[i]; fxData[o + 1] = fx.y[i]; fxData[o + 2] = fx.z[i]
            fxData[o + 3] = fx.size[i]; fxData[o + 4] = fx.alpha[i]; fxData[o + 5] = fx.add[i]
            fxData[o + 6] = fx.r[i]; fxData[o + 7] = fx.g[i]; fxData[o + 8] = fx.b[i]; fxData[o + 9] = 1f
            n++
        }
        if (n == 0) return
        fxBuf.clear(); fxBuf.put(fxData, 0, n * VERTEX_FLOATS); fxBuf.position(0)
        glBindVertexArray(fxVao)
        glBindBuffer(GL_ARRAY_BUFFER, fxVbo)
        glBufferSubData(GL_ARRAY_BUFFER, 0, n * VERTEX_FLOATS * 4, fxBuf)
        fxShader.use()
        fxShader.m4("u_vp", vp)
        fxShader.f1("u_pointScale", rh / (2f * tan(game.settings.fov * 0.5f * 0.017453292f)))
        // smoke is lit by the sky; sparks ignore it
        val env = game.env
        val lum = clamp(0.35f + (env.skyAmb[0] + env.skyAmb[1] + env.skyAmb[2]) * 0.6f + (env.sunColor[0] + env.sunColor[1] + env.sunColor[2]) * 0.08f, 0.3f, 1.6f)
        fxShader.f1("u_outMul", outMul * lum)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glDrawArrays(GL_POINTS, 0, n)
        glDepthMask(true)
        glDisable(GL_BLEND)
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    private fun drawWeather(rh: Int) {
        val env = game.env
        val p = game.player
        if (p.underwater || p.indoors) return
        val v = env.v
        particle.use()
        particle.m4("u_vp", vp)
        particle.f3("u_camPos", camPos.x, camPos.y, camPos.z)
        particle.f1("u_time", time)
        particle.f1("u_pointScale", rh * 0.012f)
        particle.f1("u_outMul", if (postActive) outScale else 1f)
        glEnable(GL_BLEND)
        glDepthMask(false)
        val lum = 0.4f + env.skyAmb[1] * 1.5f + env.sunColor[1] * 0.12f
        if (env.rain > 0.05f) {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            particle.f1("u_mode", 0f)
            particle.f1("u_fall", 14f)
            particle.f1("u_wander", 0f)
            particle.f3("u_windDir", env.wind * 1.5f, 0f, env.wind * 0.6f)
            particle.f3("u_color", 0.62f * lum, 0.72f * lum, 0.82f * lum)
            particleMesh.drawArrays(GL_LINES, (particleCount * env.rain).toInt() * 2)
        }
        if (env.drift > 0.05f) {
            // ice crystals carried on the thin wind of the frozen world
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            particle.f1("u_mode", 1f)
            particle.f1("u_fall", 0.7f)
            particle.f1("u_wander", 0.5f)
            particle.f3("u_windDir", 2.5f + env.wind * 5f, 0f, 1.2f + env.wind * 2f)
            particle.f3("u_color", 0.85f * lum, 0.88f * lum, 1f * lum)
            particleMesh.drawArrays(GL_POINTS, (particleCount * 0.5f * clamp01(env.drift)).toInt())
        }
        if (v.motes > 0.02f) {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE)
            particle.f1("u_mode", 2f)
            particle.f1("u_fall", 0.06f)
            particle.f1("u_wander", 1.6f)
            particle.f3("u_windDir", 0.2f, 0f, 0.1f)
            val k = (0.5f + 0.9f * env.nightFactor) * (if (postActive) 2.2f else 1f)
            particle.f3("u_color", 1f * k, 0.72f * k, 0.5f * k)
            particle.f1("u_pointScale", rh * 0.02f)
            particleMesh.drawArrays(GL_POINTS, (700 * v.motes * (1f - env.rain)).toInt())
        }
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun drawBuildPreview() {
        val t = game.buildType ?: return
        val bp = game.buildPos
        unlit.use()
        unlit.m4("u_vp", vp)
        unlit.f1("u_fadeHeight", 0f)
        unlit.f1("u_outMul", outMul)
        if (game.buildValid) unlit.f4("u_color", 0.5f, 1.7f, 1.5f, 0.42f) else unlit.f4("u_color", 1.9f, 0.45f, 0.5f, 0.42f)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        if (t == BuildType.POD) {
            Mat4.fromTRS(model, bp.x, bp.y, bp.z, 0f, 0f, 0f, 1f, 1f, 1f)
            unlit.m4("u_model", model)
            podBase.draw()
            for (sd in 0 until 4) {
                Mat4.fromTRS(model, bp.x, bp.y, bp.z, -sd * PI_F * 0.5f, 0f, 0f, 1f, 1f, 1f)
                unlit.m4("u_model", model)
                podSide[if (sd == game.buildDoor) 1 else 0]!!.draw()
            }
        } else {
            val m = models[t]!!
            Mat4.fromTRS(model, bp.x, bp.y, bp.z, game.buildYaw, 0f, 0f, 1f, 1f, 1f)
            unlit.m4("u_model", model)
            m.solid.draw()
            m.spin?.draw()
            m.glow?.draw()
        }
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun drawViewModel(sm: Int, rw: Int, rh: Int) {
        val p = game.player
        if (p.dead || !game.started || !game.worldReady) return
        glClear(GL_DEPTH_BUFFER_BIT)
        setCommon(lit, sm)
        litDefaults(2.2f)
        lit.f1("u_specular", 0.3f)
        Mat4.perspective(tmpM, 58f, rw.toFloat() / rh, 0.03f, 10f)
        Mat4.mul(vvp, tmpM, view)
        lit.m4("u_vp", vvp)
        val bobX = sin(p.headBob) * 0.012f * p.bobAmount
        val bobY = abs(cos(p.headBob)) * 0.012f * p.bobAmount
        val kick = game.toolKick
        val k = sin(kick * PI_F) * kick
        val building = game.buildType != null || game.deconMode
        val ox = 0.19f + bobX
        val oy = -0.125f + bobY + k * 0.03f + (if (building) 0.03f else 0f)
        val oz = -0.38f + k * 0.07f
        Mat4.fromTRS(local, ox, oy, oz, 0.3f, 0.06f + k * 0.3f, 0.06f, 1f, 1f, 1f)
        Mat4.mul(model, invView, local)
        lit.m4("u_model", model)
        lit.f1("u_emissiveScale", 0f)
        toolMesh.draw()
        if (game.deconMode) lit.f3("u_emissiveColor", 2f, 0.5f, 0.45f) else lit.f3("u_emissiveColor", 1f, 1f, 1f)
        lit.f1("u_emissiveScale", if (building) 1.6f + 0.5f * sin(time * 8f) else 0.8f + k * 2f)
        toolGlow.draw()
        lit.f3("u_emissiveColor", 1f, 1f, 1f)
    }

    // ------------------------------------------------------------------ post processing

    private fun postProcess() {
        val s = game.settings
        val env = game.env
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_CULL_FACE)
        glDisable(GL_BLEND)
        glDepthMask(false)
        var src = sceneRT!!
        for (i in bloomRT.indices) {
            val dst = bloomRT[i]!!
            dst.bind()
            down.use()
            down.i1("u_tex", 0)
            down.f2("u_texel", 1f / src.width, 1f / src.height)
            down.f1("u_threshold", if (i == 0) 1.15f * outScale else 0f)
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, src.texture)
            skyMesh.draw()
            src = dst
        }
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE)
        for (i in bloomRT.size - 1 downTo 1) {
            val from = bloomRT[i]!!; val to = bloomRT[i - 1]!!
            to.bind()
            up.use()
            up.i1("u_tex", 0)
            up.f2("u_texel", 1f / from.width, 1f / from.height)
            glBindTexture(GL_TEXTURE_2D, from.texture)
            skyMesh.draw()
        }
        glDisable(GL_BLEND)

        val ldr = ldrRT
        if (ldr != null) ldr.bind() else { glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, width, height) }
        composite.use()
        composite.i1("u_scene", 0)
        composite.i1("u_bloom", 1)
        composite.i1("u_noise", 2)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sceneRT!!.texture)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, bloomRT[0]!!.texture)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, noiseTex)
        composite.f1("u_inScale", 1f / outScale)
        composite.f1("u_bloomStrength", s.bloom * 0.13f)
        val golden = env.golden * sqrt(env.v.atmo)
        val night = env.nightFactor
        composite.f3("u_grade", lerp(1f, 1.07f, golden) * lerp(1f, 0.92f, night), 1f, lerp(1f, 0.92f, golden) * lerp(1f, 1.1f, night))
        composite.f1("u_saturation", lerp(1.04f, 0.86f, night))
        composite.f1("u_contrast", 0.2f)
        composite.f1("u_vignette", 0.5f)
        composite.i1("u_underwater", if (game.player.underwater) 1 else 0)
        composite.f3("u_waterFog", Planet.waterFog)
        // the visor frosts at the edges on the frozen world, and is gone once you can breathe
        val v = env.v
        if (v.breathable || !game.started) composite.f3("u_visor", 0f, 0f, 0f)
        else composite.f3("u_visor", lerp(0.03f, 0.3f, v.frost), lerp(0.05f, 0.34f, v.frost), lerp(0.07f, 0.46f, v.frost))
        composite.f2("u_noiseOffset", (time * 7.1f) % 1f, (time * 3.7f) % 1f)
        skyMesh.draw()
        if (ldr != null) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glViewport(0, 0, width, height)
            fxaa.use()
            fxaa.i1("u_tex", 0)
            fxaa.f2("u_texel", 1f / ldr.width, 1f / ldr.height)
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, ldr.texture)
            skyMesh.draw()
        }
        glActiveTexture(GL_TEXTURE0)
        glDepthMask(true)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_CULL_FACE)
    }
}
