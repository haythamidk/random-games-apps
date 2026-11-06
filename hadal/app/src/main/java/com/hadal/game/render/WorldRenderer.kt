package com.hadal.game.render

import android.opengl.GLES30.*
import com.hadal.game.engine.DEG2RAD
import com.hadal.game.engine.Frustum
import com.hadal.game.engine.GLCaps
import com.hadal.game.engine.GLUtil
import com.hadal.game.engine.Mat4
import com.hadal.game.engine.Mesh
import com.hadal.game.engine.MeshData
import com.hadal.game.engine.NoiseImage
import com.hadal.game.engine.ProceduralTextures
import com.hadal.game.engine.RenderTarget
import com.hadal.game.engine.Shader
import com.hadal.game.engine.Shaders
import com.hadal.game.engine.ShadowMap
import com.hadal.game.engine.TextureArray
import com.hadal.game.engine.Textures
import com.hadal.game.engine.Vec3
import com.hadal.game.engine.clamp
import com.hadal.game.engine.clamp01
import com.hadal.game.engine.lerp
import com.hadal.game.engine.smoothstep
import com.hadal.game.game.CState
import com.hadal.game.game.Creature
import com.hadal.game.game.GameState
import com.hadal.game.game.Species
import com.hadal.game.world.CHUNK_SIZE
import com.hadal.game.world.FloraMeshes
import com.hadal.game.world.FloraType
import com.hadal.game.world.SeaGen
import com.hadal.game.world.Structures
import com.hadal.game.world.Zone
import java.io.File
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** CPU side assets prepared on a background thread before the first frame. */
object RenderAssets {
    @Volatile var ready = false
        private set
    lateinit var textures: ProceduralTextures.Result
    lateinit var noise: ByteArray
    lateinit var caustics: ByteArray
    lateinit var flora: Array<MeshData>
    lateinit var creatures: Map<Species, MeshData>
    lateinit var segments: Map<Species, MeshData>
    lateinit var sub: MeshData
    lateinit var prop: MeshData
    lateinit var flare: MeshData
    lateinit var echo: MeshData
    lateinit var drone: MeshData

    @Synchronized fun prepare(cacheDir: File?, pool: ExecutorService?) {
        if (ready) return
        textures = ProceduralTextures.load(cacheDir, pool)
        noise = NoiseImage.generate(256, 99)
        caustics = ProceduralTextures.caustics(256)
        Structures.buildAll()
        flora = Array(FloraType.entries.size) { FloraMeshes.get(FloraType.entries[it]) }
        creatures = Species.entries.associateWith { Models.creature(it) }
        segments = listOf(Species.PALE, Species.RIBBON_EEL).associateWith { Models.segment(it) }
        sub = Models.sub(); prop = Models.propeller(); flare = Models.flareMesh(); echo = Structures.echoStoneMesh(); drone = Models.drone()
        ready = true
    }
}

/** Renders the ocean. All methods run on the GL thread. */
class WorldRenderer(private val game: GameState) {
    private lateinit var lit: Shader
    private lateinit var inst: Shader
    private lateinit var terrain: Shader
    private lateinit var sky: Shader
    private lateinit var depth: Shader
    private lateinit var depthInst: Shader
    private lateinit var snow: Shader
    private lateinit var glow: Shader
    private lateinit var volume: Shader
    private lateinit var down: Shader
    private lateinit var up: Shader
    private lateinit var composite: Shader
    private lateinit var fxaa: Shader

    private var noiseTex = 0
    private var causticTex = 0
    private var albedoArr: TextureArray? = null
    private var normalArr: TextureArray? = null
    private var lampShadow: ShadowMap? = null
    private var dummyShadow: ShadowMap? = null
    private val floraMeshes = arrayOfNulls<Mesh>(FloraType.entries.size)
    private val structMeshes = ArrayList<Pair<com.hadal.game.world.StructureGeo, Mesh>>()
    private val creatureMeshes = HashMap<Species, Mesh>()
    private val segmentMeshes = HashMap<Species, Mesh>()
    private lateinit var subMesh: Mesh
    private lateinit var propMesh: Mesh
    private lateinit var flareMesh: Mesh
    private lateinit var echoMesh: Mesh
    private lateinit var droneMesh: Mesh
    private lateinit var quad: Mesh
    private var snowVao = 0
    private var snowVbo = 0
    private var snowCount = 0

    private var width = 1
    private var height = 1
    private var sceneRT: RenderTarget? = null
    private var volRT: RenderTarget? = null
    private var ldrRT: RenderTarget? = null
    private val bloomRT = arrayOfNulls<RenderTarget>(5)
    private var sceneW = 0
    private var sceneH = 0
    private var hdrFormat = true
    private var outScale = 1f
    private var initialized = false
    private var postActive = false

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val vp = FloatArray(16)
    private val invVP = FloatArray(16)
    private val lampView = FloatArray(16)
    private val lampProj = FloatArray(16)
    private val lampVP = FloatArray(16)
    private val model = FloatArray(16)
    private val model2 = FloatArray(16)
    private val frustum = Frustum()
    private val lampFrustum = Frustum()
    private var time = 0f

    // environment for this frame
    private val sunAmb = FloatArray(3)
    private val ext = FloatArray(3)
    private val scatter = FloatArray(3)
    private val abyss = floatArrayOf(0.0012f, 0.003f, 0.0055f)
    private var exposure = 1f
    private var causticStr = 0f
    private val lampPos = Vec3()
    private val lampDir = Vec3()
    private val lampColor = FloatArray(3)
    private var lampCosO = 0.8f
    private var lampCosI = 0.9f
    private var lampRange = 30f
    private var shadowMode = 0
    private val lights = FloatArray(32)
    private val lightColors = FloatArray(24)
    private val volLights = FloatArray(16)
    private val volLightColors = FloatArray(12)

    // dynamic resolution
    private var dynScale = 1f
    private var dynAccum = 0f
    private var dynFrames = 0
    private var dynTimer = 0f
    private var dynGood = 0

