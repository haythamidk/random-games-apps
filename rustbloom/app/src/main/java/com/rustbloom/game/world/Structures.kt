package com.rustbloom.game.world

import com.rustbloom.game.engine.MatClass
import com.rustbloom.game.engine.MeshBuilder
import com.rustbloom.game.engine.assignMaterials
import com.rustbloom.game.engine.MeshData
import com.rustbloom.game.engine.Rng
import com.rustbloom.game.engine.TWO_PI
import kotlin.math.cos
import kotlin.math.sin

class Aabb(
    @JvmField var minX: Float, @JvmField var minY: Float, @JvmField var minZ: Float,
    @JvmField var maxX: Float, @JvmField var maxY: Float, @JvmField var maxZ: Float,
) {
    fun contains(x: Float, y: Float, z: Float) = x in minX..maxX && y in minY..maxY && z in minZ..maxZ
}

enum class InteractKind { LOG_TERMINAL, SPIRE_CONSOLE, RELAY_CONSOLE, CACHE_BOX, VAULT_TERMINAL, CROWN_CORE, WRECK_SALVAGE }

class Interactable(val poi: Poi, val kind: InteractKind, val x: Float, val y: Float, val z: Float, val radius: Float = 1.6f)

/** Geometry for one point of interest: solid mesh plus an optional glowing mesh whose intensity can change. */
class StructureGeo(val poi: Poi, val solid: MeshData, val glow: MeshData?, val boundsRadius: Float)

/** Builds every POI structure. Colliders and interactables are in world space. */
class StructureBuilder {
    val colliders = ArrayList<Aabb>()
    val interactables = ArrayList<Interactable>()
    val geos = ArrayList<StructureGeo>()

    private lateinit var mb: MeshBuilder
    private var glowMb: MeshBuilder? = null
    private lateinit var poi: Poi

    fun buildAll(): StructureBuilder {
        for (p in WorldGen.pois) build(p)
        return this
    }

    private fun build(p: Poi) {
        poi = p
        mb = MeshBuilder(2048)
        glowMb = null
        mb.trs(p.x, p.y, p.z)
        var radius = p.radius * 2f
        when (p.kind) {
            PoiKind.VAULT -> vault()
            PoiKind.RELAY -> { relay(); radius = 40f }
            PoiKind.SPIRE_GREEN -> spire(0x4fe3a0)
            PoiKind.SPIRE_DUST -> spire(0xffa94f)
            PoiKind.SPIRE_FROST -> spire(0x8fd8ff)
            PoiKind.SPIRE_TIDE -> spire(0x4f9dff)
            PoiKind.CROWN -> { crown(); radius = 90f }
            PoiKind.RUIN -> ruin()
            PoiKind.CACHE -> cache()
            PoiKind.WRECK -> wreck()
            PoiKind.CAMP -> camp()
        }
        if (p.kind.name.startsWith("SPIRE")) radius = 60f
        geos.add(StructureGeo(p, mb.build().assignMaterials(MatClass::structure), glowMb?.build(), radius))
    }

    private fun glow(): MeshBuilder {
        val g = glowMb ?: MeshBuilder(512).also { it.trs(poi.x, poi.y, poi.z); glowMb = it }
        return g
    }

    /** Box that also blocks movement. Coordinates relative to the POI origin. */
    private fun solid(cx: Float, y0: Float, cz: Float, sx: Float, sy: Float, sz: Float) {
        mb.boxBase(cx, y0, cz, sx, sy, sz)
        colliders.add(Aabb(poi.x + cx - sx / 2, poi.y + y0, poi.z + cz - sz / 2, poi.x + cx + sx / 2, poi.y + y0 + sy, poi.z + cz + sz / 2))
    }

    private fun interact(kind: InteractKind, x: Float, y: Float, z: Float, r: Float = 1.8f) {
        interactables.add(Interactable(poi, kind, poi.x + x, poi.y + y, poi.z + z, r))
    }

    private fun moss(x: Float, y: Float, z: Float, sx: Float, sz: Float) {
        mb.color(0.24f, 0.36f, 0.15f)
        mb.box(x, y, z, sx, 0.12f, sz)
    }

    // ------------------------------------------------------------------ structures

