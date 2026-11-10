package com.worldwake.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.worldwake.game.engine.Mat4
import com.worldwake.game.engine.PI_F
import com.worldwake.game.engine.angleDiff
import com.worldwake.game.game.ControlScheme
import com.worldwake.game.game.Fmt
import com.worldwake.game.game.GameState
import com.worldwake.game.game.Item
import com.worldwake.game.game.TargetKind
import com.worldwake.game.input.Action
import com.worldwake.game.input.InputState
import com.worldwake.game.world.Planet
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas HUD drawn over the GL view, plus the on-screen touch controls.
 *
 * Left thumb: a joystick that appears where you put your thumb down. Right thumb: drag anywhere to look, tap to
 * use or place what you are aiming at. Buttons bottom right; suit gauges top left; planet read-out top right.
 */
@SuppressLint("ViewConstructor")
class HudView(ctx: Context, private val game: GameState, private val input: InputState) : View(ctx) {
    private val d = resources.displayMetrics.density
    var onMenu: (() -> Unit)? = null
    var onPack: (() -> Unit)? = null
    var onBuild: (() -> Unit)? = null
    /** Space kept clear of the camera cutout and rounded corners on each side, in pixels. */
    private var safeL = 0f
    private var safeR = 0f

    fun setSafeArea(left: Int, right: Int) {
        safeL = left.toFloat(); safeR = right.toFloat()
        if (width > 0) onSizeChanged(width, height, width, height)
    }

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12.5f * d; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * d; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 10f * d; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 27f * d; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val path = Path()
    private val proj = FloatArray(4)
    private val vpCopy = FloatArray(16)

    private val TEAL = Color.rgb(89, 240, 224)
    private val GOLD = Color.rgb(255, 208, 96)
    private val ROSE = Color.rgb(255, 106, 140)
    private val PANEL = Color.argb(150, 8, 12, 24)

    private class Btn(val id: String, var x: Float = 0f, var y: Float = 0f, var r: Float = 0f) {
        var pointer = -1
        var visible = true
        fun hit(px: Float, py: Float) = visible && hypot(px - x, py - y) < r * 1.18f
    }

    private val btnUse = Btn("use")
    private val btnJump = Btn("jump")
    private val btnRotate = Btn("rotate")
    private val btnCancel = Btn("cancel")
    private val btnMenu = Btn("menu")
    private val btnPack = Btn("pack")
    private val btnBuild = Btn("build")
    private val buttons = listOf(btnUse, btnJump, btnRotate, btnCancel, btnMenu, btnPack, btnBuild)

    private var joyPointer = -1
    private var joyCx = 0f; private var joyCy = 0f
    private val joyR get() = 58f * d
    private var joyX = 0f; private var joyY = 0f
    private var lookPointer = -1
    private var lookLastX = 0f; private var lookLastY = 0f
    private var lookDownX = 0f; private var lookDownY = 0f
    private var lookDownTime = 0L
    private var lookMoved = 0f
    private var subtitleLayout: StaticLayout? = null
    private var subtitleText = ""
    private var objectiveLayout: StaticLayout? = null
    private var objectiveText = ""
    private var feedLayout: StaticLayout? = null
    private var feedTextCache = ""
    private var t = 0f
    private var lastNs = 0L

    init { isClickable = true; isFocusable = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val fw = w.toFloat() - safeR; val fh = h.toFloat()
        fun place(b: Btn, x: Float, y: Float, r: Float) { b.x = x; b.y = y; b.r = r * d }
        place(btnUse, fw - 86 * d, fh - 92 * d, 42f)
        place(btnJump, fw - 178 * d, fh - 52 * d, 30f)
        place(btnRotate, fw - 186 * d, fh - 136 * d, 28f)
        place(btnCancel, fw - 52 * d, fh - 186 * d, 24f)
        place(btnMenu, fw - 28 * d, 30 * d, 19f)
        place(btnPack, fw - 76 * d, 30 * d, 20f)
        place(btnBuild, fw - 126 * d, 30 * d, 20f)
        subtitleLayout = null; objectiveLayout = null; feedLayout = null
    }

    private val touchMode get() = game.settings.controls == ControlScheme.TOUCH
    private val building get() = game.buildType != null || game.deconMode