    fun init() {
        GLCaps.init()
        lit = Shader(Shaders.litVS, Shaders.litFS, "lit")
        inst = Shader(Shaders.instVS, Shaders.instFS, "inst")
        terrain = Shader(Shaders.terrainVS, Shaders.terrainFS, "terrain")
        sky = Shader(Shaders.skyVS, Shaders.skyFS, "sky")
        depth = Shader(Shaders.depthVS, Shaders.depthFS, "depth")
        depthInst = Shader(Shaders.depthInstVS, Shaders.depthFS, "depthInst")
        snow = Shader(Shaders.snowVS, Shaders.snowFS, "snow")
        glow = Shader(Shaders.glowVS, Shaders.glowFS, "glow")
        volume = Shader(Shaders.postVS, Shaders.volumeFS, "volume")
        down = Shader(Shaders.postVS, Shaders.downFS, "down")
        up = Shader(Shaders.postVS, Shaders.upFS, "up")
        composite = Shader(Shaders.postVS, Shaders.compositeFS, "composite")
        fxaa = Shader(Shaders.postVS, Shaders.fxaaFS, "fxaa")

        val a = RenderAssets
        noiseTex = Textures.createRGBA(256, a.noise)
        causticTex = Textures.createRGBA(256, a.caustics)
        albedoArr = TextureArray(ProceduralTextures.SIZE, a.textures.albedo, 4f)
        normalArr = TextureArray(ProceduralTextures.NSIZE, a.textures.normals, 4f)
        for (i in floraMeshes.indices) floraMeshes[i] = Mesh(a.flora[i])
        for (g in Structures.geos) structMeshes.add(g to Mesh(g.mesh))
        for ((s, m) in a.creatures) creatureMeshes[s] = Mesh(m)
        for ((s, m) in a.segments) segmentMeshes[s] = Mesh(m)
        subMesh = Mesh(a.sub); propMesh = Mesh(a.prop); flareMesh = Mesh(a.flare); echoMesh = Mesh(a.echo); droneMesh = Mesh(a.drone)
        quad = Mesh(MeshData(floatArrayOf(-1f, -1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, -1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f,
            1f, 1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f, -1f, 1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f), 4, intArrayOf(0, 1, 2, 0, 2, 3), 6))
        buildSnow(4000)
        dummyShadow = ShadowMap(16)
        hdrFormat = GLCaps.halfFloatTargets
        glClearColor(0f, 0f, 0f, 1f)
        initialized = true
    }

