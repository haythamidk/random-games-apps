package com.rustbloom.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.rustbloom.game.game.GameState
import com.rustbloom.game.world.PoiKind
import com.rustbloom.game.world.WorldGen
import kotlin.math.cos
import kotlin.math.sin

object MapImage {
    @Volatile var bitmap: Bitmap? = null
    const val SIZE = 360

    /** Renders a shaded relief map of the world. Call from a background thread. */
    fun generate() {
        if (bitmap != null) return
        val n = SIZE
        val px = IntArray(n * n)
        val col = FloatArray(4)
        val hs = FloatArray(n * n)
        val half = WorldGen.HALF
        for (j in 0 until n) for (i in 0 until n) {
            val x = -half + (i + 0.5f) / n * 2 * half
            val z = -half + (j + 0.5f) / n * 2 * half
            hs[j * n + i] = WorldGen.height(x, z)
        }
        for (j in 0 until n) for (i in 0 until n) {
            val x = -half + (i + 0.5f) / n * 2 * half
            val z = -half + (j + 0.5f) / n * 2 * half
            val h = hs[j * n + i]
            var r: Float; var g: Float; var b: Float
            if (h < WorldGen.WATER) {
                val dpt = ((WorldGen.WATER - h) / 30f).coerceIn(0f, 1f)
                r = 0.16f - dpt * 0.1f; g = 0.36f - dpt * 0.2f; b = 0.45f - dpt * 0.2f
            } else {
                WorldGen.terrainColor(x, z, h, col)
                r = col[0]; g = col[1]; b = col[2]
                val hl = if (i > 0) hs[j * n + i - 1] else h
                val hu = if (j > 0) hs[(j - 1) * n + i] else h
                val shade = (1f + ((h - hl) + (h - hu)) * 0.06f).coerceIn(0.6f, 1.4f)
                r *= shade; g *= shade; b *= shade
                // contour lines
                if ((h / 20f).toInt() != (hl / 20f).toInt()) { r *= 0.85f; g *= 0.85f; b *= 0.85f }
            }
            px[j * n + i] = Color.rgb((r.coerceIn(0f, 1f) * 255).toInt(), (g.coerceIn(0f, 1f) * 255).toInt(), (b.coerceIn(0f, 1f) * 255).toInt())
        }
        bitmap = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }
}

/** World map with discovered points of interest. Pinch-free: tap to zoom between two levels. */
@SuppressLint("ViewConstructor")
class MapView(ctx: Context, private val game: GameState) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11 * d }
    private val dst = RectF()
    private val path = Path()
    private var zoomed = false

    init { isFocusable = true }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) { zoomed = !zoomed; invalidate() }
        return true
    }

    override fun onDraw(c: Canvas) {
        val bmp = MapImage.bitmap
        val w = width.toFloat(); val h = height.toFloat()
        val size = minOf(w, h) - 8 * d
        val half = WorldGen.HALF
        val p = game.player
        val scale = if (zoomed) 3f else 1f
        val viewSize = size * scale
        // center on player when zoomed
        val cx = if (zoomed) w / 2 - (p.pos.x / (2 * half)) * viewSize else w / 2
        val cy = if (zoomed) h / 2 - (p.pos.z / (2 * half)) * viewSize else h / 2
        fun mx(x: Float) = cx + x / (2 * half) * viewSize
        fun my(z: Float) = cy + z / (2 * half) * viewSize
        c.save()
        c.clipRect(w / 2 - size / 2, h / 2 - size / 2, w / 2 + size / 2, h / 2 + size / 2)
        paint.color = Color.rgb(20, 40, 50)
        c.drawRect(0f, 0f, w, h, paint)
        if (bmp != null) {
            dst.set(cx - viewSize / 2, cy - viewSize / 2, cx + viewSize / 2, cy + viewSize / 2)
            c.drawBitmap(bmp, null, dst, paint)
        } else {
            text.textAlign = Paint.Align.CENTER
            c.drawText("Charting the world...", w / 2, h / 2, text)
        }
        // POIs
        for (poi in WorldGen.pois) {
            val known = game.discovered.contains(poi.id)
            if (!known) continue
            val x = mx(poi.x); val y = my(poi.z)
            val color = when (poi.kind) {
                PoiKind.VAULT -> Color.rgb(255, 200, 90)
                PoiKind.RELAY -> Color.rgb(120, 220, 255)
                PoiKind.CROWN -> Color.rgb(255, 80, 110)
                PoiKind.RUIN -> if (game.logsRead.contains(poi.logIndex)) Color.rgb(120, 140, 140) else Color.rgb(100, 240, 220)
                PoiKind.CACHE, PoiKind.CAMP -> if (game.cachesOpened.contains(poi.id)) Color.rgb(130, 120, 100) else Color.rgb(240, 200, 120)
                PoiKind.WRECK -> Color.rgb(200, 140, 120)
                else -> if (game.spiresActive.contains(poi.kind.name)) Color.rgb(120, 255, 190) else Color.rgb(170, 190, 210)
            }
            paint.color = Color.BLACK
            c.drawCircle(x, y, 5.5f * d, paint)
            paint.color = color
            c.drawCircle(x, y, 4f * d, paint)
            if (poi.kind != PoiKind.RUIN && poi.kind != PoiKind.CACHE && poi.kind != PoiKind.WRECK || zoomed) {
                text.textAlign = Paint.Align.CENTER
                text.color = Color.argb(230, 255, 255, 255)
                c.drawText(poi.name, x, y - 7 * d, text)
            }
        }
        // home
        game.placed.firstOrNull { it.id == p.bedId }?.let {
            paint.color = Color.rgb(140, 230, 140)
            c.drawRect(mx(it.x) - 4 * d, my(it.z) - 4 * d, mx(it.x) + 4 * d, my(it.z) + 4 * d, paint)
        }
        // player arrow (yaw 0 = +Z = down on the map)
        val px = mx(p.pos.x); val py = my(p.pos.z)
        val fx = sin(p.yaw); val fz = cos(p.yaw)
        path.reset()
        path.moveTo(px + fx * 9 * d, py + fz * 9 * d)
        path.lineTo(px - fx * 5 * d + fz * 5 * d, py - fz * 5 * d - fx * 5 * d)
        path.lineTo(px - fx * 5 * d - fz * 5 * d, py - fz * 5 * d + fx * 5 * d)
        path.close()
        paint.color = Color.WHITE
        c.drawPath(path, paint)
        c.restore()
        text.textAlign = Paint.Align.LEFT
        text.color = Color.argb(200, 255, 255, 255)
        c.drawText("N", w / 2 - 4 * d, h / 2 - size / 2 + 14 * d, text)
        c.drawText(if (zoomed) "Tap to zoom out" else "Tap to zoom in", w / 2 - size / 2 + 6 * d, h / 2 + size / 2 - 6 * d, text)
    }
}
