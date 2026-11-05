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
import com.rustbloom.game.engine.TWO_PI
import com.rustbloom.game.engine.angleDiff
import com.rustbloom.game.game.ControlScheme
import com.rustbloom.game.game.GameState
import com.rustbloom.game.game.HudData
import com.rustbloom.game.input.Action
import com.rustbloom.game.input.InputState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas HUD drawn over the GL view, plus the on-screen touch controls.
 */
@SuppressLint("ViewConstructor")
class HudView(ctx: Context, private val game: GameState, private val input: InputState) : View(ctx) {
    private val d = resources.displayMetrics.density
    var onMenu: (() -> Unit)? = null
    var onInventory: (() -> Unit)? = null
    var onMap: (() -> Unit)? = null

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * d; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * d; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 10.5f * d; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26 * d; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val rect = RectF()
    private val path = Path()

    // ---- touch controls
    private class Btn(val id: String, var x: Float, var y: Float, var r: Float, var label: String) {
        var pointer = -1
        var visible = true
        fun hit(px: Float, py: Float) = visible && hypot(px - x, py - y) < r * 1.25f
    }

    private val btnAttack = Btn("attack", 0f, 0f, 44f, "ATK")
    private val btnJump = Btn("jump", 0f, 0f, 30f, "JUMP")
    private val btnUse = Btn("use", 0f, 0f, 32f, "USE")
    private val btnCrouch = Btn("crouch", 0f, 0f, 25f, "CRCH")
    private val btnItem = Btn("item", 0f, 0f, 25f, "EAT")
    private val btnAmmo = Btn("ammo", 0f, 0f, 22f, "AMMO")
    private val btnSprint = Btn("sprint", 0f, 0f, 24f, "RUN")
    private val btnRotate = Btn("rotate", 0f, 0f, 28f, "ROT")
    private val btnCancel = Btn("cancel", 0f, 0f, 24f, "X")
    private val btnMenu = Btn("menu", 0f, 0f, 20f, "II")
    private val btnPack = Btn("pack", 0f, 0f, 22f, "PACK")
    private val btnMap = Btn("map", 0f, 0f, 20f, "MAP")
    private val buttons = listOf(btnAttack, btnJump, btnUse, btnCrouch, btnItem, btnAmmo, btnSprint, btnRotate, btnCancel, btnMenu, btnPack, btnMap)

    private var joyPointer = -1
    private var joyCx = 0f; private var joyCy = 0f
    private var joyX = 0f; private var joyY = 0f
    private var lookPointer = -1
    private var lookLastX = 0f; private var lookLastY = 0f
    private var attackLookLastX = 0f; private var attackLookLastY = 0f
    private val hotbarRects = Array(6) { RectF() }

    init {
        isClickable = true
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        layoutButtons(w.toFloat(), h.toFloat())
    }

