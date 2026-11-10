package com.worldwake.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.worldwake.game.engine.Workers
import com.worldwake.game.engine.clamp01
import com.worldwake.game.engine.lerp
import com.worldwake.game.engine.smoothstep
import com.worldwake.game.game.BuildType
import com.worldwake.game.game.GameState
import com.worldwake.game.game.POD_CELL
import com.worldwake.game.world.Planet
import com.worldwake.game.world.PoiKind
import com.worldwake.game.world.WorldGen
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A top-down picture of the basin as it looks at the current stage: frost, bare ground, water, moss and grass. */
object MapImage {
    const val SIZE = 320
    const val EXTENT = 1240f
    @Volatile var bitmap: Bitmap? = null
        private set
    @Volatile private var builtFor = -1
    @Volatile private var building = false

    /** World units per pixel. */
    val scale get() = EXTENT * 2f / SIZE

    fun request(stage: Float, onReady: () -> Unit) {
        val key = (stage * 4f).toInt()
        if (key == builtFor || building) return
        building = true
        Workers.gen.execute {
            try {
                bitmap = Bitmap.createBitmap(render(stage), SIZE, SIZE, Bitmap.Config.ARGB_8888)
                builtFor = key
            } catch (_: Throwable) {
            } finally { building = false }
            onReady()
        }
    }

    fun render(stage: Float): IntArray {
        val v = Planet.Visuals()
        Planet.visuals(stage, v)
        val px = IntArray(SIZE * SIZE)
        val sp = FloatArray(8)
        val step = scale
        val hs = FloatArray((SIZE + 1) * (SIZE + 1))
        // east is -x, so the picture runs from +x on the left to -x on the right; north (+z) is up
        for (j in 0..SIZE) for (i in 0..SIZE) hs[j * (SIZE + 1) + i] = WorldGen.height(EXTENT - i * step, EXTENT - j * step)
        for (j in 0 until SIZE) for (i in 0 until SIZE) {
            val x = EXTENT - (i + 0.5f) * step; val z = EXTENT - (j + 0.5f) * step
            val h = hs[j * (SIZE + 1) + i]
            val dx = hs[j * (SIZE + 1) + i + 1] - h; val dz = hs[(j + 1) * (SIZE + 1) + i] - h
            val r2 = x * x + z * z
            var r: Float; var g: Float; var b: Float
            if (r2 > (EXTENT - 6f) * (EXTENT - 6f)) { px[j * SIZE + i] = 0; continue }
            WorldGen.splat(x, z, h, sp)
            r = 0.2f * sp[0] + 0.48f * sp[1] + 0.6f * sp[2] + 0.29f * sp[3]
            g = 0.22f * sp[0] + 0.45f * sp[1] + 0.56f * sp[2] + 0.29f * sp[3]
            b = 0.27f * sp[0] + 0.51f * sp[1] + 0.22f * sp[2] + 0.35f * sp[3]
            val slope = clamp01(sqrt(dx * dx + dz * dz) / step)
            val rock = smoothstep(0.3f, 0.6f, slope)
            r = lerp(r, 0.24f, rock); g = lerp(g, 0.27f, rock); b = lerp(b, 0.33f, rock)
            val fert = sp[5]
            val moss = smoothstep(0f, 0.3f, v.moss * 1.3f - 0.45f - (1f - fert) * 0.9f) * (1f - rock * 0.6f)
            r = lerp(r, 0.46f, moss); g = lerp(g, 0.1f, moss); b = lerp(b, 0.17f, moss)
            val grass = smoothstep(0f, 0.3f, v.grass * 1.3f - 0.45f - (1f - fert) * 1.1f) * (1f - rock)
            r = lerp(r, 0.8f, grass); g = lerp(g, 0.7f, grass); b = lerp(b, 0.52f, grass)
            val trees = grass * v.trees * (0.25f + 0.35f * smoothstep(0.45f, 0.75f, ((x * 0.031f).let { it - kotlin.math.floor(it) } + (z * 0.027f).let { it - kotlin.math.floor(it) }) * 0.5f))
            r = lerp(r, 0.8f, trees); g = lerp(g, 0.2f, trees); b = lerp(b, 0.22f, trees)
            val frost = smoothstep(0.2f, 0.45f, v.frost * 1.6f + sp[4] - 0.4f - slope * 0.55f)
            r = lerp(r, 0.8f, frost); g = lerp(g, 0.79f, frost); b = lerp(b, 0.94f, frost)
            // light from the north-east
            val shade = (1f + (dx * 0.7f + dz * 0.7f) / step * 1.6f).coerceIn(0.55f, 1.35f)
            r *= shade; g *= shade; b *= shade
            if (v.waterLevel > -50f && h < v.waterLevel) {
                val d = clamp01((v.waterLevel - h) / 6f)
                r = lerp(0.3f, 0.06f, d); g = lerp(0.74f, 0.4f, d); b = lerp(0.7f, 0.42f, d)
            }
            val edge = smoothstep(EXTENT - 6f, EXTENT - 40f, sqrt(r2))
            px[j * SIZE + i] = Color.argb((255 * edge).toInt(), (clamp01(r) * 255).toInt(), (clamp01(g) * 255).toInt(), (clamp01(b) * 255).toInt())
        }
        return px
    }
}

