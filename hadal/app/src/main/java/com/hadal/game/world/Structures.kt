package com.hadal.game.world

import com.hadal.game.engine.Mat
import com.hadal.game.engine.MeshBuilder
import com.hadal.game.engine.MeshData
import com.hadal.game.engine.PI_F
import com.hadal.game.engine.Rng
import com.hadal.game.engine.TWO_PI
import kotlin.math.cos
import kotlin.math.sin

/** Axis aligned box or vertical cylinder used for sub collisions. */
class Collider(val minX: Float, val minY: Float, val minZ: Float, val maxX: Float, val maxY: Float, val maxZ: Float, val cylinder: Boolean = false) {
    val cx get() = (minX + maxX) * 0.5f
    val cz get() = (minZ + maxZ) * 0.5f
    val radius get() = (maxX - minX) * 0.5f
}

/** A light fixture on a structure. [group] lets the game switch sets of lights (e.g. relay repaired). */
class Fixture(val x: Float, val y: Float, val z: Float, val r: Float, val g: Float, val b: Float, val range: Float, val group: Int = 0, val blink: Float = 0f)

enum class InteractKind { DOCK, RELAY, LOG, BEACON, CHOIR, ECHO }

class Interact(val id: Int, val kind: InteractKind, val x: Float, val y: Float, val z: Float, val radius: Float, val poi: Poi, val index: Int = 0)

class StructureGeo(val poi: Poi, val mesh: MeshData, val colliders: List<Collider>, val fixtures: List<Fixture>)

/** Builds every man-made (and not so man-made) structure in the world. Runs on a worker thread. */
object Structures {
    val interacts = ArrayList<Interact>()
    lateinit var geos: List<StructureGeo>
        private set
    val colliders = ArrayList<Collider>()

    /** Docking point under Lantern Station. */
    var dockX = 0f; var dockY = 0f; var dockZ = 0f
        private set

    @Volatile var built = false
        private set

    @Synchronized
    fun buildAll() {
        if (built) return
        val list = ArrayList<StructureGeo>()
        var iid = 0
        for (p in SeaGen.pois) {
            val b = MeshBuilder(4096)
            val cols = ArrayList<Collider>()
            val fx = ArrayList<Fixture>()
            val y = p.y
            when (p.type) {
                PoiType.STATION -> {
                    station(b, cols, fx, p.x, y, p.z)
                    dockX = p.x; dockY = y + 9f; dockZ = p.z + 15f
                    interacts.add(Interact(iid++, InteractKind.DOCK, dockX, dockY - 3.5f, dockZ, 6f, p))
                }
                PoiType.RELAY -> {
                    relay(b, cols, fx, p.x, y, p.z)
                    interacts.add(Interact(iid++, InteractKind.RELAY, p.x + 2.2f, y + 2f, p.z, 5f, p))
                }
                PoiType.MERIDIAN -> {
                    ship(b, cols, fx, p.x, y, p.z, 0.35f, 1f, Rng(77))
                    interacts.add(Interact(iid++, InteractKind.LOG, p.x - 18f, y + 6f, p.z + 3f, 5f, p, 0))
                    interacts.add(Interact(iid++, InteractKind.LOG, p.x + 4f, y + 14f, p.z - 1f, 5f, p, 1))
                    interacts.add(Interact(iid++, InteractKind.LOG, p.x + 34f, y + 4f, p.z + 8f, 5f, p, 2))
                }
                PoiType.WRECK -> {
                    ship(b, cols, fx, p.x, y, p.z, if (p.x > 0) -0.6f else 1.1f, 0.55f, Rng(p.id.toLong() * 13))
                    interacts.add(Interact(iid++, InteractKind.LOG, p.x, y + 5f, p.z + 4f, 5f, p, 0))
                }
                PoiType.CINDER -> {
                    outpost(b, cols, fx, p.x, y, p.z)
                    interacts.add(Interact(iid++, InteractKind.LOG, p.x + 7f, y + 4f, p.z + 6f, 5f, p, 0))
                    interacts.add(Interact(iid++, InteractKind.LOG, p.x - 9f, y + 3f, p.z - 4f, 5f, p, 1))
                }
                PoiType.BEACON -> {
                    beacon(b, cols, fx, p.x, y, p.z)
                    interacts.add(Interact(iid++, InteractKind.BEACON, p.x, y + 3f, p.z, 5f, p, p.name.last().digitToInt() - 1))
                }
                PoiType.CHOIR -> {
                    choir(b, cols, fx, p.x, y, p.z)
                    interacts.add(Interact(iid++, InteractKind.CHOIR, p.x, y + 4f, p.z, 9f, p))
                }
                PoiType.WHALEFALL, PoiType.ECHO -> {}
            }
            if (b.vertexCount > 0) list.add(StructureGeo(p, b.build(), cols, fx))
            colliders.addAll(cols)
        }
        // echo stones share one small mesh, drawn by the renderer; only interactions here
        for ((i, e) in SeaGen.echoStones.withIndex()) interacts.add(Interact(iid++, InteractKind.ECHO, e.x, e.y + 1.2f, e.z, 4f, e, i))
        geos = list
        built = true
    }