    private fun vault() {
        // concrete bunker sunk into the ground
        mb.color(0.5f, 0.5f, 0.48f)
        solid(0f, -2f, -4f, 16f, 7f, 10f)
        mb.color(0.42f, 0.42f, 0.41f)
        solid(0f, 5f, -4f, 13f, 1.2f, 8f)
        // door frame
        mb.color(0.32f, 0.33f, 0.35f)
        solid(-3.2f, 0f, 1.3f, 1.2f, 4.6f, 1.2f)
        solid(3.2f, 0f, 1.3f, 1.2f, 4.6f, 1.2f)
        solid(0f, 4.2f, 1.3f, 7.6f, 1f, 1.2f)
        // blast door, slightly open
        mb.color(0.22f, 0.24f, 0.26f)
        mb.box(-1.2f, 2.1f, 1.0f, 2.6f, 4.2f, 0.4f)
        mb.color(0.62f, 0.45f, 0.12f)
        for (i in 0 until 5) mb.box(-1.2f, 0.6f + i * 0.8f, 1.25f, 2.4f, 0.12f, 0.05f)
        // pipes
        mb.color(0.45f, 0.3f, 0.2f)
        mb.push().trs(6.5f, 0f, 1.2f).cylinder(0f, 0f, 0f, 0.35f, 0.35f, 5f, 8).pop()
        mb.push().trs(-6.5f, 0f, 1.2f).cylinder(0f, 0f, 0f, 0.35f, 0.35f, 4f, 8).pop()
        moss(0f, 6.3f, -4f, 10f, 6f)
        moss(-5f, 5.1f, 0f, 4f, 2f)
        // terminal with amber glow
        mb.color(0.3f, 0.3f, 0.32f)
        solid(4.6f, 0f, 3.2f, 0.9f, 1.3f, 0.7f)
        glow().color(1f, 0.7f, 0.25f, 1f).box(4.6f, 1.15f, 3.58f, 0.6f, 0.35f, 0.05f)
        glow().color(1f, 0.7f, 0.25f, 1f).box(0f, 4.0f, 1.95f, 4f, 0.12f, 0.05f)
        interact(InteractKind.VAULT_TERMINAL, 4.6f, 1.2f, 3.6f)
        // scattered crates
        mb.color(0.36f, 0.32f, 0.24f)
        solid(-6f, 0f, 5f, 1.4f, 1.0f, 1.2f)
        solid(-4.6f, 0f, 6f, 1.0f, 0.8f, 1.0f)
    }

    private fun relay() {
        val h = 24f
        mb.color(0.34f, 0.33f, 0.32f)
        solid(0f, -0.5f, 0f, 8f, 0.8f, 8f)
        mb.color(0.45f, 0.28f, 0.17f)
        val legs = arrayOf(floatArrayOf(-3f, -3f), floatArrayOf(3f, -3f), floatArrayOf(3f, 3f), floatArrayOf(-3f, 3f))
        for (l in legs) {
            mb.push().trs(l[0], 0f, l[1], 0f, -l[1] * 0.025f, l[0] * 0.025f)
            mb.box(0f, h / 2, 0f, 0.4f, h, 0.4f)
            mb.pop()
            colliders.add(Aabb(poi.x + l[0] - 0.4f, poi.y, poi.z + l[1] - 0.4f, poi.x + l[0] + 0.4f, poi.y + h, poi.z + l[1] + 0.4f))
        }
        // cross bracing
        mb.color(0.4f, 0.26f, 0.16f)
        for (i in 1..5) {
            val y = i * 4f
            val w = 6f - i * 0.25f
            mb.box(0f, y, -w / 2, w, 0.2f, 0.2f); mb.box(0f, y, w / 2, w, 0.2f, 0.2f)
            mb.box(-w / 2, y, 0f, 0.2f, 0.2f, w); mb.box(w / 2, y, 0f, 0.2f, 0.2f, w)
        }
        // dish
        mb.color(0.6f, 0.6f, 0.58f)
        mb.push().trs(0f, h + 1f, 0f, 0.6f, -0.7f, 0f)
        mb.cylinder(0f, 0f, 0f, 3.2f, 0.4f, 1.2f, 12, caps = true, flat = true)
        mb.pop()
        glow().color(1f, 0.25f, 0.2f, 1f).box(0f, h + 2.6f, 0f, 0.4f, 0.4f, 0.4f)
        // console
        mb.color(0.28f, 0.29f, 0.3f)
        solid(0f, 0.3f, 4.8f, 1.6f, 1.2f, 0.8f)
        glow().color(0.3f, 0.9f, 1f, 1f).box(0f, 1.3f, 5.22f, 1.2f, 0.4f, 0.05f)
        interact(InteractKind.RELAY_CONSOLE, 0f, 1.2f, 5.4f)
    }

