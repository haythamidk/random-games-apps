package scrapline.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Align
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** UI palette. */
object Ui {
    val INK = Color.valueOf("14100CFF")
    val PANEL = Color.valueOf("1C1611C8")
    val PANEL_SOLID = Color.valueOf("241C15FF")
    val SLOT = Color.valueOf("3A2E24E6")
    val LINE = Color.valueOf("F1E6D255")
    val BONE = Color.valueOf("F1E6D2FF")
    val MUTED = Color.valueOf("B5A58EFF")
    val AMBER = Color.valueOf("F2A93BFF")
    val TEAL = Color.valueOf("5FD0C5FF")
    val CORAL = Color.valueOf("EF6F5EFF")
    val DIM = Color.valueOf("0A0806A6")
    val OFF = Color.valueOf("6B5C4CFF")
}

/** The three text sizes the UI uses, in logical units. */
enum class Type(val size: Int) { SMALL(11), BODY(13), TITLE(16) }

/**
 * Immediate-mode 2D drawing for the UI, in logical units: the screen is always
 * 390 units tall and as wide as its aspect ratio makes it, with the origin at
 * the top left. Shapes are drawn as they are called; text is queued and drawn
 * when the layer is flushed, so one layer's text always sits on its own shapes.
 */
class Canvas {
    val shapes = ShapeRenderer()
    private val batch = SpriteBatch()
    private val layout = GlyphLayout()
    private val proj = Matrix4()
    private val fonts = arrayOfNulls<BitmapFont>(Type.values().size)
    private var fontScale = 0f

    var width = 844f
        private set
    val height = 390f
    /** Screen pixels per logical unit. */
    var scale = 1f
        private set
    /** Insets for notches and rounded corners, logical units. */
    var safeLeft = 0f
    var safeRight = 0f

    // Queued text.
    private val qText = ArrayList<String>()
    private val qX = ArrayList<Float>()
    private val qY = ArrayList<Float>()
    private val qW = ArrayList<Float>()
    private val qType = ArrayList<Type>()
    private val qColor = ArrayList<Color>()
    private val qAlign = ArrayList<Int>()
    private var drawing = false

    fun resize(pxWidth: Int, pxHeight: Int) {
        scale = pxHeight / height
        width = pxWidth / scale
        proj.setToOrtho(0f, width, height, 0f, -1f, 1f)
        if (scale != fontScale) {
            fontScale = scale
            val gen = FreeTypeFontGenerator(Gdx.files.internal("inter-semibold.otf"))
            for (t in Type.values()) {
                fonts[t.ordinal]?.dispose()
                val p = FreeTypeFontGenerator.FreeTypeFontParameter().apply {
                    size = (t.size * scale).toInt().coerceAtLeast(8)
                    flip = true
                    hinting = FreeTypeFontGenerator.Hinting.Slight
                    characters = FreeTypeFontGenerator.DEFAULT_CHARS + "×·−"
                }
                fonts[t.ordinal] = gen.generateFont(p).apply {
                    setUseIntegerPositions(false)
                    data.setScale(1f / scale)
                }
            }
            gen.dispose()
        }
    }

    fun begin() {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.projectionMatrix = proj
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        drawing = true
    }

    /** Draws the queued text on top of the shapes drawn so far, then carries on with shapes. */
    fun flush() {
        shapes.end()
        if (qText.isNotEmpty()) {
            batch.projectionMatrix = proj
            batch.begin()
            for (i in qText.indices) {
                val font = fonts[qType[i].ordinal]!!
                font.color = qColor[i]
                layout.setText(font, qText[i], qColor[i], qW[i], qAlign[i], qW[i] > 0f)
                font.draw(batch, layout, qX[i], qY[i])
            }
            batch.end()
            qText.clear(); qX.clear(); qY.clear(); qW.clear(); qType.clear(); qColor.clear(); qAlign.clear()
        }
        Gdx.gl.glEnable(GL20.GL_BLEND)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
    }

    fun end() {
        flush()
        shapes.end()
        Gdx.gl.glDisable(GL20.GL_BLEND)
        drawing = false
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, c: Color) {
        shapes.color = c
        shapes.rect(x, y, w, h)
    }