    fun echoStoneMesh(): MeshData {
        val b = MeshBuilder(256)
        b.color(0.25f, 0.27f, 0.3f).mat(Mat.BASALT)
        b.cylinder(0f, 0f, 0f, 0.5f, 0.15f, 2.4f, 5, flat = true)
        b.color(0.5f, 0.9f, 1f, 3f)
        for (i in 0 until 5) {
            val a = i * TWO_PI / 5 + 0.3f
            b.push().trs(cos(a) * 0.33f, 0.9f + i * 0.22f, sin(a) * 0.33f, -a + PI_F / 2f).box(0f, 0f, 0f, 0.3f, 0.06f, 0.02f).pop()
        }
        return b.build()
    }

    // ------------------------------------------------------------------ builders

    private fun station(b: MeshBuilder, cols: ArrayList<Collider>, fx: ArrayList<Fixture>, x: Float, y: Float, z: Float) {
        b.push().trs(x, y, z)
        // legs
        b.color(0.5f, 0.5f, 0.5f).mat(Mat.RUST)
        for (i in 0 until 6) {
            val a = i * TWO_PI / 6
            b.cylinder(cos(a) * 6.5f, -1f, sin(a) * 6.5f, 0.45f, 0.35f, 6f, 8)
            b.box(cos(a) * 6.5f, -0.6f, sin(a) * 6.5f, 1.6f, 0.4f, 1.6f)
        }
        // main hub
        b.color(0.92f, 0.9f, 0.85f).mat(Mat.PAINT)
        b.cylinder(0f, 4.5f, 0f, 7f, 7f, 7.5f, 28, caps = false)
        b.color(0.85f, 0.62f, 0.12f).mat(Mat.PAINT)
        b.cylinder(0f, 4f, 0f, 7.3f, 7.3f, 0.8f, 28)
        b.cylinder(0f, 11.8f, 0f, 7.3f, 6.6f, 0.6f, 28, caps = false)
        // underside
        b.color(0.5f, 0.5f, 0.5f).mat(Mat.METAL)
        b.disc(0f, 4.5f, 0f, 7f, 28, false)
        // glass dome
        b.color(0.35f, 0.55f, 0.6f, 0.25f)
        b.sphere(0f, 12f, 0f, 6.4f, 3.6f, 6.4f, 8, 24)
        // window band
        for (i in 0 until 14) {
            val a = i * TWO_PI / 14
            b.color(1f, 0.72f, 0.38f, 1.6f)
            b.push().trs(cos(a) * 7.02f, 8.6f, sin(a) * 7.02f, -a + PI_F / 2f).box(0f, 0f, 0f, 1.6f, 1.1f, 0.12f).pop()
        }
        // habitat arms
        for (side in listOf(-1f, 1f)) {
            b.color(0.92f, 0.9f, 0.85f).mat(Mat.PAINT)
            b.push().trs(side * 13f, 8f, 0f, 0f, 0f, PI_F / 2f).cylinder(0f, -6.5f, 0f, 3.2f, 3.2f, 13f, 20).pop()
            b.color(0.85f, 0.62f, 0.12f).mat(Mat.PAINT)
            b.push().trs(side * 19.5f, 8f, 0f, 0f, 0f, PI_F / 2f).cylinder(0f, -0.4f, 0f, 3.5f, 3.5f, 0.8f, 20).pop()
            for (k in 0 until 4) {
                b.color(1f, 0.75f, 0.42f, 1.4f)
                b.push().trs(side * (9.5f + k * 2.6f), 8.6f, 3.12f).box(0f, 0f, 0f, 0.9f, 0.9f, 0.15f).pop()
                b.push().trs(side * (9.5f + k * 2.6f), 8.6f, -3.12f).box(0f, 0f, 0f, 0.9f, 0.9f, 0.15f).pop()
            }
            b.color(0.5f, 0.5f, 0.5f).mat(Mat.RUST)
            b.cylinder(side * 16f, -1f, 0f, 0.4f, 0.4f, 6.5f, 8)
            fx.add(Fixture(x + side * 20.5f, y + 8f, z, 1f, 0.7f, 0.4f, 18f))
            cols.add(Collider(x + minOf(side * 6.5f, side * 20f), y + 4.6f, z - 3.4f, x + maxOf(side * 6.5f, side * 20f), y + 11.4f, z + 3.4f))
        }
        // docking module (moon pool) toward +z
        b.color(0.92f, 0.9f, 0.85f).mat(Mat.PAINT)
        b.push().trs(0f, 8f, 9f, 0f, PI_F / 2f, 0f).cylinder(0f, -2f, 0f, 2.6f, 2.6f, 6f, 18).pop()
        b.cylinder(0f, 10f, 15f, 4.5f, 4.5f, 3f, 22)
        b.color(0.5f, 0.5f, 0.5f).mat(Mat.METAL)
        b.cylinder(0f, 7.6f, 15f, 4.8f, 4.8f, 2.4f, 22, caps = false)
        b.color(0.3f, 1f, 0.6f, 1.1f)
        b.cylinder(0f, 7.5f, 15f, 4.9f, 4.9f, 0.25f, 22, caps = false)
        cols.add(Collider(x - 4.5f, y + 10f, z + 10.5f, x + 4.5f, y + 13f, z + 19.5f, cylinder = true))
        fx.add(Fixture(x, y + 6.5f, z + 15f, 0.3f, 1f, 0.6f, 9f, group = 1))
        // mast and beacon
        b.color(0.5f, 0.5f, 0.5f).mat(Mat.METAL)
        b.cylinder(2.5f, 14f, -2f, 0.18f, 0.12f, 9f, 6)
        b.color(1f, 0.15f, 0.1f, 3f)
        b.sphere(2.5f, 23.2f, -2f, 0.35f, 0.35f, 0.35f, 4, 8)
        fx.add(Fixture(x + 2.5f, y + 23.2f, z - 2f, 1f, 0.15f, 0.1f, 14f, blink = 1.2f))
        // floodlights on the hub
        for (i in 0 until 3) {
            val a = i * TWO_PI / 3 + 0.5f
            b.color(0.4f, 0.4f, 0.4f).mat(Mat.METAL)
            b.box(cos(a) * 7.6f, 4.8f, sin(a) * 7.6f, 0.7f, 0.5f, 0.7f)
            fx.add(Fixture(x + cos(a) * 9f, y + 3.5f, z + sin(a) * 9f, 1f, 0.82f, 0.6f, 22f))
        }
        // pipes into the floor
        b.color(0.5f, 0.5f, 0.5f).mat(Mat.RUST)
        b.push().trs(-4f, 0f, -8f, 0f, 0.4f).cylinder(0f, 0f, 0f, 0.5f, 0.5f, 6f, 8).pop()
        b.pop()
        cols.add(Collider(x - 7.2f, y + 4.5f, z - 7.2f, x + 7.2f, y + 16f, z + 7.2f, cylinder = true))
        fx.add(Fixture(x, y + 9f, z, 1f, 0.75f, 0.45f, 20f))
    }