    private fun layoutButtons(w: Float, h: Float) {
        fun place(b: Btn, x: Float, y: Float, r: Float) { b.x = x; b.y = y; b.r = r * d }
        place(btnAttack, w - 96 * d, h - 100 * d, 44f)
        place(btnJump, w - 190 * d, h - 52 * d, 30f)
        place(btnUse, w - 196 * d, h - 140 * d, 32f)
        place(btnCrouch, w - 52 * d, h - 196 * d, 25f)
        place(btnItem, w - 270 * d, h - 46 * d, 24f)
        place(btnAmmo, w - 130 * d, h - 200 * d, 22f)
        place(btnSprint, 205 * d, h - 172 * d, 22f)
        place(btnRotate, w - 196 * d, h - 140 * d, 30f)
        place(btnCancel, w - 52 * d, h - 196 * d, 25f)
        place(btnMenu, w - 30 * d, 30 * d, 19f)
        place(btnPack, w - 80 * d, 30 * d, 21f)
        place(btnMap, w - 128 * d, 30 * d, 19f)
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
            MotionEvent.ACTION_CANCEL -> { for (i in 0 until e.pointerCount) up(e.getPointerId(i)) }
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float, h: HudData) {
        if (h.loading || h.dead) return
        // dialog skip
        if (h.dialog.isNotEmpty() && y > height * 0.62f && y < height * 0.78f && abs(x - width / 2f) < width * 0.3f) { input.push(Action.SKIP_DIALOG); return }
        // hotbar
        for (i in 0 until 6) if (hotbarRects[i].contains(x, y)) { input.push(Action.entries[Action.HOTBAR_1.ordinal + i]); return }
        // top buttons work in every scheme
        if (btnMenu.hit(x, y)) { onMenu?.invoke(); return }
        if (btnPack.hit(x, y)) { onInventory?.invoke(); return }
        if (btnMap.hit(x, y)) { onMap?.invoke(); return }
        if (touchMode) {
            for (b in buttons) {
                if (!b.visible || b.pointer != -1 || !b.hit(x, y)) continue
                if (b === btnMenu || b === btnPack || b === btnMap) continue
                b.pointer = id
                when (b.id) {
                    "attack" -> {
                        if (h.buildMode) input.push(Action.ATTACK_PRESS) else input.attackHeld = true
                        attackLookLastX = x; attackLookLastY = y
                    }
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
        }
        if (x < width * 0.42f && joyPointer == -1 && y > height * 0.3f) {
            joyPointer = id
            joyCx = x; joyCy = y; joyX = 0f; joyY = 0f
            input.setTouchMove(0f, 0f)
            return
        }
        if (lookPointer == -1) {
            lookPointer = id
            lookLastX = x; lookLastY = y
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val sens = 0.16f
        if (id == joyPointer) {
            val r = 56 * d
            var dx = (x - joyCx) / r
            var dy = (y - joyCy) / r
            val l = hypot(dx, dy)
            if (l > 1f) {
                // drag the joystick base along for comfort
                joyCx += (x - joyCx) * (1 - 1 / l) * 0.5f
                joyCy += (y - joyCy) * (1 - 1 / l) * 0.5f
                dx /= l; dy /= l
            }
            joyX = dx; joyY = dy
            val dz = 0.12f
            val mx = if (abs(dx) < dz) 0f else dx
            val my = if (abs(dy) < dz) 0f else -dy
            input.setTouchMove(mx, my)
        } else if (id == lookPointer) {
            input.addLook((x - lookLastX) * sens, (y - lookLastY) * sens)
            lookLastX = x; lookLastY = y
        } else if (id == btnAttack.pointer) {
            // attack button doubles as aim/look while held
            input.addLook((x - attackLookLastX) * sens, (y - attackLookLastY) * sens)
            attackLookLastX = x; attackLookLastY = y
        }
    }

    private fun up(id: Int) {
        if (id == joyPointer) { joyPointer = -1; input.setTouchMove(0f, 0f); joyX = 0f; joyY = 0f }
        if (id == lookPointer) lookPointer = -1
        for (b in buttons) if (b.pointer == id) {
            b.pointer = -1
            if (b === btnAttack) input.attackHeld = false
        }
    }

    fun releaseAll() {
        joyPointer = -1; lookPointer = -1
        for (b in buttons) b.pointer = -1
        input.attackHeld = false
        input.setTouchMove(0f, 0f)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val h = game.hud
        val w = width.toFloat()
        val ht = height.toFloat()
        if (h.loading) { drawLoading(c, w, ht); return }

        // screen tints
        if (h.underwater) { fill.color = Color.argb(90, 10, 60, 70); c.drawRect(0f, 0f, w, ht, fill) }
        if (h.hurt > 0.01f) drawVignette(c, w, ht, Color.argb((min(1f, h.hurt) * 150).toInt(), 160, 0, 0))
        else if (h.hp < h.maxHp * 0.25f) drawVignette(c, w, ht, Color.argb((60 + 40 * sin(System.nanoTime() / 2e8)).toInt(), 140, 0, 0))
        if (h.heal > 0.01f) drawVignette(c, w, ht, Color.argb((h.heal * 80).toInt(), 60, 200, 90))
        if (h.tempState <= -1) drawVignette(c, w, ht, Color.argb(if (h.tempState == -2) 110 else 60, 160, 210, 255))
        if (h.tempState >= 1) drawVignette(c, w, ht, Color.argb(if (h.tempState == 2) 100 else 50, 255, 150, 40))

        drawStats(c, h)
        drawCompass(c, h, w)
        drawQuest(c, h, w)
        drawCrosshair(c, h, w, ht)
        drawHotbar(c, h, w, ht)
        drawNotes(c, h, ht)
        drawTopRight(c, h, w)
        if (h.bossName.isNotEmpty()) drawBoss(c, h, w)
        if (h.encounterTime >= 0f) drawEncounter(c, h, w)
        if (h.dialog.isNotEmpty()) drawDialog(c, h, w, ht)
        if (h.hint.isNotEmpty()) drawHint(c, h, w, ht)
        if (h.flashLevelUp > 0f) {
            big.color = Color.argb((min(1f, h.flashLevelUp) * 255).toInt(), 255, 224, 122)
            c.drawText("LEVEL ${h.level}", w / 2, ht * 0.3f, big)
        }
        if (touchMode && !h.dead) drawTouch(c, h)
        else drawTopButtonsOnly(c)
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
        fill.shader = LinearGradient(0f, 0f, edge, 0f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, edge, h, fill)
        fill.shader = LinearGradient(w, 0f, w - edge, 0f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(w - edge, 0f, w, h, fill)
        fill.shader = LinearGradient(0f, 0f, 0f, edge, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, edge, fill)
        fill.shader = LinearGradient(0f, h, 0f, h - edge, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, h - edge, w, h, fill)
        fill.shader = null
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, v: Float, color: Int, label: String) {
        val bh = 7 * d
        fill.color = Color.argb(140, 0, 0, 0)
        rect.set(x, y, x + w, y + bh); c.drawRoundRect(rect, bh / 2, bh / 2, fill)
        fill.color = color
        rect.set(x, y, x + w * v.coerceIn(0f, 1f), y + bh); c.drawRoundRect(rect, bh / 2, bh / 2, fill)
        small.color = Color.argb(220, 255, 255, 255)
        c.drawText(label, x - 16 * d, y + bh, small)
    }

    private fun drawStats(c: Canvas, h: HudData) {
        val x = 34 * d
        var y = 16 * d
        val bw = 120 * d
        bar(c, x, y, bw, h.hp / h.maxHp, if (h.hp < h.maxHp * 0.3f) Color.rgb(230, 60, 60) else Color.rgb(214, 70, 70), "HP")
        y += 14 * d
        bar(c, x, y, bw, h.hunger / 100f, if (h.hunger < 20) Color.rgb(255, 120, 40) else Color.rgb(222, 160, 70), "FD")
        y += 14 * d
        bar(c, x, y, bw, h.thirst / 100f, if (h.thirst < 20) Color.rgb(80, 120, 255) else Color.rgb(90, 170, 230), "H2O")
        y += 14 * d
        bar(c, x, y, bw, h.stamina / h.maxStamina, Color.rgb(140, 210, 120), "ST")
        y += 20 * d
        // temperature + level
        val tc = when (h.tempState) { -2 -> Color.rgb(120, 190, 255); -1 -> Color.rgb(170, 215, 255); 1 -> Color.rgb(255, 180, 90); 2 -> Color.rgb(255, 110, 60); else -> Color.rgb(220, 220, 210) }
        small.color = tc
        val tLabel = when (h.tempState) { -2 -> "FREEZING"; -1 -> "Cold"; 1 -> "Hot"; 2 -> "OVERHEATING"; else -> "Comfortable" }
        c.drawText("${h.temp.toInt()}°C  $tLabel", 18 * d, y, small)
        y += 14 * d
        small.color = Color.rgb(255, 224, 122)
        c.drawText("LV ${h.level}" + if (h.skillPoints > 0) "  (+${h.skillPoints} skill pts)" else "", 18 * d, y, small)
        fill.color = Color.argb(120, 0, 0, 0)
        rect.set(18 * d, y + 4 * d, 18 * d + 136 * d, y + 7 * d); c.drawRect(rect, fill)
        fill.color = Color.rgb(255, 210, 90)
        rect.set(18 * d, y + 4 * d, 18 * d + 136 * d * h.xpFrac.coerceIn(0f, 1f), y + 7 * d); c.drawRect(rect, fill)
        if (h.crouching) { small.color = Color.rgb(170, 200, 255); c.drawText("CROUCHED (stealthy)", 18 * d, y + 22 * d, small) }
    }

    private fun drawCompass(c: Canvas, h: HudData, w: Float) {
        val cw = 300 * d
        val cx = w / 2
        val y = 22 * d
        fill.color = Color.argb(110, 0, 0, 0)
        rect.set(cx - cw / 2, y - 14 * d, cx + cw / 2, y + 10 * d)
        c.drawRoundRect(rect, 8 * d, 8 * d, fill)
        val fov = PI_F * 0.9f
        small.textAlign = Paint.Align.CENTER
        val dirs = arrayOf("S" to 0f, "SE" to PI_F * 0.25f, "E" to PI_F * 0.5f, "NE" to PI_F * 0.75f, "N" to PI_F, "NW" to -PI_F * 0.75f, "W" to -PI_F * 0.5f, "SW" to -PI_F * 0.25f)
        // world: yaw 0 faces +Z (south on our map), yaw pi faces -Z (north). Increasing yaw turns left.
        for ((label, a) in dirs) {
            val rel = angleDiff(a, h.compassYaw)
            if (abs(rel) > fov / 2) continue
            val x = cx + rel / (fov / 2) * cw / 2
            small.color = if (label == "N") Color.rgb(255, 120, 100) else Color.argb(220, 255, 255, 255)
            c.drawText(label, x, y + 4 * d, small)
        }
        for (i in 0 until h.markerCount) {
            val rel = -h.markerAngle[i]
            val clamped = rel.coerceIn(-fov / 2, fov / 2)
            val x = cx + clamped / (fov / 2) * cw / 2
            fill.color = h.markerColor[i]
            path.reset()
            path.moveTo(x, y + 12 * d); path.lineTo(x - 5 * d, y + 20 * d); path.lineTo(x + 5 * d, y + 20 * d); path.close()
            c.drawPath(path, fill)
            if (abs(rel) < 0.35f) {
                small.color = h.markerColor[i]
                c.drawText("${h.markerLabel[i]} ${h.markerDist[i].toInt()}m", x, y + 32 * d, small)
            }
        }
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawQuest(c: Canvas, h: HudData, w: Float) {
        if (h.questTitle.isEmpty()) return
        val x = 18 * d
        var y = 150 * d
        bold.color = Color.rgb(255, 214, 110)
        shadowText(c, h.questTitle, x, y, bold)
        y += 16 * d
        for (i in 0 until h.objectiveCount) {
            val s = h.objectives[i] ?: continue
            text.color = if (h.objectiveDone[i]) Color.rgb(140, 220, 140) else Color.argb(235, 255, 255, 255)
            shadowText(c, (if (h.objectiveDone[i]) "✓ " else "• ") + s, x, y, text)
            y += 15 * d
        }
    }

    private fun shadowText(c: Canvas, s: String, x: Float, y: Float, p: Paint) {
        val col = p.color
        p.color = Color.argb(160, 0, 0, 0)
        c.drawText(s, x + 1.2f * d, y + 1.2f * d, p)
        p.color = col
        c.drawText(s, x, y, p)
    }

    private fun drawCrosshair(c: Canvas, h: HudData, w: Float, ht: Float) {
        val cx = w / 2; val cy = ht / 2
        if (h.buildMode) {
            bold.textAlign = Paint.Align.CENTER
            bold.color = if (h.buildValid) Color.rgb(150, 255, 160) else Color.rgb(255, 130, 120)
            shadowText(c, "Placing: ${h.buildName}" + if (!h.buildValid) " (blocked / missing materials)" else "", cx, cy + 60 * d, bold)
            bold.textAlign = Paint.Align.LEFT
        }
        stroke.color = Color.argb(200, 255, 255, 255)
        stroke.strokeWidth = 1.5f * d
        if (h.aiming) {
            val r = 18 * d * (1.2f - h.bowDraw)
            c.drawCircle(cx, cy, r, stroke)
            fill.color = if (h.bowDraw >= 1f) Color.rgb(255, 220, 120) else Color.WHITE
            c.drawCircle(cx, cy, 2 * d, fill)
        } else {
            fill.color = Color.argb(200, 255, 255, 255)
            c.drawCircle(cx, cy, 2.2f * d, fill)
        }
        if (h.targetName.isNotEmpty()) {
            bold.textAlign = Paint.Align.CENTER
            bold.color = if (h.targetHostile) Color.rgb(255, 140, 120) else Color.rgb(230, 230, 220)
            shadowText(c, h.targetName, cx, cy - 36 * d, bold)
            val bw = 90 * d
            fill.color = Color.argb(150, 0, 0, 0)
            rect.set(cx - bw / 2, cy - 30 * d, cx + bw / 2, cy - 25 * d); c.drawRect(rect, fill)
            fill.color = if (h.targetHostile) Color.rgb(230, 70, 60) else Color.rgb(230, 200, 90)
            rect.set(cx - bw / 2, cy - 30 * d, cx - bw / 2 + bw * h.targetHp, cy - 25 * d); c.drawRect(rect, fill)
            bold.textAlign = Paint.Align.LEFT
        }
        if (h.prompt.isNotEmpty()) {
            bold.textAlign = Paint.Align.CENTER
            val key = when (game.settings.controls) { ControlScheme.TOUCH -> "USE"; ControlScheme.CONTROLLER -> "(X)"; else -> "[E]" }
            bold.color = Color.WHITE
            shadowText(c, "$key  ${h.prompt}", cx, cy + 34 * d, bold)
            if (h.promptSub.isNotEmpty()) {
                small.textAlign = Paint.Align.CENTER
                small.color = Color.argb(200, 220, 220, 200)
                shadowText(c, h.promptSub, cx, cy + 50 * d, small)
                small.textAlign = Paint.Align.LEFT
            }
            bold.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawHotbar(c: Canvas, h: HudData, w: Float, ht: Float) {
        val s = 46 * d
        val gap = 6 * d
        val total = 6 * s + 5 * gap
        val x0 = w / 2 - total / 2
        val y0 = ht - s - 10 * d
        for (i in 0 until 6) {
            val x = x0 + i * (s + gap)
            val r = hotbarRects[i]
            r.set(x, y0, x + s, y0 + s)
            fill.color = if (i == h.selected) Color.argb(170, 60, 50, 30) else Color.argb(120, 0, 0, 0)
            c.drawRoundRect(r, 8 * d, 8 * d, fill)
            if (i == h.selected) { stroke.color = Color.rgb(255, 210, 110); stroke.strokeWidth = 2 * d; c.drawRoundRect(r, 8 * d, 8 * d, stroke) }
            val name = h.hotbarShort[i]
            if (name != null) {
                fill.color = h.hotbarColor[i]
                c.drawCircle(x + s / 2, y0 + s / 2 - 4 * d, 12 * d, fill)
                small.textAlign = Paint.Align.CENTER
                small.color = Color.argb(240, 20, 20, 20)
                c.drawText(name, x + s / 2, y0 + s / 2, small)
                small.color = Color.WHITE
                if (h.hotbarCount[i] > 1) c.drawText("${h.hotbarCount[i]}", x + s - 9 * d, y0 + s - 5 * d, small)
                small.textAlign = Paint.Align.LEFT
                if (h.hotbarDur[i] >= 0f) {
                    fill.color = Color.argb(150, 0, 0, 0)
                    c.drawRect(x + 6 * d, y0 + s - 6 * d, x + s - 6 * d, y0 + s - 3 * d, fill)
                    fill.color = if (h.hotbarDur[i] > 0.3f) Color.rgb(140, 220, 120) else Color.rgb(240, 120, 70)
                    c.drawRect(x + 6 * d, y0 + s - 6 * d, x + 6 * d + (s - 12 * d) * h.hotbarDur[i].coerceIn(0f, 1f), y0 + s - 3 * d, fill)
                }
            }
            small.color = Color.argb(150, 255, 255, 255)
            c.drawText("${i + 1}", x + 4 * d, y0 + 11 * d, small)
        }
        val sel = h.hotbarName[h.selected]
        if (sel != null) {
            text.textAlign = Paint.Align.CENTER
            text.color = Color.WHITE
            val extra = if (h.showAmmo) "  -  ${h.ammoName} x${h.ammoCount}" else ""
            shadowText(c, sel + extra, w / 2, y0 - 8 * d, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawNotes(c: Canvas, h: HudData, ht: Float) {
        var y = ht - 110 * d
        for (i in h.noteCount - 1 downTo 0) {
            val s = h.notes[i] ?: continue
            val a = (1f - (h.noteAge[i] - 3.5f).coerceIn(0f, 1.5f) / 1.5f)
            val col = h.noteColor[i]
            text.color = Color.argb((a * 255).toInt(), Color.red(col), Color.green(col), Color.blue(col))
            val x = if (touchMode) 18 * d else 18 * d
            if (touchMode) {
                // notes sit above the joystick area on the left
                c.drawText(s, x, y - 120 * d, text)
            } else c.drawText(s, x, y, text)
            y -= 17 * d
        }
    }

    private fun drawTopRight(c: Canvas, h: HudData, w: Float) {
        val hh = h.time.toInt()
        val mm = ((h.time - hh) * 60).toInt()
        small.textAlign = Paint.Align.RIGHT
        small.color = Color.WHITE
        val x = w - 160 * d
        shadowText(c, String.format("Day %d  %02d:%02d", h.day, hh, mm), x, 24 * d, small)
        small.color = Color.argb(210, 220, 230, 240)
        shadowText(c, "${h.weather}  -  ${h.biome}", x, 38 * d, small)
        if (game.settings.showFps) { small.color = Color.rgb(160, 255, 160); shadowText(c, "${h.fps} FPS", x, 52 * d, small) }
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawBoss(c: Canvas, h: HudData, w: Float) {
        val bw = w * 0.5f
        val y = 64 * d
        bold.textAlign = Paint.Align.CENTER
        bold.color = Color.rgb(255, 120, 140)
        shadowText(c, h.bossName, w / 2, y - 6 * d, bold)
        bold.textAlign = Paint.Align.LEFT
        fill.color = Color.argb(170, 0, 0, 0)
        rect.set(w / 2 - bw / 2, y, w / 2 + bw / 2, y + 9 * d); c.drawRect(rect, fill)
        fill.color = Color.rgb(220, 40, 70)
        rect.set(w / 2 - bw / 2, y, w / 2 - bw / 2 + bw * h.bossHp, y + 9 * d); c.drawRect(rect, fill)
    }

    private fun drawEncounter(c: Canvas, h: HudData, w: Float) {
        bold.textAlign = Paint.Align.CENTER
        bold.color = Color.rgb(120, 255, 200)
        val t = if (h.encounterTime > 0f) "  ${h.encounterTime.toInt()}s" else ""
        shadowText(c, "${h.encounterLabel}$t", w / 2, 88 * d, bold)
        bold.textAlign = Paint.Align.LEFT
    }

    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val words = s.split(" ")
        val lines = ArrayList<String>()
        var cur = ""
        for (wd in words) {
            val t = if (cur.isEmpty()) wd else "$cur $wd"
            if (p.measureText(t) > maxW && cur.isNotEmpty()) { lines.add(cur); cur = wd } else cur = t
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }

    private fun drawDialog(c: Canvas, h: HudData, w: Float, ht: Float) {
        val maxW = w * 0.55f
        val lines = wrap(h.dialog, text, maxW)
        val lh = 17 * d
        val boxH = lines.size * lh + 16 * d
        val y0 = ht * 0.66f
        fill.color = Color.argb(150, 5, 12, 14)
        rect.set(w / 2 - maxW / 2 - 14 * d, y0, w / 2 + maxW / 2 + 14 * d, y0 + boxH)
        c.drawRoundRect(rect, 10 * d, 10 * d, fill)
        stroke.color = Color.argb(160, 80, 220, 200); stroke.strokeWidth = 1.2f * d
        c.drawRoundRect(rect, 10 * d, 10 * d, stroke)
        text.textAlign = Paint.Align.CENTER
        var y = y0 + 8 * d + lh * 0.8f
        for (l in lines) {
            text.color = if (l.startsWith("ECHO:")) Color.rgb(150, 240, 225) else Color.rgb(225, 240, 235)
            c.drawText(l, w / 2, y, text)
            y += lh
        }
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawHint(c: Canvas, h: HudData, w: Float, ht: Float) {
        small.textAlign = Paint.Align.CENTER
        small.color = Color.argb(230, 255, 240, 200)
        shadowText(c, h.hint, w / 2, ht * 0.22f, small)
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawButton(c: Canvas, b: Btn, label: String, active: Boolean = false, accent: Int = Color.WHITE) {
        if (!b.visible) return
        val op = game.settings.touchOpacity
        fill.color = Color.argb(((if (b.pointer >= 0 || active) 150 else 80) * op / 0.6f).toInt().coerceIn(0, 255), 20, 20, 20)
        c.drawCircle(b.x, b.y, b.r, fill)
        stroke.color = Color.argb((200 * op / 0.6f).toInt().coerceIn(0, 255), Color.red(accent), Color.green(accent), Color.blue(accent))
        stroke.strokeWidth = 1.6f * d
        c.drawCircle(b.x, b.y, b.r, stroke)
        small.textAlign = Paint.Align.CENTER
        small.color = Color.argb((235 * op / 0.6f).toInt().coerceIn(0, 255), 255, 255, 255)
        c.drawText(label, b.x, b.y + 4 * d, small)
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawTopButtonsOnly(c: Canvas) {
        drawButton(c, btnMenu, "II")
        drawButton(c, btnPack, "PACK")
        drawButton(c, btnMap, "MAP")
    }

    private fun drawTouch(c: Canvas, h: HudData) {
        val build = h.buildMode
        btnRotate.visible = build
        btnCancel.visible = build
        btnUse.visible = !build
        btnCrouch.visible = !build
        btnAmmo.visible = h.showAmmo && !build
        val selName = h.hotbarName[h.selected] ?: ""
        btnItem.visible = !build && h.hasTool && (selName.isNotEmpty())
        drawButton(c, btnAttack, if (build) "PLACE" else if (h.showAmmo) "DRAW" else "ATK", accent = Color.rgb(255, 150, 120))
        drawButton(c, btnJump, "JUMP")
        if (!build) {
            drawButton(c, btnUse, if (h.prompt.isNotEmpty()) "USE" else "use", active = h.prompt.isNotEmpty(), accent = if (h.prompt.isNotEmpty()) Color.rgb(255, 220, 120) else Color.WHITE)
            drawButton(c, btnCrouch, if (h.crouching) "STAND" else "CRCH", active = h.crouching)
            if (btnAmmo.visible) drawButton(c, btnAmmo, "AMMO")
            if (btnItem.visible) drawButton(c, btnItem, "USE IT")
        } else {
            drawButton(c, btnRotate, "ROT")
            drawButton(c, btnCancel, "X", accent = Color.rgb(255, 120, 120))
        }
        drawButton(c, btnSprint, if (h.sprinting) "RUN!" else "RUN", active = h.sprinting)
        drawTopButtonsOnly(c)
        // joystick
        if (joyPointer >= 0) {
            val r = 56 * d
            fill.color = Color.argb(60, 255, 255, 255)
            c.drawCircle(joyCx, joyCy, r, fill)
            fill.color = Color.argb(150, 255, 255, 255)
            c.drawCircle(joyCx + joyX * r, joyCy + joyY * r, 22 * d, fill)
        } else {
            fill.color = Color.argb((35 * game.settings.touchOpacity / 0.6f).toInt(), 255, 255, 255)
            c.drawCircle(110 * d, height - 100 * d, 56 * d, fill)
            small.textAlign = Paint.Align.CENTER
            small.color = Color.argb(90, 255, 255, 255)
            c.drawText("MOVE", 110 * d, height - 96 * d, small)
            small.textAlign = Paint.Align.LEFT
        }
    }

}