    private fun spire(accent: Int) {
        val rng = Rng(poi.id.toLong() * 991)
        // octagonal platform
        mb.color(0.4f, 0.4f, 0.41f)
        mb.cylinder(0f, -2f, 0f, 14f, 13f, 2.6f, 8, flat = true)
        colliders.add(Aabb(poi.x - 9.5f, poi.y - 2f, poi.z - 9.5f, poi.x + 9.5f, poi.y + 0.6f, poi.z + 9.5f))
        mb.color(0.3f, 0.31f, 0.33f)
        val segs = 7
        var y = 0.6f
        var r = 4.2f
        for (i in 0 until segs) {
            val hgt = 6.5f - i * 0.4f
            val nr = r * 0.82f
            mb.cylinder(0f, y, 0f, r, nr, hgt, 6, flat = true)
            // fins
            mb.color(0.36f, 0.36f, 0.38f)
            for (f in 0 until 3) {
                val a = f * TWO_PI / 3 + i * 0.4f
                mb.push().trs(cos(a) * r, y + hgt * 0.5f, sin(a) * r, -a, 0f, 0f)
                mb.box(0f, 0f, 0f, 0.3f, hgt * 0.8f, 1.6f)
                mb.pop()
            }
            mb.color(0.3f, 0.31f, 0.33f)
            glow().color(accent, 1f).cylinder(0f, y + hgt - 0.35f, 0f, nr * 1.03f, nr * 1.03f, 0.3f, 12, caps = false)
            y += hgt
            r = nr
        }
        colliders.add(Aabb(poi.x - 3.6f, poi.y, poi.z - 3.6f, poi.x + 3.6f, poi.y + y, poi.z + 3.6f))
        // crown tip
        glow().color(accent, 1f).sphere(0f, y + 1.5f, 0f, 1.2f, 1.6f, 1.2f, 4, 6, 0f, 0, flat = true)
        // console on the platform edge
        mb.color(0.27f, 0.28f, 0.3f)
        solid(0f, 0.6f, 7.5f, 2f, 1.3f, 1f)
        glow().color(accent, 1f).box(0f, 1.75f, 8.02f, 1.4f, 0.5f, 0.05f)
        interact(InteractKind.SPIRE_CONSOLE, 0f, 1.6f, 8.3f)
        // broken pylons around
        for (i in 0 until 6) {
            val a = i * TWO_PI / 6 + rng.range(-0.2f, 0.2f)
            val d = 17f + rng.range(0f, 4f)
            val hh = rng.range(2f, 6f)
            mb.color(0.35f, 0.34f, 0.33f)
            solid(cos(a) * d, -0.5f, sin(a) * d, 1.2f, hh, 1.2f)
            glow().color(accent, 0.5f).box(cos(a) * d, hh - 0.6f, sin(a) * d, 1.25f, 0.15f, 1.25f)
        }
    }