    private fun relay(b: MeshBuilder, cols: ArrayList<Collider>, fx: ArrayList<Fixture>, x: Float, y: Float, z: Float) {
        b.push().trs(x, y, z)
        b.color(0.5f, 0.5f, 0.5f).mat(Mat.RUST)
        b.box(0f, 0.4f, 0f, 4f, 0.8f, 4f)
        for (i in 0 until 4) {
            val a = i * PI_F / 2f + PI_F / 4f
            b.push().trs(cos(a) * 1.4f, 0.8f, sin(a) * 1.4f, 0f, 0f, 0f).cylinder(0f, 0f, 0f, 0.14f, 0.1f, 12f, 6).pop()
        }
        for (k in 0 until 5) b.box(0f, 2f + k * 2.2f, 0f, 2.1f, 0.12f, 2.1f)
        b.color(0.8f, 0.62f, 0.12f).mat(Mat.PAINT)
        b.push().trs(0f, 13f, 0f, 0.6f, -0.7f).cylinder(0f, 0f, 0f, 2.2f, 0.4f, 0.8f, 16).pop()
        b.color(0.5f, 0.5f, 0.5f).mat(Mat.METAL)
        b.box(2.2f, 1.6f, 0f, 0.8f, 1.6f, 1.2f)
        b.pop()
        cols.add(Collider(x - 2f, y, z - 2f, x + 2f, y + 14f, z + 2f))
        fx.add(Fixture(x + 2.7f, y + 2f, z, 1f, 0.2f, 0.1f, 6f, group = 2, blink = 0.6f))
        fx.add(Fixture(x, y + 14f, z, 0.3f, 1f, 0.7f, 16f, group = 3))
    }

