package com.worldwake.game.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

const val DEG2RAD = (PI / 180.0).toFloat()
const val TWO_PI = (PI * 2.0).toFloat()
const val PI_F = PI.toFloat()

fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
fun clamp01(v: Float): Float = clamp(v, 0f, 1f)
fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3f - 2f * t)
}

/** Shortest signed difference between two angles in radians. */
fun angleDiff(a: Float, b: Float): Float {
    var d = (b - a) % TWO_PI
    if (d > PI_F) d -= TWO_PI
    if (d < -PI_F) d += TWO_PI
    return d
}

fun approach(current: Float, target: Float, maxDelta: Float): Float {
    val d = target - current
    return if (abs(d) <= maxDelta) target else current + if (d > 0) maxDelta else -maxDelta
}

fun yawTo(dx: Float, dz: Float): Float = atan2(dx, dz)

class Vec3(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f) {
    fun set(x: Float, y: Float, z: Float): Vec3 { this.x = x; this.y = y; this.z = z; return this }
    fun set(o: Vec3): Vec3 = set(o.x, o.y, o.z)
    fun add(x: Float, y: Float, z: Float): Vec3 { this.x += x; this.y += y; this.z += z; return this }
    fun addScaled(o: Vec3, s: Float): Vec3 { x += o.x * s; y += o.y * s; z += o.z * s; return this }
    fun scale(s: Float): Vec3 { x *= s; y *= s; z *= s; return this }
    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun lengthXZ(): Float = sqrt(x * x + z * z)
    fun normalize(): Vec3 {
        val l = length()
        if (l > 1e-6f) { x /= l; y /= l; z /= l }
        return this
    }
    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun copy(): Vec3 = Vec3(x, y, z)
    fun dist(o: Vec3): Float { val dx = x - o.x; val dy = y - o.y; val dz = z - o.z; return sqrt(dx * dx + dy * dy + dz * dz) }
    fun distXZ(o: Vec3): Float { val dx = x - o.x; val dz = z - o.z; return sqrt(dx * dx + dz * dz) }
    fun distXZ(ox: Float, oz: Float): Float { val dx = x - ox; val dz = z - oz; return sqrt(dx * dx + dz * dz) }
    override fun toString(): String = "($x, $y, $z)"
}

/** Column-major 4x4 matrix helpers operating on FloatArray(16), compatible with OpenGL. */
object Mat4 {
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)

    fun identity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    fun perspective(m: FloatArray, fovYDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(fovYDeg * DEG2RAD * 0.5f)
        for (i in 0 until 16) m[i] = 0f
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
    }

    fun ortho(m: FloatArray, l: Float, r: Float, b: Float, t: Float, n: Float, f: Float) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 2f / (r - l)
        m[5] = 2f / (t - b)
        m[10] = -2f / (f - n)
        m[12] = -(r + l) / (r - l)
        m[13] = -(t + b) / (t - b)
        m[14] = -(f + n) / (f - n)
        m[15] = 1f
    }

    fun lookAt(m: FloatArray, ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float, ux: Float, uy: Float, uz: Float) {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        var l = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= l; fy /= l; fz /= l
        // s = f x up
        var sx = fy * uz - fz * uy
        var sy = fz * ux - fx * uz
        var sz = fx * uy - fy * ux
        l = sqrt(sx * sx + sy * sy + sz * sz)
        if (l < 1e-6f) { sx = 1f; sy = 0f; sz = 0f } else { sx /= l; sy /= l; sz /= l }
        // u = s x f
        val vx = sy * fz - sz * fy
        val vy = sz * fx - sx * fz
        val vz = sx * fy - sy * fx
        m[0] = sx; m[4] = sy; m[8] = sz
        m[1] = vx; m[5] = vy; m[9] = vz
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[3] = 0f; m[7] = 0f; m[11] = 0f
        m[12] = -(sx * ex + sy * ey + sz * ez)
        m[13] = -(vx * ex + vy * ey + vz * ez)
        m[14] = fx * ex + fy * ey + fz * ez
        m[15] = 1f
    }

    /** out = a * b. out may alias a or b. */
    fun mul(out: FloatArray, a: FloatArray, b: FloatArray) {
        val r = tmpA
        for (c in 0 until 4) {
            for (row in 0 until 4) {
                r[c * 4 + row] = a[row] * b[c * 4] + a[4 + row] * b[c * 4 + 1] + a[8 + row] * b[c * 4 + 2] + a[12 + row] * b[c * 4 + 3]
            }
        }
        System.arraycopy(r, 0, out, 0, 16)
    }

    /**
     * Builds translation * rotY(yaw) * rotX(pitch) * rotZ(roll) * scale.
     */
    fun fromTRS(m: FloatArray, tx: Float, ty: Float, tz: Float, yaw: Float, pitch: Float, roll: Float, sx: Float, sy: Float, sz: Float) {
        val cy = cos(yaw); val syy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        // R = Ry * Rx * Rz  (row-major notation r[row][col])
        // Ry = [[cy,0,sy],[0,1,0],[-sy,0,cy]]
        // Rx = [[1,0,0],[0,cp,-sp],[0,sp,cp]]
        // Rz = [[cr,-sr,0],[sr,cr,0],[0,0,1]]
        // RyRx = [[cy, sy*sp, sy*cp],[0,cp,-sp],[-sy, cy*sp, cy*cp]]
        val a00 = cy; val a01 = syy * sp; val a02 = syy * cp
        val a10 = 0f; val a11 = cp; val a12 = -sp
        val a20 = -syy; val a21 = cy * sp; val a22 = cy * cp
        // (RyRx)Rz
        val r00 = a00 * cr + a01 * sr; val r01 = -a00 * sr + a01 * cr; val r02 = a02
        val r10 = a10 * cr + a11 * sr; val r11 = -a10 * sr + a11 * cr; val r12 = a12
        val r20 = a20 * cr + a21 * sr; val r21 = -a20 * sr + a21 * cr; val r22 = a22
        // column-major: m[col*4+row]
        m[0] = r00 * sx; m[1] = r10 * sx; m[2] = r20 * sx; m[3] = 0f
        m[4] = r01 * sy; m[5] = r11 * sy; m[6] = r21 * sy; m[7] = 0f
        m[8] = r02 * sz; m[9] = r12 * sz; m[10] = r22 * sz; m[11] = 0f
        m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
    }

    /** out = parent * TRS(...) */
    fun mulTRS(out: FloatArray, parent: FloatArray, tx: Float, ty: Float, tz: Float, yaw: Float, pitch: Float, roll: Float, sx: Float, sy: Float, sz: Float) {
        fromTRS(tmpB, tx, ty, tz, yaw, pitch, roll, sx, sy, sz)
        mul(out, parent, tmpB)
    }

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: Vec3): Vec3 {
        out.x = m[0] * x + m[4] * y + m[8] * z + m[12]
        out.y = m[1] * x + m[5] * y + m[9] * z + m[13]
        out.z = m[2] * x + m[6] * y + m[10] * z + m[14]
        return out
    }

    fun transformDir(m: FloatArray, x: Float, y: Float, z: Float, out: Vec3): Vec3 {
        out.x = m[0] * x + m[4] * y + m[8] * z
        out.y = m[1] * x + m[5] * y + m[9] * z
        out.z = m[2] * x + m[6] * y + m[10] * z
        return out
    }

    /** Projects a world point with a view-projection matrix. Returns w in out[3]; xyz are NDC. */
    fun project(vp: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
        val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
        val cz = vp[2] * x + vp[6] * y + vp[10] * z + vp[14]
        val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
        out[3] = cw
        if (abs(cw) < 1e-6f) { out[0] = 0f; out[1] = 0f; out[2] = 0f; return }
        out[0] = cx / cw; out[1] = cy / cw; out[2] = cz / cw
    }

    fun invert(out: FloatArray, m: FloatArray): Boolean {
        val inv = tmpA
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        var det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (abs(det) < 1e-12f) return false
        det = 1f / det
        for (i in 0 until 16) out[i] = inv[i] * det
        return true
    }
}

