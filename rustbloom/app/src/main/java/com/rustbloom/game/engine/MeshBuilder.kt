package com.rustbloom.game.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural geometry builder. Colors are sRGB 0..1 floats, the 4th component is emissive strength.
 * A current transform matrix is applied to everything added.
 */
class MeshBuilder(initialVerts: Int = 256) {
    private var v = FloatArray(initialVerts * VERTEX_FLOATS)
    private var idx = IntArray(initialVerts * 2)
    var vertexCount = 0
        private set
    var indexCount = 0
        private set

    val transform = FloatArray(16).also { Mat4.identity(it) }
    private val stack = ArrayList<FloatArray>()
    private val tp = Vec3()
    private val tn = Vec3()

    var r = 1f; var g = 1f; var b = 1f; var e = 0f

    fun color(r: Float, g: Float, b: Float, emissive: Float = 0f): MeshBuilder { this.r = r; this.g = g; this.b = b; this.e = emissive; return this }
    fun color(rgb: Int, emissive: Float = 0f): MeshBuilder =
        color(((rgb shr 16) and 255) / 255f, ((rgb shr 8) and 255) / 255f, (rgb and 255) / 255f, emissive)

    fun push(): MeshBuilder { stack.add(transform.copyOf()); return this }
    fun pop(): MeshBuilder { System.arraycopy(stack.removeAt(stack.size - 1), 0, transform, 0, 16); return this }
    fun reset(): MeshBuilder { Mat4.identity(transform); stack.clear(); return this }

    /** Applies translate * rotY * rotX * rotZ * scale to the current transform. */
    fun trs(tx: Float, ty: Float, tz: Float, yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f): MeshBuilder {
        Mat4.mulTRS(transform, transform, tx, ty, tz, yaw, pitch, roll, sx, sy, sz)
        return this
    }

    private fun ensure(nv: Int, ni: Int) {
        if ((vertexCount + nv) * VERTEX_FLOATS > v.size) v = v.copyOf(maxOf(v.size * 2, (vertexCount + nv) * VERTEX_FLOATS))
        if (indexCount + ni > idx.size) idx = idx.copyOf(maxOf(idx.size * 2, indexCount + ni))
    }

    /** Adds a vertex in local space (transformed). Returns its index. */
    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int {
        ensure(1, 0)
        Mat4.transformPoint(transform, x, y, z, tp)
        Mat4.transformDir(transform, nx, ny, nz, tn).normalize()
        val o = vertexCount * VERTEX_FLOATS
        v[o] = tp.x; v[o + 1] = tp.y; v[o + 2] = tp.z
        v[o + 3] = tn.x; v[o + 4] = tn.y; v[o + 5] = tn.z
        v[o + 6] = r; v[o + 7] = g; v[o + 8] = b; v[o + 9] = e
        return vertexCount++
    }