    private fun ship(b: MeshBuilder, cols: ArrayList<Collider>, fx: ArrayList<Fixture>, x: Float, y: Float, z: Float, yaw: Float, s: Float, r: Rng) {
        val len = 70f * s; val w = 12f * s; val h = 9f * s
        b.push().trs(x, y - 1.5f * s, z, yaw, 0f, 0.12f)
        // two hull halves with a broken gap
        for (half in 0 until 2) {
            val sign = if (half == 0) -1f else 1f
            val cx = sign * len * 0.27f
            b.push().trs(cx, 0f, 0f, 0f, 0f, sign * 0.04f)
            b.color(0.5f, 0.5f, 0.5f).mat(Mat.RUST)
            b.box(0f, h * 0.5f, 0f, len * 0.42f, h, w)
            b.color(0.45f, 0.45f, 0.45f).mat(Mat.RUST)
            b.box(0f, h + 0.2f, 0f, len * 0.42f, 0.4f, w * 0.95f)
            // bow / stern taper
            b.color(0.5f, 0.5f, 0.5f).mat(Mat.RUST)
            b.push().trs(sign * len * 0.21f, h * 0.5f, 0f, PI_F / 4f).box(0f, 0f, 0f, w * 0.7f, h * 0.98f, w * 0.7f).pop()
            b.pop()
        }
        // superstructure
        b.color(0.75f, 0.73f, 0.68f).mat(Mat.PAINT)
        b.box(len * 0.12f, h + 3.5f * s, 0f, 10f * s, 7f * s, w * 0.75f)
        b.box(len * 0.12f, h + 8f * s, 0f, 7f * s, 2.5f * s, w * 0.6f)
        b.color(0.08f, 0.1f, 0.1f)
        b.box(len * 0.12f + 3.6f * s, h + 8f * s, 0f, 0.2f, 1.2f * s, w * 0.5f)
        // crane
        b.color(0.8f, 0.6f, 0.12f).mat(Mat.PAINT)
        b.cylinder(-len * 0.2f, h, 0f, 0.5f * s, 0.4f * s, 8f * s, 8)
        b.push().trs(-len * 0.2f, h + 8f * s, 0f, 0.4f, 0f, -1.1f).box(0f, 5f * s, 0f, 0.6f * s, 10f * s, 0.6f * s).pop()
        // containers
        for (i in 0 until (3 + r.nextInt(3))) {
            val cc = listOf(0x8A3B2A, 0x2E5A7A, 0x5A6B3A, 0x9A7A2A)[r.nextInt(4)]
            b.color(cc).mat(Mat.RUST)
            b.push().trs(-len * 0.35f + i * 5f * s, h + 1.4f * s, r.range(-2f, 2f) * s, r.range(-0.2f, 0.2f)).box(0f, 0f, 0f, 4.5f * s, 2.6f * s, 2.6f * s).pop()
        }
        // emergency light still running on the bridge
        b.color(1f, 0.3f, 0.15f, 2.5f)
        b.sphere(len * 0.12f, h + 9.6f * s, 0f, 0.25f, 0.25f, 0.25f, 4, 6)
        b.pop()
        val c = cos(yaw); val sn = sin(yaw)
        // colliders for each half (axis aligned approximation of the rotated hull)
        for (half in listOf(-1f, 1f)) {
            val hx = x + c * half * len * 0.27f; val hz = z - sn * half * len * 0.27f
            val ex = kotlin.math.abs(c) * len * 0.23f + kotlin.math.abs(sn) * w * 0.5f
            val ez = kotlin.math.abs(sn) * len * 0.23f + kotlin.math.abs(c) * w * 0.5f
            cols.add(Collider(hx - ex, y - 2f, hz - ez, hx + ex, y + h + 0.5f, hz + ez))
        }
        val bx = x + c * len * 0.12f; val bz = z - sn * len * 0.12f
        cols.add(Collider(bx - 5f * s, y + h, bz - 5f * s, bx + 5f * s, y + h + 9.5f * s, bz + 5f * s))
        fx.add(Fixture(bx, y + h + 9.6f * s, bz, 1f, 0.3f, 0.15f, 10f, blink = 2.3f))
    }