    private fun crown() {
        val rng = Rng(4040)
        mb.color(0.38f, 0.38f, 0.4f)
        mb.cylinder(0f, -3f, 0f, 22f, 20f, 3.6f, 12, flat = true)
        colliders.add(Aabb(poi.x - 15f, poi.y - 3f, poi.z - 15f, poi.x + 15f, poi.y + 0.6f, poi.z + 15f))
        // ring of broken arches
        for (i in 0 until 10) {
            val a = i * TWO_PI / 10
            val d = 34f
            val cx = cos(a) * d; val cz = sin(a) * d
            val hh = if (i % 3 == 0) rng.range(6f, 10f) else rng.range(14f, 22f)
            mb.color(0.33f, 0.33f, 0.35f)
            solid(cx, -1f, cz, 2.6f, hh, 2.6f)
            if (i % 3 != 0) {
                mb.push().trs(cx, hh - 1f, cz, -a + 1.57f, 0f, 0.25f)
                mb.box(4f, 0f, 0f, 8f, 1.6f, 2f)
                mb.pop()
            }
            glow().color(0xff4f6a, 0.8f).box(cx, hh - 2.5f, cz, 2.7f, 0.3f, 2.7f)
        }
        // central dais and core
        mb.color(0.28f, 0.28f, 0.3f)
        mb.cylinder(0f, 0.6f, 0f, 6f, 5f, 1.8f, 10, flat = true)
        colliders.add(Aabb(poi.x - 4f, poi.y, poi.z - 4f, poi.x + 4f, poi.y + 2.4f, poi.z + 4f))
        for (i in 0 until 4) {
            val a = i * TWO_PI / 4 + 0.78f
            mb.color(0.3f, 0.3f, 0.32f)
            mb.push().trs(cos(a) * 4.5f, 2.4f, sin(a) * 4.5f, -a, 0f, 0f)
            mb.box(0f, 3f, 0f, 0.6f, 6f, 1.2f)
            mb.pop()
        }
        glow().color(0xff3355, 1f).sphere(0f, 6.5f, 0f, 2.2f, 2.2f, 2.2f, 6, 10, 0.08f, 5, flat = true)
        interact(InteractKind.CROWN_CORE, 0f, 3f, 6.5f, 3.5f)
        // debris
        for (i in 0 until 14) {
            val a = rng.range(0f, TWO_PI)
            val d = rng.range(12f, 30f)
            mb.color(0.34f, 0.33f, 0.33f)
            mb.push().trs(cos(a) * d, 0f, sin(a) * d, rng.range(0f, 6f), rng.range(-0.3f, 0.3f), rng.range(-0.3f, 0.3f))
            mb.box(0f, 0.6f, 0f, rng.range(1f, 4f), rng.range(0.6f, 2f), rng.range(1f, 3f))
            mb.pop()
        }
    }

    private fun ruin() {
        val rng = Rng(poi.id.toLong() * 7717 + 13)
        val style = rng.nextInt(3)
        val wallC = if (style == 2) floatArrayOf(0.55f, 0.52f, 0.47f) else floatArrayOf(0.48f, 0.47f, 0.45f)
        // floor slab
        mb.color(0.4f, 0.39f, 0.37f)
        mb.box(0f, -0.1f, 0f, 14f, 0.5f, 12f)
        // walls along the slab edges with gaps
        for (side in 0 until 4) {
            val segs = 4
            for (s in 0 until segs) {
                if (rng.chance(0.3f)) continue
                val hh = rng.range(1.2f, 4.8f)
                mb.color(wallC[0] * rng.range(0.9f, 1.05f), wallC[1], wallC[2])
                val t = -5.25f + s * 3.5f
                when (side) {
                    0 -> solid(t, 0f, -5.8f, 3.4f, hh, 0.5f)
                    1 -> solid(t, 0f, 5.8f, 3.4f, hh, 0.5f)
                    2 -> solid(-6.8f, 0f, t * 0.85f, 0.5f, hh, 2.9f)
                    else -> solid(6.8f, 0f, t * 0.85f, 0.5f, hh, 2.9f)
                }
                if (hh > 2f && rng.chance(0.5f)) moss(if (side < 2) t else if (side == 2) -6.8f else 6.8f, hh + 0.05f, if (side == 0) -5.8f else if (side == 1) 5.8f else t * 0.85f, 1.5f, 0.6f)
            }
        }
        // pillars
        for (i in 0 until 3) {
            mb.color(0.44f, 0.43f, 0.41f)
            val x = rng.range(-4f, 4f); val z = rng.range(-3.5f, 3.5f)
            solid(x, 0f, z, 0.8f, rng.range(2f, 6f), 0.8f)
        }
        // rusted vehicle hulk
        mb.color(0.46f, 0.26f, 0.15f)
        val vx = rng.range(8f, 11f) * if (rng.chance(0.5f)) 1 else -1
        solid(vx, 0f, rng.range(-3f, 3f), 2.2f, 1.4f, 4.2f)
        // terminal with the echo log
        mb.color(0.3f, 0.31f, 0.32f)
        solid(1.5f, 0.15f, -1f, 0.8f, 1.2f, 0.6f)
        glow().color(0.3f, 0.95f, 0.85f, 1f).box(1.5f, 1.15f, -0.68f, 0.55f, 0.35f, 0.05f)
        interact(InteractKind.LOG_TERMINAL, 1.5f, 1.2f, -0.4f)
    }