    // ------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(e.getPointerId(idx), e.getX(idx), e.getY(idx))
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(idx), false)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) up(e.getPointerId(i), true)
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        btnRotate.visible = game.buildType != null
        btnCancel.visible = building
        for (b in buttons) {
            if (!touchMode && b !== btnMenu && b !== btnPack && b !== btnBuild) continue
            if (b.pointer == -1 && b.hit(x, y)) {
                b.pointer = id
                press(b)
                return
            }
        }
        if (game.player.dead) return
        if (x < width * 0.42f && y > height * 0.3f && joyPointer == -1) {
            joyPointer = id
            joyCx = max(x, joyR + 8 * d + safeL); joyCy = min(y, height - joyR - 8 * d)
            joyX = 0f; joyY = 0f
            input.setTouchMove(0f, 0f)
            return
        }
        if (lookPointer == -1) {
            lookPointer = id
            lookLastX = x; lookLastY = y
            lookDownX = x; lookDownY = y
            lookDownTime = System.nanoTime()
            lookMoved = 0f
        }
    }

    private fun press(b: Btn) {
        when (b) {
            btnUse -> input.push(if (game.buildType != null || game.deconMode) Action.PLACE else Action.INTERACT)
            btnJump -> { input.push(Action.JUMP); input.touchJump = true }
            btnRotate -> input.push(Action.ROTATE)
            btnCancel -> input.push(Action.CANCEL)
            btnMenu -> onMenu?.invoke()
            btnPack -> onPack?.invoke()
            btnBuild -> onBuild?.invoke()
            else -> {}
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (id == joyPointer) {
            var dx = (x - joyCx) / joyR; var dy = (y - joyCy) / joyR
            val l = hypot(dx, dy)
            if (l > 1f) {
                dx /= l; dy /= l
                // the stick follows a thumb that drifts past its edge
                joyCx = x - dx * joyR; joyCy = y - dy * joyR
            }
            joyX = dx; joyY = dy
            val dead = 0.12f
            val m = hypot(dx, dy)
            if (m < dead) input.setTouchMove(0f, 0f) else {
                val k = (m - dead) / (1f - dead) / m
                input.setTouchMove(dx * k, -dy * k)
            }
        } else if (id == lookPointer) {
            val dx = x - lookLastX; val dy = y - lookLastY
            lookLastX = x; lookLastY = y
            lookMoved += abs(dx) + abs(dy)
            input.addLook(dx / d, dy / d)
        }
    }

    private fun up(id: Int, cancelled: Boolean) {
        for (b in buttons) if (b.pointer == id) {
            b.pointer = -1
            if (b === btnJump) input.touchJump = false
        }
        if (id == joyPointer) { joyPointer = -1; joyX = 0f; joyY = 0f; input.setTouchMove(0f, 0f) }
        if (id == lookPointer) {
            lookPointer = -1
            // a quick tap on the view uses or places whatever is under the crosshair
            val quick = System.nanoTime() - lookDownTime < 230_000_000L
            if (!cancelled && quick && lookMoved < 12 * d && touchMode) {
                if (game.buildType != null || game.deconMode) input.push(Action.PLACE)
                else if (game.targetKind != TargetKind.NONE) input.push(Action.INTERACT)
                else if (game.subtitle.isNotEmpty()) input.push(Action.SKIP_DIALOG)
            }
        }
    }

    fun releaseAll() {
        for (b in buttons) b.pointer = -1
        joyPointer = -1; lookPointer = -1; joyX = 0f; joyY = 0f
        input.setTouchMove(0f, 0f)
        input.touchJump = false
    }

    // ------------------------------------------------------------------ drawing

    private fun panel(c: Canvas, l: Float, tp: Float, r: Float, b: Float, rad: Float = 10f) {
        rect.set(l, tp, r, b)
        fill.color = PANEL
        c.drawRoundRect(rect, rad * d, rad * d, fill)
    }

    private fun gauge(c: Canvas, x: Float, y: Float, w: Float, frac: Float, color: Int, label: String, value: String, warn: Boolean) {
        val h = 15f * d
        rect.set(x, y, x + w, y + h)
        fill.color = Color.argb(150, 8, 12, 24)
        c.drawRoundRect(rect, h / 2, h / 2, fill)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.01f) {
            rect.set(x + 2 * d, y + 2 * d, x + 2 * d + (w - 4 * d) * f, y + h - 2 * d)
            fill.color = if (warn && (t * 3f).toInt() % 2 == 0) ROSE else color
            c.drawRoundRect(rect, h / 2, h / 2, fill)
        }
        small.color = Color.WHITE; small.textAlign = Paint.Align.LEFT
        small.setShadowLayer(2 * d, 0f, 0f, Color.BLACK)
        c.drawText(label, x + 8 * d, y + h - 4.2f * d, small)
        small.textAlign = Paint.Align.RIGHT
        c.drawText(value, x + w - 8 * d, y + h - 4.2f * d, small)
        small.clearShadowLayer()
    }

    private fun layout(s: String, width: Int, paint: TextPaint, align: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, paint, max(10, width)).setAlignment(align).setLineSpacing(0f, 1.08f).build()

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        t += dt
        val w = width.toFloat(); val h = height.toFloat()
        val g = game
        val p = g.player
        if (!g.started) { postInvalidateOnAnimation(); return }
        if (!g.worldReady) {
            c.drawColor(Color.argb(255, 3, 4, 10))
            big.textAlign = Paint.Align.CENTER; big.color = Color.WHITE
            c.drawText("Y S M E R E", w / 2, h / 2 - 6 * d, big)
            text.textAlign = Paint.Align.CENTER; text.color = Color.argb(200, 190, 200, 220)
            c.drawText("landing" + ".".repeat(1 + (t * 2f).toInt() % 3), w / 2, h / 2 + 22 * d, text)
            postInvalidateOnAnimation(); return
        }
        val v = g.env.v
        val breathable = v.breathable && !p.underwater

        // ---- danger vignette
        val o2Frac = p.o2 / p.o2Max
        val danger = max(if (!p.sheltered && !breathable) (1f - o2Frac / 0.2f) else 0f, 1f - p.health / 45f).coerceIn(0f, 1f)
        if (danger > 0.02f) {
            val a = (danger * (0.55f + 0.45f * sin(t * 5f)) * 120).toInt().coerceIn(0, 160)
            stroke.color = Color.argb(a, 255, 60, 90); stroke.strokeWidth = 46 * d
            c.drawRect(0f, 0f, w, h, stroke)
            stroke.strokeWidth = 1.6f * d
        }

        // ---- suit gauges, top left
        val gx = 14 * d + safeL; var gy = 12 * d
        val gw = 150 * d
        gauge(c, gx, gy, gw, o2Frac, TEAL, "AIR", if (breathable) "open" else p.o2.toInt().toString(), o2Frac < 0.2f && !p.sheltered && !breathable)
        gy += 19 * d
        gauge(c, gx, gy, gw, p.water / 100f, Color.rgb(96, 170, 255), "WATER", p.water.toInt().toString(), p.water < 15f)
        gy += 19 * d
        gauge(c, gx, gy, gw, p.food / 100f, Color.rgb(255, 150, 120), "FOOD", p.food.toInt().toString(), p.food < 15f)
        gy += 19 * d
        if (p.health < 99.5f) { gauge(c, gx, gy, gw, p.health / 100f, ROSE, "HEALTH", p.health.toInt().toString(), p.health < 30f); gy += 19 * d }
        if (p.has(Item.JETPACK) && p.jetFuel < 0.995f) { gauge(c, gx, gy, gw, p.jetFuel, GOLD, "JET", "", false); gy += 19 * d }

        // ---- objective under the gauges
        val obj = g.objective
        if (obj.isNotEmpty()) {
            if (obj != objectiveText || objectiveLayout == null) {
                objectiveText = obj
                text.textSize = 11.5f * d; text.color = Color.WHITE; text.textAlign = Paint.Align.LEFT
                objectiveLayout = layout(obj, (w * 0.3f).toInt(), text, Layout.Alignment.ALIGN_NORMAL)
            }
            val l = objectiveLayout!!
            val oy = gy + 6 * d
            panel(c, gx, oy, gx + l.width + 26 * d, oy + l.height + 10 * d, 8f)
            fill.color = GOLD
            c.drawCircle(gx + 10 * d, oy + 12 * d, 3 * d, fill)
            c.save(); c.translate(gx + 20 * d, oy + 5 * d)
            text.textSize = 11.5f * d; text.color = Color.WHITE; text.textAlign = Paint.Align.LEFT
            l.draw(c); c.restore()
            gy = oy + l.height + 14 * d
        }

        // ---- pickup feed
        val ft = g.feedText
        if (ft.isNotEmpty()) {
            if (ft != feedTextCache || feedLayout == null) {
                feedTextCache = ft
                text.textSize = 11.5f * d; text.color = Color.WHITE; text.textAlign = Paint.Align.LEFT
                feedLayout = layout(ft, (w * 0.3f).toInt(), text, Layout.Alignment.ALIGN_NORMAL)
            }
            c.save(); c.translate(gx + 4 * d, gy + 6 * d)
            text.textSize = 11.5f * d; text.color = Color.argb(235, 220, 255, 250); text.textAlign = Paint.Align.LEFT
            text.setShadowLayer(3 * d, 0f, 0f, Color.BLACK)
            feedLayout!!.draw(c)
            text.clearShadowLayer()
            c.restore()
        }

        drawCompass(c, w)
        drawPlanet(c, w)
        if (p.has(Item.SCANNER)) drawOreMarks(c, w, h)

        // ---- crosshair and prompt
        val cx = w / 2; val cy = h / 2
        if (!p.dead) {
            val hot = g.targetKind != TargetKind.NONE || building
            stroke.color = if (g.deconMode) ROSE else if (hot) TEAL else Color.argb(170, 255, 255, 255)
            stroke.strokeWidth = 1.6f * d
            if (hot) c.drawCircle(cx, cy, 9 * d, stroke)
            fill.color = stroke.color
            c.drawCircle(cx, cy, 1.8f * d, fill)
            var line = g.prompt
            if (g.buildType != null) line = g.buildType!!.label + (if (g.buildValid) "" else "  ·  " + g.buildWhy)
            else if (g.deconMode && line.isEmpty()) line = "Aim at something you built"
            if (line.isNotEmpty()) {
                bold.textAlign = Paint.Align.CENTER
                bold.color = if (g.buildType != null && !g.buildValid) ROSE else Color.WHITE
                val tw = bold.measureText(line)
                panel(c, cx - tw / 2 - 12 * d, cy + 22 * d, cx + tw / 2 + 12 * d, cy + 46 * d, 12f)
                c.drawText(line, cx, cy + 39 * d, bold)
            }
        }

        // ---- stage banner
        val banner = g.banner
        if (banner.isNotEmpty()) {
            big.textAlign = Paint.Align.CENTER
            big.textSize = 22f * d
            big.color = Color.WHITE
            big.setShadowLayer(8 * d, 0f, 0f, Color.argb(220, 0, 0, 0))
            c.drawText(banner, cx, h * 0.24f, big)
            big.clearShadowLayer()
            fill.color = TEAL
            val bw = min(big.measureText(banner), w * 0.6f)
            c.drawRect(cx - bw / 2, h * 0.24f + 9 * d, cx + bw / 2, h * 0.24f + 10.5f * d, fill)
            big.textSize = 27f * d
        }

        // ---- WREN subtitles
        val sub = g.subtitle
        if (sub.isNotEmpty() && g.settings.subtitles) {
            if (sub != subtitleText || subtitleLayout == null) {
                subtitleText = sub
                text.textSize = 13.5f * d; text.color = Color.WHITE; text.textAlign = Paint.Align.LEFT
                subtitleLayout = layout(sub, (w * 0.46f).toInt(), text, Layout.Alignment.ALIGN_CENTER)
            }
            val l = subtitleLayout!!
            val sw = w * 0.46f
            val sy = h - l.height - 30 * d
            panel(c, cx - sw / 2 - 14 * d, sy - 20 * d, cx + sw / 2 + 14 * d, sy + l.height + 8 * d, 12f)
            small.textAlign = Paint.Align.CENTER; small.color = TEAL
            c.drawText("W R E N", cx, sy - 7 * d, small)
            c.save(); c.translate(cx - sw / 2, sy)
            text.textSize = 13.5f * d; text.color = Color.WHITE; text.textAlign = Paint.Align.LEFT
            l.draw(c); c.restore()
        }

        if (touchMode) drawTouch(c)
        else if (building) {
            small.textAlign = Paint.Align.CENTER; small.color = Color.argb(220, 255, 255, 255)
            c.drawText(if (g.deconMode) "RT take apart   B cancel" else "RT place   LB / RB rotate   D-pad switch   B cancel", cx, h - 16 * d, small)
        }
        drawTop(c)
        if (g.settings.showFps) { small.textAlign = Paint.Align.LEFT; small.color = Color.WHITE; c.drawText("${g.fps} fps", 14 * d + safeL, h - 8 * d, small) }
        postInvalidateOnAnimation()
    }

    private fun drawCompass(c: Canvas, w: Float) {
        val g = game
        val cw = w * 0.3f
        val x0 = w / 2 - cw / 2; val y = 14 * d
        panel(c, x0, y, x0 + cw, y + 22 * d, 11f)
        val yaw = g.player.yaw
        // heading 0 = north (+z); the strip shows 120 degrees
        val span = PI_F * 2f / 3f
        c.save()
        c.clipRect(x0 + 6 * d, y, x0 + cw - 6 * d, y + 40 * d)
        val names = arrayOf("N", "NW", "W", "SW", "S", "SE", "E", "NE")
        for (i in 0 until 24) {
            val a = i * PI_F / 12f
            val diff = angleDiff(yaw, a)
            if (abs(diff) > span / 2) continue
            val x = w / 2 - diff / span * cw
            if (i % 3 == 0) {
                small.textAlign = Paint.Align.CENTER
                small.color = if (i % 6 == 0) Color.WHITE else Color.argb(170, 255, 255, 255)
                c.drawText(names[i / 3], x, y + 15.5f * d, small)
            } else {
                stroke.color = Color.argb(120, 255, 255, 255); stroke.strokeWidth = 1.2f * d
                c.drawLine(x, y + 8 * d, x, y + 14 * d, stroke)
            }
        }
        for (m in g.markers) {
            val a = atan2(m.x - g.player.pos.x, m.z - g.player.pos.z)
            val diff = angleDiff(yaw, a).coerceIn(-span / 2 + 0.03f, span / 2 - 0.03f)
            val x = w / 2 - diff / span * cw
            fill.color = m.color
            path.reset(); path.moveTo(x, y + 23 * d); path.lineTo(x - 5 * d, y + 31 * d); path.lineTo(x + 5 * d, y + 31 * d); path.close()
            c.drawPath(path, fill)
            val dist = hypot(m.x - g.player.pos.x, m.z - g.player.pos.z)
            small.textAlign = Paint.Align.CENTER; small.color = m.color
            small.textSize = 8.5f * d
            c.drawText(if (dist >= 1000f) String.format("%.1fkm", dist / 1000f) else dist.toInt().toString() + "m", x, y + 40 * d, small)
            small.textSize = 10f * d
        }
        c.restore()
        fill.color = TEAL
        c.drawRect(w / 2 - 0.8f * d, y + 2 * d, w / 2 + 0.8f * d, y + 7 * d, fill)
    }

    private fun drawPlanet(c: Canvas, w: Float) {
        val g = game
        val x1 = w - 14 * d - safeR
        val y = 58 * d
        val pct = g.stage / Planet.LAST * 100f
        val pctText = String.format(java.util.Locale.US, if (pct < 10f) "%.2f%%" else "%.1f%%", pct)
        big.textAlign = Paint.Align.RIGHT; big.color = Color.WHITE
        big.setShadowLayer(5 * d, 0f, 0f, Color.argb(200, 0, 0, 0))
        c.drawText(pctText, x1, y + 22 * d, big)
        big.clearShadowLayer()
        small.textAlign = Paint.Align.RIGHT
        small.setShadowLayer(3 * d, 0f, 0f, Color.BLACK)
        small.color = TEAL
        c.drawText("LIVABLE  ·  " + Planet.stageNames[g.stageReached].uppercase(), x1, y + 36 * d, small)
        small.color = Color.argb(230, 255, 255, 255)
        c.drawText(Fmt.wi(g.wi) + " Wi   +" + Fmt.wi(g.rate) + "/s", x1, y + 50 * d, small)
        val short = g.powerUse > g.powerGen + 0.01f
        small.color = if (short) ROSE else Color.argb(200, 255, 255, 255)
        c.drawText("power " + g.powerUse.toInt() + " / " + g.powerGen.toInt() + " kW" + (if (short) "  LOW" else ""), x1, y + 64 * d, small)
        small.clearShadowLayer()
        // progress to the next stage
        val f = g.stage - g.stageReached
        if (g.stageReached < Planet.LAST) {
            rect.set(x1 - 120 * d, y + 70 * d, x1, y + 74 * d)
            fill.color = Color.argb(140, 8, 12, 24); c.drawRoundRect(rect, 2 * d, 2 * d, fill)
            rect.set(x1 - 120 * d, y + 70 * d, x1 - 120 * d + 120 * d * f.coerceIn(0f, 1f), y + 74 * d)
            fill.color = TEAL; c.drawRoundRect(rect, 2 * d, 2 * d, fill)
        }
    }

    private fun drawOreMarks(c: Canvas, w: Float, h: Float) {
        val marks = game.oreMarks
        if (marks.isEmpty()) return
        System.arraycopy(game.viewProj, 0, vpCopy, 0, 16)
        var i = 0
        while (i + 3 < marks.size) {
            Mat4.project(vpCopy, marks[i], marks[i + 1], marks[i + 2], proj)
            if (proj[3] > 0.5f && abs(proj[0]) < 1f && abs(proj[1]) < 1f) {
                val sx = (proj[0] * 0.5f + 0.5f) * w; val sy = (1f - (proj[1] * 0.5f + 0.5f)) * h
                val item = Item.entries[marks[i + 3].toInt().coerceIn(0, Item.entries.size - 1)]
                val a = (255 * (1f - proj[3] / 50f)).toInt().coerceIn(40, 230)
                stroke.color = (item.color and 0x00ffffff) or (a shl 24); stroke.strokeWidth = 1.5f * d
                path.reset(); path.moveTo(sx, sy - 6 * d); path.lineTo(sx + 5 * d, sy); path.lineTo(sx, sy + 6 * d); path.lineTo(sx - 5 * d, sy); path.close()
                c.drawPath(path, stroke)
            }
            i += 4
        }
    }

    private fun drawBtn(c: Canvas, b: Btn, label: String, color: Int, big: Boolean = false) {
        if (!b.visible) return
        val a = game.settings.touchOpacity
        val pressed = b.pointer != -1
        fill.color = Color.argb(((if (pressed) 150 else 90) * a).toInt().coerceIn(20, 230), 8, 12, 24)
        c.drawCircle(b.x, b.y, b.r, fill)
        stroke.color = (color and 0x00ffffff) or (((if (pressed) 255 else 190) * a).toInt().coerceIn(40, 255) shl 24)
        stroke.strokeWidth = (if (big) 2.4f else 1.8f) * d
        c.drawCircle(b.x, b.y, b.r, stroke)
        small.textAlign = Paint.Align.CENTER
        small.color = (0xffffff) or ((255 * a.coerceAtLeast(0.6f)).toInt() shl 24)
        small.textSize = (if (big) 11.5f else 9.5f) * d
        c.drawText(label, b.x, b.y + 4 * d, small)
        small.textSize = 10f * d
    }

    private fun drawTouch(c: Canvas) {
        val g = game
        if (g.player.dead) return
        val a = g.settings.touchOpacity
        // joystick: only drawn while held, where the thumb went down
        if (joyPointer != -1) {
            fill.color = Color.argb((70 * a).toInt(), 8, 12, 24)
            c.drawCircle(joyCx, joyCy, joyR, fill)
            stroke.color = Color.argb((150 * a).toInt(), 89, 240, 224); stroke.strokeWidth = 1.6f * d
            c.drawCircle(joyCx, joyCy, joyR, stroke)
            fill.color = Color.argb((200 * a).toInt(), 89, 240, 224)
            c.drawCircle(joyCx + joyX * joyR, joyCy + joyY * joyR, 22 * d, fill)
        } else {
            stroke.color = Color.argb((70 * a).toInt(), 255, 255, 255); stroke.strokeWidth = 1.4f * d
            c.drawCircle(96 * d + safeL, height - 96 * d, joyR * 0.8f, stroke)
        }
        btnRotate.visible = g.buildType != null
        btnCancel.visible = building
        val useLabel = if (g.buildType != null) "PLACE" else if (g.deconMode) "TAKE" else "USE"
        val hot = g.targetKind != TargetKind.NONE || (g.buildType != null && g.buildValid)
        drawBtn(c, btnUse, useLabel, if (g.deconMode) ROSE else if (hot) TEAL else Color.WHITE, true)
        drawBtn(c, btnJump, if (g.player.has(Item.JETPACK)) "JET" else "JUMP", Color.WHITE)
        drawBtn(c, btnRotate, "TURN", GOLD)
        drawBtn(c, btnCancel, "X", ROSE)
    }

    private fun drawTop(c: Canvas) {
        drawBtn(c, btnBuild, "BUILD", GOLD)
        drawBtn(c, btnPack, "PACK", TEAL)
        drawBtn(c, btnMenu, "II", Color.WHITE)
    }
}