    /** Rounded rectangle. */
    fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color) {
        val rr = minOf(r, w / 2, h / 2)
        shapes.color = c
        shapes.rect(x + rr, y, w - 2 * rr, h)
        shapes.rect(x, y + rr, rr, h - 2 * rr)
        shapes.rect(x + w - rr, y + rr, rr, h - 2 * rr)
        // Quarter discs only, so nothing is drawn twice and translucent panels stay even.
        shapes.arc(x + rr, y + rr, rr, 180f, 90f, 6)
        shapes.arc(x + w - rr, y + rr, rr, 270f, 90f, 6)
        shapes.arc(x + rr, y + h - rr, rr, 90f, 90f, 6)
        shapes.arc(x + w - rr, y + h - rr, rr, 0f, 90f, 6)
    }

    /** Rounded rectangle with a border of `line` colour. */
    fun panel(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Color, line: Color? = null, lineWidth: Float = 1.5f) {
        rrect(x, y, w, h, r, fill)
        if (line != null) outline(x, y, w, h, r, lineWidth, line)
    }

    /** Border of a rounded rectangle, drawn as four edges and four corner arcs made of short lines. */
    fun outline(x: Float, y: Float, w: Float, h: Float, r: Float, width: Float, c: Color) {
        val rr = minOf(r, w / 2, h / 2)
        shapes.color = c
        shapes.rectLine(x + rr, y, x + w - rr, y, width)
        shapes.rectLine(x + rr, y + h, x + w - rr, y + h, width)
        shapes.rectLine(x, y + rr, x, y + h - rr, width)
        shapes.rectLine(x + w, y + rr, x + w, y + h - rr, width)
        corner(x + rr, y + rr, rr, 180f, width)
        corner(x + w - rr, y + rr, rr, 270f, width)
        corner(x + rr, y + h - rr, rr, 90f, width)
        corner(x + w - rr, y + h - rr, rr, 0f, width)
    }

    private fun corner(cx: Float, cy: Float, r: Float, startDeg: Float, width: Float) {
        var px = cx + r * cos(Math.toRadians(startDeg.toDouble())).toFloat()
        var py = cy + r * sin(Math.toRadians(startDeg.toDouble())).toFloat()
        for (s in 1..5) {
            val a = Math.toRadians((startDeg + 18f * s).toDouble())
            val nx = cx + r * cos(a).toFloat()
            val ny = cy + r * sin(a).toFloat()
            shapes.rectLine(px, py, nx, ny, width)
            px = nx; py = ny
        }
    }

    fun circle(cx: Float, cy: Float, r: Float, c: Color) {
        shapes.color = c
        shapes.circle(cx, cy, r, 24)
    }

    fun ring(cx: Float, cy: Float, r: Float, width: Float, c: Color, fill: Color) {
        circle(cx, cy, r, c)
        circle(cx, cy, r - width, fill)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, c: Color) {
        shapes.color = c
        shapes.rectLine(x1, y1, x2, y2, width)
    }

    fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, c: Color) {
        shapes.color = c
        shapes.triangle(x1, y1, x2, y2, x3, y3)
    }

    /** Smooth curve between two ports, as short straight pieces. */
    fun curve(x0: Float, y0: Float, x3: Float, y3: Float, width: Float, c: Color) {
        val k = maxOf(30f, kotlin.math.abs(x3 - x0) * 0.5f)
        var px = x0
        var py = y0
        for (s in 1..18) {
            val t = s / 18f
            val u = 1 - t
            val x = u * u * u * x0 + 3 * u * u * t * (x0 + k) + 3 * u * t * t * (x3 - k) + t * t * t * x3
            val y = u * u * u * y0 + 3 * u * u * t * y0 + 3 * u * t * t * y3 + t * t * t * y3
            line(px, py, x, y, width, c)
            px = x; py = y
        }
    }

    /** Rotated rectangle around its centre; angle in radians. Used by icons. */
    fun bar(cx: Float, cy: Float, len: Float, thick: Float, angle: Float, c: Color) {
        val dx = cos(angle) * len / 2
        val dy = sin(angle) * len / 2
        line(cx - dx, cy - dy, cx + dx, cy + dy, thick, c)
    }

    /** Queues text. `y` is the top of the line. With `w` > 0 the text wraps and aligns inside that width. */
    fun text(s: String, x: Float, y: Float, type: Type, c: Color, align: Int = Align.left, w: Float = 0f) {
        if (s.isEmpty()) return
        qText.add(s); qX.add(x); qY.add(y); qW.add(w); qType.add(type); qColor.add(c); qAlign.add(align)
    }

    fun textWidth(s: String, type: Type): Float {
        layout.setText(fonts[type.ordinal]!!, s)
        return layout.width
    }

    /** Height a wrapped block of text would take. */
    fun textHeight(s: String, type: Type, w: Float): Float {
        layout.setText(fonts[type.ordinal]!!, s, Color.WHITE, w, Align.left, true)
        return layout.height
    }

    fun dispose() {
        shapes.dispose()
        batch.dispose()
        fonts.forEach { it?.dispose() }
    }
}

/** Distance from a point to a segment; used for hit-testing wires. */
fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 < 1e-6f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
    return hypot(px - (ax + dx * t), py - (ay + dy * t))
}