    private fun cache() {
        mb.color(0.3f, 0.34f, 0.3f)
        solid(0f, 0f, 0f, 1.6f, 0.9f, 1.0f)
        mb.color(0.25f, 0.28f, 0.25f)
        mb.box(0f, 0.95f, 0f, 1.7f, 0.12f, 1.1f)
        glow().color(1f, 0.8f, 0.3f, 1f).box(0f, 0.6f, 0.52f, 0.3f, 0.12f, 0.03f)
        // marker pole with cloth
        mb.color(0.4f, 0.32f, 0.22f)
        mb.box(1.2f, 1.5f, 0f, 0.1f, 3f, 0.1f)
        mb.color(0.7f, 0.25f, 0.15f)
        mb.box(1.45f, 2.6f, 0f, 0.5f, 0.5f, 0.02f)
        interact(InteractKind.CACHE_BOX, 0f, 0.9f, 0.9f)
    }

    private fun wreck() {
        val rng = Rng(poi.id.toLong() * 3331)
        val yaw = rng.range(0f, TWO_PI)
        mb.push().trs(0f, 0f, 0f, yaw)
        // spine
        mb.color(0.33f, 0.32f, 0.31f)
        for (i in 0 until 9) mb.box(0f, 1.8f + sin(i * 0.5f) * 0.6f, -10f + i * 2.4f, 1.4f, 1.2f, 2.0f)
        // ribs
        for (i in 1 until 8) {
            val z = -10f + i * 2.4f
            for (s in 0..1) {
                val side = if (s == 0) -1f else 1f
                for (k in 0 until 5) {
                    val a = k * 0.36f
                    mb.color(0.36f, 0.3f, 0.26f)
                    mb.box(side * (0.8f + sin(a) * 3.4f), 1.8f + cos(a) * 3f - 2.2f + k * 0.25f, z, 0.45f, 1.1f, 0.4f)
                }
            }
        }
        // skull
        mb.color(0.35f, 0.34f, 0.34f)
        mb.box(0f, 2.2f, 12.5f, 3f, 2.2f, 3.6f)
        glow().color(1f, 0.3f, 0.2f, 0.25f).box(0f, 2.6f, 14.35f, 2f, 0.3f, 0.05f)
        mb.pop()
        colliders.add(Aabb(poi.x - 2f, poi.y, poi.z - 2f, poi.x + 2f, poi.y + 3f, poi.z + 2f))
        interact(InteractKind.WRECK_SALVAGE, 0f, 1.5f, 0f, 3.5f)
    }

    private fun camp() {
        // tent
        mb.color(0.5f, 0.42f, 0.3f)
        mb.flatQuad(-1.5f, 0f, -2f, -1.5f, 0f, 2f, 0f, 2f, 2f, 0f, 2f, -2f)
        mb.flatQuad(1.5f, 0f, 2f, 1.5f, 0f, -2f, 0f, 2f, -2f, 0f, 2f, 2f)
        mb.flatTri(-1.5f, 0f, 2f, 1.5f, 0f, 2f, 0f, 2f, 2f)
        // fire ring
        mb.color(0.4f, 0.4f, 0.4f)
        for (i in 0 until 8) {
            val a = i * TWO_PI / 8
            mb.box(cos(a) * 0.8f + 4f, 0.1f, sin(a) * 0.8f, 0.3f, 0.25f, 0.3f)
        }
        mb.color(0.15f, 0.12f, 0.1f)
        mb.box(4f, 0.05f, 0f, 1f, 0.1f, 1f)
        mb.color(0.4f, 0.3f, 0.2f)
        mb.push().trs(4f, 0.25f, 2f, 0.3f).cylinder(0f, 0f, 0f, 0.25f, 0.25f, 0.01f, 6).pop()
        mb.push().trs(4f, 0.25f, 2f, 0f, 1.57f, 0f).cylinder(0f, -1f, 0f, 0.25f, 0.25f, 2f, 6).pop()
        interact(InteractKind.CACHE_BOX, -0.0f, 0.5f, 2.6f)
    }
}
