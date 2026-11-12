package scrapline.view

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import scrapline.sim.hash2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Floats per vertex: position (3), normal (3), packed colour (1). */
const val VSIZE = 7

/** Alpha values double as a material flag for the world shader. */
const val MAT_PLAIN = 1f
const val MAT_SAND = 0.2f
/** Drawn at full brightness regardless of lighting (flames, status lights, tracers). */
const val MAT_GLOW = 0.6f

fun rgb(hex: Int, material: Float = MAT_PLAIN): Float =
    Color.toFloatBits(((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f, material)

fun worldAttributes() = VertexAttributes(
    VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked(),
)

/** Growable buffer of flat-shaded triangles in the world vertex format. */
class Tris(initialVerts: Int = 256) {
    var data = FloatArray(initialVerts * VSIZE)
        private set
    /** Floats used. */
    var size = 0
        private set
    val vertexCount get() = size / VSIZE

    fun clear() {
        size = 0
    }

    private fun ensure(extraFloats: Int) {
        if (size + extraFloats > data.size) data = data.copyOf(maxOf(data.size * 2, size + extraFloats))
    }

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, color: Float) {
        ensure(VSIZE)
        data[size++] = x; data[size++] = y; data[size++] = z
        data[size++] = nx; data[size++] = ny; data[size++] = nz
        data[size++] = color
    }

    /** Adds a triangle with its face normal; counter-clockwise when seen from outside. */
    fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, color: Float) {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 1e-9f) { nx /= len; ny /= len; nz /= len }
        vertex(ax, ay, az, nx, ny, nz, color)
        vertex(bx, by, bz, nx, ny, nz, color)
        vertex(cx, cy, cz, nx, ny, nz, color)
    }

    fun quad(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, color: Float) {
        tri(ax, ay, az, bx, by, bz, cx, cy, cz, color)
        tri(ax, ay, az, cx, cy, cz, dx, dy, dz, color)
    }

    /** Axis-aligned box centred at (x, y, z). */
    fun box(w: Float, h: Float, d: Float, x: Float, y: Float, z: Float, color: Float): Tris {
        val x0 = x - w / 2; val x1 = x + w / 2
        val y0 = y - h / 2; val y1 = y + h / 2
        val z0 = z - d / 2; val z1 = z + d / 2
        quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, color) // top
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, color) // bottom
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, color) // +z
        quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, color) // -z
        quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, color) // +x
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, color) // -x
        return this
    }

    /** Upright tapered cylinder with its base at y; a top radius of 0 makes a cone. */
    fun cyl(rBottom: Float, rTop: Float, h: Float, sides: Int, x: Float, y: Float, z: Float, color: Float, capColor: Float = color): Tris {
        for (i in 0 until sides) {
            val a0 = i * (Math.PI.toFloat() * 2f) / sides
            val a1 = (i + 1) * (Math.PI.toFloat() * 2f) / sides
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val bx0 = x + c0 * rBottom; val bz0 = z + s0 * rBottom
            val bx1 = x + c1 * rBottom; val bz1 = z + s1 * rBottom
            val tx0 = x + c0 * rTop; val tz0 = z + s0 * rTop
            val tx1 = x + c1 * rTop; val tz1 = z + s1 * rTop
            tri(bx0, y, bz0, tx0, y + h, tz0, bx1, y, bz1, color)
            if (rTop > 0f) {
                tri(bx1, y, bz1, tx0, y + h, tz0, tx1, y + h, tz1, color)
                tri(x, y + h, z, tx1, y + h, tz1, tx0, y + h, tz0, capColor)
            }
        }
        return this
    }

    /** Low-poly boulder: an icosahedron with jittered corners, squashed by (sx, sy, sz), base near y. */
    fun rock(radius: Float, seed: Int, sx: Float, sy: Float, sz: Float, x: Float, y: Float, z: Float, color: Float, color2: Float = color): Tris {
        val t = 1.618034f
        val p = floatArrayOf(
            -1f, t, 0f, 1f, t, 0f, -1f, -t, 0f, 1f, -t, 0f, 0f, -1f, t, 0f, 1f, t,
            0f, -1f, -t, 0f, 1f, -t, t, 0f, -1f, t, 0f, 1f, -t, 0f, -1f, -t, 0f, 1f,
        )
        val norm = 1f / sqrt(1f + t * t)
        for (i in 0 until 12) {
            val j = 0.78f + 0.44f * hash2(i, seed, 71)
            p[i * 3] *= norm * radius * j * sx
            p[i * 3 + 1] *= norm * radius * j * sy
            p[i * 3 + 2] *= norm * radius * j * sz
        }
        val f = intArrayOf(
            0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11, 1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
            3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9, 4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1,
        )
        val lift = radius * sy * 0.55f
        for (k in 0 until 20) {
            val a = f[k * 3] * 3; val b = f[k * 3 + 1] * 3; val c = f[k * 3 + 2] * 3
            tri(
                x + p[a], y + lift + p[a + 1], z + p[a + 2],
                x + p[b], y + lift + p[b + 1], z + p[b + 2],
                x + p[c], y + lift + p[c + 1], z + p[c + 2],
                if (hash2(k, seed, 13) < 0.35f) color2 else color,
            )
        }
        return this
    }

    /** Rotates everything added since `fromFloat` about an axis through (px, py, pz). axis: 0 = X, 1 = Y, 2 = Z. */
    fun rotate(fromFloat: Int, axis: Int, angle: Float, px: Float = 0f, py: Float = 0f, pz: Float = 0f): Tris {
        val c = cos(angle)
        val s = sin(angle)
        var i = fromFloat
        while (i < size) {
            for (o in intArrayOf(0, 3)) {
                val isPos = o == 0
                var x = data[i + o] - (if (isPos) px else 0f)
                var y = data[i + o + 1] - (if (isPos) py else 0f)
                var z = data[i + o + 2] - (if (isPos) pz else 0f)
                when (axis) {
                    0 -> { val ny = y * c - z * s; z = y * s + z * c; y = ny }
                    1 -> { val nx = x * c + z * s; z = -x * s + z * c; x = nx }
                    else -> { val nx = x * c - y * s; y = x * s + y * c; x = nx }
                }
                data[i + o] = x + (if (isPos) px else 0f)
                data[i + o + 1] = y + (if (isPos) py else 0f)
                data[i + o + 2] = z + (if (isPos) pz else 0f)
            }
            i += VSIZE
        }
        return this
    }

    /** Moves everything added since `fromFloat`. */
    fun shift(fromFloat: Int, dx: Float, dy: Float, dz: Float): Tris {
        var i = fromFloat
        while (i < size) {
            data[i] += dx; data[i + 1] += dy; data[i + 2] += dz
            i += VSIZE
        }
        return this
    }

    /**
     * Appends a copy of `shape`, turned by `yaw` about Y, scaled, then moved to (x, y, z).
     * `tint` multiplies nothing; pass a colour >= 0 bits to override every vertex colour (used by the ghost).
     */
    fun place(shape: Tris, x: Float, y: Float, z: Float, yaw: Float = 0f, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f, override: Float = Float.NaN) {
        ensure(shape.size)
        val c = cos(yaw)
        val s = sin(yaw)
        val src = shape.data
        var i = 0
        while (i < shape.size) {
            val px = src[i] * sx; val py = src[i + 1] * sy; val pz = src[i + 2] * sz
            data[size++] = x + px * c + pz * s
            data[size++] = y + py
            data[size++] = z - px * s + pz * c
            val nx = src[i + 3]; val nz = src[i + 5]
            data[size++] = nx * c + nz * s
            data[size++] = src[i + 4]
            data[size++] = -nx * s + nz * c
            data[size++] = if (override.isNaN()) src[i + 6] else override
            i += VSIZE
        }
    }

    /** Appends a copy of `shape` expressed in an arbitrary basis (right, up, back) at an origin. Used for the held tool. */
    fun placeBasis(
        shape: Tris, ox: Float, oy: Float, oz: Float,
        rx: Float, ry: Float, rz: Float, ux: Float, uy: Float, uz: Float, bx: Float, by: Float, bz: Float,
    ) {
        ensure(shape.size)
        val src = shape.data
        var i = 0
        while (i < shape.size) {
            val px = src[i]; val py = src[i + 1]; val pz = src[i + 2]
            data[size++] = ox + rx * px + ux * py + bx * pz
            data[size++] = oy + ry * px + uy * py + by * pz
            data[size++] = oz + rz * px + uz * py + bz * pz
            val nx = src[i + 3]; val ny = src[i + 4]; val nz = src[i + 5]
            data[size++] = rx * nx + ux * ny + bx * nz
            data[size++] = ry * nx + uy * ny + by * nz
            data[size++] = rz * nx + uz * ny + bz * nz
            data[size++] = src[i + 6]
            i += VSIZE
        }
    }

    /** Uploads to a new static mesh, or returns null when empty. */
    fun toMesh(): Mesh? {
        if (size == 0) return null
        val mesh = Mesh(true, vertexCount, 0, worldAttributes())
        mesh.setVertices(data, 0, size)
        return mesh
    }
}
