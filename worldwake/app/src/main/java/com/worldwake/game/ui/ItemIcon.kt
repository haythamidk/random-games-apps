package com.worldwake.game.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.worldwake.game.game.Icon
import com.worldwake.game.game.Item
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Item pictures drawn as vectors, so there are no image assets to ship. */
class ItemIcon(private val item: Item) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()

    private fun shade(c: Int, k: Float): Int =
        Color.rgb((Color.red(c) * k).toInt().coerceIn(0, 255), (Color.green(c) * k).toInt().coerceIn(0, 255), (Color.blue(c) * k).toInt().coerceIn(0, 255))

    private fun poly(c: Canvas, cx: Float, cy: Float, rad: Float, n: Int, rot: Float, color: Int, squash: Float = 1f) {
        path.reset()
        for (i in 0 until n) {
            val a = rot + i * 6.2832f / n
            val x = cx + cos(a) * rad; val y = cy + sin(a) * rad * squash
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        p.style = Paint.Style.FILL; p.color = color
        c.drawPath(path, p)
    }

    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The flat shapes are drawn into a layer, then one diagonal light-to-dark wash and a soft highlight are laid
     * over only the painted pixels. Corners are rounded. The result reads as a lit object instead of flat tiles.
     */
    override fun draw(c: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        // contact shadow
        p.shader = null; p.pathEffect = null; p.style = Paint.Style.FILL
        p.color = Color.argb(70, 0, 0, 0)
        r.set(cx - s * 0.3f, cy + s * 0.3f, cx + s * 0.3f, cy + s * 0.42f); c.drawOval(r, p)
        val layer = c.saveLayer(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), null)
        p.pathEffect = android.graphics.CornerPathEffect(s * 0.05f)
        drawShapes(c, s, cx, cy)
        p.pathEffect = null
        shade.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
        shade.shader = android.graphics.LinearGradient(cx - s * 0.4f, cy - s * 0.42f, cx + s * 0.36f, cy + s * 0.42f,
            intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(110, 0, 0, 20)), floatArrayOf(0f, 0.48f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(b, shade)
        shade.shader = android.graphics.RadialGradient(cx - s * 0.14f, cy - s * 0.18f, s * 0.2f, Color.argb(95, 255, 255, 255), Color.argb(0, 255, 255, 255), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(b, shade)
        shade.shader = null; shade.xfermode = null
        c.restoreToCount(layer)
    }

    private fun drawShapes(c: Canvas, s: Float, cx: Float, cy: Float) {
        val col = item.color
        val dark = shade(col, 0.6f); val light = shade(col, 1.25f)
        p.style = Paint.Style.FILL
        when (item.icon) {
            Icon.NUGGET -> {
                poly(c, cx - s * 0.1f, cy + s * 0.08f, s * 0.26f, 7, 0.3f, dark)
                poly(c, cx + s * 0.12f, cy - s * 0.06f, s * 0.22f, 6, 0.9f, col)
                poly(c, cx + s * 0.06f, cy - s * 0.12f, s * 0.1f, 5, 0.2f, light)
            }
            Icon.SHARD -> {
                for (i in 0 until 3) {
                    val ox = (i - 1) * s * 0.16f
                    path.reset(); path.moveTo(cx + ox, cy - s * (0.34f - i * 0.05f)); path.lineTo(cx + ox + s * 0.09f, cy + s * 0.26f); path.lineTo(cx + ox - s * 0.09f, cy + s * 0.26f); path.close()
                    p.color = if (i == 1) light else if (i == 0) dark else col
                    c.drawPath(path, p)
                }
            }
            Icon.CUBE -> {
                poly(c, cx, cy + s * 0.02f, s * 0.32f, 6, 0.5236f, col)
                poly(c, cx, cy - s * 0.13f, s * 0.3f, 4, 0f, light, 0.5f)
                p.color = dark; path.reset(); path.moveTo(cx, cy + s * 0.02f); path.lineTo(cx, cy + s * 0.34f); path.lineTo(cx - s * 0.28f, cy + s * 0.18f); path.lineTo(cx - s * 0.28f, cy - s * 0.13f); path.close(); c.drawPath(path, p)
            }
            Icon.PLATE -> {
                for (i in 0 until 3) poly(c, cx, cy + s * (0.16f - i * 0.14f), s * 0.3f, 6, 0f, if (i == 2) light else if (i == 1) col else dark, 0.45f)
            }
            Icon.BAR -> {
                path.reset(); path.moveTo(cx - s * 0.32f, cy + s * 0.14f); path.lineTo(cx - s * 0.2f, cy - s * 0.12f); path.lineTo(cx + s * 0.32f, cy - s * 0.12f); path.lineTo(cx + s * 0.2f, cy + s * 0.14f); path.close()
                p.color = col; c.drawPath(path, p)
                p.color = light; c.drawRect(cx - s * 0.18f, cy - s * 0.12f, cx + s * 0.3f, cy - s * 0.07f, p)
                p.color = dark; c.drawRect(cx - s * 0.32f, cy + s * 0.14f, cx + s * 0.2f, cy + s * 0.2f, p)
            }
            Icon.PANE -> {
                p.color = Color.argb(150, Color.red(col), Color.green(col), Color.blue(col))
                r.set(cx - s * 0.26f, cy - s * 0.3f, cx + s * 0.26f, cy + s * 0.3f); c.drawRoundRect(r, s * 0.04f, s * 0.04f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.04f; p.color = light; c.drawRoundRect(r, s * 0.04f, s * 0.04f, p)
                c.drawLine(cx - s * 0.14f, cy + s * 0.1f, cx + s * 0.06f, cy - s * 0.16f, p)
            }
            Icon.CHIP -> {
                p.color = dark; r.set(cx - s * 0.24f, cy - s * 0.24f, cx + s * 0.24f, cy + s * 0.24f); c.drawRoundRect(r, s * 0.05f, s * 0.05f, p)
                p.color = col; r.set(cx - s * 0.13f, cy - s * 0.13f, cx + s * 0.13f, cy + s * 0.13f); c.drawRect(r, p)
                p.color = light
                for (i in -1..1) { c.drawRect(cx + i * s * 0.12f - s * 0.02f, cy - s * 0.34f, cx + i * s * 0.12f + s * 0.02f, cy - s * 0.24f, p); c.drawRect(cx + i * s * 0.12f - s * 0.02f, cy + s * 0.24f, cx + i * s * 0.12f + s * 0.02f, cy + s * 0.34f, p) }
            }
            Icon.GEAR -> {
                poly(c, cx, cy, s * 0.34f, 8, 0f, dark); poly(c, cx, cy, s * 0.34f, 8, 0.3927f, dark)
                p.color = col; c.drawCircle(cx, cy, s * 0.25f, p)
                p.color = shade(col, 0.4f); c.drawCircle(cx, cy, s * 0.09f, p)
            }
            Icon.CELL -> {
                p.color = dark; r.set(cx - s * 0.17f, cy - s * 0.3f, cx + s * 0.17f, cy + s * 0.32f); c.drawRoundRect(r, s * 0.05f, s * 0.05f, p)
                p.color = shade(col, 0.5f); c.drawRect(cx - s * 0.07f, cy - s * 0.36f, cx + s * 0.07f, cy - s * 0.3f, p)
                p.color = col; r.set(cx - s * 0.11f, cy - s * 0.1f, cx + s * 0.11f, cy + s * 0.26f); c.drawRect(r, p)
                p.color = light; path.reset(); path.moveTo(cx + s * 0.03f, cy - s * 0.24f); path.lineTo(cx - s * 0.07f, cy - s * 0.02f); path.lineTo(cx, cy - s * 0.02f); path.lineTo(cx - s * 0.03f, cy + s * 0.12f); path.lineTo(cx + s * 0.08f, cy - s * 0.1f); path.lineTo(cx + s * 0.01f, cy - s * 0.1f); path.close(); c.drawPath(path, p)
            }
            Icon.LENS -> {
                p.color = dark; c.drawCircle(cx, cy, s * 0.33f, p)
                p.color = col; c.drawCircle(cx, cy, s * 0.25f, p)
                p.color = Color.WHITE; c.drawCircle(cx - s * 0.08f, cy - s * 0.09f, s * 0.07f, p)
            }
            Icon.ORB -> {
                p.color = dark; c.drawCircle(cx, cy, s * 0.31f, p)
                p.color = col; c.drawCircle(cx - s * 0.03f, cy - s * 0.03f, s * 0.25f, p)
                p.color = light; c.drawCircle(cx - s * 0.1f, cy - s * 0.11f, s * 0.09f, p)
            }
            Icon.FLASK -> {
                p.color = shade(col, 0.5f); c.drawRect(cx - s * 0.07f, cy - s * 0.36f, cx + s * 0.07f, cy - s * 0.2f, p)
                p.color = dark; r.set(cx - s * 0.2f, cy - s * 0.22f, cx + s * 0.2f, cy + s * 0.34f); c.drawRoundRect(r, s * 0.1f, s * 0.1f, p)
                p.color = col; r.set(cx - s * 0.15f, cy - s * 0.02f, cx + s * 0.15f, cy + s * 0.29f); c.drawRoundRect(r, s * 0.07f, s * 0.07f, p)
            }
            Icon.CAPSULE -> {
                p.color = dark; r.set(cx - s * 0.32f, cy - s * 0.14f, cx + s * 0.32f, cy + s * 0.14f); c.drawRoundRect(r, s * 0.14f, s * 0.14f, p)
                p.color = col; r.set(cx - s * 0.32f, cy - s * 0.14f, cx, cy + s * 0.14f); c.drawRoundRect(r, s * 0.14f, s * 0.14f, p)
                p.color = Color.WHITE; c.drawCircle(cx - s * 0.16f, cy - s * 0.04f, s * 0.035f, p)
            }
            Icon.FOOD -> {
                p.color = dark; c.drawCircle(cx, cy + s * 0.05f, s * 0.28f, p)
                p.color = col; c.drawCircle(cx - s * 0.03f, cy + s * 0.02f, s * 0.23f, p)
                p.color = shade(col, 0.45f); c.drawRect(cx - s * 0.02f, cy - s * 0.36f, cx + s * 0.02f, cy - s * 0.2f, p)
                p.color = light; c.drawCircle(cx - s * 0.1f, cy - s * 0.05f, s * 0.06f, p)
            }
            Icon.POD -> {
                poly(c, cx, cy + s * 0.04f, s * 0.3f, 10, 0f, dark, 1.15f)
                poly(c, cx, cy + s * 0.04f, s * 0.22f, 10, 0f, col, 1.15f)
                p.color = light
                for (i in 0 until 5) { val a = i * 1.2566f; c.drawCircle(cx + cos(a) * s * 0.1f, cy + s * 0.04f + sin(a) * s * 0.12f, s * 0.035f, p) }
            }
            Icon.TANK -> {
                for (i in 0 until 2) {
                    val ox = (i - 0.5f) * s * 0.3f
                    p.color = dark; r.set(cx + ox - s * 0.12f, cy - s * 0.28f, cx + ox + s * 0.12f, cy + s * 0.32f); c.drawRoundRect(r, s * 0.1f, s * 0.1f, p)
                    p.color = col; r.set(cx + ox - s * 0.07f, cy - s * 0.2f, cx + ox + s * 0.07f, cy + s * 0.26f); c.drawRoundRect(r, s * 0.06f, s * 0.06f, p)
                }
                p.color = shade(col, 0.4f); c.drawRect(cx - s * 0.15f, cy - s * 0.36f, cx + s * 0.15f, cy - s * 0.3f, p)
            }
            Icon.PACK -> {
                p.color = dark; r.set(cx - s * 0.26f, cy - s * 0.28f, cx + s * 0.26f, cy + s * 0.32f); c.drawRoundRect(r, s * 0.1f, s * 0.1f, p)
                p.color = col; r.set(cx - s * 0.18f, cy - s * 0.02f, cx + s * 0.18f, cy + s * 0.24f); c.drawRoundRect(r, s * 0.05f, s * 0.05f, p)
                p.color = light; c.drawRect(cx - s * 0.2f, cy - s * 0.18f, cx + s * 0.2f, cy - s * 0.12f, p)
            }
            Icon.BOOT -> {
                path.reset(); path.moveTo(cx - s * 0.2f, cy - s * 0.32f); path.lineTo(cx + s * 0.02f, cy - s * 0.32f); path.lineTo(cx + s * 0.04f, cy + s * 0.06f); path.lineTo(cx + s * 0.32f, cy + s * 0.16f); path.lineTo(cx + s * 0.32f, cy + s * 0.3f); path.lineTo(cx - s * 0.22f, cy + s * 0.3f); path.close()
                p.color = col; c.drawPath(path, p)
                p.color = dark; c.drawRect(cx - s * 0.24f, cy + s * 0.24f, cx + s * 0.34f, cy + s * 0.32f, p)
            }
            Icon.JET -> {
                for (i in 0 until 2) {
                    val ox = (i - 0.5f) * s * 0.3f
                    p.color = dark; r.set(cx + ox - s * 0.11f, cy - s * 0.3f, cx + ox + s * 0.11f, cy + s * 0.12f); c.drawRoundRect(r, s * 0.08f, s * 0.08f, p)
                    path.reset(); path.moveTo(cx + ox - s * 0.09f, cy + s * 0.14f); path.lineTo(cx + ox + s * 0.09f, cy + s * 0.14f); path.lineTo(cx + ox, cy + s * 0.38f); path.close()
                    p.color = col; c.drawPath(path, p)
                }
            }
            Icon.SCAN -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.05f
                p.color = dark; c.drawCircle(cx, cy, s * 0.3f, p)
                p.color = col; c.drawCircle(cx, cy, s * 0.18f, p)
                p.style = Paint.Style.FILL; c.drawCircle(cx, cy, s * 0.06f, p)
                p.color = light; c.drawCircle(cx + s * 0.16f, cy - s * 0.14f, s * 0.05f, p)
            }
            Icon.LAMP -> {
                path.reset(); path.moveTo(cx - s * 0.1f, cy - s * 0.3f); path.lineTo(cx + s * 0.1f, cy - s * 0.3f); path.lineTo(cx + s * 0.28f, cy + s * 0.3f); path.lineTo(cx - s * 0.28f, cy + s * 0.3f); path.close()
                p.color = Color.argb(110, Color.red(col), Color.green(col), Color.blue(col)); c.drawPath(path, p)
                p.color = dark; c.drawRect(cx - s * 0.13f, cy - s * 0.36f, cx + s * 0.13f, cy - s * 0.22f, p)
                p.color = col; c.drawCircle(cx, cy - s * 0.2f, s * 0.07f, p)
            }
        }
        p.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
