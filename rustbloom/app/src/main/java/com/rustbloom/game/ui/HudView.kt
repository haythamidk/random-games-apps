package com.rustbloom.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.rustbloom.game.engine.PI_F
import com.rustbloom.game.engine.angleDiff
import com.rustbloom.game.game.ControlScheme
import com.rustbloom.game.game.GameState
import com.rustbloom.game.game.HudData
import com.rustbloom.game.game.Item
import com.rustbloom.game.input.Action
import com.rustbloom.game.input.InputState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas HUD drawn over the GL view, plus on-screen touch controls.
 *
 * Layout zones (landscape): stats top-left, quest under stats, compass + notifications top-center,
 * clock + menu buttons top-right, hotbar bottom-center with the dialog box above it, fixed joystick bottom-left,
 * action buttons bottom-right. Zones never overlap.
 */
@SuppressLint("ViewConstructor")
class HudView(ctx: Context, private val game: GameState, private val input: InputState) : View(ctx) {
    private val d = resources.displayMetrics.density
    var onMenu: (() -> Unit)? = null
    var onInventory: (() -> Unit)? = null
    var onMap: (() -> Unit)? = null

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12.5f * d; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13.5f * d; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 10f * d; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26 * d; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val rect = RectF()
    private val path = Path()

    private class Btn(val id: String, var x: Float = 0f, var y: Float = 0f, var r: Float = 0f) {
        var pointer = -1
        var visible = true
        fun hit(px: Float, py: Float) = visible && hypot(px - x, py - y) < r * 1.2f
    }

    private val btnAttack = Btn("attack")
    private val btnJump = Btn("jump")
    private val btnUse = Btn("use")
    private val btnCrouch = Btn("crouch")
    private val btnItem = Btn("item")
    private val btnAmmo = Btn("ammo")
    private val btnSprint = Btn("sprint")
    private val btnRotate = Btn("rotate")
    private val btnCancel = Btn("cancel")
    private val btnMenu = Btn("menu")
    private val btnPack = Btn("pack")
    private val btnMap = Btn("map")
    private val actionButtons = listOf(btnAttack, btnJump, btnUse, btnCrouch, btnItem, btnAmmo, btnSprint, btnRotate, btnCancel)
    private val topButtons = listOf(btnMenu, btnPack, btnMap)

    private var joyPointer = -1
    private var joyCx = 0f; private var joyCy = 0f; private var joyR = 0f
    private var joyX = 0f; private var joyY = 0f
    private var lookPointer = -1
    private var lookLastX = 0f; private var lookLastY = 0f
    private var attackLookLastX = 0f; private var attackLookLastY = 0f
    private val hotbarRects = Array(6) { RectF() }
    private var hotbarTop = 0f

