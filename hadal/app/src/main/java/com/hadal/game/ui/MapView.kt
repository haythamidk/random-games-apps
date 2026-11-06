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
    private var activeId = -1
    private val scaler = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(0.8f, 8f)
            clampPan()
            invalidate()
            return true
        }
    })

    init { isFocusable = true }

    /** Keeps the chart from being dragged completely out of view. */
    private fun clampPan() {
        val lim = min(width, height) * zoom * 0.5f
        panX = panX.coerceIn(-lim, lim); panY = panY.coerceIn(-lim, lim)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { activeId = e.getPointerId(0); lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // a second finger starts a pinch: restart panning from the first finger so nothing jumps afterwards
                val i = e.findPointerIndex(activeId).takeIf { it >= 0 } ?: 0
                activeId = e.getPointerId(i); lastX = e.getX(i); lastY = e.getY(i)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activeId)
                if (i >= 0) {
                    if (!scaler.isInProgress && e.pointerCount == 1) { panX += e.getX(i) - lastX; panY += e.getY(i) - lastY; clampPan(); invalidate() }
                    lastX = e.getX(i); lastY = e.getY(i)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // hand panning over to a finger that is still down
                if (e.getPointerId(e.actionIndex) == activeId) {
                    val ni = if (e.actionIndex == 0) 1 else 0
                    activeId = e.getPointerId(ni); lastX = e.getX(ni); lastY = e.getY(ni)
                } else { val i = e.findPointerIndex(activeId); if (i >= 0) { lastX = e.getX(i); lastY = e.getY(i) } }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeId = -1
        }
        return true
    }

    fun zoomBy(f: Float) { zoom = (zoom * f).coerceIn(0.8f, 8f); clampPan(); invalidate() }

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
        for (b in game.bases) {
            if (b.index == 0) continue
            val x = sx(b.x); val y = sy(b.z)
            paint.color = Color.rgb(255, 196, 90)
            c.drawCircle(x, y, 6 * dp, paint)
            text.color = Color.rgb(255, 196, 90)
            c.drawText(b.name, x + 9 * dp, y + 4 * dp, text)
        }
        for ((i, e) in SeaGen.echoStones.withIndex()) {
            if (!game.story.echoes.contains(i)) continue
            paint.color = Color.rgb(140, 230, 255)
            c.drawRect(sx(e.x) - 3 * dp, sy(e.z) - 3 * dp, sx(e.x) + 3 * dp, sy(e.z) + 3 * dp, paint)
        }
        // search areas: the real thing is somewhere inside the circle, never at its center
        fun area(t: com.hadal.game.game.Track?, col: Int, tag: String) {
            if (t == null) return
            val rr = max(t.radius * scale, 16 * dp)
            val x0 = sx(t.x); val y0 = sy(t.z)
            paint.style = Paint.Style.FILL; paint.color = Color.argb(45, Color.red(col), Color.green(col), Color.blue(col)); paint.pathEffect = null
            c.drawCircle(x0, y0, rr, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * dp; paint.color = col
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10 * dp, 7 * dp), 0f)
            c.drawCircle(x0, y0, rr, paint)
            paint.style = Paint.Style.FILL; paint.pathEffect = null
            text.color = col
            c.drawText("$tag: ${t.label}", x0 - rr * 0.7f, y0 - rr - 6 * dp, text)
        }
        area(game.tracked, Color.rgb(90, 230, 255), "Looking for")
        area(game.goal, Color.rgb(255, 214, 80), "Goal")
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
        c.drawText("Drag to move, pinch to zoom.   Gold circle: your goal.   Blue circle: what you are looking for.", 14 * dp, height - 14 * dp, text)
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
