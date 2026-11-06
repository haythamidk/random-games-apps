package com.hadal.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.hadal.game.game.ControlScheme
import com.hadal.game.game.GameState
import com.hadal.game.game.HudData
import com.hadal.game.input.Action
import com.hadal.game.input.InputState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Canvas HUD and touch controls. Layout zones never overlap: gauges top left, compass top center, sonar radar
 * top right, objective under the gauges, messages bottom center, joystick bottom left (fixed in place) and the
 * action cluster on the right edge.
 */
@SuppressLint("ViewConstructor")
class HudView(ctx: Context, private val game: GameState, private val input: InputState) : View(ctx) {
    private val d = HudData()
    private val dp = resources.displayMetrics.density
    var onMenu: (() -> Unit)? = null
    var onMap: (() -> Unit)? = null
    /** True while a menu panel covers the screen: gauges and goals are hidden so nothing shows through. */
    @Volatile var menuOpen = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.MONOSPACE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()

    private class Btn(val id: String, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f, val hold: Boolean = false) {
        var pressed = false
        var pointer = -1
        var visible = true
        var label = ""
    }

    private val btns = listOf(
        Btn("use"), Btn("scan", hold = true), Btn("lamp"), Btn("sonar"), Btn("up", hold = true), Btn("down", hold = true),
        Btn("boost", hold = true), Btn("flare"), Btn("pulse"), Btn("repair"), Btn("deploy"), Btn("menu"), Btn("map"), Btn("cam"),
    )
    private fun btn(id: String) = btns.first { it.id == id }

    // joystick
    private var joyPointer = -1
    private var joyCx = 0f; private var joyCy = 0f; private var joyR = 0f
    private var joyX = 0f; private var joyY = 0f
    // look
    private var lookPointer = -1
    private var lookLastX = 0f; private var lookLastY = 0f

    private var lastLayoutW = 0
    private var time = 0f

    init { isFocusable = false }

    fun releaseAll() {
        for (b in btns) { b.pressed = false; b.pointer = -1 }
        joyPointer = -1; lookPointer = -1; joyX = 0f; joyY = 0f
        input.setTouchMove(0f, 0f); input.touchUp = false; input.touchDown = false; input.boostHeld = false; input.scanHeld = false
    }

    private fun layout() {
        val w = width.toFloat(); val h = height.toFloat()
        val m = 18 * dp
        joyR = 62 * dp
        joyCx = m + joyR + 20 * dp; joyCy = h - m - joyR - 8 * dp
        val big = 40 * dp; val mid = 30 * dp; val sm = 24 * dp
        val use = btn("use"); use.r = big; use.cx = w - m - big - 70 * dp; use.cy = h - m - big - 10 * dp
        val scan = btn("scan"); scan.r = mid; scan.cx = use.cx - big - mid - 18 * dp; scan.cy = use.cy + 8 * dp
        val upB = btn("up"); upB.r = mid; upB.cx = w - m - mid; upB.cy = use.cy - big - 70 * dp
        val dn = btn("down"); dn.r = mid; dn.cx = w - m - mid; dn.cy = use.cy + 4 * dp
        val boost = btn("boost"); boost.r = mid; boost.cx = use.cx - 14 * dp; boost.cy = use.cy - big - mid - 22 * dp
        val lamp = btn("lamp"); lamp.r = sm; lamp.cx = boost.cx - mid - sm - 30 * dp; lamp.cy = boost.cy - 6 * dp
        val sonar = btn("sonar"); sonar.r = sm; sonar.cx = scan.cx - mid - sm - 16 * dp; sonar.cy = scan.cy
        val flare = btn("flare"); flare.r = sm; flare.cx = upB.cx; flare.cy = upB.cy - mid - sm - 18 * dp
        val pulse = btn("pulse"); pulse.r = sm; pulse.cx = flare.cx - sm * 2 - 14 * dp; pulse.cy = flare.cy
        val rep = btn("repair"); rep.r = sm; rep.cx = lamp.cx - sm * 2 - 12 * dp; rep.cy = lamp.cy
        val dep = btn("deploy"); dep.r = sm; dep.cx = rep.cx - sm * 2 - 12 * dp; dep.cy = lamp.cy
        // top right row under the radar
        val radarR = 64 * dp
        val menu = btn("menu"); menu.r = 20 * dp; menu.cx = w - m - menu.r; menu.cy = m + radarR * 2 + 26 * dp
        val map = btn("map"); map.r = 20 * dp; map.cx = menu.cx - 50 * dp; map.cy = menu.cy
        val cam = btn("cam"); cam.r = 20 * dp; cam.cx = map.cx - 50 * dp; cam.cy = menu.cy
        lastLayoutW = width
    }