    private fun buildSnow(n: Int) {
        val r = com.hadal.game.engine.Rng(31337)
        val data = FloatArray(n * 3) { r.nextFloat() }
        snowVao = GLUtil.genVao(); snowVbo = GLUtil.genBuffer()
        glBindVertexArray(snowVao)
        glBindBuffer(GL_ARRAY_BUFFER, snowVbo)
        glBufferData(GL_ARRAY_BUFFER, n * 12, GLUtil.floatBuffer(data), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        glBindVertexArray(0)
        snowCount = n
    }

    fun resize(w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        releaseTargets()
    }

    private fun releaseTargets() {
        sceneRT?.release(); sceneRT = null
        volRT?.release(); volRT = null
        ldrRT?.release(); ldrRT = null
        for (i in bloomRT.indices) { bloomRT[i]?.release(); bloomRT[i] = null }
    }

    private fun ensureTargets(): Boolean {
        val s = game.settings
        val scale = clamp(if (s.dynamicRes) min(dynScale, s.renderScale) else s.renderScale, 0.4f, 1f)
        val w = max(16, (width * scale).toInt()); val h = max(16, (height * scale).toInt())
        val volDiv = if (s.volumetrics >= 2) 2 else 3
        if (sceneRT != null && sceneW == w && sceneH == h && (ldrRT != null) == s.fxaa && (volRT != null) == (s.volumetrics > 0) &&
            (volRT == null || volRT!!.width == max(8, w / volDiv))) return true
        releaseTargets()
        sceneW = w; sceneH = h
        var rt = RenderTarget(w, h, hdrFormat, true, depthTexture = true)
        if (!rt.complete && hdrFormat) { rt.release(); hdrFormat = false; rt = RenderTarget(w, h, false, true, depthTexture = true) }
        if (!rt.complete) { rt.release(); return false }
        sceneRT = rt
        outScale = if (hdrFormat) 1f else 0.25f
        if (s.volumetrics > 0) volRT = RenderTarget(max(8, w / volDiv), max(8, h / volDiv), hdrFormat, false)
        var bw = w; var bh = h
        for (i in bloomRT.indices) { bw = max(4, bw / 2); bh = max(4, bh / 2); bloomRT[i] = RenderTarget(bw, bh, hdrFormat, false) }
        if (s.fxaa) ldrRT = RenderTarget(width, height, false, false)
        return true
    }

    private fun updateDynamicRes(dt: Float) {
        val s = game.settings
        if (!s.dynamicRes || !postActive) { dynScale = s.renderScale; return }
        dynAccum += dt; dynFrames++; dynTimer += dt
        if (dynTimer < 1.5f) return
        val avg = dynAccum / dynFrames
        val target = 1f / s.fpsCap
        val old = dynScale
        if (avg > target * 1.12f) { dynScale = max(0.5f, dynScale - 0.05f); dynGood = 0 }
        else if (avg < target * 1.04f) { dynGood++; if (dynGood >= 3) { dynScale = min(s.renderScale, dynScale + 0.05f); dynGood = 0 } }
        if (dynScale > s.renderScale) dynScale = s.renderScale
        dynTimer = 0f; dynFrames = 0; dynAccum = 0f
        if (abs(old - dynScale) > 0.01f) releaseTargets()
    }

    private fun ensureShadow() {
        val s = game.settings
        shadowMode = if (s.shadows == 0 || !game.sub.lampOn) 0 else s.shadows
        if (s.shadows == 0) return
        val want = if (s.shadows >= 2) 2048 else 1024
        if (lampShadow?.size != want) { lampShadow?.release(); lampShadow = ShadowMap(want) }
    }

    // ------------------------------------------------------------------ environment

    private fun computeEnvironment() {
        val g = game
        val camY = g.camPos.y
        // water: murkier and greener on the shelf, clearer and bluer offshore
        val shelf = clamp01(1f - (-g.floorY - 60f) / 200f)
        ext[0] = lerp(0.2f, 0.22f, 1f - shelf); ext[1] = lerp(0.036f, 0.04f, 1f - shelf); ext[2] = lerp(0.027f, 0.025f, 1f - shelf)
        val surf = 2.6f
        sunAmb[0] = 0.75f * surf; sunAmb[1] = 0.95f * surf; sunAmb[2] = 1f * surf
        val d = max(0f, -camY)
        val sx = sunAmb[0] * kotlin.math.exp(-ext[0] * d); val sy = sunAmb[1] * kotlin.math.exp(-ext[1] * d); val sz = sunAmb[2] * kotlin.math.exp(-ext[2] * d)
        scatter[0] = sx * 0.06f + abyss[0]; scatter[1] = sy * 0.2f + abyss[1]; scatter[2] = sz * 0.32f + abyss[2]
        val lum = sx * 0.2f + sy * 0.7f + sz * 0.1f
        // eyes adapt to the dark, but never fully: the abyss stays black
        val target = clamp(0.5f / (lum + 0.12f), 0.55f, 3.2f)
        exposure += (target - exposure) * 0.05f
        causticStr = if (g.settings.caustics) smoothstep(140f, 10f, d) * 1.4f else 0f

        // floodlamp
        val sub = g.sub
        sub.nose(lampPos)
        lampDir.set(sin(sub.bodyYaw) * cos(sub.bodyPitch), sin(sub.bodyPitch), cos(sub.bodyYaw) * cos(sub.bodyPitch))
        // aim slightly toward the look direction so the beam follows the camera
        val f = Vec3(); sub.forward(f)
        lampDir.x = lampDir.x * 0.4f + f.x * 0.6f; lampDir.y = lampDir.y * 0.4f + f.y * 0.6f; lampDir.z = lampDir.z * 0.4f + f.z * 0.6f
        lampDir.normalize()
        var on = if (sub.lampOn && !g.dead) 1f else 0f
        if (sub.flicker > 0f) on *= if (sin(time * 47f) * sin(time * 13f) > 0.1f) 0.15f else 1f
        val lvl = g.upgrades[com.hadal.game.game.Upgrade.LAMP.ordinal]
        val inten = (7f + lvl * 2f) * on
        lampColor[0] = 1f * inten; lampColor[1] = 0.93f * inten; lampColor[2] = 0.82f * inten
        lampRange = sub.lampRange
        lampCosO = sub.lampCos; lampCosI = min(0.99f, sub.lampCos + 0.08f)
    }

    private fun gatherLights() {
        val g = game
        val cam = g.camPos
        class L(val x: Float, val y: Float, val z: Float, val r: Float, val cr: Float, val cg: Float, val cb: Float, val d: Float)
        val list = ArrayList<L>(24)
        fun add(x: Float, y: Float, z: Float, r: Float, cr: Float, cg: Float, cb: Float) {
            val dx = x - cam.x; val dy = y - cam.y; val dz = z - cam.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d - r < 90f) list.add(L(x, y, z, r, cr, cg, cb, max(0f, d - r * 0.5f)))
        }
        for (d in g.fleet.drones) if (d.state != com.hadal.game.game.DroneState.LOST) add(d.pos.x, d.pos.y, d.pos.z, 7f, 0.3f, 0.8f, 1f)
        // the Wren's own hull lights so the sub reads in the dark
        if (!g.dead) {
            val sp = g.sub.pos
            add(sp.x, sp.y + 2.4f, sp.z, 8f, 1.5f, 1.2f, 0.8f)
        }
        for (f in g.flares) { val k = 5f * clamp01(f.life / 4f) * (0.85f + 0.15f * sin(time * 30f + f.life)); add(f.pos.x, f.pos.y, f.pos.z, 26f, 1f * k, 0.32f * k, 0.12f * k) }
        for ((geo, _) in structMeshes) {
            for (fx in geo.fixtures) {
                if (!fixtureOn(fx)) continue
                var k = 1.6f
                if (fx.blink > 0f) k *= if (sin(time * 6.28f / fx.blink) > 0.2f) 1f else 0.08f
                add(fx.x, fx.y, fx.z, fx.range, fx.r * k, fx.g * k, fx.b * k)
            }
        }
        for (c in g.ecology.list) {
            when (c.species) {
                Species.ANGLER -> { val k = c.glow * 1.4f; headPoint(c, 0f, 1.6f * c.size, 1.25f * c.size, tmpV); add(tmpV.x, tmpV.y, tmpV.z, 9f, 0.4f * k, 0.85f * k, 1f * k) }
                Species.WISP -> add(c.pos.x, c.pos.y, c.pos.z, 7f, 0.35f, 0.6f, 1f)
                Species.MOON_JELLY -> add(c.pos.x, c.pos.y, c.pos.z, 5f, 0.15f, 0.3f, 0.5f)
                else -> {}
            }
        }
        // glowing vents nearby
        if (g.zone == Zone.VENTS || g.zone == Zone.RIM || g.zone == Zone.HADAL) {
            g.chunks.forEachNodeNear(cam.x, cam.z, 60f) { n ->
                if (n.type == FloraType.CHIMNEY) add(n.x, n.y + 13f * n.scale, n.z, 22f, 2.2f, 0.75f, 0.2f)
                else if (n.type == FloraType.CRYSTAL) add(n.x, n.y + 0.8f, n.z, 6f, 0.9f, 0.45f, 0.12f)
            }
        }
        list.sortBy { it.d }
        for (i in 0 until 8) {
            val l = list.getOrNull(i)
            if (l == null) { lights[i * 4 + 3] = 0f; continue }
            lights[i * 4] = l.x; lights[i * 4 + 1] = l.y; lights[i * 4 + 2] = l.z; lights[i * 4 + 3] = l.r
            lightColors[i * 3] = l.cr; lightColors[i * 3 + 1] = l.cg; lightColors[i * 3 + 2] = l.cb
        }
        for (i in 0 until 4) {
            val l = list.getOrNull(i)
            if (l == null || l.d > 40f) { volLights[i * 4 + 3] = 0f; continue }
            volLights[i * 4] = l.x; volLights[i * 4 + 1] = l.y; volLights[i * 4 + 2] = l.z; volLights[i * 4 + 3] = l.r * 0.8f
            volLightColors[i * 3] = l.cr; volLightColors[i * 3 + 1] = l.cg; volLightColors[i * 3 + 2] = l.cb
        }
    }

