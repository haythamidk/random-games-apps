package com.hadal.game.ui

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
import com.hadal.game.game.GameState
import com.hadal.game.game.Item
import com.hadal.game.world.PoiType
import com.hadal.game.world.SeaGen
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Bathymetric chart of the explored ocean, generated once in the background. North (+z) is up, east (-x) right. */
object MapImage {
    const val SIZE = 320
    const val EXTENT = 3200f
    @Volatile var bitmap: Bitmap? = null

    fun generate() {
        if (bitmap != null) return
        val px = IntArray(SIZE * SIZE)
        for (j in 0 until SIZE) for (i in 0 until SIZE) {
            val wx = -((i + 0.5f) / SIZE * 2f - 1f) * EXTENT
            val wz = (1f - (j + 0.5f) / SIZE * 2f) * EXTENT
            val h = SeaGen.height(wx, wz)
            val d = -h
            // shallow turquoise to midnight blue to black trench
            val t = (d / 1700f).coerceIn(0f, 1f)
            val r = (30 * exp(-t * 6f) + 4).toInt()
            val g = (150 * exp(-t * 3.2f) + 10).toInt()
            val b = (190 * exp(-t * 1.6f) + 18).toInt()
            // contour lines every 100 m
            val c = (d % 100f) < 4f
            px[j * SIZE + i] = if (c) Color.rgb(min(255, r + 25), min(255, g + 30), min(255, b + 30)) else Color.rgb(r, g, b)
        }
        bitmap = Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }
}

@SuppressLint("ViewConstructor")
class MapView(ctx: Context, private val game: GameState) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12 * dp }
    private val path = Path()
    private var zoom = 1.6f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var pinch = 0f

    init { isFocusable = true }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) pinch = dist(e)
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val dd = dist(e)
                    if (pinch > 0f) zoom = (zoom * dd / pinch).coerceIn(0.8f, 8f)
                    pinch = dd
                } else {
                    panX += e.x - lastX; panY += e.y - lastY
                    lastX = e.x; lastY = e.y
                }
                invalidate()
            }
        }
        return true
    }

    private fun dist(e: MotionEvent): Float { val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1); return kotlin.math.sqrt(dx * dx + dy * dy) }

    fun zoomBy(f: Float) { zoom = (zoom * f).coerceIn(0.8f, 8f); invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(2, 8, 12))
        val sub = game.sub.pos
        val size = min(width, height) * zoom
        // center on the sub
        val scale = size / (MapImage.EXTENT * 2f)
        val cx = width / 2f + panX + sub.x * scale
        val cy = height / 2f + panY + sub.z * scale
        fun sx(wx: Float) = cx - wx * scale
        fun sy(wz: Float) = cy - wz * scale
        MapImage.bitmap?.let { bmp ->
            paint.alpha = 255
            c.drawBitmap(bmp, null, RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), paint)
        }
        // fog over undiscovered parts: dim everything, then reveal around discovered places and the sub
        paint.color = Color.argb(150, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        for (p in SeaGen.pois) {
            if (!game.discovered.contains(p.id)) continue
            val x = sx(p.x); val y = sy(p.z)
            val col = when (p.type) {
                PoiType.STATION -> Color.rgb(255, 196, 90)
                PoiType.CHOIR -> Color.rgb(160, 220, 255)
                PoiType.BEACON -> if (game.story.beacons.contains(p.name.last().digitToInt() - 1)) Color.rgb(120, 230, 255) else Color.rgb(120, 140, 150)
                else -> Color.rgb(240, 230, 200)
            }
            paint.color = col
            c.drawCircle(x, y, 5 * dp, paint)
            text.color = col
            c.drawText(p.name, x + 8 * dp, y + 4 * dp, text)
        }
        for ((i, e) in SeaGen.echoStones.withIndex()) {
            if (!game.story.echoes.contains(i)) continue
            paint.color = Color.rgb(140, 230, 255)
            c.drawRect(sx(e.x) - 3 * dp, sy(e.z) - 3 * dp, sx(e.x) + 3 * dp, sy(e.z) + 3 * dp, paint)
        }
        // sub arrow (forward is +z at yaw 0, east is -x)
        val x = sx(sub.x); val y = sy(sub.z)
        val yaw = game.sub.yaw
        val fx = -sin(yaw); val fy = -cos(yaw)
        paint.color = Color.WHITE
        path.reset()
        path.moveTo(x + fx * 10 * dp, y + fy * 10 * dp)
        path.lineTo(x - fx * 6 * dp + fy * 6 * dp, y - fy * 6 * dp - fx * 6 * dp)
        path.lineTo(x - fx * 6 * dp - fy * 6 * dp, y - fy * 6 * dp + fx * 6 * dp)
        path.close()
        c.drawPath(path, paint)
        text.color = Color.argb(200, 200, 230, 255)
        c.drawText("N", width - 28 * dp, 24 * dp, text)
        c.drawText("Drag to pan, pinch to zoom.  Depth ${(-game.sub.pos.y).toInt()} m", 14 * dp, height - 14 * dp, text)
        if (!game.story.relayFixed) c.drawText("Repair the Shelf Relay to chart the area.", 14 * dp, 22 * dp, text)
    }
}