@SuppressLint("ViewConstructor")
class MapView(ctx: Context, private val game: GameState) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 10.5f * d; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val dst = RectF()

    init { MapImage.request(game.stage) { postInvalidate() } }

    private var cx = 0f; private var cy = 0f; private var k = 1f

    private fun sx(x: Float) = cx - x * k
    private fun sy(z: Float) = cy - z * k

    private fun label(c: Canvas, x: Float, z: Float, s: String, color: Int) {
        text.color = color
        text.setShadowLayer(3 * d, 0f, 0f, Color.BLACK)
        c.drawText(s, sx(x), sy(z), text)
        text.clearShadowLayer()
    }

    private fun dot(c: Canvas, x: Float, z: Float, color: Int, r: Float, name: String?) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 6, 8, 16); c.drawCircle(sx(x), sy(z), (r + 1.6f) * d, paint)
        paint.color = color; c.drawCircle(sx(x), sy(z), r * d, paint)
        if (name != null) { text.textSize = 9.5f * d; label(c, x, z - (r + 9f) * d / k, name, color); text.textSize = 10.5f * d }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val side = min(w, h)
        cx = w / 2; cy = h / 2
        k = side / (MapImage.EXTENT * 2f)
        val bmp = MapImage.bitmap
        if (bmp == null) {
            text.color = Color.rgb(150, 160, 185)
            c.drawText("drawing the map…", cx, cy, text)
            return
        }
        dst.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        c.drawBitmap(bmp, null, dst, paint)

        // region names
        val dim = Color.argb(215, 235, 238, 250)
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        label(c, -640f, 150f, "SLATE SPIRES", dim); label(c, 600f, 640f, "THE SHARDS", dim); label(c, 640f, -470f, "BRIMSTONE VENTS", dim)
        label(c, -80f, 880f, "NORTH TERRACES", dim); label(c, WorldGen.SINK_X, WorldGen.SINK_Z - 120f, "THE SINK", dim); label(c, -260f, -600f, "FROST FLATS", dim)
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        label(c, 0f, MapImage.EXTENT - 110f, "N", Color.WHITE)

        // what you have found
        for (p in WorldGen.pois) {
            when (p.kind) {
                PoiKind.LANDER -> dot(c, p.x, p.z, Color.rgb(89, 240, 224), 4.5f, "Lander")
                PoiKind.OUTPOST, PoiKind.SLATE -> if (game.logsRead.contains(p.logIndex)) dot(c, p.x, p.z, Color.rgb(255, 208, 96), if (p.kind == PoiKind.OUTPOST) 3.5f else 2.4f, null)
                PoiKind.CARGO, PoiKind.WRECK -> if (game.lootInv.containsKey(p.id)) dot(c, p.x, p.z, Color.rgb(200, 205, 220), 2.2f, null)
                PoiKind.VAULT -> if (game.logsRead.contains(9) || game.stageReached >= Planet.LAST) dot(c, p.x, p.z, Color.rgb(255, 106, 140), 4f, "Door")
                else -> {}
            }
        }
        var firstPod = true
        for (pl in game.placed.toTypedArray()) {
            when {
                pl.type == BuildType.POD -> {
                    val x = (pl.gx + 0.5f) * POD_CELL; val z = (pl.gz + 0.5f) * POD_CELL
                    dot(c, x, z, Color.rgb(155, 232, 138), 3f, if (firstPod) "Base" else null); firstPod = false
                }
                pl.type == BuildType.BEACON -> dot(c, pl.x, pl.z, Color.WHITE, 2.6f, "Beacon")
                pl.type.isMachine || pl.type.power > 0f -> { paint.color = Color.argb(210, 120, 220, 255); c.drawCircle(sx(pl.x), sy(pl.z), 1.3f * d, paint) }
            }
        }
        if (!game.objectiveX.isNaN()) dot(c, game.objectiveX, game.objectiveZ, Color.rgb(255, 208, 96), 4f, "Goal")

        // you
        val p = game.player
        val px = sx(p.pos.x); val py = sy(p.pos.z)
        val fx = -sin(p.yaw); val fy = -cos(p.yaw)
        val s = 9f * d
        path.reset()
        path.moveTo(px + fx * s, py + fy * s)
        path.lineTo(px - fx * s * 0.7f - fy * s * 0.6f, py - fy * s * 0.7f + fx * s * 0.6f)
        path.lineTo(px - fx * s * 0.25f, py - fy * s * 0.25f)
        path.lineTo(px - fx * s * 0.7f + fy * s * 0.6f, py - fy * s * 0.7f - fx * s * 0.6f)
        path.close()
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; c.drawPath(path, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = Color.rgb(8, 12, 24); c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}