    private val tmpV = Vec3()

    private fun fixtureOn(fx: com.hadal.game.world.Fixture): Boolean = when (fx.group) {
        2 -> !game.story.relayFixed
        3 -> game.story.relayFixed
        4 -> true
        else -> true
    }

    /** World position of a point on a creature's head in local coordinates. */
    private fun headPoint(c: Creature, lx: Float, ly: Float, lz: Float, out: Vec3) {
        Mat4.fromTRS(model2, c.pos.x, c.pos.y, c.pos.z, c.yaw, -c.pitch, 0f, c.size, c.size, c.size)
        Mat4.transformPoint(model2, lx / c.size, ly / c.size, lz / c.size, out)
    }

    private fun setCommon(sh: Shader, hdr: Boolean) {
        sh.m4("u_vp", vp)
        sh.f3("u_camPos", game.camPos.x, game.camPos.y, game.camPos.z)
        sh.f1("u_time", time)
        sh.f3("u_sunAmb", sunAmb); sh.f3("u_ext", ext); sh.f3("u_scatter", scatter); sh.f3("u_abyss", abyss)
        sh.f1("u_causticStr", causticStr)
        sh.f3("u_lampPos", lampPos.x, lampPos.y, lampPos.z); sh.f3("u_lampDir", lampDir.x, lampDir.y, lampDir.z)
        sh.f3("u_lampColor", lampColor); sh.f2("u_lampCone", lampCosO, lampCosI); sh.f1("u_lampRange", lampRange)
        sh.i1("u_shadowMode", shadowMode)
        sh.m4("u_lampVP", lampVP)
        sh.f1("u_shadowTexel", 1f / (lampShadow?.size ?: 1024))
        sh.f4v("u_lights", lights, 8); sh.f3v("u_lightColors", lightColors, 8)
        sh.i1("u_texQuality", game.settings.texQuality)
        sh.i1("u_hdr", if (hdr) 1 else 0)
        sh.f1("u_exposure", exposure)
        sh.f1("u_outScale", outScale)
        sh.i1("u_albedoArr", 0); sh.i1("u_normalArr", 1); sh.i1("u_caustics", 2); sh.i1("u_lampShadow", 3)
    }