    init { isClickable = true; isFocusable = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutButtons(w.toFloat(), h.toFloat())

    private fun layoutButtons(w: Float, h: Float) {
        fun place(b: Btn, x: Float, y: Float, r: Float) { b.x = x; b.y = y; b.r = r * d }
        // right hand cluster
        place(btnAttack, w - 92 * d, h - 96 * d, 46f)
        place(btnJump, w - 188 * d, h - 52 * d, 30f)
        place(btnUse, w - 196 * d, h - 138 * d, 32f)
        place(btnCrouch, w - 50 * d, h - 196 * d, 24f)
        place(btnItem, w - 124 * d, h - 200 * d, 24f)
        place(btnAmmo, w - 124 * d, h - 200 * d, 24f)
        place(btnRotate, w - 196 * d, h - 138 * d, 30f)
        place(btnCancel, w - 50 * d, h - 196 * d, 24f)
        // left: fixed joystick and sprint toggle
        joyR = 60 * d
        joyCx = 112 * d; joyCy = h - 104 * d
        place(btnSprint, joyCx + joyR + 34 * d, joyCy - joyR + 6 * d, 21f)
        // top right
        place(btnMenu, w - 28 * d, 28 * d, 19f)
        place(btnPack, w - 76 * d, 28 * d, 20f)
        place(btnMap, w - 122 * d, 28 * d, 19f)
    }

    private val touchMode get() = game.settings.controls == ControlScheme.TOUCH

    // ------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = game.hud
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(e.getPointerId(idx), e.getX(idx), e.getY(idx), h)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(idx))
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) up(e.getPointerId(i))
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float, h: HudData) {
        if (h.loading || h.dead) return
        if (h.dialog.isNotEmpty() && dialogRect.contains(x, y)) { input.push(Action.SKIP_DIALOG); return }
        for (i in 0 until 6) if (hotbarRects[i].contains(x, y)) { input.push(Action.entries[Action.HOTBAR_1.ordinal + i]); return }
        if (btnMenu.hit(x, y)) { onMenu?.invoke(); return }
        if (btnPack.hit(x, y)) { onInventory?.invoke(); return }
        if (btnMap.hit(x, y)) { onMap?.invoke(); return }
        if (touchMode) {
            for (b in actionButtons) {
                if (!b.visible || b.pointer != -1 || !b.hit(x, y)) continue
                b.pointer = id
                when (b.id) {
                    "attack" -> { if (h.buildMode) input.push(Action.ATTACK_PRESS) else input.attackHeld = true; attackLookLastX = x; attackLookLastY = y }
                    "jump" -> input.push(Action.JUMP)
                    "use" -> input.push(Action.INTERACT)
                    "crouch" -> input.push(Action.CROUCH_TOGGLE)
                    "item" -> input.push(Action.USE_ITEM)
                    "ammo" -> input.push(Action.CYCLE_AMMO)
                    "sprint" -> input.push(Action.SPRINT_TOGGLE)
                    "rotate" -> input.push(Action.ROTATE)
                    "cancel" -> input.push(Action.CANCEL)
                }
                return
            }
            // the joystick is fixed; touches near it grab the knob
            if (joyPointer == -1 && hypot(x - joyCx, y - joyCy) < joyR * 1.6f) {
                joyPointer = id
                updateJoy(x, y)
                return
            }
        }
        if (lookPointer == -1) { lookPointer = id; lookLastX = x; lookLastY = y }
    }

    private fun updateJoy(x: Float, y: Float) {
        var dx = (x - joyCx) / joyR
        var dy = (y - joyCy) / joyR
        val l = hypot(dx, dy)
        if (l > 1f) { dx /= l; dy /= l }
        joyX = dx; joyY = dy
        val dz = 0.12f
        val mag = min(1f, l)
        val k = if (mag < dz) 0f else (mag - dz) / (1f - dz) / maxOf(mag, 1e-4f)
        input.setTouchMove(dx * k, -dy * k)
    }

    private fun move(id: Int, x: Float, y: Float) {
        val sens = 0.16f
        when (id) {
            joyPointer -> updateJoy(x, y)
            lookPointer -> { input.addLook((x - lookLastX) * sens, (y - lookLastY) * sens); lookLastX = x; lookLastY = y }
            btnAttack.pointer -> { input.addLook((x - attackLookLastX) * sens, (y - attackLookLastY) * sens); attackLookLastX = x; attackLookLastY = y }
        }
    }

    private fun up(id: Int) {
        if (id == joyPointer) { joyPointer = -1; input.setTouchMove(0f, 0f); joyX = 0f; joyY = 0f }
        if (id == lookPointer) lookPointer = -1
        for (b in actionButtons) if (b.pointer == id) { b.pointer = -1; if (b === btnAttack) input.attackHeld = false }
    }

    fun releaseAll() {
        joyPointer = -1; lookPointer = -1; joyX = 0f; joyY = 0f
        for (b in actionButtons) b.pointer = -1
        input.attackHeld = false
        input.setTouchMove(0f, 0f)
    }

    // ------------------------------------------------------------------ drawing

    private val dialogRect = RectF()

    override fun onDraw(c: Canvas) {
        val h = game.hud
        val w = width.toFloat()
        val ht = height.toFloat()
        if (h.loading) { drawLoading(c, w, ht); return }

        if (h.underwater) { fill.color = Color.argb(70, 10, 60, 70); c.drawRect(0f, 0f, w, ht, fill) }
        if (h.hurt > 0.01f) drawVignette(c, w, ht, Color.argb((min(1f, h.hurt) * 150).toInt(), 160, 0, 0))
        else if (h.hp < h.maxHp * 0.25f) drawVignette(c, w, ht, Color.argb((60 + 40 * sin(System.nanoTime() / 2e8)).toInt(), 140, 0, 0))
        if (h.heal > 0.01f) drawVignette(c, w, ht, Color.argb((h.heal * 80).toInt(), 60, 200, 90))
        if (h.tempState <= -1) drawVignette(c, w, ht, Color.argb(if (h.tempState == -2) 110 else 55, 160, 210, 255))
        if (h.tempState >= 1) drawVignette(c, w, ht, Color.argb(if (h.tempState == 2) 100 else 45, 255, 150, 40))

        val statsBottom = drawStats(c, h)
        drawQuest(c, h, statsBottom + 12 * d)
        var topY = drawCompass(c, h, w)
        if (h.bossName.isNotEmpty()) topY = drawBoss(c, h, w, topY)
        if (h.encounterTime >= 0f) topY = drawEncounter(c, h, w, topY)
        topY = drawNotes(c, h, w, topY)
        drawTopRight(c, h, w)
        drawHotbar(c, h, w, ht)
        drawCrosshair(c, h, w, ht)
        if (h.dialog.isNotEmpty()) drawDialog(c, h, w) else dialogRect.setEmpty()
        val hint = when (h.fishing) { 1 -> "Waiting for a bite..."; 2 -> "BITE! Attack now to reel in!"; else -> h.hint }
        if (hint.isNotEmpty()) drawHint(c, hint, w, topY + 8 * d, h.fishing == 2)
        if (h.flashLevelUp > 0f) {
            big.color = Color.argb((min(1f, h.flashLevelUp) * 255).toInt(), 255, 224, 122)
            c.drawText("LEVEL ${h.level}", w / 2, ht * 0.32f, big)
        }
        if (touchMode && !h.dead) drawTouch(c, h) else drawTopButtons(c)
        if (h.fade > 0.01f) { fill.color = Color.argb((min(1f, h.fade) * 255).toInt(), 0, 0, 0); c.drawRect(0f, 0f, w, ht, fill) }
        postInvalidateOnAnimation()
    }

    private fun drawLoading(c: Canvas, w: Float, h: Float) {
        fill.color = Color.rgb(8, 12, 14)
        c.drawRect(0f, 0f, w, h, fill)
        big.color = Color.rgb(200, 230, 220)
        c.drawText("RUSTBLOOM", w / 2, h * 0.45f, big)
        text.textAlign = Paint.Align.CENTER
        text.color = Color.rgb(140, 170, 165)
        val dots = ".".repeat(((System.nanoTime() / 400_000_000L) % 4).toInt())
        c.drawText("Waking the world$dots", w / 2, h * 0.45f + 34 * d, text)
        text.textAlign = Paint.Align.LEFT
        postInvalidateOnAnimation()
    }

    private fun drawVignette(c: Canvas, w: Float, h: Float, color: Int) {
        val edge = 90 * d
        fill.shader = LinearGradient(0f, 0f, edge, 0f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, edge, h, fill)
        fill.shader = LinearGradient(w, 0f, w - edge, 0f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP); c.drawRect(w - edge, 0f, w, h, fill)
        fill.shader = LinearGradient(0f, 0f, 0f, edge, color, Color.TRANSPARENT, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, edge, fill)
        fill.shader = LinearGradient(0f, h, 0f, h - edge, color, Color.TRANSPARENT, Shader.TileMode.CLAMP); c.drawRect(0f, h - edge, w, h, fill)
        fill.shader = null
    }

    private fun shadowText(c: Canvas, s: String, x: Float, y: Float, p: Paint) {
        val col = p.color
        p.color = Color.argb((Color.alpha(col) * 0.65f).toInt(), 0, 0, 0)
        c.drawText(s, x + 1.2f * d, y + 1.2f * d, p)
        p.color = col
        c.drawText(s, x, y, p)
    }

    /** Small rounded bar with a symbol drawn to its left. */
    private fun bar(c: Canvas, x: Float, y: Float, w: Float, v: Float, color: Int, icon: Int) {
        val bh = 7 * d
        fill.color = Color.argb(130, 0, 0, 0)
        rect.set(x, y, x + w, y + bh); c.drawRoundRect(rect, bh / 2, bh / 2, fill)
        fill.color = color
        rect.set(x, y, x + w * v.coerceIn(0f, 1f), y + bh); c.drawRoundRect(rect, bh / 2, bh / 2, fill)
        // icon
        val ix = x - 11 * d; val iy = y + bh / 2
        fill.color = color
        when (icon) {
            0 -> { // heart
                path.reset(); path.moveTo(ix, iy + 4 * d); path.cubicTo(ix - 7 * d, iy - 1 * d, ix - 3 * d, iy - 6 * d, ix, iy - 2.5f * d); path.cubicTo(ix + 3 * d, iy - 6 * d, ix + 7 * d, iy - 1 * d, ix, iy + 4 * d); c.drawPath(path, fill)
            }
            1 -> { c.drawCircle(ix, iy, 4.2f * d, fill); fill.color = Color.argb(200, 60, 30, 10); c.drawCircle(ix + 1.5f * d, iy - 1.5f * d, 1.3f * d, fill) } // food
            2 -> { path.reset(); path.moveTo(ix, iy - 5 * d); path.cubicTo(ix + 5 * d, iy + 1 * d, ix + 3 * d, iy + 5 * d, ix, iy + 5 * d); path.cubicTo(ix - 3 * d, iy + 5 * d, ix - 5 * d, iy + 1 * d, ix, iy - 5 * d); c.drawPath(path, fill) } // drop
            else -> { path.reset(); path.moveTo(ix + 1 * d, iy - 5 * d); path.lineTo(ix - 3 * d, iy + 1 * d); path.lineTo(ix, iy + 1 * d); path.lineTo(ix - 1 * d, iy + 5 * d); path.lineTo(ix + 3 * d, iy - 1 * d); path.lineTo(ix, iy - 1 * d); path.close(); c.drawPath(path, fill) } // bolt
        }
    }

    private fun drawStats(c: Canvas, h: HudData): Float {
        val x = 30 * d
        var y = 14 * d
        val bw = 112 * d
        bar(c, x, y, bw, h.hp / h.maxHp, if (h.hp < h.maxHp * 0.3f) Color.rgb(240, 70, 70) else Color.rgb(220, 80, 80), 0); y += 13 * d
        bar(c, x, y, bw, h.hunger / 100f, if (h.hunger < 20) Color.rgb(255, 130, 50) else Color.rgb(226, 166, 80), 1); y += 13 * d
        bar(c, x, y, bw, h.thirst / 100f, if (h.thirst < 20) Color.rgb(90, 130, 255) else Color.rgb(100, 180, 236), 2); y += 13 * d
        bar(c, x, y, bw, h.stamina / h.maxStamina, Color.rgb(150, 215, 130), 3); y += 18 * d
        val tc = when (h.tempState) { -2 -> Color.rgb(120, 190, 255); -1 -> Color.rgb(170, 215, 255); 1 -> Color.rgb(255, 180, 90); 2 -> Color.rgb(255, 110, 60); else -> Color.argb(220, 230, 230, 220) }
        small.color = tc
        val tLabel = when (h.tempState) { -2 -> "Freezing"; -1 -> "Cold"; 1 -> "Hot"; 2 -> "Overheating"; else -> "" }
        shadowText(c, "${h.temp.toInt()}°C $tLabel", 18 * d, y, small)
        small.color = Color.rgb(255, 224, 122)
        val lvl = "Lv ${h.level}" + if (h.skillPoints > 0) "  +${h.skillPoints} pts" else ""
        shadowText(c, lvl, 18 * d + small.measureText("${h.temp.toInt()}°C $tLabel") + 12 * d, y, small)
        y += 5 * d
        fill.color = Color.argb(110, 0, 0, 0); rect.set(18 * d, y, 142 * d, y + 3 * d); c.drawRect(rect, fill)
        fill.color = Color.rgb(255, 210, 90); rect.set(18 * d, y, 18 * d + 124 * d * h.xpFrac.coerceIn(0f, 1f), y + 3 * d); c.drawRect(rect, fill)
        y += 6 * d
        if (h.crouching) { y += 11 * d; small.color = Color.rgb(170, 200, 255); shadowText(c, "Sneaking", 18 * d, y, small) }
        return y
    }

    private fun drawQuest(c: Canvas, h: HudData, y0: Float) {
        if (h.questTitle.isEmpty()) return
        var y = y0 + 12 * d
        bold.color = Color.rgb(255, 214, 110)
        shadowText(c, h.questTitle, 18 * d, y, bold)
        y += 15 * d
        for (i in 0 until h.objectiveCount) {
            val s = h.objectives[i] ?: continue
            text.color = if (h.objectiveDone[i]) Color.rgb(140, 220, 140) else Color.argb(235, 255, 255, 255)
            shadowText(c, (if (h.objectiveDone[i]) "✓ " else "• ") + s, 18 * d, y, text)
            y += 14 * d
        }
    }

    private fun drawCompass(c: Canvas, h: HudData, w: Float): Float {
        val cw = min(300 * d, w * 0.36f)
        val cx = w / 2
        val y = 20 * d
        fill.color = Color.argb(100, 0, 0, 0)
        rect.set(cx - cw / 2, y - 13 * d, cx + cw / 2, y + 9 * d)
        c.drawRoundRect(rect, 8 * d, 8 * d, fill)
        val fov = PI_F * 0.9f
        small.textAlign = Paint.Align.CENTER
        val dirs = arrayOf("S" to 0f, "SE" to PI_F * 0.25f, "E" to PI_F * 0.5f, "NE" to PI_F * 0.75f, "N" to PI_F, "NW" to -PI_F * 0.75f, "W" to -PI_F * 0.5f, "SW" to -PI_F * 0.25f)
        for ((label, a) in dirs) {
            val rel = angleDiff(a, h.compassYaw)
            if (abs(rel) > fov / 2) continue
            val x = cx + rel / (fov / 2) * cw / 2
            small.color = if (label == "N") Color.rgb(255, 120, 100) else Color.argb(220, 255, 255, 255)
            c.drawText(label, x, y + 3.5f * d, small)
        }
        var labelY = y + 22 * d
        for (i in 0 until h.markerCount) {
            val rel = -h.markerAngle[i]
            val x = cx + rel.coerceIn(-fov / 2, fov / 2) / (fov / 2) * cw / 2
            fill.color = h.markerColor[i]
            path.reset(); path.moveTo(x, y + 10 * d); path.lineTo(x - 5 * d, y + 17 * d); path.lineTo(x + 5 * d, y + 17 * d); path.close()
            c.drawPath(path, fill)
            if (abs(rel) < 0.3f) {
                small.color = h.markerColor[i]
                shadowText(c, "${h.markerLabel[i]}  ${h.markerDist[i].toInt()}m", cx, labelY + 6 * d, small)
                labelY += 13 * d
            }
        }
        small.textAlign = Paint.Align.LEFT
        return labelY
    }

    private fun drawBoss(c: Canvas, h: HudData, w: Float, y0: Float): Float {
        val bw = w * 0.42f
        val y = y0 + 10 * d
        bold.textAlign = Paint.Align.CENTER
        bold.color = Color.rgb(255, 120, 140)
        shadowText(c, h.bossName, w / 2, y, bold)
        bold.textAlign = Paint.Align.LEFT
        fill.color = Color.argb(170, 0, 0, 0)
        rect.set(w / 2 - bw / 2, y + 5 * d, w / 2 + bw / 2, y + 13 * d); c.drawRoundRect(rect, 4 * d, 4 * d, fill)
        fill.color = Color.rgb(220, 40, 70)
        rect.set(w / 2 - bw / 2, y + 5 * d, w / 2 - bw / 2 + bw * h.bossHp, y + 13 * d); c.drawRoundRect(rect, 4 * d, 4 * d, fill)
        return y + 18 * d
    }

    private fun drawEncounter(c: Canvas, h: HudData, w: Float, y0: Float): Float {
        bold.textAlign = Paint.Align.CENTER
        bold.color = Color.rgb(120, 255, 200)
        val t = if (h.encounterTime > 0f) "  ${h.encounterTime.toInt()}s" else ""
        shadowText(c, "${h.encounterLabel}$t", w / 2, y0 + 14 * d, bold)
        bold.textAlign = Paint.Align.LEFT
        return y0 + 20 * d
    }

    private fun drawNotes(c: Canvas, h: HudData, w: Float, y0: Float): Float {
        var y = y0 + 6 * d
        text.textAlign = Paint.Align.CENTER
        val start = maxOf(0, h.noteCount - 3)
        for (i in start until h.noteCount) {
            val s = h.notes[i] ?: continue
            val a = 1f - ((h.noteAge[i] - 3f).coerceIn(0f, 1.5f) / 1.5f)
            val col = h.noteColor[i]
            val tw = text.measureText(s)
            fill.color = Color.argb((a * 120).toInt(), 0, 0, 0)
            rect.set(w / 2 - tw / 2 - 10 * d, y, w / 2 + tw / 2 + 10 * d, y + 18 * d)
            c.drawRoundRect(rect, 9 * d, 9 * d, fill)
            text.color = Color.argb((a * 255).toInt(), Color.red(col), Color.green(col), Color.blue(col))
            c.drawText(s, w / 2, y + 13 * d, text)
            y += 21 * d
        }
        text.textAlign = Paint.Align.LEFT
        return y
    }

    private fun drawTopRight(c: Canvas, h: HudData, w: Float) {
        val hh = h.time.toInt()
        val mm = ((h.time - hh) * 60).toInt()
        small.textAlign = Paint.Align.RIGHT
        small.color = Color.WHITE
        val x = w - 148 * d
        shadowText(c, String.format("Day %d  %02d:%02d", h.day, hh, mm), x, 24 * d, small)
        small.color = Color.argb(210, 220, 230, 240)
        shadowText(c, "${h.weather}  ·  ${h.biome}", x, 37 * d, small)
        if (game.settings.showFps) { small.color = Color.rgb(160, 255, 160); shadowText(c, "${h.fps} FPS", x, 50 * d, small) }
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawCrosshair(c: Canvas, h: HudData, w: Float, ht: Float) {
        val cx = w / 2; val cy = ht / 2
        if (h.buildMode) {
            bold.textAlign = Paint.Align.CENTER
            bold.color = if (h.buildValid) Color.rgb(150, 255, 160) else Color.rgb(255, 130, 120)
            shadowText(c, "Placing ${h.buildName}" + if (!h.buildValid) "  (blocked or missing materials)" else "", cx, cy + 56 * d, bold)
            bold.textAlign = Paint.Align.LEFT
        }
        stroke.color = Color.argb(200, 255, 255, 255)
        stroke.strokeWidth = 1.5f * d
        if (h.aiming) {
            c.drawCircle(cx, cy, 18 * d * (1.2f - h.bowDraw), stroke)
            fill.color = if (h.bowDraw >= 1f) Color.rgb(255, 220, 120) else Color.WHITE
            c.drawCircle(cx, cy, 2 * d, fill)
        } else {
            fill.color = Color.argb(190, 255, 255, 255)
            c.drawCircle(cx, cy, 2.2f * d, fill)
        }
        if (h.targetName.isNotEmpty()) {
            bold.textAlign = Paint.Align.CENTER
            bold.color = if (h.targetHostile) Color.rgb(255, 140, 120) else Color.rgb(230, 230, 220)
            shadowText(c, h.targetName, cx, cy - 34 * d, bold)
            val bw = 90 * d
            fill.color = Color.argb(150, 0, 0, 0); rect.set(cx - bw / 2, cy - 28 * d, cx + bw / 2, cy - 23 * d); c.drawRect(rect, fill)
            fill.color = if (h.targetHostile) Color.rgb(230, 70, 60) else Color.rgb(230, 200, 90)
            rect.set(cx - bw / 2, cy - 28 * d, cx - bw / 2 + bw * h.targetHp, cy - 23 * d); c.drawRect(rect, fill)
            bold.textAlign = Paint.Align.LEFT
        }
        if (h.prompt.isNotEmpty() && !h.buildMode) {
            bold.textAlign = Paint.Align.CENTER
            val key = when (game.settings.controls) { ControlScheme.TOUCH -> ""; ControlScheme.CONTROLLER -> "(X)  "; else -> "[E]  " }
            bold.color = Color.WHITE
            shadowText(c, key + h.prompt, cx, cy + 32 * d, bold)
            if (h.promptSub.isNotEmpty()) {
                small.textAlign = Paint.Align.CENTER
                small.color = Color.argb(200, 220, 220, 200)
                shadowText(c, h.promptSub, cx, cy + 47 * d, small)
                small.textAlign = Paint.Align.LEFT
            }
            bold.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawHotbar(c: Canvas, h: HudData, w: Float, ht: Float) {
        val s = 46 * d
        val gap = 5 * d
        val total = 6 * s + 5 * gap
        val x0 = w / 2 - total / 2
        val y0 = ht - s - 8 * d
        hotbarTop = y0
        for (i in 0 until 6) {
            val x = x0 + i * (s + gap)
            val r = hotbarRects[i]
            r.set(x, y0, x + s, y0 + s)
            fill.color = if (i == h.selected) Color.argb(170, 60, 50, 30) else Color.argb(115, 0, 0, 0)
            c.drawRoundRect(r, 9 * d, 9 * d, fill)
            if (i == h.selected) { stroke.color = Color.rgb(255, 210, 110); stroke.strokeWidth = 2 * d; c.drawRoundRect(r, 9 * d, 9 * d, stroke) }
            val itemIdx = h.hotbarItem[i]
            if (itemIdx >= 0) {
                val bmp = ItemIcons.get(Item.entries[itemIdx])
                rect.set(x + 6 * d, y0 + 5 * d, x + s - 6 * d, y0 + s - 7 * d)
                c.drawBitmap(bmp, null, rect, bmpPaint)
                small.textAlign = Paint.Align.RIGHT
                small.color = Color.WHITE
                if (h.hotbarCount[i] > 1) shadowText(c, "${h.hotbarCount[i]}", x + s - 4 * d, y0 + s - 6 * d, small)
                small.textAlign = Paint.Align.LEFT
                if (h.hotbarDur[i] >= 0f) {
                    fill.color = Color.argb(150, 0, 0, 0)
                    c.drawRect(x + 6 * d, y0 + s - 5 * d, x + s - 6 * d, y0 + s - 3 * d, fill)
                    fill.color = if (h.hotbarDur[i] > 0.3f) Color.rgb(140, 220, 120) else Color.rgb(240, 120, 70)
                    c.drawRect(x + 6 * d, y0 + s - 5 * d, x + 6 * d + (s - 12 * d) * h.hotbarDur[i].coerceIn(0f, 1f), y0 + s - 3 * d, fill)
                }
            }
            small.color = Color.argb(130, 255, 255, 255)
            c.drawText("${i + 1}", x + 4 * d, y0 + 10 * d, small)
        }
        val sel = h.hotbarName[h.selected]
        if (sel != null && h.dialog.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.color = Color.WHITE
            val extra = if (h.showAmmo) "  ·  ${h.ammoName} x${h.ammoCount}" else ""
            shadowText(c, sel + extra, w / 2, y0 - 7 * d, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val lines = ArrayList<String>()
        var cur = ""
        for (wd in s.split(" ")) {
            val t = if (cur.isEmpty()) wd else "$cur $wd"
            if (p.measureText(t) > maxW && cur.isNotEmpty()) { lines.add(cur); cur = wd } else cur = t
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }

    private fun drawDialog(c: Canvas, h: HudData, w: Float) {
        val maxW = min(w * 0.46f, 460 * d)
        val lines = wrap(h.dialog, text, maxW)
        val lh = 16 * d
        val boxH = lines.size * lh + 14 * d
        val y0 = hotbarTop - boxH - 10 * d
        dialogRect.set(w / 2 - maxW / 2 - 14 * d, y0, w / 2 + maxW / 2 + 14 * d, y0 + boxH)
        fill.color = Color.argb(165, 5, 12, 14)
        c.drawRoundRect(dialogRect, 10 * d, 10 * d, fill)
        stroke.color = Color.argb(150, 80, 220, 200); stroke.strokeWidth = 1.2f * d
        c.drawRoundRect(dialogRect, 10 * d, 10 * d, stroke)
        text.textAlign = Paint.Align.CENTER
        var y = y0 + 7 * d + lh * 0.8f
        for (l in lines) {
            text.color = Color.rgb(170, 240, 225)
            c.drawText(l, w / 2, y, text)
            y += lh
        }
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawHint(c: Canvas, hint: String, w: Float, y: Float, urgent: Boolean) {
        small.textAlign = Paint.Align.CENTER
        small.color = if (urgent) Color.rgb(255, 230, 120) else Color.argb(230, 255, 240, 200)
        val maxW = w * 0.5f
        var yy = y + 10 * d
        for (l in wrap(hint, small, maxW)) { shadowText(c, l, w / 2, yy, small); yy += 13 * d }
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawButton(c: Canvas, b: Btn, label: String, active: Boolean = false, accent: Int = Color.WHITE, icon: Item? = null) {
        if (!b.visible) return
        val op = game.settings.touchOpacity
        fun a(v: Int) = (v * op / 0.6f).toInt().coerceIn(0, 255)
        fill.color = Color.argb(a(if (b.pointer >= 0 || active) 150 else 80), 20, 20, 20)
        c.drawCircle(b.x, b.y, b.r, fill)
        stroke.color = Color.argb(a(200), Color.red(accent), Color.green(accent), Color.blue(accent))
        stroke.strokeWidth = 1.6f * d
        c.drawCircle(b.x, b.y, b.r, stroke)
        if (icon != null) {
            val s = b.r * 0.62f
            rect.set(b.x - s, b.y - s - 3 * d, b.x + s, b.y + s - 3 * d)
            bmpPaint.alpha = a(240)
            c.drawBitmap(ItemIcons.get(icon), null, rect, bmpPaint)
            bmpPaint.alpha = 255
        }
        small.textAlign = Paint.Align.CENTER
        small.color = Color.argb(a(240), 255, 255, 255)
        c.drawText(label, b.x, if (icon != null) b.y + b.r * 0.72f else b.y + 4 * d, small)
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawTopButtons(c: Canvas) {
        drawButton(c, btnMenu, "II")
        drawButton(c, btnPack, "PACK")
        drawButton(c, btnMap, "MAP")
    }

    private fun drawTouch(c: Canvas, h: HudData) {
        val build = h.buildMode
        btnRotate.visible = build
        btnCancel.visible = build
        // the use button only appears when there is something to use
        btnUse.visible = !build && h.prompt.isNotEmpty()
        btnCrouch.visible = !build
        btnAmmo.visible = h.showAmmo && !build
        btnItem.visible = !build && !h.showAmmo && h.selectedConsumable.isNotEmpty()
        btnSprint.visible = true
        val selItem = h.hotbarItem[h.selected].let { if (it >= 0) Item.entries[it] else null }
        drawButton(c, btnAttack, if (build) "PLACE" else if (h.showAmmo) "DRAW" else if (h.fishing > 0 || selItem == Item.FISHING_ROD) "CAST" else "ATTACK", accent = Color.rgb(255, 150, 120))
        drawButton(c, btnJump, "JUMP")
        if (!build) {
            if (btnUse.visible) drawButton(c, btnUse, "USE", active = true, accent = Color.rgb(255, 220, 120))
            drawButton(c, btnCrouch, if (h.crouching) "STAND" else "SNEAK", active = h.crouching)
            if (btnAmmo.visible) drawButton(c, btnAmmo, "ARROWS")
            if (btnItem.visible) drawButton(c, btnItem, h.selectedConsumable, icon = selItem)
        } else {
            drawButton(c, btnRotate, "ROTATE")
            drawButton(c, btnCancel, "CANCEL", accent = Color.rgb(255, 120, 120))
        }
        drawButton(c, btnSprint, if (h.sprinting) "RUN!" else "RUN", active = h.sprinting)
        drawTopButtons(c)
        // fixed joystick: the base never moves, only the knob
        val op = game.settings.touchOpacity
        fill.color = Color.argb((50 * op / 0.6f).toInt().coerceIn(0, 255), 255, 255, 255)
        c.drawCircle(joyCx, joyCy, joyR, fill)
        stroke.color = Color.argb((90 * op / 0.6f).toInt().coerceIn(0, 255), 255, 255, 255)
        stroke.strokeWidth = 1.5f * d
        c.drawCircle(joyCx, joyCy, joyR, stroke)
        fill.color = Color.argb((if (joyPointer >= 0) 190 else 110) * 1, 255, 255, 255)
        c.drawCircle(joyCx + joyX * joyR, joyCy + joyY * joyR, 22 * d, fill)
    }
}