/** Small item icons drawn with Canvas. */
object ItemIcons {
    private val cache = HashMap<Item, Bitmap>()

    @Synchronized
    fun get(i: Item, px: Int = 64): Bitmap = cache.getOrPut(i) { draw(i, px) }

    private fun draw(i: Item, n: Int): Bitmap {
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = n / 64f
        p.color = Color.argb(255, 16, 26, 32)
        c.drawRoundRect(RectF(2 * s, 2 * s, 62 * s, 62 * s), 12 * s, 12 * s, p)
        p.color = i.color
        val path = Path()
        when (i) {
            Item.SCRAP -> { c.save(); c.rotate(-20f, 32 * s, 32 * s); c.drawRect(14 * s, 24 * s, 50 * s, 40 * s, p); p.color = Color.rgb(90, 70, 50); c.drawRect(14 * s, 30 * s, 50 * s, 33 * s, p); c.restore() }
            Item.COPPER -> { p.color = Color.rgb(90, 90, 92); c.drawCircle(32 * s, 34 * s, 20 * s, p); p.color = i.color; c.drawCircle(26 * s, 28 * s, 6 * s, p); c.drawCircle(38 * s, 38 * s, 5 * s, p); c.drawCircle(36 * s, 24 * s, 3 * s, p) }
            Item.NODULE -> { c.drawCircle(24 * s, 36 * s, 11 * s, p); c.drawCircle(40 * s, 30 * s, 9 * s, p); c.drawCircle(36 * s, 44 * s, 7 * s, p) }
            Item.CRYSTAL -> { path.moveTo(32 * s, 8 * s); path.lineTo(44 * s, 30 * s); path.lineTo(36 * s, 56 * s); path.lineTo(26 * s, 56 * s); path.lineTo(20 * s, 30 * s); path.close(); c.drawPath(path, p) }
            Item.PEARL -> { c.drawCircle(32 * s, 32 * s, 15 * s, p); p.color = Color.WHITE; c.drawCircle(27 * s, 27 * s, 4 * s, p) }
            Item.BIOSAMPLE -> { c.drawOval(RectF(22 * s, 14 * s, 42 * s, 46 * s), p); p.color = Color.rgb(60, 110, 90); c.drawRect(30 * s, 46 * s, 34 * s, 56 * s, p) }
            Item.ALLOY -> { c.save(); c.rotate(15f, 32 * s, 32 * s); c.drawRect(22 * s, 12 * s, 42 * s, 52 * s, p); p.color = Color.rgb(80, 180, 255); c.drawRect(30 * s, 16 * s, 34 * s, 48 * s, p); c.restore() }
            Item.FLARE -> { p.color = Color.rgb(120, 120, 120); c.drawRect(28 * s, 26 * s, 36 * s, 54 * s, p); p.color = i.color; c.drawCircle(32 * s, 20 * s, 9 * s, p) }
            Item.REPAIR_KIT -> { c.drawRoundRect(RectF(14 * s, 20 * s, 50 * s, 48 * s), 5 * s, 5 * s, p); p.color = Color.WHITE; c.drawRect(29 * s, 25 * s, 35 * s, 43 * s, p); c.drawRect(23 * s, 31 * s, 41 * s, 37 * s, p) }
            Item.DATA_CORE -> { c.drawRect(20 * s, 16 * s, 44 * s, 48 * s, p); p.color = Color.rgb(20, 40, 50); for (k in 0 until 4) c.drawRect(24 * s, (21 + k * 6) * s, 40 * s, (23 + k * 6) * s, p) }
        }
        return b
    }
}