    /** Raw vertex in world space, no transform. */
    fun rawVertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, cr: Float, cg: Float, cb: Float, ce: Float): Int {
        ensure(1, 0)
        val o = vertexCount * VERTEX_FLOATS
        v[o] = x; v[o + 1] = y; v[o + 2] = z
        v[o + 3] = nx; v[o + 4] = ny; v[o + 5] = nz
        v[o + 6] = cr; v[o + 7] = cg; v[o + 8] = cb; v[o + 9] = ce
        return vertexCount++
    }

    fun tri(a: Int, b: Int, c: Int) {
        ensure(0, 3)
        idx[indexCount++] = a; idx[indexCount++] = b; idx[indexCount++] = c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    /** Flat shaded triangle (counter-clockwise front face). */
    fun flatTri(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
        val ux = x1 - x0; val uy = y1 - y0; val uz = z1 - z0
        val wx = x2 - x0; val wy = y2 - y0; val wz = z2 - z0
        var nx = uy * wz - uz * wy
        var ny = uz * wx - ux * wz
        var nz = ux * wy - uy * wx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l > 1e-9f) { nx /= l; ny /= l; nz /= l } else { ny = 1f }
        val a = vertex(x0, y0, z0, nx, ny, nz)
        val b = vertex(x1, y1, z1, nx, ny, nz)
        val c = vertex(x2, y2, z2, nx, ny, nz)
        tri(a, b, c)
    }

    fun flatQuad(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float) {
        flatTri(x0, y0, z0, x1, y1, z1, x2, y2, z2)
        flatTri(x0, y0, z0, x2, y2, z2, x3, y3, z3)
    }

    /** Axis aligned box centered at (cx, cy, cz) in local space. */
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float): MeshBuilder {
        val x0 = cx - sx / 2; val x1 = cx + sx / 2
        val y0 = cy - sy / 2; val y1 = cy + sy / 2
        val z0 = cz - sz / 2; val z1 = cz + sz / 2
        face(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f)
        face(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f)
        face(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1f, 0f, 0f)
        face(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f)
        face(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0f, 1f, 0f)
        face(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0f, -1f, 0f)
        return this
    }

    private fun face(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, nx: Float, ny: Float, nz: Float) {
        val a = vertex(ax, ay, az, nx, ny, nz)
        val b = vertex(bx, by, bz, nx, ny, nz)
        val c = vertex(cx, cy, cz, nx, ny, nz)
        val d = vertex(dx, dy, dz, nx, ny, nz)
        quad(a, b, c, d)
    }

    /** Box with base at y=0 (convenient for legs/pillars). */
    fun boxBase(cx: Float, y0: Float, cz: Float, sx: Float, sy: Float, sz: Float): MeshBuilder = box(cx, y0 + sy / 2, cz, sx, sy, sz)

    /** Cylinder / truncated cone along Y from y0 to y0+h, smooth sides. */
    fun cylinder(cx: Float, y0: Float, cz: Float, rBottom: Float, rTop: Float, h: Float, seg: Int, caps: Boolean = true, flat: Boolean = false): MeshBuilder {
        val slope = (rBottom - rTop) / h
        if (flat) {
            for (i in 0 until seg) {
                val a0 = i * TWO_PI / seg; val a1 = (i + 1) * TWO_PI / seg
                val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                flatQuad(cx + c0 * rBottom, y0, cz + s0 * rBottom, cx + c0 * rTop, y0 + h, cz + s0 * rTop,
                    cx + c1 * rTop, y0 + h, cz + s1 * rTop, cx + c1 * rBottom, y0, cz + s1 * rBottom)
            }
        } else {
            val base = vertexCount
            for (i in 0..seg) {
                val a = i * TWO_PI / seg
                val c = cos(a); val s = sin(a)
                val nl = sqrt(1 + slope * slope)
                vertex(cx + c * rBottom, y0, cz + s * rBottom, c / nl, slope / nl, s / nl)
                vertex(cx + c * rTop, y0 + h, cz + s * rTop, c / nl, slope / nl, s / nl)
            }
            for (i in 0 until seg) {
                val a = base + i * 2
                tri(a, a + 1, a + 3)
                tri(a, a + 3, a + 2)
            }
        }
        if (caps) {
            if (rTop > 0.001f) disc(cx, y0 + h, cz, rTop, seg, true)
            if (rBottom > 0.001f) disc(cx, y0, cz, rBottom, seg, false)
        }
        return this
    }

    fun disc(cx: Float, y: Float, cz: Float, r: Float, seg: Int, up: Boolean) {
        val ny = if (up) 1f else -1f
        val c = vertex(cx, y, cz, 0f, ny, 0f)
        val base = vertexCount
        for (i in 0..seg) {
            val a = i * TWO_PI / seg
            vertex(cx + cos(a) * r, y, cz + sin(a) * r, 0f, ny, 0f)
        }
        for (i in 0 until seg) {
            if (up) tri(c, base + i + 1, base + i) else tri(c, base + i, base + i + 1)
        }
    }

    /** Low-poly sphere (UV sphere) optionally with deterministic radial jitter for rocks/foliage. */
    fun sphere(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, rings: Int, seg: Int, jitter: Float = 0f, seed: Int = 0, flat: Boolean = false): MeshBuilder {
        val pts = Array((rings + 1) * (seg + 1)) { FloatArray(3) }
        for (ri in 0..rings) {
            val phi = PI_F * ri / rings
            for (si in 0..seg) {
                val th = TWO_PI * si / seg
                var nx = sin(phi) * cos(th); val ny = cos(phi); var nz = sin(phi) * sin(th)
                val sIdx = if (si == seg) 0 else si
                var j = 1f
                if (jitter > 0 && ri != 0 && ri != rings) {
                    val h = Rng.hash(ri, sIdx, seed)
                    j = 1f + ((h ushr 40).toFloat() / (1L shl 24) - 0.5f) * 2f * jitter
                }
                if (ri == 0 || ri == rings) { nx = 0f; nz = 0f }
                val p = pts[ri * (seg + 1) + si]
                p[0] = cx + nx * rx * j; p[1] = cy + ny * ry * j; p[2] = cz + nz * rz * j
            }
        }
        if (flat) {
            for (ri in 0 until rings) for (si in 0 until seg) {
                val a = pts[ri * (seg + 1) + si]; val b2 = pts[ri * (seg + 1) + si + 1]
                val c = pts[(ri + 1) * (seg + 1) + si + 1]; val d = pts[(ri + 1) * (seg + 1) + si]
                if (ri != 0) flatTri(a[0], a[1], a[2], b2[0], b2[1], b2[2], d[0], d[1], d[2])
                if (ri != rings - 1) flatTri(b2[0], b2[1], b2[2], c[0], c[1], c[2], d[0], d[1], d[2])
            }
        } else {
            val base = vertexCount
            for (p in pts) {
                var nx = (p[0] - cx) / (rx * rx); var ny = (p[1] - cy) / (ry * ry); var nz = (p[2] - cz) / (rz * rz)
                val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                nx /= l; ny /= l; nz /= l
                vertex(p[0], p[1], p[2], nx, ny, nz)
            }
            for (ri in 0 until rings) for (si in 0 until seg) {
                val a = base + ri * (seg + 1) + si
                val b2 = a + 1
                val d = a + (seg + 1)
                val c = d + 1
                tri(a, b2, d)
                tri(b2, c, d)
            }
        }
        return this
    }

    /** A thin double sided blade/leaf quad (used for grass). */
    fun blade(x: Float, z: Float, w: Float, h: Float, yaw: Float, lean: Float) {
        val c = cos(yaw); val s = sin(yaw)
        val dx = c * w * 0.5f; val dz = s * w * 0.5f
        val tx = x + (-s) * lean; val tz = z + c * lean
        val nx = -s; val nz = c
        val a = vertex(x - dx, 0f, z - dz, nx, 0.6f, nz)
        val b2 = vertex(x + dx, 0f, z + dz, nx, 0.6f, nz)
        val t = vertex(tx, h, tz, nx, 0.8f, nz)
        tri(a, b2, t)
        val a2 = vertex(x - dx, 0f, z - dz, -nx, 0.6f, -nz)
        val b3 = vertex(x + dx, 0f, z + dz, -nx, 0.6f, -nz)
        val t2 = vertex(tx, h, tz, -nx, 0.8f, -nz)
        tri(b3, a2, t2)
    }

    fun readVertex(index: Int, out: FloatArray) { System.arraycopy(v, index * VERTEX_FLOATS, out, 0, VERTEX_FLOATS) }
    fun vertexFloat(index: Int, offset: Int): Float = v[index * VERTEX_FLOATS + offset]

    fun build(): MeshData = MeshData(v, vertexCount, idx, indexCount)
}