    private fun outpost(b: MeshBuilder, cols: ArrayList<Collider>, fx: ArrayList<Fixture>, x: Float, y: Float, z: Float) {
        b.push().trs(x, y, z, 0.7f)
        b.color(0.5f, 0.5f, 0.5f).mat(Mat.RUST)
        for (i in 0 until 4) { val a = i * PI_F / 2f + 0.4f; b.cylinder(cos(a) * 5f, -1f, sin(a) * 5f, 0.4f, 0.3f, 4.5f, 6) }
        b.color(0.42f, 0.42f, 0.4f).mat(Mat.PAINT)
        b.cylinder(0f, 3.5f, 0f, 6f, 6f, 6f, 22)
        b.color(0.3f, 0.3f, 0.3f).mat(Mat.RUST)
        b.sphere(0f, 9.5f, 0f, 6f, 2.5f, 6f, 6, 22)
        b.color(0.42f, 0.42f, 0.4f).mat(Mat.PAINT)
        b.push().trs(10f, 5f, 0f, 0f, 0f, PI_F / 2f).cylinder(0f, -4f, 0f, 2.5f, 2.5f, 8f, 16).pop()
        // breached module, open to the sea
        b.color(0.35f, 0.35f, 0.35f).mat(Mat.RUST)
        b.push().trs(-9f, 4f, 0f, 0f, 0.5f, PI_F / 2f + 0.3f).cylinder(0f, -4f, 0f, 2.5f, 2.2f, 7f, 16, caps = false).pop()
        for (i in 0 until 10) {
            val a = i * TWO_PI / 10
            val lit = i % 3 == 0
            b.color(if (lit) 0.9f else 0.05f, if (lit) 0.55f else 0.07f, if (lit) 0.3f else 0.08f, if (lit) 1.2f else 0f)
            b.push().trs(cos(a) * 6.02f, 7f, sin(a) * 6.02f, -a + PI_F / 2f).box(0f, 0f, 0f, 1.4f, 0.9f, 0.1f).pop()
        }
        b.pop()
        cols.add(Collider(x - 6.2f, y + 3.5f, z - 6.2f, x + 6.2f, y + 12f, z + 6.2f, cylinder = true))
        val c = cos(0.7f); val s = sin(0.7f)
        cols.add(Collider(x + c * 10f - 4f, y + 2.5f, z - s * 10f - 4f, x + c * 10f + 4f, y + 7.5f, z - s * 10f + 4f))
        fx.add(Fixture(x, y + 7f, z, 1f, 0.5f, 0.25f, 14f, blink = 0.15f))
    }