    private val touchControls get() = game.settings.controls == ControlScheme.TOUCH

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (lastLayoutW != width) layout()
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(e.getPointerId(idx), e.getX(idx), e.getY(idx))
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(idx))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        // menu buttons always work
        for (b in btns) {
            if (!b.visible) continue
            if (!touchControls && b.id != "menu" && b.id != "map" && b.id != "cam") continue
            if (hypot(x - b.cx, y - b.cy) <= b.r * 1.25f) { press(b, id); return }
        }
        // tapping the message box skips it
        if (d.message != null && y > height - 120 * dp && x > width * 0.3f && x < width * 0.7f) { input.push(Action.SKIP); return }
        if (!touchControls) return
        if (x < width * 0.42f && y > height * 0.4f && joyPointer < 0) {
            joyPointer = id; move(id, x, y); return
        }
        if (lookPointer < 0) { lookPointer = id; lookLastX = x; lookLastY = y }
    }

    private fun press(b: Btn, id: Int) {
        b.pressed = true; b.pointer = id
        when (b.id) {
            "use" -> input.push(Action.USE)
            "scan" -> input.scanHeld = true
            "lamp" -> input.push(Action.LAMP)
            "sonar" -> input.push(Action.SONAR)
            "up" -> input.touchUp = true
            "down" -> input.touchDown = true
            "boost" -> input.boostHeld = true
            "flare" -> input.push(Action.FLARE)
            "pulse" -> input.push(Action.PULSE)
            "repair" -> input.push(Action.REPAIR)
            "deploy" -> input.push(Action.DEPLOY)
            "menu" -> post { onMenu?.invoke() }
            "map" -> post { onMap?.invoke() }
            "cam" -> input.push(Action.CAMERA)
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (id == joyPointer) {
            var dx = (x - joyCx) / joyR; var dy = (y - joyCy) / joyR
            val l = sqrt(dx * dx + dy * dy)
            if (l > 1f) { dx /= l; dy /= l }
            joyX = dx; joyY = dy
            input.setTouchMove(dx, -dy)
        } else if (id == lookPointer) {
            val k = 0.16f
            input.addLook((x - lookLastX) * k, (y - lookLastY) * k)
            lookLastX = x; lookLastY = y
        }
    }

    private fun up(id: Int) {
        if (id == joyPointer) { joyPointer = -1; joyX = 0f; joyY = 0f; input.setTouchMove(0f, 0f) }
        if (id == lookPointer) lookPointer = -1
        for (b in btns) if (b.pointer == id) {
            b.pressed = false; b.pointer = -1
            when (b.id) {
                "scan" -> input.scanHeld = false
                "up" -> input.touchUp = false
                "down" -> input.touchDown = false
                "boost" -> input.boostHeld = false
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (lastLayoutW != width) layout()
        synchronized(game.hud) { d.copyFrom(game.hud) }
        time += 1f / 60f
        if (d.loading) { drawLoading(c); postInvalidateOnAnimation(); return }
        if (d.dead) { postInvalidateOnAnimation(); return }
        if (menuOpen) {
            // only crew messages stay visible, in the strip left of the panel
            drawMessage(c)
            postInvalidateOnAnimation()
            return
        }
        val alpha = (game.settings.touchOpacity * 255).toInt()
        if (game.settings.cockpitCamera && !d.docked) drawCockpit(c)
        drawGauges(c)
        drawCompass(c)
        drawRadar(c)
        drawObjective(c)
        drawWarnings(c)
        drawCrosshair(c)
        drawMessage(c)
        drawToast(c)
        btn("menu").visible = true; btn("map").visible = true; btn("cam").visible = !d.docked
        val tc = touchControls && !d.docked
        for (b in btns) if (b.id != "menu" && b.id != "map" && b.id != "cam") b.visible = tc
        btn("use").visible = touchControls && (d.useLabel.isNotEmpty() || d.docked)
        btn("flare").visible = tc && d.hasFlares
        btn("pulse").visible = tc && d.hasPulse
        btn("repair").visible = tc && d.kits > 0 && d.hull < 0.95f
        btn("deploy").visible = tc && d.outpostKits > 0
        if (tc) drawJoystick(c, alpha)
        drawButtons(c, alpha)
        if (game.settings.showFps) { mono.textSize = 12 * dp; mono.color = Color.argb(200, 140, 255, 160); c.drawText("${d.fps} FPS", width / 2f - 20 * dp, 66 * dp, mono) }
        postInvalidateOnAnimation()
    }

    private fun drawLoading(c: Canvas) {
        c.drawColor(Color.argb(255, 2, 6, 10))
        text.textSize = 22 * dp; text.color = Color.argb(230, 180, 230, 255); text.textAlign = Paint.Align.CENTER
        c.drawText("Pressurizing", width / 2f, height / 2f - 10 * dp, text)
        val w = 220 * dp
        paint.color = Color.argb(60, 120, 200, 255)
        c.drawRoundRect(width / 2f - w / 2, height / 2f + 10 * dp, width / 2f + w / 2, height / 2f + 16 * dp, 3 * dp, 3 * dp, paint)
        paint.color = Color.argb(220, 120, 220, 255)
        c.drawRoundRect(width / 2f - w / 2, height / 2f + 10 * dp, width / 2f - w / 2 + w * d.loadProgress.coerceIn(0.05f, 1f), height / 2f + 16 * dp, 3 * dp, 3 * dp, paint)
        text.textAlign = Paint.Align.LEFT
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, v: Float, col: Int, label: String, value: String, warn: Boolean) {
        val h = 7 * dp
        paint.color = Color.argb(110, 0, 0, 0)
        c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, paint)
        val blink = warn && (time * 3f).toInt() % 2 == 0
        paint.color = if (blink) Color.rgb(255, 80, 60) else col
        c.drawRoundRect(x, y, x + max(h, w * v.coerceIn(0f, 1f)), y + h, h / 2, h / 2, paint)
        text.textSize = 11 * dp; text.color = Color.argb(200, 220, 235, 245)
        c.drawText(label, x - 34 * dp, y + h, text)
        mono.textSize = 11 * dp; mono.color = Color.argb(200, 220, 235, 245)
        c.drawText(value, x + w + 6 * dp, y + h, mono)
    }

    private fun drawGauges(c: Canvas) {
        val x = 52 * dp; var y = 18 * dp; val w = 150 * dp
        bar(c, x, y, w, d.hull, Color.rgb(240, 196, 70), "HULL", "${d.hullV}", d.hull < 0.25f); y += 18 * dp
        bar(c, x, y, w, d.power, Color.rgb(90, 200, 255), "PWR", "${d.powerV}", d.lowPower); y += 18 * dp
        bar(c, x, y, w, d.oxygen, Color.rgb(140, 240, 200), "O2", "${d.oxygenV}", d.lowOxygen); y += 30 * dp
        // depth readout
        text.textSize = 30 * dp
        text.color = if (d.pressure) Color.rgb(255, 90, 70) else if (d.depth > d.rating * 0.88f) Color.rgb(255, 200, 90) else Color.WHITE
        val depthStr = "${d.depth.toInt()} m"
        c.drawText(depthStr, 18 * dp, y + 6 * dp, text)
        val tw = text.measureText(depthStr)
        text.textSize = 12 * dp; text.color = Color.argb(170, 200, 220, 235)
        c.drawText("rated ${d.rating.toInt()} m", 18 * dp + tw + 10 * dp, y + 6 * dp, text)
        y += 20 * dp
        text.textSize = 12 * dp; text.color = Color.argb(180, 150, 210, 240)
        c.drawText("${d.zone}   cargo ${d.cargo}/${d.cargoCap}", 18 * dp, y, text)
        // noise meter: how loud the sub is in the water
        y += 12 * dp
        val nw = 90 * dp
        paint.color = Color.argb(90, 0, 0, 0); c.drawRect(18 * dp, y, 18 * dp + nw, y + 4 * dp, paint)
        paint.color = Color.argb(220, 255, (220 - d.noise * 160).toInt().coerceIn(40, 220), 120)
        c.drawRect(18 * dp, y, 18 * dp + nw * d.noise.coerceIn(0f, 1f), y + 4 * dp, paint)
        text.textSize = 9 * dp; text.color = Color.argb(150, 220, 220, 220)
        c.drawText("NOISE", 18 * dp + nw + 6 * dp, y + 5 * dp, text)
    }

    private fun drawCompass(c: Canvas) {
        val cx = width / 2f; val y = 22 * dp; val w = 280 * dp
        paint.color = Color.argb(90, 0, 10, 20)
        c.drawRoundRect(cx - w / 2, y - 14 * dp, cx + w / 2, y + 14 * dp, 14 * dp, 14 * dp, paint)
        // east is screen right when facing north (+z), which is -x in world space
        val heading = -d.heading
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        text.textSize = 13 * dp; text.textAlign = Paint.Align.CENTER
        for (i in 0 until 8) {
            // yaw 0 faces +z which we call north; yaw grows toward +x (east)
            val ang = i * PI.toFloat() / 4f
            var diff = ang - heading
            while (diff > PI) diff -= (2 * PI).toFloat()
            while (diff < -PI) diff += (2 * PI).toFloat()
            if (kotlin.math.abs(diff) > 1.3f) continue
            val x = cx + diff / 1.3f * (w / 2 - 16 * dp)
            text.color = if (i == 0) Color.rgb(255, 110, 90) else Color.argb(220, 230, 240, 250)
            c.drawText(names[i], x, y + 5 * dp, text)
        }
        fun marker(dx: Float, dz: Float, col: Int, kind: Int) {
            var sd = kotlin.math.atan2(-dx, dz) - heading
            while (sd > PI) sd -= (2 * PI).toFloat(); while (sd < -PI) sd += (2 * PI).toFloat()
            val off = kotlin.math.abs(sd) > 1.3f
            val k = sd.coerceIn(-1.3f, 1.3f)
            val x = cx + k / 1.3f * (w / 2 - 16 * dp)
            paint.color = col
            when (kind) {
                0 -> { path.reset(); path.moveTo(x, y + 10 * dp); path.lineTo(x - 5 * dp, y + 17 * dp); path.lineTo(x + 5 * dp, y + 17 * dp); path.close(); c.drawPath(path, paint) }
                else -> { c.drawCircle(x, y + 14 * dp, 5 * dp, paint); stroke.color = Color.argb(200, 0, 0, 0); stroke.strokeWidth = 1.2f * dp; c.drawCircle(x, y + 14 * dp, 5 * dp, stroke) }
            }
            if (off) {
                path.reset()
                val dir = if (sd > 0) 1f else -1f
                path.moveTo(x + dir * 9 * dp, y + 14 * dp); path.lineTo(x + dir * 3 * dp, y + 9 * dp); path.lineTo(x + dir * 3 * dp, y + 19 * dp); path.close()
                c.drawPath(path, paint)
            }
        }
        marker(d.stationDx, d.stationDz, Color.rgb(255, 196, 90), 0)
        if (d.trackOn) marker(d.trackDx, d.trackDz, Color.rgb(90, 230, 255), 1)
        if (d.goalOn) marker(d.goalDx, d.goalDz, Color.rgb(255, 214, 80), 1)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawRadar(c: Canvas) {
        val r = 64 * dp
        val cx = width - 18 * dp - r; val cy = 18 * dp + r
        paint.color = Color.argb(120, 0, 18, 26)
        c.drawCircle(cx, cy, r, paint)
        stroke.strokeWidth = 1.2f * dp; stroke.color = Color.argb(90, 100, 220, 255)
        c.drawCircle(cx, cy, r, stroke); c.drawCircle(cx, cy, r * 0.5f, stroke)
        c.drawLine(cx - r, cy, cx + r, cy, stroke); c.drawLine(cx, cy - r, cx, cy + r, stroke)
        // ping wave
        if (d.pingAge < 2f) {
            val pr = (d.pingAge * 140f / d.sonarRange).coerceAtMost(1f) * r
            stroke.color = Color.argb(((1f - d.pingAge / 2f) * 220).toInt(), 120, 230, 255); stroke.strokeWidth = 2 * dp
            c.drawCircle(cx, cy, pr, stroke)
        }
        val fade = if (d.pingAge < 9f) 1f else (1f - (d.pingAge - 9f) / 3f).coerceIn(0f, 1f)
        val h = d.heading
        val ch = cos(h); val sh = sin(h)
        val scale = r / d.sonarRange
        for (i in 0 until d.blipCount) {
            val bx = d.blips[i * 4]; val bz = d.blips[i * 4 + 1]; val by = d.blips[i * 4 + 2]; val kind = d.blips[i * 4 + 3].toInt()
            // project on the sub's right and forward axes: forward is up on the radar
            var px = (bx * -ch + bz * sh) * scale; var py = -(bx * sh + bz * ch) * scale
            val l = sqrt(px * px + py * py)
            val edge = l > r - 4 * dp
            if (edge) { px = px / l * (r - 4 * dp); py = py / l * (r - 4 * dp) }
            val a = (fade * 255).toInt()
            paint.color = when (kind) {
                0 -> Color.argb(a, 120, 255, 170)
                1 -> Color.argb(a, 160, 200, 255)
                2 -> Color.argb(a, 255, 80, 70)
                3 -> Color.argb(a, 255, 220, 120)
                4 -> Color.argb(a, 140, 230, 255)
                6 -> Color.argb(a, 255, 130, 255)
                else -> Color.argb(a, 255, 190, 80)
            }
            val s = if (kind >= 3) 4f * dp else 2.6f * dp
            if (kind >= 3) c.drawRect(cx + px - s, cy + py - s, cx + px + s, cy + py + s, paint) else c.drawCircle(cx + px, cy + py, s, paint)
            // vertical hint: blips above get a tick
            if (!edge && kotlin.math.abs(by) > 6f) { stroke.color = paint.color; stroke.strokeWidth = 1.5f * dp; c.drawLine(cx + px, cy + py, cx + px, cy + py + (if (by > 0) -6f else 6f) * dp, stroke) }
        }
        // the sub
        paint.color = Color.WHITE
        path.reset(); path.moveTo(cx, cy - 6 * dp); path.lineTo(cx - 4 * dp, cy + 4 * dp); path.lineTo(cx + 4 * dp, cy + 4 * dp); path.close()
        c.drawPath(path, paint)
        text.textSize = 9 * dp; text.color = Color.argb(150, 160, 220, 255)
        text.textAlign = Paint.Align.CENTER
        c.drawText("SONAR ${d.sonarRange.toInt()} m", cx, cy + r - 12 * dp, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (w in s.split(' ')) {
            val t = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(t) > maxW && line.isNotEmpty()) { out.add(line); line = w } else line = t
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    private fun drawObjective(c: Canvas) {
        if (d.objective.isEmpty()) return
        val x = 18 * dp; var y = 160 * dp
        val maxW = 250 * dp
        text.textSize = 12 * dp; text.color = Color.argb(200, 255, 200, 110)
        c.drawText(d.chapter.uppercase(), x, y, text)
        y += 17 * dp
        text.textSize = 14.5f * dp; text.color = Color.argb(240, 245, 248, 252)
        for (l in wrap(d.objective, text, maxW).take(2)) { c.drawText(l, x, y, text); y += 17 * dp }
        if (d.goalHint.isNotEmpty()) {
            text.textSize = 11.5f * dp; text.color = Color.argb(205, 170, 205, 225)
            for (l in wrap(d.goalHint, text, maxW).take(3)) { c.drawText(l, x, y, text); y += 14 * dp }
        }
        if (d.goalOn) {
            text.textSize = 12 * dp; text.color = Color.argb(240, 255, 214, 80)
            c.drawText("\u25CF Goal: ${d.goalWhere}", x, y + 2 * dp, text); y += 15 * dp
        }
        if (d.trackOn) {
            text.textSize = 12 * dp; text.color = Color.argb(240, 90, 230, 255)
            c.drawText("\u25CF ${d.trackLabel}: ${d.trackWhere}", x, y + 2 * dp, text)
        }
    }

    private fun drawWarnings(c: Canvas) {
        val list = ArrayList<String>()
        if (d.pressure) list.add("HULL PRESSURE")
        if (d.lowPower) list.add("LOW POWER")
        if (d.lowOxygen) list.add("LOW OXYGEN")
        if (d.hull < 0.25f) list.add("HULL CRITICAL")
        if (list.isEmpty() || (time * 2f).toInt() % 2 == 1) return
        text.textSize = 15 * dp; text.color = Color.rgb(255, 90, 70); text.textAlign = Paint.Align.CENTER
        c.drawText(list.joinToString("   "), width / 2f, 60 * dp, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawCrosshair(c: Canvas) {
        if (d.docked) return
        val cx = width / 2f; val cy = height / 2f
        stroke.strokeWidth = 1.5f * dp; stroke.color = Color.argb(110, 200, 240, 255)
        c.drawCircle(cx, cy, 3 * dp, stroke)
        if (d.scanProgress >= 0f) {
            stroke.strokeWidth = 3 * dp; stroke.color = Color.argb(230, 120, 240, 255)
            rect.set(cx - 26 * dp, cy - 26 * dp, cx + 26 * dp, cy + 26 * dp)
            c.drawArc(rect, -90f, 360f * d.scanProgress.coerceIn(0f, 1f), false, stroke)
        }
        if (d.scanLabel.isNotEmpty()) {
            text.textSize = 13 * dp; text.color = Color.argb(230, 150, 240, 255); text.textAlign = Paint.Align.CENTER
            c.drawText(d.scanLabel, cx, cy + 46 * dp, text)
            text.textAlign = Paint.Align.LEFT
        }
        if (!touchControls && d.useLabel.isNotEmpty()) {
            text.textSize = 14 * dp; text.color = Color.argb(240, 255, 230, 160); text.textAlign = Paint.Align.CENTER
            val key = if (game.settings.controls == ControlScheme.CONTROLLER) "A" else "E"
            c.drawText("[$key] ${d.useLabel}", cx, cy + 66 * dp, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawMessage(c: Canvas) {
        val m = d.message ?: return
        val a = d.messageAlpha
        if (a <= 0.01f) return
        // while docked the station panel fills the right side, so messages move left
        val w = if (menuOpen) width * 0.26f - 8 * dp else min(width * 0.46f, 520 * dp)
        val x = if (menuOpen) 14 * dp else width / 2f - w / 2
        text.textSize = 14 * dp
        val lines = wrap(m.text, text, w - 28 * dp)
        val h = 34 * dp + lines.size * 18 * dp
        val y = height - h - 16 * dp
        paint.color = Color.argb((170 * a).toInt(), 2, 14, 22)
        c.drawRoundRect(x, y, x + w, y + h, 12 * dp, 12 * dp, paint)
        stroke.strokeWidth = 1 * dp; stroke.color = Color.argb((120 * a).toInt(), 120, 220, 255)
        c.drawRoundRect(x, y, x + w, y + h, 12 * dp, 12 * dp, stroke)
        text.textSize = 12 * dp; text.color = Color.argb((255 * a).toInt(), 120, 220, 255)
        c.drawText(m.speaker.uppercase(), x + 14 * dp, y + 20 * dp, text)
        text.textSize = 14 * dp; text.color = Color.argb((255 * a).toInt(), 235, 242, 248)
        var ly = y + 40 * dp
        for (l in lines) { c.drawText(l, x + 14 * dp, ly, text); ly += 18 * dp }
    }

    private fun drawToast(c: Canvas) {
        if (d.toastAlpha <= 0.01f || d.toast.isEmpty()) return
        text.textSize = 15 * dp; text.textAlign = Paint.Align.CENTER
        val w = text.measureText(d.toast) + 30 * dp
        val y = height * 0.3f
        paint.color = Color.argb((150 * d.toastAlpha).toInt(), 0, 12, 18)
        c.drawRoundRect(width / 2f - w / 2, y - 22 * dp, width / 2f + w / 2, y + 10 * dp, 16 * dp, 16 * dp, paint)
        text.color = Color.argb((255 * d.toastAlpha).toInt(), 230, 245, 255)
        c.drawText(d.toast, width / 2f, y, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawCockpit(c: Canvas) {
        // the edges of the viewport ring
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = Color.argb(255, 8, 9, 10)
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        path.addOval(RectF(-w * 0.04f, -h * 0.18f, w * 1.04f, h * 1.12f), Path.Direction.CW)
        c.drawPath(path, paint)
        path.fillType = Path.FillType.WINDING
        stroke.strokeWidth = 6 * dp; stroke.color = Color.argb(255, 40, 38, 34)
        c.drawOval(RectF(-w * 0.04f, -h * 0.18f, w * 1.04f, h * 1.12f), stroke)
    }

    private fun drawJoystick(c: Canvas, alpha: Int) {
        paint.color = Color.argb(alpha / 3, 20, 40, 50)
        c.drawCircle(joyCx, joyCy, joyR, paint)
        stroke.strokeWidth = 2 * dp; stroke.color = Color.argb(alpha / 2, 160, 220, 240)
        c.drawCircle(joyCx, joyCy, joyR, stroke)
        paint.color = Color.argb(alpha, 210, 235, 245)
        c.drawCircle(joyCx + joyX * joyR * 0.6f, joyCy + joyY * joyR * 0.6f, joyR * 0.36f, paint)
    }

    private fun drawButtons(c: Canvas, alpha: Int) {
        for (b in btns) {
            if (!b.visible) continue
            val active = b.pressed || (b.id == "lamp" && d.lampOn)
            paint.color = if (active) Color.argb(alpha, 70, 160, 200) else Color.argb(alpha / 2, 10, 24, 32)
            c.drawCircle(b.cx, b.cy, b.r, paint)
            stroke.strokeWidth = 2 * dp
            stroke.color = when (b.id) {
                "use" -> Color.argb(alpha + 40, 255, 200, 100)
                "scan" -> Color.argb(alpha + 40, 120, 240, 255)
                "pulse" -> Color.argb(alpha + 40, 150, 200, 255)
                "flare" -> Color.argb(alpha + 40, 255, 120, 80)
                else -> Color.argb(alpha + 20, 190, 220, 235)
            }
            c.drawCircle(b.cx, b.cy, b.r, stroke)
            drawIcon(c, b)
        }
    }

    private fun drawIcon(c: Canvas, b: Btn) {
        val x = b.cx; val y = b.cy; val s = b.r * 0.45f
        paint.color = Color.argb(235, 235, 245, 250)
        stroke.color = Color.argb(235, 235, 245, 250); stroke.strokeWidth = 2.2f * dp
        text.textAlign = Paint.Align.CENTER
        when (b.id) {
            "up", "down" -> {
                val dir = if (b.id == "up") -1f else 1f
                path.reset(); path.moveTo(x, y + dir * s); path.lineTo(x - s, y - dir * s * 0.4f); path.lineTo(x + s, y - dir * s * 0.4f); path.close()
                c.drawPath(path, paint)
            }
            "use" -> {
                text.textSize = 12 * dp; text.color = Color.WHITE
                val lbl = if (d.docked) "Undock" else d.useLabel
                val lines = wrap(lbl, text, b.r * 1.7f)
                var ly = y - (lines.size - 1) * 7 * dp + 4 * dp
                for (l in lines.take(3)) { c.drawText(l, x, ly, text); ly += 14 * dp }
            }
            "lamp" -> {
                c.drawCircle(x - s * 0.3f, y, s * 0.45f, if (d.lampOn) paint else stroke)
                for (i in -1..1) c.drawLine(x + s * 0.3f, y + i * s * 0.4f, x + s, y + i * s * 0.7f, stroke)
            }
            "sonar" -> { c.drawCircle(x, y, s * 0.25f, paint); stroke.strokeWidth = 1.6f * dp; c.drawCircle(x, y, s * 0.6f, stroke); c.drawCircle(x, y, s * 0.95f, stroke) }
            "scan" -> {
                rect.set(x - s, y - s * 0.6f, x + s, y + s * 0.6f); c.drawOval(rect, stroke)
                c.drawCircle(x, y, s * 0.3f, paint)
            }
            "boost" -> {
                for (k in 0 until 2) { val ox = (k - 0.5f) * s * 0.7f; path.reset(); path.moveTo(x + ox - s * 0.35f, y - s * 0.6f); path.lineTo(x + ox + s * 0.35f, y); path.lineTo(x + ox - s * 0.35f, y + s * 0.6f); c.drawPath(path, stroke) }
            }
            "flare" -> { text.textSize = 10 * dp; text.color = Color.WHITE; c.drawCircle(x, y - s * 0.2f, s * 0.35f, paint); c.drawText("${d.flares}", x, y + s * 0.95f, text) }
            "pulse" -> { path.reset(); path.moveTo(x + s * 0.2f, y - s); path.lineTo(x - s * 0.4f, y + s * 0.1f); path.lineTo(x + s * 0.1f, y + s * 0.1f); path.lineTo(x - s * 0.2f, y + s); c.drawPath(path, stroke) }
            "deploy" -> {
                path.reset(); path.moveTo(x - s, y + s * 0.2f); path.lineTo(x, y - s); path.lineTo(x + s, y + s * 0.2f); c.drawPath(path, stroke)
                c.drawRect(x - s * 0.7f, y + s * 0.2f, x + s * 0.7f, y + s, stroke)
                text.textSize = 9 * dp; text.color = Color.WHITE; c.drawText("${d.outpostKits}", x + s * 1.1f, y + s * 1.3f, text)
            }
            "repair" -> { c.drawLine(x - s, y, x + s, y, stroke); c.drawLine(x, y - s, x, y + s, stroke); text.textSize = 9 * dp; text.color = Color.WHITE; c.drawText("${d.kits}", x + s, y + s, text) }
            "menu" -> for (i in -1..1) c.drawLine(x - s, y + i * s * 0.6f, x + s, y + i * s * 0.6f, stroke)
            "map" -> { rect.set(x - s, y - s * 0.8f, x + s, y + s * 0.8f); c.drawRect(rect, stroke); c.drawLine(x - s * 0.3f, y - s * 0.8f, x - s * 0.3f, y + s * 0.8f, stroke); c.drawLine(x + s * 0.3f, y - s * 0.8f, x + s * 0.3f, y + s * 0.8f, stroke) }
            "cam" -> { rect.set(x - s, y - s * 0.6f, x + s * 0.5f, y + s * 0.6f); c.drawRect(rect, stroke); path.reset(); path.moveTo(x + s * 0.5f, y); path.lineTo(x + s, y - s * 0.5f); path.lineTo(x + s, y + s * 0.5f); path.close(); c.drawPath(path, paint) }
        }
        text.textAlign = Paint.Align.LEFT
    }
}
