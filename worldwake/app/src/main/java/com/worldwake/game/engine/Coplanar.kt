package com.worldwake.game.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds flat faces that lie in the same plane, face the same way and overlap, and lifts the later one a few
 * millimetres along its normal. Two surfaces in exactly the same place fight over every pixel and flicker as the
 * camera moves; models are built from overlapping boxes, so this runs on all of them once at load.
 */
object Coplanar {
    class Report(var pairs: Int = 0, var lifted: Int = 0)

    private class Face(val nx: Float, val ny: Float, val nz: Float, val d: Float) {
        val tris = ArrayList<Int>()
        var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE; var minB = Float.MAX_VALUE; var maxB = -Float.MAX_VALUE
        var level = 0
    }

    fun lift(m: MeshData, eps: Float = 0.006f, apply: Boolean = true): Report {
        val rep = Report()
        val st = m.stride
        val v = m.vertices
        val triCount = m.indexCount / 3
        if (triCount < 2) return rep
        val tn = FloatArray(triCount * 4)
        val ok = BooleanArray(triCount)
        for (t in 0 until triCount) {
            val a = m.indices[t * 3] * st; val b = m.indices[t * 3 + 1] * st; val c = m.indices[t * 3 + 2] * st
            val ux = v[b] - v[a]; val uy = v[b + 1] - v[a + 1]; val uz = v[b + 2] - v[a + 2]
            val wx = v[c] - v[a]; val wy = v[c + 1] - v[a + 1]; val wz = v[c + 2] - v[a + 2]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-7f) continue
            nx /= l; ny /= l; nz /= l
            tn[t * 4] = nx; tn[t * 4 + 1] = ny; tn[t * 4 + 2] = nz; tn[t * 4 + 3] = nx * v[a] + ny * v[a + 1] + nz * v[a + 2]
            ok[t] = true
        }
        // bucket triangles by plane
        val buckets = HashMap<Long, ArrayList<Int>>()
        for (t in 0 until triCount) {
            if (!ok[t]) continue
            val k = ((tn[t * 4] * 40f).roundToInt() + 64).toLong() or (((tn[t * 4 + 1] * 40f).roundToInt() + 64).toLong() shl 8) or
                (((tn[t * 4 + 2] * 40f).roundToInt() + 64).toLong() shl 16) or (((tn[t * 4 + 3] * 60f).roundToInt() + 100000).toLong() shl 24)
            buckets.getOrPut(k) { ArrayList() }.add(t)
        }
        val parent = IntArray(triCount) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        val pa = FloatArray(6); val pb = FloatArray(6)
        for (list in buckets.values) {
            if (list.size < 2) continue
            val nx = tn[list[0] * 4]; val ny = tn[list[0] * 4 + 1]; val nz = tn[list[0] * 4 + 2]
            // triangles that share a vertex index belong to one face (a box side is two triangles on four vertices)
            for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                var shared = false
                for (x in 0 until 3) for (y in 0 until 3) if (m.indices[a * 3 + x] == m.indices[b * 3 + y]) shared = true
                if (shared) parent[find(a)] = find(b)
            }
            val faces = LinkedHashMap<Int, Face>()
            val ax = abs(nx); val ay = abs(ny); val az = abs(nz)
            val drop = if (ax >= ay && ax >= az) 0 else if (ay >= az) 1 else 2
            val ia = if (drop == 0) 1 else 0; val ib = if (drop == 2) 1 else 2
            for (t in list) {
                val f = faces.getOrPut(find(t)) { Face(tn[t * 4], tn[t * 4 + 1], tn[t * 4 + 2], tn[t * 4 + 3]) }
                f.tris.add(t)
                for (x in 0 until 3) {
                    val o = m.indices[t * 3 + x] * st
                    f.minA = min(f.minA, v[o + ia]); f.maxA = max(f.maxA, v[o + ia]); f.minB = min(f.minB, v[o + ib]); f.maxB = max(f.maxB, v[o + ib])
                }
            }
            if (faces.size < 2) continue
            val fl = faces.values.toList()
            for (j in 1 until fl.size) {
                val fj = fl[j]
                for (i in 0 until j) {
                    val fi = fl[i]
                    if (fi.maxA <= fj.minA || fj.maxA <= fi.minA || fi.maxB <= fj.minB || fj.maxB <= fi.minB) continue
                    if (abs(fi.d - fj.d) > 0.0035f || fi.nx * fj.nx + fi.ny * fj.ny + fi.nz * fj.nz < 0.9995f) continue
                    var area = 0f
                    for (ta in fi.tris) for (tb in fj.tris) {
                        for (x in 0 until 3) {
                            val oa = m.indices[ta * 3 + x] * st; val ob = m.indices[tb * 3 + x] * st
                            pa[x * 2] = v[oa + ia]; pa[x * 2 + 1] = v[oa + ib]; pb[x * 2] = v[ob + ia]; pb[x * 2 + 1] = v[ob + ib]
                        }
                        area += overlap(pa, pb)
                    }
                    if (area > 2.5e-4f) { rep.pairs++; fj.level = max(fj.level, fi.level + 1) }
                }
            }
            if (!apply) continue
            for (f in fl) {
                if (f.level == 0) continue
                rep.lifted++
                val seen = HashSet<Int>()
                val k = eps * min(f.level, 4)
                for (t in f.tris) for (x in 0 until 3) {
                    val idx = m.indices[t * 3 + x]
                    if (!seen.add(idx)) continue
                    val o = idx * st
                    v[o] += f.nx * k; v[o + 1] += f.ny * k; v[o + 2] += f.nz * k
                }
            }
        }
        return rep
    }

    /** Area shared by two triangles in 2D (Sutherland-Hodgman clip of one by the other). */
    private fun overlap(a: FloatArray, b: FloatArray): Float {
        var poly = FloatArray(24); var n = 3
        System.arraycopy(a, 0, poly, 0, 6)
        var out = FloatArray(24)
        // orient the clip triangle counter-clockwise
        val cross = (b[2] - b[0]) * (b[5] - b[1]) - (b[3] - b[1]) * (b[4] - b[0])
        val sgn = if (cross >= 0f) 1f else -1f
        for (e in 0 until 3) {
            val x0 = b[e * 2]; val y0 = b[e * 2 + 1]; val x1 = b[((e + 1) % 3) * 2]; val y1 = b[((e + 1) % 3) * 2 + 1]
            var m = 0
            for (i in 0 until n) {
                val px = poly[i * 2]; val py = poly[i * 2 + 1]
                val qx = poly[((i + 1) % n) * 2]; val qy = poly[((i + 1) % n) * 2 + 1]
                val sp = ((x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)) * sgn
                val sq = ((x1 - x0) * (qy - y0) - (y1 - y0) * (qx - x0)) * sgn
                if (sp >= 0f) { if (m < 11) { out[m * 2] = px; out[m * 2 + 1] = py; m++ } }
                if ((sp >= 0f) != (sq >= 0f)) {
                    val t = sp / (sp - sq)
                    if (m < 11) { out[m * 2] = px + (qx - px) * t; out[m * 2 + 1] = py + (qy - py) * t; m++ }
                }
            }
            val tmp = poly; poly = out; out = tmp
            n = m
            if (n < 3) return 0f
        }
        var s = 0f
        for (i in 0 until n) { val j = (i + 1) % n; s += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1] }
        return abs(s) * 0.5f
    }
}