    private fun bindTextures() {
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D_ARRAY, albedoArr!!.id)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D_ARRAY, normalArr!!.id)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, causticTex)
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, (if (shadowMode > 0) lampShadow else dummyShadow)!!.texture)
        glActiveTexture(GL_TEXTURE0)
    }

    // ------------------------------------------------------------------ frame

    fun render(dt: Float) {
        if (!initialized) return
        time += dt
        val g = game
        val s = g.settings
        updateDynamicRes(dt)
        g.chunks.viewRadius = s.viewDistance
        g.chunks.density = s.floraDensity
        val missing = g.chunks.update(g.sub.pos.x, g.sub.pos.z, if (g.worldReady) 2 else 8)
        g.hud.loadProgress = 1f - missing / 9f
        postActive = s.postFx && ensureTargets()
        ensureShadow()
        computeEnvironment()
        gatherLights()

        // camera
        val aspect = width.toFloat() / height
        val far = (s.viewDistance + 1) * CHUNK_SIZE + 20f
        Mat4.perspective(proj, s.fov / aspect.coerceAtLeast(1f) * 1.25f, aspect, 0.15f, far)
        Mat4.lookAt(view, g.camPos.x, g.camPos.y, g.camPos.z, g.camTarget.x, g.camTarget.y, g.camTarget.z, 0f, 1f, 0f)
        Mat4.mul(vp, proj, view)
        Mat4.invert(invVP, vp)
        frustum.update(vp)

        if (shadowMode > 0) renderLampShadow()

        // scene
        if (postActive) { sceneRT!!.bind() } else { glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, width, height) }
        glDepthMask(true)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST)
        glDisable(GL_CULL_FACE)
        glDisable(GL_BLEND)
        bindTextures()
        drawScene(postActive)

        if (postActive) postProcess()
        glBindVertexArray(0)
    }

    private fun drawScene(hdr: Boolean) {
        val g = game
        val s = g.settings
        // open water
        glDepthMask(false)
        sky.use(); setCommon(sky, hdr); sky.m4("u_invVP", invVP)
        quad.draw()
        glDepthMask(true)

        // sea floor
        terrain.use(); setCommon(terrain, hdr)
        for (c in g.chunks.chunks.values) {
            val m = c.terrain ?: continue
            val x0 = c.cx * CHUNK_SIZE; val z0 = c.cz * CHUNK_SIZE
            if (!frustum.box(x0, c.minY - 3f, z0, x0 + CHUNK_SIZE, c.maxY + 30f, z0 + CHUNK_SIZE)) continue
            m.draw()
        }

        // flora, instanced per chunk
        inst.use(); setCommon(inst, hdr)
        inst.f3("u_current", sin(time * 0.05f) * 0.3f, 0f, cos(time * 0.04f) * 0.25f)
        inst.f1("u_glowPulse", 1f)
        val pcx = kotlin.math.floor(g.camPos.x / CHUNK_SIZE).toInt(); val pcz = kotlin.math.floor(g.camPos.z / CHUNK_SIZE).toInt()
        for (c in g.chunks.chunks.values) {
            if (!c.nodesReady) continue
            val x0 = c.cx * CHUNK_SIZE; val z0 = c.cz * CHUNK_SIZE
            if (!frustum.box(x0, c.minY - 3f, z0, x0 + CHUNK_SIZE, c.maxY + 30f, z0 + CHUNK_SIZE)) continue
            val cd = max(abs(c.cx - pcx), abs(c.cz - pcz))
            for (t in FloraType.entries) {
                val ib = c.inst[t.ordinal] ?: continue
                if (cd > 1 && (t == FloraType.SEAGRASS || t == FloraType.CORAL_BRANCH || t == FloraType.TUBEWORMS || t == FloraType.ANEMONE)) continue
                if (cd > 2 && t == FloraType.KELP) continue
                floraMeshes[t.ordinal]!!.drawInstanced(ib)
            }
        }

        // structures
        lit.use(); setCommon(lit, hdr); resetLit()
        for ((geo, m) in structMeshes) {
            if (!frustum.box(m.minX, m.minY, m.minZ, m.maxX, m.maxY, m.maxZ)) continue
            Mat4.identity(model); lit.m4("u_model", model)
            // a dead outpost flickers; the station glows warmer as you decorate it
            lit.f1("u_glow", when (geo.poi.type) {
                com.hadal.game.world.PoiType.CINDER -> if (sin(time * 9f) * sin(time * 3.3f) > -0.3f) 1f else 0.2f
                com.hadal.game.world.PoiType.STATION -> 1f + g.decor.count { it } * 0.05f
                else -> 1f
            })
            m.draw()
        }
        lit.f1("u_glow", 1f)
        // echo stones
        for ((i, e) in SeaGen.echoStones.withIndex()) {
            if (g.story.echoes.contains(i)) continue
            val y = e.y
            if (!frustum.sphere(e.x, y + 1f, e.z, 3f)) continue
            Mat4.fromTRS(model, e.x, y - 0.2f, e.z, i * 1.3f, 0f, 0f, 1f, 1f, 1f)
            lit.m4("u_model", model)
            lit.f1("u_glow", 0.7f + 0.3f * sin(time * 2f + i))
            echoMesh.draw()
        }
        lit.f1("u_glow", 1f)

        drawCreatures(opaque = true)

        // the drone fleet
        for (d in g.fleet.drones) {
            if (d.state == com.hadal.game.game.DroneState.LOST) continue
            if (!frustum.sphere(d.pos.x, d.pos.y, d.pos.z, 2f)) continue
            if (d.pos.dist(g.camPos) > 140f) continue
            val bob = if (d.state == com.hadal.game.game.DroneState.WORKING) sin(time * 1.5f + d.index) * 0.2f else 0f
            Mat4.fromTRS(model, d.pos.x, d.pos.y + bob, d.pos.z, if (d.state == com.hadal.game.game.DroneState.IDLE) 0f else d.yaw, 0f, 0f, 1f, 1f, 1f)
            lit.m4("u_model", model)
            lit.f1("u_wet", 0.5f)
            droneMesh.draw()
        }
        lit.f1("u_wet", 0f)

        // the sub
        if (!(s.cockpitCamera && !g.docked)) drawSub()
        // flares
        for (f in g.flares) {
            Mat4.fromTRS(model, f.pos.x, f.pos.y, f.pos.z, f.life * 2f, 0.4f, 0f, 1f, 1f, 1f)
            lit.m4("u_model", model); flareMesh.draw()
        }

        // translucent: jellies
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        drawCreatures(opaque = false)

        // marine snow and glows (additive)
        glBlendFunc(GL_ONE, GL_ONE)
        val n = when (s.particles) { 0 -> 0; 1 -> 1800; else -> snowCount }
        if (n > 0) {
            snow.use()
            snow.m4("u_vp", vp); snow.f3("u_camPos", g.camPos.x, g.camPos.y, g.camPos.z); snow.f1("u_time", time)
            snow.f1("u_box", 28f); snow.f1("u_pxScale", sceneHeightPx() * 0.9f)
            snow.f3("u_lampPos", lampPos.x, lampPos.y, lampPos.z); snow.f3("u_lampDir", lampDir.x, lampDir.y, lampDir.z)
            snow.f3("u_lampColor", lampColor); snow.f2("u_lampCone", lampCosO, lampCosI); snow.f1("u_lampRange", lampRange)
            snow.f3("u_sunAmb", sunAmb); snow.f3("u_ext", ext)
            snow.i1("u_hdr", if (hdr) 1 else 0); snow.f1("u_exposure", exposure); snow.f1("u_outScale", outScale)
            glBindVertexArray(snowVao)
            glDrawArrays(GL_POINTS, 0, n)
        }
        drawGlows(hdr)
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun sceneHeightPx(): Float = (if (postActive) sceneH else height).toFloat()

    private fun resetLit() {
        lit.f3("u_tint", 1f, 1f, 1f); lit.f1("u_glow", 1f); lit.f1("u_alpha", 1f); lit.f1("u_wet", 0f)
        lit.f4("u_swim", 0f, 0f, 0f, 0f); lit.f2("u_flap", 0f, 0f)
    }

    private fun drawSub() {
        val g = game
        val sub = g.sub
        lit.f1("u_wet", 0.6f)
        Mat4.fromTRS(model, sub.pos.x, sub.pos.y, sub.pos.z, sub.bodyYaw, -sub.bodyPitch, sub.bodyRoll, 1f, 1f, 1f)
        lit.m4("u_model", model)
        lit.f1("u_glow", if (sub.lampOn) 1f else 0.15f)
        subMesh.draw()
        Mat4.mulTRS(model2, model, 0f, 0f, -2.6f, 0f, 0f, time * (2f + sub.speed * 3f), 1f, 1f, 1f)
        lit.m4("u_model", model2)
        lit.f1("u_glow", 1f)
        propMesh.draw()
        lit.f1("u_wet", 0f)
    }

    private fun drawCreatures(opaque: Boolean) {
        val g = game
        if (!opaque) { lit.use(); setCommon(lit, postActive); resetLit() }
        for (c in g.ecology.list) {
            val sp = c.species
            val translucent = sp == Species.MOON_JELLY
            if (translucent == opaque) continue
            val r = (if (sp == Species.PALE) 50f else if (sp == Species.SONGWHALE) 14f else sp.radius * 4f) * c.size
            if (!frustum.sphere(c.pos.x, c.pos.y, c.pos.z, r)) continue
            val mesh = creatureMeshes[sp] ?: continue
            lit.f4("u_swim", 0f, 0f, 0f, 0f); lit.f2("u_flap", 0f, 0f)
            lit.f1("u_glow", 1f); lit.f1("u_alpha", 1f); lit.f1("u_wet", 0.4f)
            var sy = 1f
            when (sp) {
                Species.GLASSFIN -> lit.f4("u_swim", 0.05f, 14f, c.anim * 14f, 0f)
                Species.MANTA -> lit.f2("u_flap", 0.5f, c.anim * 1.6f)
                Species.SONGWHALE -> lit.f4("u_swim", 0.35f, 0.3f, c.anim * 1.2f, 1f)
                Species.ANGLER -> { lit.f4("u_swim", 0.12f, 2f, c.anim * 4f, 0f); lit.f1("u_glow", c.glow) }
                Species.GULPER -> lit.f4("u_swim", 0.4f, 0.6f, c.anim * 3f, 0f)
                Species.LANTERN_SQUID -> lit.f1("u_glow", c.glow)
                Species.MOON_JELLY -> { sy = 1f + 0.15f * sin(c.anim * 2.5f); lit.f1("u_alpha", 0.55f) }
                Species.WISP -> lit.f1("u_glow", 0.8f + 0.2f * sin(time * 3f + c.id))
                else -> {}
            }
            Mat4.fromTRS(model, c.pos.x, c.pos.y, c.pos.z, c.yaw, -c.pitch, 0f, c.size, c.size * sy, c.size)
            lit.m4("u_model", model)
            mesh.draw()
            val trail = c.trail
            val seg = segmentMeshes[sp]
            if (trail != null && seg != null) {
                lit.f4("u_swim", 0f, 0f, 0f, 0f)
                val n = sp.segments
                val baseR = if (sp == Species.PALE) 1.15f * c.size else 0.17f * c.size
                for (i in 1 until n) {
                    val ax = trail[(i - 1) * 3]; val ay = trail[(i - 1) * 3 + 1]; val az = trail[(i - 1) * 3 + 2]
                    val bx = trail[i * 3]; val by = trail[i * 3 + 1]; val bz = trail[i * 3 + 2]
                    val dx = ax - bx; val dy = ay - by; val dz = az - bz
                    val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01f)
                    val yaw = atan2(dx, dz); val pitch = atan2(dy, sqrt(dx * dx + dz * dz))
                    val t = i.toFloat() / n
                    val rr = baseR * (if (sp == Species.PALE) (1f - t * 0.8f) * (0.85f + 0.15f * sin(t * 9f)) else (1f - t * 0.75f))
                    // undulate the body sideways a little
                    val wob = sin(c.anim * 3f - i * 0.6f) * 0.08f
                    Mat4.fromTRS(model, (ax + bx) * 0.5f, (ay + by) * 0.5f, (az + bz) * 0.5f, yaw + wob, -pitch, 0f, rr, rr, len * 1.08f)
                    lit.m4("u_model", model)
                    seg.draw()
                }
            }
        }
        resetLit()
    }

    private val right = Vec3()
    private val upV = Vec3()

    private fun drawGlows(hdr: Boolean) {
        val g = game
        // camera basis from the view matrix
        right.set(view[0], view[4], view[8]); upV.set(view[1], view[5], view[9])
        glow.use()
        glow.m4("u_vp", vp); glow.f3("u_right", right.x, right.y, right.z); glow.f3("u_up", upV.x, upV.y, upV.z)
        glow.i1("u_hdr", if (hdr) 1 else 0); glow.f1("u_outScale", outScale)
        val cam = g.camPos
        fun sprite(x: Float, y: Float, z: Float, size: Float, r: Float, gg: Float, b: Float, streak: Float = 0f) {
            val dx = x - cam.x; val dy = y - cam.y; val dz = z - cam.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > 120f || !frustum.sphere(x, y, z, size)) return
            // the water eats the glow with distance, red first
            val tr = kotlin.math.exp(-ext[0] * d); val tg = kotlin.math.exp(-ext[1] * d); val tb = kotlin.math.exp(-ext[2] * d)
            val k = exposure * 0.6f
            glow.f3("u_center", x, y, z); glow.f1("u_size", size * (1f + d * 0.01f))
            glow.f3("u_color", r * tr * k, gg * tg * k, b * tb * k); glow.f1("u_streak", streak)
            quad.draw()
        }
        val sub = g.sub
        if (lampColor[0] > 0.1f) {
            Mat4.fromTRS(model, sub.pos.x, sub.pos.y, sub.pos.z, sub.bodyYaw, -sub.bodyPitch, sub.bodyRoll, 1f, 1f, 1f)
            // lamp lenses flare when they face the camera
            val f = Vec3(); sub.forward(f)
            val vx = cam.x - sub.pos.x; val vy = cam.y - sub.pos.y; val vz = cam.z - sub.pos.z
            val vl = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(0.01f)
            val facing = clamp01((f.x * vx + f.y * vy + f.z * vz) / vl)
            for (side in listOf(-1f, 1f)) {
                Mat4.transformPoint(model, side * 1.05f, 0.55f, 1.6f, tmpV)
                val k = lampColor[0] / 7f
                sprite(tmpV.x, tmpV.y, tmpV.z, 0.6f + facing * 1.6f, 1.2f * k, 1.1f * k, 0.95f * k, facing * 0.6f)
            }
        }
        for (f in g.flares) sprite(f.pos.x, f.pos.y, f.pos.z, 1.6f, 4f, 1.3f, 0.5f, 0.3f)
        for (d in g.fleet.drones) {
            if (d.state == com.hadal.game.game.DroneState.LOST) continue
            val pulse = 0.7f + 0.3f * sin(time * 4f + d.index * 2f)
            sprite(d.pos.x, d.pos.y + 0.85f, d.pos.z, 0.3f, 1.8f * pulse, 0.5f * pulse, 0.4f * pulse)
        }
        for ((geo, _) in structMeshes) for (fx in geo.fixtures) {
            if (!fixtureOn(fx)) continue
            var k = 1f
            if (fx.blink > 0f) k = if (sin(time * 6.28f / fx.blink) > 0.2f) 1f else 0.05f
            sprite(fx.x, fx.y, fx.z, 1.2f, fx.r * 2f * k, fx.g * 2f * k, fx.b * 2f * k)
        }
        for (c in g.ecology.list) {
            when (c.species) {
                Species.ANGLER -> { headPoint(c, 0f, 1.6f * c.size, 1.25f * c.size, tmpV); sprite(tmpV.x, tmpV.y, tmpV.z, 0.9f, 1f * c.glow, 2.2f * c.glow, 2.6f * c.glow) }
                Species.PALE -> {
                    // pairs of cold eyes in the dark
                    for (i in 0 until 2) for (s in listOf(-1f, 1f)) {
                        headPoint(c, s * (0.9f - i * 0.12f) * c.size, (0.45f + i * 0.1f) * c.size, (2.6f - i * 0.45f) * c.size, tmpV)
                        sprite(tmpV.x, tmpV.y, tmpV.z, 0.45f, 1.6f, 2.2f, 2.8f)
                    }
                }
                Species.WISP -> sprite(c.pos.x, c.pos.y, c.pos.z, 1.6f, 0.8f, 1.6f, 2.6f)
                Species.GULPER -> { val tx = c.pos.x - sin(c.yaw) * 7f * c.size; val tz = c.pos.z - cos(c.yaw) * 7f * c.size; sprite(tx, c.pos.y, tz, 0.6f, 2f, 0.6f, 1f) }
                Species.LANTERN_SQUID -> if (c.glow > 0.2f) sprite(c.pos.x, c.pos.y, c.pos.z, 0.35f, 0.3f * c.glow, 0.9f * c.glow, 2f * c.glow)
                Species.MOON_JELLY -> sprite(c.pos.x, c.pos.y, c.pos.z, 1.8f, 0.2f, 0.45f, 0.8f)
                else -> {}
            }
        }
        // echo stones call out softly
        for ((i, e) in SeaGen.echoStones.withIndex()) if (!g.story.echoes.contains(i)) sprite(e.x, e.y + 1.6f, e.z, 1.4f, 0.6f, 1.4f, 2f)
    }

    // ------------------------------------------------------------------ lamp shadow

    private fun renderLampShadow() {
        val g = game
        val sm = lampShadow ?: return
        val fovDeg = acos(lampCosO.coerceIn(-1f, 1f)) * 2f / DEG2RAD + 8f
        Mat4.perspective(lampProj, fovDeg, 1f, 0.4f, lampRange * 1.3f)
        val ux = if (abs(lampDir.y) > 0.95f) 1f else 0f; val uy = if (abs(lampDir.y) > 0.95f) 0f else 1f
        Mat4.lookAt(lampView, lampPos.x, lampPos.y, lampPos.z, lampPos.x + lampDir.x, lampPos.y + lampDir.y, lampPos.z + lampDir.z, ux, uy, 0f)
        Mat4.mul(lampVP, lampProj, lampView)
        lampFrustum.update(lampVP)
        glBindFramebuffer(GL_FRAMEBUFFER, sm.fbo)
        glViewport(0, 0, sm.size, sm.size)
        glClear(GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST)
        glDisable(GL_CULL_FACE)
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(1.5f, 3f)
        depth.use(); depth.m4("u_vp", lampVP)
        Mat4.identity(model); depth.m4("u_model", model)
        for (c in g.chunks.chunks.values) {
            val m = c.terrain ?: continue
            val x0 = c.cx * CHUNK_SIZE; val z0 = c.cz * CHUNK_SIZE
            if (!lampFrustum.box(x0, c.minY - 3f, z0, x0 + CHUNK_SIZE, c.maxY + 30f, z0 + CHUNK_SIZE)) continue
            // skirts only hide cracks for the camera; in the shadow map they would cause acne along chunk edges
            m.drawIndices(c.mainIdx)
        }
        for ((_, m) in structMeshes) if (lampFrustum.box(m.minX, m.minY, m.minZ, m.maxX, m.maxY, m.maxZ)) m.draw()
        for (c in g.ecology.list) {
            if (c.species == Species.MOON_JELLY || c.species == Species.WISP) continue
            if (!lampFrustum.sphere(c.pos.x, c.pos.y, c.pos.z, c.species.radius * 3f * c.size)) continue
            Mat4.fromTRS(model, c.pos.x, c.pos.y, c.pos.z, c.yaw, -c.pitch, 0f, c.size, c.size, c.size)
            depth.m4("u_model", model)
            creatureMeshes[c.species]?.draw()
        }
        depthInst.use(); depthInst.m4("u_vp", lampVP); depthInst.f1("u_time", time)
        for (c in g.chunks.chunks.values) {
            if (!c.nodesReady) continue
            val x0 = c.cx * CHUNK_SIZE; val z0 = c.cz * CHUNK_SIZE
            if (!lampFrustum.box(x0, c.minY - 3f, z0, x0 + CHUNK_SIZE, c.maxY + 30f, z0 + CHUNK_SIZE)) continue
            for (t in FloraType.entries) {
                if (t == FloraType.SEAGRASS || t == FloraType.TUBEWORMS) continue
                val ib = c.inst[t.ordinal] ?: continue
                floraMeshes[t.ordinal]!!.drawInstanced(ib)
            }
        }
        glDisable(GL_POLYGON_OFFSET_FILL)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    // ------------------------------------------------------------------ post processing

    private fun postProcess() {
        val g = game
        val s = g.settings
        val scene = sceneRT!!
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        // volumetric light
        val vol = volRT
        if (vol != null) {
            vol.bind()
            volume.use()
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, scene.depthTex)
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, noiseTex)
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, causticTex)
            glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, (if (shadowMode > 0) lampShadow else dummyShadow)!!.texture)
            volume.i1("u_depth", 0); volume.i1("u_noise", 1); volume.i1("u_caustics", 2); volume.i1("u_lampShadow", 3)
            volume.m4("u_lampVP", lampVP); volume.i1("u_shadowMode", shadowMode)
            volume.m4("u_invVP", invVP)
            volume.f3("u_camPos", g.camPos.x, g.camPos.y, g.camPos.z)
            volume.f3("u_lampPos", lampPos.x, lampPos.y, lampPos.z); volume.f3("u_lampDir", lampDir.x, lampDir.y, lampDir.z)
            // the visible beam is narrower than the area the lamp lights, like a real floodlight in turbid water
            volume.f3("u_lampColor", lampColor); volume.f2("u_lampCone", min(0.97f, lampCosO + 0.1f), min(0.99f, lampCosI + 0.06f)); volume.f1("u_lampRange", lampRange)
            volume.f3("u_sunAmb", sunAmb); volume.f3("u_ext", ext)
            volume.f1("u_time", time)
            volume.i1("u_steps", if (s.volumetrics >= 2) 20 else 12)
            volume.f1("u_maxDist", 70f)
            volume.f1("u_density", 0.006f * exposure * outScale)
            volume.f4v("u_lights", volLights, 4); volume.f3v("u_lightColors", volLightColors, 4)
            volume.f2("u_noiseOffset", (time * 7.3f) % 1f, (time * 3.1f) % 1f)
            quad.draw()
        }
        // bloom
        var src = scene.texture
        var sw = scene.width; var sh = scene.height
        down.use(); down.i1("u_tex", 0)
        for (i in bloomRT.indices) {
            val t = bloomRT[i]!!
            t.bind()
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, src)
            down.f2("u_texel", 1f / sw, 1f / sh)
            down.f1("u_threshold", if (i == 0) 0.9f * outScale else 0f)
            quad.draw()
            src = t.texture; sw = t.width; sh = t.height
        }
        up.use(); up.i1("u_tex", 0)
        glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE)
        for (i in bloomRT.size - 1 downTo 1) {
            val from = bloomRT[i]!!; val to = bloomRT[i - 1]!!
            to.bind()
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, from.texture)
            up.f2("u_texel", 1f / from.width, 1f / from.height)
            quad.draw()
        }
        glDisable(GL_BLEND)
        // composite
        val ldr = ldrRT
        if (ldr != null) ldr.bind() else { glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, width, height) }
        composite.use()
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, scene.texture)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, bloomRT[0]!!.texture)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, vol?.texture ?: noiseTex)
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, scene.depthTex)
        glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, noiseTex)
        composite.i1("u_scene", 0); composite.i1("u_bloom", 1); composite.i1("u_volume", 2); composite.i1("u_depth", 3); composite.i1("u_noise", 4)
        composite.f1("u_inScale", 1f / outScale)
        composite.f1("u_bloomStrength", s.bloom * 0.6f)
        composite.f1("u_volumeOn", if (vol != null) 1f else 0f)
        composite.f1("u_vignette", if (s.cockpitCamera && !g.docked) 1.6f else 0.9f)
        composite.f2("u_noiseOffset", (time * 13.7f) % 1f, (time * 7.9f) % 1f)
        composite.f1("u_time", time)
        composite.f1("u_damage", g.damageFlash)
        composite.f1("u_fear", g.paleFear * 0.8f)
        composite.f1("u_pulse", g.pulseFlash)
        composite.f1("u_lowO2", clamp01(1f - g.sub.oxygen / (g.sub.maxOxygen * 0.2f)) * 0.5f)
        composite.f1("u_aberration", 0.012f + g.damageFlash * 0.03f + g.paleFear * 0.02f)
        composite.m4("u_invVP", invVP)
        composite.f3("u_camPos", g.camPos.x, g.camPos.y, g.camPos.z)
        composite.f3("u_pingOrigin", g.pingOrigin.x, g.pingOrigin.y, g.pingOrigin.z)
        composite.f1("u_pingRadius", g.pingRadius)
        composite.f1("u_pingFade", if (g.pingAge < 3f) clamp01(1f - g.pingAge / 3f) * clamp01(1f - g.pingRadius / (g.sub.sonarRange * 1.3f)) else 0f)
        composite.f1("u_dark", if (g.dead) clamp01(g.deathTimer / 2.5f) else 0f)
        quad.draw()
        glActiveTexture(GL_TEXTURE0)
        if (ldr != null) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, width, height)
            fxaa.use(); fxaa.i1("u_tex", 0)
            glBindTexture(GL_TEXTURE_2D, ldr.texture)
            fxaa.f2("u_texel", 1f / width, 1f / height)
            quad.draw()
        }
        glEnable(GL_DEPTH_TEST)
    }

    fun release() {
        releaseTargets()
        for (m in floraMeshes) m?.release()
        for ((_, m) in structMeshes) m.release()
        structMeshes.clear()
        for (m in creatureMeshes.values) m.release()
        for (m in segmentMeshes.values) m.release()
        lampShadow?.release(); dummyShadow?.release()
        albedoArr?.release(); normalArr?.release()
    }
}