    private fun beacon(b: MeshBuilder, cols: ArrayList<Collider>, fx: ArrayList<Fixture>, x: Float, y: Float, z: Float) {
        b.push().trs(x, y, z)
        b.color(0.5f, 0.5f, 0.5f).mat(Mat.METAL)
        for (i in 0 until 3) {
            val a = i * TWO_PI / 3
            b.push().trs(cos(a) * 1.2f, 0f, sin(a) * 1.2f, a, 0f, 0f).push().trs(0f, 0f, 0f, 0f, 0f, 0.3f).cylinder(0f, 0f, 0f, 0.1f, 0.08f, 3f, 5).pop().pop()
        }
        b.color(0.8f, 0.62f, 0.12f).mat(Mat.PAINT)
        b.cylinder(0f, 2.4f, 0f, 0.5f, 0.4f, 1.6f, 10)
        b.color(0.4f, 0.9f, 1f, 0.4f)
        b.sphere(0f, 4.3f, 0f, 0.4f, 0.4f, 0.4f, 5, 8)
        b.pop()
        cols.add(Collider(x - 1f, y, z - 1f, x + 1f, y + 4.5f, z + 1f, cylinder = true))
        fx.add(Fixture(x, y + 4.3f, z, 0.4f, 0.9f, 1f, 30f, group = 4, blink = 1.6f))
    }

    private fun choir(b: MeshBuilder, cols: ArrayList<Collider>, fx: ArrayList<Fixture>, x: Float, y: Float, z: Float) {
        b.push().trs(x, y, z)
        val r = Rng(999)
        for (i in 0 until 9) {
            val a = i * TWO_PI / 9
            val rr = 34f + r.range(-3f, 3f)
            val h = r.range(22f, 38f)
            val lean = r.range(-0.12f, 0.12f)
            b.push().trs(cos(a) * rr, -1f, sin(a) * rr, -a, lean, r.range(-0.08f, 0.08f))
            b.color(0.82f, 0.84f, 0.86f).mat(Mat.METAL)
            b.cylinder(0f, 0f, 0f, 2.6f, 1.1f, h, 6, flat = true)
            b.color(0.45f, 0.8f, 1f, 2.5f)
            for (k in 0 until 7) {
                val ky = 3f + k * (h - 6f) / 7f
                b.push().trs(0f, ky, 0f, k * 0.9f).box(2.2f - ky / h * 1.3f, 0f, 0f, 0.1f, 1.2f, 0.5f).pop()
            }
            b.pop()
            cols.add(Collider(x + cos(a) * rr - 2.6f, y - 1f, z + sin(a) * rr - 2.6f, x + cos(a) * rr + 2.6f, y + h, z + sin(a) * rr + 2.6f, cylinder = true))
        }
        // the core: a ring floating over a pit
        b.color(0.82f, 0.84f, 0.86f).mat(Mat.METAL)
        for (i in 0 until 24) {
            val a = i * TWO_PI / 24
            b.push().trs(cos(a) * 8f, 6f, sin(a) * 8f, -a).box(0f, 0f, 0f, 2.2f, 0.8f, 0.9f).pop()
        }
        b.color(0.5f, 0.85f, 1f, 4f)
        b.sphere(0f, 6f, 0f, 1.6f, 1.6f, 1.6f, 8, 12)
        b.pop()
        fx.add(Fixture(x, y + 6f, z, 0.45f, 0.8f, 1f, 45f, group = 5))
    }
}
