package scrapline.view

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector3
import scrapline.sim.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val CHUNK = 32
private const val CHUNKS = WORLD_SIZE / CHUNK
private const val FOG_NEAR = 60f
private const val FOG_FAR = 210f
private const val SHADOW_SIZE = 2048
private const val SHADOW_SPAN = 84f
private const val DYNAMIC_VERTS = 60000
private const val GLOW_VERTS = 12000
private const val MAX_PARTICLES = 96
private const val TRACERS = 16

private const val FOV_LANDSCAPE = 62f
private const val FOV_PORTRAIT = 88f

/**
 * Draws the world. Static things (terrain, rocks, trees, machines) are baked into
 * a few big meshes per map chunk, so the whole scene is a few dozen draw calls on
 * any phone. Small moving things are rebuilt into one dynamic mesh each frame.
 */
class WorldRenderer(private val sim: Sim) {
    val camera = PerspectiveCamera(FOV_LANDSCAPE, 1f, 1f)
    private val shaders = Shaders()
    private val terrain = arrayOfNulls<Mesh>(CHUNKS * CHUNKS)
    private val props = arrayOfNulls<Mesh>(CHUNKS * CHUNKS)
    private val propDirty = BooleanArray(CHUNKS * CHUNKS) { true }
    private val chunkY = FloatArray(CHUNKS * CHUNKS)
    private var entityMesh: Mesh? = null
    private var entityVersion = -1
    private val scratch = Tris(4096)
    private val dynamic = Tris(4096)
    private val dynamicMesh = Mesh(false, DYNAMIC_VERTS, 0, worldAttributes())
    private val glows = Tris(1024)
    private val glowMesh = Mesh(false, GLOW_VERTS, 0, worldAttributes())
    private val overlay = Tris(1024)
    private val overlayMesh = Mesh(false, 4000, 0, worldAttributes())
    private val skyMesh: Mesh

    private val shadowCam = OrthographicCamera(SHADOW_SPAN, SHADOW_SPAN)
    private var shadowFbo: FrameBuffer? = null
    private val lightDir = Vector3()
    private val tmp = Vector3()
    private val right = Vector3()

    // Scene colours for this frame.
    private val sunColor = FloatArray(3)
    private val skyAmb = FloatArray(3)
    private val groundAmb = FloatArray(3)
    private val fogColor = FloatArray(3)
    private val zenith = FloatArray(3)
    private val discColor = FloatArray(3)
    private var nightness = 0f

    // Small effects.
    private val px = FloatArray(MAX_PARTICLES)
    private val py = FloatArray(MAX_PARTICLES)
    private val pz = FloatArray(MAX_PARTICLES)
    private val pvx = FloatArray(MAX_PARTICLES)
    private val pvy = FloatArray(MAX_PARTICLES)
    private val pvz = FloatArray(MAX_PARTICLES)
    private val pLife = FloatArray(MAX_PARTICLES)
    private val pColor = FloatArray(MAX_PARTICLES)
    private var pNext = 0
    private val tracer = FloatArray(TRACERS * 6)
    private val tracerLife = FloatArray(TRACERS)
    private var tracerNext = 0
    private var swing = 0f
    private var bob = 0f

    /** Draw calls and triangles in the last frame, for the debug overlay. */
    var drawCalls = 0
        private set
    var triangles = 0
        private set

    init {
        camera.near = 0.1f
        camera.far = 700f
        buildTerrain()
        skyMesh = Mesh(true, 36, 0, VertexAttribute.Position()).apply {
            val t = Tris().box(2f, 2f, 2f, 0f, 0f, 0f, 0f)
            val v = FloatArray(36 * 3)
            for (i in 0 until 36) for (k in 0 until 3) v[i * 3 + k] = t.data[i * VSIZE + k]
            setVertices(v)
        }
        try {
            shadowFbo = FrameBuffer(Pixmap.Format.RGBA8888, SHADOW_SIZE, SHADOW_SIZE, true).also {
                it.colorBufferTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
            }
        } catch (e: Exception) {
            Gdx.app.log("Scrapline", "Shadows disabled: ${e.message}")
        }
    }

    fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.fieldOfView = if (width < height) FOV_PORTRAIT else FOV_LANDSCAPE
    }

    // ------------------------------------------------------------ static geometry

    private fun buildTerrain() {
        val w = sim.world
        // Smooth normals at lattice points, for the dunes.
        val nx = FloatArray(GRID_N * GRID_N)
        val ny = FloatArray(GRID_N * GRID_N)
        val nz = FloatArray(GRID_N * GRID_N)
        for (iz in 0 until GRID_N) for (ix in 0 until GRID_N) {
            val l = w.heights[iz * GRID_N + max(ix - 1, 0)]
            val r = w.heights[iz * GRID_N + min(ix + 1, GRID_N - 1)]
            val d = w.heights[max(iz - 1, 0) * GRID_N + ix]
            val u = w.heights[min(iz + 1, GRID_N - 1) * GRID_N + ix]
            val x = l - r
            val z = d - u
            val y = 2f * CELL
            val len = sqrt(x * x + y * y + z * z)
            val i = iz * GRID_N + ix
            nx[i] = x / len; ny[i] = y / len; nz[i] = z / len
        }
        val strata = intArrayOf(0xB5653E, 0xC98452, 0x9C5334, 0xD39A66, 0xA85E3A)
        val cells = CHUNK / CELL
        val t = Tris(cells * cells * 6)
        for (cz in 0 until CHUNKS) for (cx in 0 until CHUNKS) {
            t.clear()
            var ySum = 0f
            for (j in 0 until cells) for (i in 0 until cells) {
                val ix = cx * cells + i
                val iz = cz * cells + j
                // Same diagonal as World.heightAt: split from (ix+1, iz) to (ix, iz+1).
                terrainTri(t, ix, iz, ix, iz + 1, ix + 1, iz, nx, ny, nz, strata)
                terrainTri(t, ix + 1, iz + 1, ix + 1, iz, ix, iz + 1, nx, ny, nz, strata)
                ySum += w.heights[iz * GRID_N + ix]
            }
            terrain[cz * CHUNKS + cx] = t.toMesh()
            chunkY[cz * CHUNKS + cx] = ySum / (cells * cells)
        }
    }

    private fun terrainTri(
        t: Tris, ax: Int, az: Int, bx: Int, bz: Int, cx: Int, cz: Int,
        nx: FloatArray, ny: FloatArray, nz: FloatArray, strata: IntArray,
    ) {
        val w = sim.world
        val ia = az * GRID_N + ax
        val ib = bz * GRID_N + bx
        val ic = cz * GRID_N + cx
        val ha = w.heights[ia]; val hb = w.heights[ib]; val hc = w.heights[ic]
        val rock = (w.rock[ia] + w.rock[ib] + w.rock[ic]) / 3f
        val mx = (ax + bx + cx) * CELL / 3f
        val mz = (az + bz + cz) * CELL / 3f
        val avg = (ha + hb + hc) / 3f
        val jitter = hash2(ax * 2 + (if (ax == bx) 0 else 1), az, sim.seed + 31) - 0.5f
        if (rock > 0.2f) {
            // Mesa rock: flat facets, with strata colours taken per corner so bands run level.
            val k = 1f + jitter * 0.1f
            val start = t.size
            t.tri(ax * CELL.toFloat(), ha, az * CELL.toFloat(), bx * CELL.toFloat(), hb, bz * CELL.toFloat(), cx * CELL.toFloat(), hc, cz * CELL.toFloat(), 0f)
            t.data[start + 6] = strataColor(strata, ha, ax, az, k)
            t.data[start + VSIZE + 6] = strataColor(strata, hb, bx, bz, k)
            t.data[start + 2 * VSIZE + 6] = strataColor(strata, hc, cx, cz, k)
            return
        }
        // Sand: smooth normals; slightly darker in the hollows; stained over deposits.
        var hex = 0xD9A766
        var k = 1f + jitter * 0.035f - (1.5f - avg).coerceIn(0f, 3f) * 0.012f
        for (d in sim.nodes.deposits) {
            val dist = hypot(mx - d.x, mz - d.z)
            if (dist < d.radius + 0.8f) {
                hex = if (d.item == Item.IRON_ORE) 0xA85A3A else 0x4A4444
                k = if (dist < d.radius) 1f else 1.25f
            }
        }
        val color = shade(hex, k, MAT_SAND)
        t.vertex(ax * CELL.toFloat(), ha, az * CELL.toFloat(), nx[ia], ny[ia], nz[ia], color)
        t.vertex(bx * CELL.toFloat(), hb, bz * CELL.toFloat(), nx[ib], ny[ib], nz[ib], color)
        t.vertex(cx * CELL.toFloat(), hc, cz * CELL.toFloat(), nx[ic], ny[ic], nz[ic], color)
    }

    private fun strataColor(strata: IntArray, h: Float, ix: Int, iz: Int, k: Float): Float {
        val band = floor((h + valueNoise(ix / 5f, iz / 5f, sim.seed + 5) * 1.6f) / 3.2f).toInt()
        return shade(strata[Math.floorMod(band, strata.size)], k, MAT_PLAIN)
    }

    private fun shade(hex: Int, k: Float, material: Float): Float {
        val r = (((hex shr 16) and 255) * k).coerceIn(0f, 255f).toInt()
        val g = (((hex shr 8) and 255) * k).coerceIn(0f, 255f).toInt()
        val b = ((hex and 255) * k).coerceIn(0f, 255f).toInt()
        return rgb((r shl 16) or (g shl 8) or b, material)
    }

    /** Bakes the living resource nodes and the scenery of one chunk into a single mesh. */
    private fun rebuildProps(chunk: Int) {
        val t = scratch
        t.clear()
        val cx = chunk % CHUNKS
        val cz = chunk / CHUNKS
        val x0 = cx * CHUNK.toFloat()
        val z0 = cz * CHUNK.toFloat()
        val n = sim.nodes
        for (i in 0 until n.count) {
            if (n.hp[i] == 0 || n.x[i] < x0 || n.x[i] >= x0 + CHUNK || n.z[i] < z0 || n.z[i] >= z0 + CHUNK) continue
            val s = n.scale[i]
            t.place(Models.nodes[n.kind[i]]!!, n.x[i], n.y[i] - 0.05f, n.z[i], n.rot[i], s, s, s)
        }
        // Scenery: cacti, dry brush and pebbles on a jittered 4 m grid. No gameplay effect.
        val w = sim.world
        val half = WORLD_SIZE / 2f
        var gz = z0 + 2f
        while (gz < z0 + CHUNK) {
            var gx = x0 + 2f
            while (gx < x0 + CHUNK) {
                val ix = gx.toInt()
                val iz = gz.toInt()
                val roll = hash2(ix, iz, sim.seed + 400)
                val x = gx + (hash2(ix, iz, sim.seed + 401) - 0.5f) * 3f
                val z = gz + (hash2(ix, iz, sim.seed + 402) - 0.5f) * 3f
                gx += 4f
                if (roll > 0.2f) continue
                if (w.slopeAt(x, z) > 0.45f || hypot(x - half, z - half) < 7f) continue
                if (abs(x - half) > half - 36f || abs(z - half) > half - 36f) continue
                if (sim.deposits[z.toInt() * WORLD_SIZE + x.toInt()].toInt() != 0) continue
                val y = w.heightAt(x, z)
                val yaw = roll * 80f
                val s = 0.8f + hash2(ix, iz, sim.seed + 403) * 0.6f
                when {
                    roll < 0.035f -> t.place(Models.cactus, x, y - 0.05f, z, yaw, s, s, s)
                    roll < 0.13f -> t.place(Models.bush, x, y - 0.02f, z, yaw, s, s, s)
                    else -> t.place(Models.pebbles, x, y, z, yaw, s, s, s)
                }
            }
            gz += 4f
        }
        props[chunk]?.dispose()
        props[chunk] = t.toMesh()
        propDirty[chunk] = false
    }

    private fun rebuildEntities() {
        val es = sim.entities
        val t = scratch
        t.clear()
        for (id in 1 until es.high) {
            val shape = Models.entities[es.kind[id]] ?: continue
            t.place(shape, es.cx[id] + 0.5f, es.y[id] - 0.04f, es.cz[id] + 0.5f, es.rot[id] * MathUtils.HALF_PI)
        }
        entityMesh?.dispose()
        entityMesh = t.toMesh()
        entityVersion = es.version
    }

    // ------------------------------------------------------------ light

    private fun mix(out: FloatArray, a: Int, b: Int, t: Float, scale: Float = 1f) {
        for (k in 0 until 3) {
            val ca = ((a shr (16 - 8 * k)) and 255) / 255f
            val cb = ((b shr (16 - 8 * k)) and 255) / 255f
            out[k] = (ca + (cb - ca) * t) * scale
        }
    }

    private fun updateLight() {
        val day = sim.daylight()
        val t = sim.tick % CYCLE_TICKS
        val arc = if (t < DAY_TICKS) t.toFloat() / DAY_TICKS else (t - DAY_TICKS).toFloat() / NIGHT_TICKS
        val a = (0.06f + 0.88f * arc) * MathUtils.PI
        lightDir.set(cos(a), sin(a) * 0.95f + 0.05f, 0.38f).nor()
        val high = smooth(sin(a).coerceIn(0f, 1f))
        nightness = 1f - day
        if (t < DAY_TICKS) {
            // Low sun is orange, high sun is nearly white.
            mix(sunColor, 0xFF8A3D, 0xFFF1DC, high, 1.0f * day)
            mix(discColor, 0xFFB060, 0xFFF4E0, high)
        } else {
            mix(sunColor, 0x6F86C8, 0x8FA6E0, high, 0.42f)
            mix(discColor, 0xC8D6FF, 0xE6EEFF, high, 0.9f)
        }
        // Twilight tints the horizon; it peaks half way through dusk and dawn.
        val twilight = (4f * day * (1f - day)).coerceIn(0f, 1f)
        mix(skyAmb, 0x1A2238, 0x93A9C8, day, 0.6f)
        mix(groundAmb, 0x0C0E16, 0x8A6A48, day, 0.5f)
        mix(zenith, 0x05070F, 0x3E7EC8, day)
        mix(fogColor, 0x0B1020, 0xE6CDA6, day)
        val dusk = 0xF08A50
        for (k in 0 until 3) {
            val c = ((dusk shr (16 - 8 * k)) and 255) / 255f
            fogColor[k] += (c - fogColor[k]) * twilight * 0.75f
        }
    }

    // ------------------------------------------------------------ frame

    /** `alpha` is progress through the current sim tick (0..1), `time` is seconds since start. */
    fun render(dt: Float, time: Float, alpha: Float) {
        val p = sim.player
        handleEvents()
        if (entityVersion != sim.entities.version) rebuildEntities()
        updateLight()

        camera.position.set(p.x, p.y + EYE_HEIGHT, p.z)
        val cp = cos(p.pitch)
        camera.direction.set(-sin(p.yaw) * cp, sin(p.pitch), -cos(p.yaw) * cp)
        camera.up.set(0f, 1f, 0f)
        camera.update()

        for (c in 0 until CHUNKS * CHUNKS) if (propDirty[c] && chunkNear(c, FOG_FAR + 40f)) rebuildProps(c)

        buildDynamic(dt, time, alpha)
        drawCalls = 0
        triangles = 0

        val gl = Gdx.gl
        val fbo = shadowFbo
        val shadows = fbo != null && lightDir.y > 0.12f
        if (fbo != null && shadows) renderShadows(fbo)

        gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        gl.glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f)
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        // Sky first, behind everything.
        gl.glDisable(GL20.GL_DEPTH_TEST)
        gl.glDisable(GL20.GL_CULL_FACE)
        shaders.sky.bind()
        shaders.sky.setUniformMatrix("u_projView", camera.combined)
        shaders.sky.setUniformf("u_camPos", camera.position)
        shaders.sky.setUniformf("u_zenith", zenith[0], zenith[1], zenith[2])
        shaders.sky.setUniformf("u_horizon", fogColor[0], fogColor[1], fogColor[2])
        shaders.sky.setUniformf("u_sunDir", lightDir)
        shaders.sky.setUniformf("u_discColor", discColor[0], discColor[1], discColor[2])
        shaders.sky.setUniformf("u_night", nightness)
        skyMesh.render(shaders.sky, GL20.GL_TRIANGLES)
        drawCalls++

        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LEQUAL)
        gl.glEnable(GL20.GL_CULL_FACE)
        gl.glCullFace(GL20.GL_BACK)
        val s = shaders.world
        s.bind()
        s.setUniformMatrix("u_projView", camera.combined)
        s.setUniformMatrix("u_shadowMat", shadowCam.combined)
        s.setUniformf("u_sunDir", lightDir)
        s.setUniformf("u_sunColor", sunColor[0], sunColor[1], sunColor[2])
        s.setUniformf("u_skyAmb", skyAmb[0], skyAmb[1], skyAmb[2])
        s.setUniformf("u_groundAmb", groundAmb[0], groundAmb[1], groundAmb[2])
        s.setUniformf("u_fogColor", fogColor[0], fogColor[1], fogColor[2])
        s.setUniformf("u_camPos", camera.position)
        s.setUniformf("u_fog", FOG_NEAR, FOG_FAR)
        s.setUniformf("u_shadowOn", if (shadows) 1f else 0f)
        s.setUniformf("u_alpha", 1f)
        if (fbo != null) {
            fbo.colorBufferTexture.bind(0)
            s.setUniformi("u_shadowMap", 0)
        }

        for (c in 0 until CHUNKS * CHUNKS) {
            if (!chunkVisible(c)) continue
            draw(terrain[c])
            draw(props[c])
        }
        draw(entityMesh)
        drawDynamic(dynamicMesh, dynamic)

        // Pools of light: additive, no depth writes.
        if (glows.size > 0) {
            gl.glEnable(GL20.GL_BLEND)
            gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE)
            gl.glDepthMask(false)
            s.setUniformf("u_fog", 1e6f, 2e6f)
            s.setUniformf("u_alpha", 1f)
            drawDynamic(glowMesh, glows)
            gl.glDepthMask(true)
        }

        // Build ghost and held tool: drawn last, the tool on a cleared depth buffer so it never clips.
        if (overlay.size > 0) {
            gl.glEnable(GL20.GL_BLEND)
            gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
            s.setUniformf("u_fog", 1e6f, 2e6f)
            s.setUniformf("u_alpha", 0.55f)
            drawDynamic(overlayMesh, overlay)
        }
        gl.glDisable(GL20.GL_BLEND)
        if (held.size > 0) {
            gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)
            s.setUniformf("u_fog", 1e6f, 2e6f)
            s.setUniformf("u_alpha", 1f)
            s.setUniformf("u_shadowOn", 0f)
            drawDynamic(heldMesh, held)
        }
        gl.glDisable(GL20.GL_CULL_FACE)
        gl.glDisable(GL20.GL_DEPTH_TEST)
    }

    private val held = Tris(512)
    private val heldMesh = Mesh(false, 2000, 0, worldAttributes())

    private fun draw(mesh: Mesh?) {
        if (mesh == null) return
        mesh.render(shaders.world, GL20.GL_TRIANGLES)
        drawCalls++
        triangles += mesh.numVertices / 3
    }

    private fun drawDynamic(mesh: Mesh, t: Tris) {
        if (t.size == 0) return
        val floats = min(t.size, mesh.maxVertices * VSIZE)
        mesh.setVertices(t.data, 0, floats)
        mesh.render(shaders.world, GL20.GL_TRIANGLES, 0, floats / VSIZE)
        drawCalls++
        triangles += floats / VSIZE / 3
    }

    private fun chunkNear(c: Int, range: Float): Boolean {
        val x = (c % CHUNKS + 0.5f) * CHUNK
        val z = (c / CHUNKS + 0.5f) * CHUNK
        return hypot(x - sim.player.x, z - sim.player.z) < range + CHUNK * 0.75f
    }

    private fun chunkVisible(c: Int): Boolean {
        if (!chunkNear(c, FOG_FAR)) return false
        val x = (c % CHUNKS + 0.5f) * CHUNK
        val z = (c / CHUNKS + 0.5f) * CHUNK
        return camera.frustum.sphereInFrustum(x, chunkY[c] + 6f, z, 36f)
    }

    private fun renderShadows(fbo: FrameBuffer) {
        val p = sim.player
        // Snap the shadow volume to whole texels so shadows do not swim as the player moves.
        val texel = SHADOW_SPAN / SHADOW_SIZE
        shadowCam.direction.set(lightDir).scl(-1f)
        shadowCam.up.set(0f, 1f, 0f)
        right.set(shadowCam.direction).crs(shadowCam.up).nor()
        tmp.set(right).crs(shadowCam.direction).nor()
        val cx = floor(right.dot(p.x, p.y, p.z) / texel) * texel
        val cy = floor(tmp.dot(p.x, p.y, p.z) / texel) * texel
        val along = shadowCam.direction.dot(p.x, p.y, p.z)
        shadowCam.position.set(right).scl(cx).mulAdd(tmp, cy).mulAdd(shadowCam.direction, along - 110f)
        shadowCam.near = 1f
        shadowCam.far = 240f
        shadowCam.update()

        val gl = Gdx.gl
        fbo.begin()
        gl.glViewport(0, 0, SHADOW_SIZE, SHADOW_SIZE)
        gl.glClearColor(1f, 1f, 1f, 1f)
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDisable(GL20.GL_CULL_FACE)
        val d = shaders.depth
        d.bind()
        d.setUniformMatrix("u_projView", shadowCam.combined)
        for (c in 0 until CHUNKS * CHUNKS) {
            if (!chunkNear(c, SHADOW_SPAN * 0.6f)) continue
            terrain[c]?.render(d, GL20.GL_TRIANGLES)
            props[c]?.render(d, GL20.GL_TRIANGLES)
            drawCalls += 2
        }
        entityMesh?.render(d, GL20.GL_TRIANGLES)
        if (dynamic.size > 0) {
            val floats = min(dynamic.size, dynamicMesh.maxVertices * VSIZE)
            dynamicMesh.setVertices(dynamic.data, 0, floats)
            dynamicMesh.render(d, GL20.GL_TRIANGLES, 0, floats / VSIZE)
        }
        fbo.end()
    }

    // ------------------------------------------------------------ moving things

    private fun handleEvents() {
        for (i in 0 until sim.eventCount) {
            val code = sim.events[i * 3]
            val a = sim.events[i * 3 + 1]
            val b = sim.events[i * 3 + 2]
            when (code) {
                Ev.SWING -> {
                    val n = sim.nodes
                    burst(n.x[a], n.y[a] + 0.9f, n.z[a], 5, Models.cargoColor(NODE_DEFS[n.kind[a]].item))
                }
                Ev.NODE_GONE -> {
                    val n = sim.nodes
                    burst(n.x[a], n.y[a] + 0.6f, n.z[a], 12, Models.cargoColor(NODE_DEFS[n.kind[a]].item))
                    propDirty[(n.z[a].toInt() / CHUNK) * CHUNKS + n.x[a].toInt() / CHUNK] = true
                }
                Ev.PLACED -> burst(sim.entities.cx[a] + 0.5f, sim.entities.y[a] + 0.2f, sim.entities.cz[a] + 0.5f, 8, rgb(0xE2B274))
                Ev.STRAY_HIT -> burst(sim.strays.x[a], sim.strays.y[a], sim.strays.z[a], 5, rgb(0xFFB84A, MAT_GLOW))
                Ev.STRAY_DOWN -> burst(sim.strays.x[a], sim.strays.y[a], sim.strays.z[a], 16, rgb(0xFF7A45, MAT_GLOW))
                Ev.SHOT -> {
                    val es = sim.entities
                    val o = tracerNext * 6
                    tracer[o] = es.cx[a] + 0.5f; tracer[o + 1] = es.y[a] + 0.8f; tracer[o + 2] = es.cz[a] + 0.5f
                    tracer[o + 3] = sim.strays.x[b]; tracer[o + 4] = sim.strays.y[b]; tracer[o + 5] = sim.strays.z[b]
                    tracerLife[tracerNext] = 0.09f
                    tracerNext = (tracerNext + 1) % TRACERS
                }
            }
        }
    }

    /** Marks every prop chunk for rebuilding, e.g. after loading a save with depleted nodes. */
    fun invalidateProps() = propDirty.fill(true)

    private fun burst(x: Float, y: Float, z: Float, count: Int, color: Float) {
        for (k in 0 until count) {
            val i = pNext
            pNext = (pNext + 1) % MAX_PARTICLES
            px[i] = x; py[i] = y; pz[i] = z
            pvx[i] = MathUtils.random(-1.6f, 1.6f)
            pvy[i] = MathUtils.random(1.2f, 3.4f)
            pvz[i] = MathUtils.random(-1.6f, 1.6f)
            pLife[i] = MathUtils.random(0.35f, 0.7f)
            pColor[i] = color
        }
    }

    /** A thin glowing beam between two points, as two crossed ribbons. */
    private fun beam(t: Tris, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, width: Float, color: Float) {
        val w = width / 2
        t.quad(ax, ay - w, az, bx, by - w, bz, bx, by + w, bz, ax, ay + w, az, color)
        t.quad(ax, ay + w, az, bx, by + w, bz, bx, by - w, bz, ax, ay - w, az, color)
        val dx = bx - ax
        val dz = bz - az
        val len = max(1e-4f, hypot(dx, dz))
        val ox = -dz / len * w
        val oz = dx / len * w
        t.quad(ax - ox, ay, az - oz, bx - ox, by, bz - oz, bx + ox, by, bz + oz, ax + ox, ay, az + oz, color)
        t.quad(ax + ox, ay, az + oz, bx + ox, by, bz + oz, bx - ox, by, bz - oz, ax - ox, ay, az - oz, color)
    }

    private fun buildDynamic(dt: Float, time: Float, alpha: Float) {
        val d = dynamic
        val g = glows
        d.clear()
        g.clear()
        overlay.clear()
        held.clear()
        val es = sim.entities
        val p = sim.player
        val glowScale = 0.25f + 0.75f * nightness

        // Belt cargo near the player, eased between slots so it glides instead of hopping.
        val phase = min(1f, ((sim.tick % BELT_PERIOD) + alpha) / BELT_PERIOD)
        for (id in 1 until es.high) {
            val kind = es.kind[id]
            if (kind == 0) continue
            val x = es.cx[id] + 0.5f
            val y = es.y[id]
            val z = es.cz[id] + 0.5f
            if (abs(x - p.x) > 60f || abs(z - p.z) > 60f) continue
            val on = es.active[id] != 0
            when (kind) {
                EntityKind.BELT -> for (s in 0 until BELT_SLOTS) {
                    val item = es.belt[id * BELT_SLOTS + s]
                    if (item == 0) continue
                    var t = (s + 0.5f) / BELT_SLOTS - 0.5f
                    if (es.beltMoved[id * BELT_SLOTS + s] != 0) t -= (1f - phase) / BELT_SLOTS
                    d.place(Models.cargo, x + DIR_X[es.rot[id]] * t, y + 0.34f, z + DIR_Z[es.rot[id]] * t, override = Models.cargoColor(item))
                }
                EntityKind.CAMPFIRE -> {
                    flame(d, x, y, z, time, id)
                    g.place(Models.glow, x, y + 0.07f, z, 0f, 4.5f * glowScale, 1f, 4.5f * glowScale)
                }
                EntityKind.SMELTER -> if (on) {
                    flame(d, x, y + 1.45f, z, time, id)
                    g.place(Models.glow, x, y + 0.07f, z, 0f, 3.6f * glowScale, 1f, 3.6f * glowScale)
                }
                EntityKind.LAMP -> if (on) {
                    d.place(Models.light, x, y + 2.02f, z, 0f, 2f, 2f, 2f, rgb(0xFFD27A, MAT_GLOW))
                    g.place(Models.glow, x, y + 0.07f, z, 0f, 7f * glowScale, 1f, 7f * glowScale)
                }
                EntityKind.SWITCH -> if (on) d.place(Models.light, x, y + 1.25f, z, override = rgb(0x6FE6DA, MAT_GLOW))
                EntityKind.SENSOR -> if (on) d.place(Models.light, x, y + 1.62f, z, override = rgb(0xFF6B57, MAT_GLOW))
                EntityKind.TURRET -> if (on) d.place(Models.light, x, y + 1.12f, z, override = rgb(0xFF6B57, MAT_GLOW))
            }
        }

        val st = sim.strays
        if (st.count > 0) for (i in 0 until MAX_STRAYS) {
            if (!st.alive[i]) continue
            val yaw = MathUtils.atan2(st.x[i] - p.x, st.z[i] - p.z)
            d.place(Models.stray, st.x[i], st.y[i] + 0.08f * sin(time * 4f + i), st.z[i], yaw, 1.15f, 1.15f, 1.15f)
        }

        for (i in 0 until MAX_PARTICLES) {
            if (pLife[i] <= 0f) continue
            pLife[i] -= dt
            pvy[i] -= 9f * dt
            px[i] += pvx[i] * dt; py[i] += pvy[i] * dt; pz[i] += pvz[i] * dt
            val s = (pLife[i] * 1.6f).coerceIn(0.1f, 0.5f)
            d.place(Models.cargo, px[i], py[i], pz[i], i.toFloat(), s, s, s, pColor[i])
        }

        for (i in 0 until TRACERS) {
            if (tracerLife[i] <= 0f) continue
            tracerLife[i] -= dt
            val o = i * 6
            beam(d, tracer[o], tracer[o + 1], tracer[o + 2], tracer[o + 3], tracer[o + 4], tracer[o + 5], 0.05f, rgb(0xFFE08A, MAT_GLOW))
        }

        // While the Linker is in hand, direct device-to-device wires are drawn in the world.
        val heldDef = ITEMS[sim.selectedItem()]
        if (heldDef.kind == ItemKind.LINKER) {
            val gr = sim.graph
            for (i in 0 until gr.wireCount) {
                val a = wireSrcNode(gr.wires[i])
                val b = wireDstNode(gr.wires[i])
                if (gr.type[a] != NodeType.DEVICE || gr.type[b] != NodeType.DEVICE) continue
                val ea = gr.entity[a]
                val eb = gr.entity[b]
                val live = gr.outCur[a * PORTS + wireSrcPort(gr.wires[i])] > 0
                beam(
                    d, es.cx[ea] + 0.5f, es.y[ea] + ENTITY_DEFS[es.kind[ea]].height + 0.15f, es.cz[ea] + 0.5f,
                    es.cx[eb] + 0.5f, es.y[eb] + ENTITY_DEFS[es.kind[eb]].height + 0.15f, es.cz[eb] + 0.5f,
                    0.04f, rgb(if (live) 0xFFC266 else 0x6FE6DA, MAT_GLOW),
                )
            }
        }

        // Build ghost.
        val t = sim.target
        if (heldDef.kind == ItemKind.KIT && t.type == TargetType.GROUND && !sim.dead) {
            val shape = Models.entities[heldDef.places]
            if (shape != null) {
                overlay.place(
                    shape, t.cx + 0.5f, sim.world.heightAt(t.cx + 0.5f, t.cz + 0.5f) - 0.02f, t.cz + 0.5f,
                    sim.buildRot * MathUtils.HALF_PI, override = rgb(if (t.buildable) 0x5FD0C5 else 0xEF6F5E, MAT_GLOW),
                )
            }
        }

        buildHeld(dt, time, heldDef)
    }

    private fun flame(d: Tris, x: Float, y: Float, z: Float, time: Float, i: Int) {
        val f = 0.85f + 0.2f * sin(time * 11f + i * 2.1f) + 0.1f * sin(time * 23f + i)
        d.place(Models.flame, x, y, z, time * 1.7f + i, 1f, f, 1f)
    }

    /** The tool or kit in the player's hand, bottom right, bobbing with their step and swinging while they work. */
    private fun buildHeld(dt: Float, time: Float, def: ItemDef) {
        if (sim.dead) return
        val p = sim.player
        val moving = hypot(p.vx, p.vz)
        bob += dt * moving * 1.7f
        val working = sim.work.need > 0 && sim.actHeld
        swing = if (working) swing + dt * 13f else swing * 0.8f
        val shape = when {
            def.id == Item.AXE -> Models.heldAxe
            def.id == Item.PICK -> Models.heldPick
            def.kind == ItemKind.LINKER -> Models.heldLinker
            def.kind == ItemKind.KIT || def.id != 0 -> Models.heldBox
            else -> null
        }
        val fwd = camera.direction
        right.set(fwd).crs(camera.up).nor()
        tmp.set(right).crs(fwd).nor() // camera up
        val sway = sin(bob) * 0.012f
        val lift = abs(cos(bob)) * 0.012f
        val chop = if (working) sin(swing) * 0.5f + 0.5f else 0f
        val ox = camera.position.x + fwd.x * (0.55f + chop * 0.12f) + right.x * (0.36f + sway) + tmp.x * (-0.27f + lift - chop * 0.1f)
        val oy = camera.position.y + fwd.y * (0.55f + chop * 0.12f) + right.y * (0.36f + sway) + tmp.y * (-0.27f + lift - chop * 0.1f)
        val oz = camera.position.z + fwd.z * (0.55f + chop * 0.12f) + right.z * (0.36f + sway) + tmp.z * (-0.27f + lift - chop * 0.1f)
        // Tilt the tool forward as it chops: rotate the up and back axes about the right axis.
        val ang = -0.35f - chop * 0.9f
        val c = cos(ang)
        val s = sin(ang)
        val ux = tmp.x * c - fwd.x * s; val uy = tmp.y * c - fwd.y * s; val uz = tmp.z * c - fwd.z * s
        val bx = -fwd.x * c - tmp.x * s; val by = -fwd.y * c - tmp.y * s; val bz = -fwd.z * c - tmp.z * s
        val k = 0.62f
        held.placeBasis(Models.heldHand, ox, oy, oz, right.x * k, right.y * k, right.z * k, ux * k, uy * k, uz * k, bx * k, by * k, bz * k)
        if (shape != null) held.placeBasis(shape, ox, oy, oz, right.x * k, right.y * k, right.z * k, ux * k, uy * k, uz * k, bx * k, by * k, bz * k)
    }

    fun dispose() {
        shaders.dispose()
        terrain.forEach { it?.dispose() }
        props.forEach { it?.dispose() }
        entityMesh?.dispose()
        dynamicMesh.dispose()
        glowMesh.dispose()
        overlayMesh.dispose()
        heldMesh.dispose()
        skyMesh.dispose()
        shadowFbo?.dispose()
    }
}