/** View frustum for culling, planes extracted from a view-projection matrix. */
class Frustum {
    private val p = FloatArray(24)

    fun update(m: FloatArray) {
        // rows of column-major matrix: row r = (m[r], m[4+r], m[8+r], m[12+r])
        setPlane(0, m[3] + m[0], m[7] + m[4], m[11] + m[8], m[15] + m[12])
        setPlane(1, m[3] - m[0], m[7] - m[4], m[11] - m[8], m[15] - m[12])
        setPlane(2, m[3] + m[1], m[7] + m[5], m[11] + m[9], m[15] + m[13])
        setPlane(3, m[3] - m[1], m[7] - m[5], m[11] - m[9], m[15] - m[13])
        setPlane(4, m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14])
        setPlane(5, m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14])
    }

    private fun setPlane(i: Int, a: Float, b: Float, c: Float, d: Float) {
        val l = sqrt(a * a + b * b + c * c)
        p[i * 4] = a / l; p[i * 4 + 1] = b / l; p[i * 4 + 2] = c / l; p[i * 4 + 3] = d / l
    }

    fun sphere(x: Float, y: Float, z: Float, r: Float): Boolean {
        for (i in 0 until 6) {
            if (p[i * 4] * x + p[i * 4 + 1] * y + p[i * 4 + 2] * z + p[i * 4 + 3] < -r) return false
        }
        return true
    }

    fun box(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float): Boolean {
        for (i in 0 until 6) {
            val a = p[i * 4]; val b = p[i * 4 + 1]; val c = p[i * 4 + 2]; val d = p[i * 4 + 3]
            val x = if (a >= 0) maxX else minX
            val y = if (b >= 0) maxY else minY
            val z = if (c >= 0) maxZ else minZ
            if (a * x + b * y + c * z + d < 0) return false
        }
        return true
    }
}

/** Small deterministic random generator (xorshift), independent from java.util.Random. */
class Rng(seed: Long) {
    private var s: Long = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

    fun nextLong(): Long {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        s = x
        return x
    }

    fun nextFloat(): Float = ((nextLong() ushr 40).toFloat() / (1L shl 24).toFloat())
    fun range(a: Float, b: Float): Float = a + (b - a) * nextFloat()
    fun nextInt(n: Int): Int = ((nextLong() ushr 33) % n).toInt()
    fun chance(p: Float): Boolean = nextFloat() < p

    companion object {
        fun hash(a: Int, b: Int, c: Int = 0): Long {
            var h = a.toLong() * 0x27d4eb2dL + b.toLong() * 0x165667b1L + c.toLong() * 0x1f1f1f1fL + 0x5bd1e995L
            h = h xor (h ushr 15)
            h *= 0x2c1b3c6dL
            h = h xor (h ushr 12)
            h *= 0x297a2d39L
            h = h xor (h ushr 15)
            return if (h == 0L) 1L else h
        }
    }
}
