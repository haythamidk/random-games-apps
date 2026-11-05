package com.rustbloom.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.rustbloom.game.game.BuildType
import com.rustbloom.game.game.Item
import kotlin.math.cos
import kotlin.math.sin

/** Vector-style icons drawn with Canvas at startup, one bitmap per item and build piece. */
object ItemIcons {
    const val SIZE = 128
    private val cache = HashMap<Any, Bitmap>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val r = RectF()

    @Synchronized fun get(item: Item): Bitmap = cache.getOrPut(item) { draw { c -> drawItem(c, item) } }
    @Synchronized fun get(b: BuildType): Bitmap = cache.getOrPut(b) { draw { c -> drawBuild(c, b) } }

    /** Pre-renders every icon (call from a background thread). */
    fun warmUp() { for (i in Item.entries) get(i); for (b in BuildType.entries) get(b) }

    private inline fun draw(block: (Canvas) -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        block(c)
        return bmp
    }

    private fun fill(col: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = col }
    private fun line(col: Int, w: Float) { s.shader = null; s.color = col; s.strokeWidth = w }
    private fun shade(col: Int, f: Float): Int = Color.rgb((Color.red(col) * f).toInt().coerceIn(0, 255), (Color.green(col) * f).toInt().coerceIn(0, 255), (Color.blue(col) * f).toInt().coerceIn(0, 255))
    private fun outline(c: Canvas, pth: Path) { line(Color.argb(170, 0, 0, 0), 4f); c.drawPath(pth, s) }

    private fun log(c: Canvas, x: Float, y: Float, len: Float, rad: Float, ang: Float) {
        c.save(); c.rotate(ang, x, y)
        fill(0xff7a5434.toInt()); r.set(x - len / 2, y - rad, x + len / 2, y + rad); c.drawRoundRect(r, rad * 0.4f, rad * 0.4f, p)
        line(0xff5a3c22.toInt(), 3f); c.drawLine(x - len / 2 + 8, y - rad * 0.3f, x + len / 2 - 14, y - rad * 0.3f, s); c.drawLine(x - len / 2 + 14, y + rad * 0.35f, x + len / 2 - 20, y + rad * 0.35f, s)
        fill(0xffd8b47a.toInt()); c.drawOval(x + len / 2 - rad * 0.7f, y - rad, x + len / 2 + rad * 0.7f, y + rad, p)
        line(0xffa07a48.toInt(), 2.5f); c.drawOval(x + len / 2 - rad * 0.4f, y - rad * 0.55f, x + len / 2 + rad * 0.4f, y + rad * 0.55f, s)
        c.restore()
    }

    private fun rock(c: Canvas, cx: Float, cy: Float, rad: Float, col: Int) {
        path.reset()
        for (i in 0 until 7) {
            val a = i / 7f * 6.283f; val rr = rad * (0.8f + ((i * 37) % 5) * 0.06f)
            val x = cx + cos(a) * rr; val y = cy + sin(a) * rr * 0.8f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(cx - rad, cy - rad, cx + rad, cy + rad, shade(col, 1.25f), shade(col, 0.7f), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        outline(c, path)
    }

    private fun glowOrb(c: Canvas, cx: Float, cy: Float, rad: Float, col: Int) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, rad * 1.6f, intArrayOf(Color.WHITE, col, Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 1.6f, p); p.shader = null
    }

    private fun handle(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        line(0xff3a2616.toInt(), 13f); c.drawLine(x0, y0, x1, y1, s)
        line(0xff9a6a3e.toInt(), 9f); c.drawLine(x0, y0, x1, y1, s)
    }

    private fun drawItem(c: Canvas, item: Item) {
        val col = item.color
        when (item) {
            Item.WOOD -> { log(c, 60f, 80f, 86f, 15f, -20f); log(c, 68f, 54f, 80f, 13f, -12f) }
            Item.STONE -> { rock(c, 48f, 78f, 26f, 0xff8d8d8d.toInt()); rock(c, 82f, 64f, 22f, 0xff9a9890.toInt()) }
            Item.FIBER -> {
                for (i in -3..3) { line(shade(col, 0.8f + (i + 3) * 0.06f), 5f); path.reset(); path.moveTo(64f + i * 4, 112f); path.quadTo(64f + i * 9, 60f, 64f + i * 14, 16f); c.drawPath(path, s) }
                fill(0xffb08a50.toInt()); r.set(46f, 70f, 82f, 82f); c.drawRoundRect(r, 4f, 4f, p)
            }
            Item.RESIN -> { path.reset(); path.moveTo(64f, 18f); path.cubicTo(96f, 60f, 96f, 104f, 64f, 108f); path.cubicTo(32f, 104f, 32f, 60f, 64f, 18f); p.style = Paint.Style.FILL; p.shader = LinearGradient(40f, 30f, 90f, 108f, 0xfff0c050.toInt(), 0xffa05a10.toInt(), Shader.TileMode.CLAMP); c.drawPath(path, p); p.shader = null; outline(c, path); fill(0x99ffffff.toInt()); c.drawOval(50f, 52f, 60f, 70f, p) }
            Item.HIDE, Item.FUR -> {
                path.reset(); path.moveTo(30f, 30f); path.quadTo(64f, 40f, 98f, 30f); path.quadTo(92f, 64f, 106f, 100f); path.quadTo(64f, 88f, 22f, 100f); path.quadTo(36f, 64f, 30f, 30f)
                p.style = Paint.Style.FILL; p.shader = RadialGradient(64f, 64f, 60f, shade(col, 1.2f), shade(col, 0.75f), Shader.TileMode.CLAMP); c.drawPath(path, p); p.shader = null; outline(c, path)
                if (item == Item.FUR) { line(shade(col, 0.6f), 3f); for (i in 0 until 12) { val x = 36f + i * 5f; c.drawLine(x, 50f + (i % 3) * 10, x + 4, 60f + (i % 3) * 10, s) } }
            }
            Item.FEATHER -> {
                path.reset(); path.moveTo(30f, 108f); path.quadTo(40f, 40f, 100f, 18f); path.quadTo(80f, 70f, 30f, 108f)
                fill(0xffeae6dc.toInt()); c.drawPath(path, p); outline(c, path); line(0xff8a8070.toInt(), 3f); c.drawLine(28f, 112f, 96f, 22f, s)
            }
            Item.SCRAP -> { c.save(); c.rotate(-15f, 64f, 64f); fill(0xff8a7a6a.toInt()); path.reset(); path.moveTo(24f, 40f); path.lineTo(80f, 30f); path.lineTo(96f, 60f); path.lineTo(70f, 74f); path.lineTo(88f, 96f); path.lineTo(30f, 92f); path.close(); c.drawPath(path, p); outline(c, path); fill(0xffa05a30.toInt()); c.drawCircle(50f, 60f, 9f, p); c.drawCircle(72f, 82f, 6f, p); c.restore() }
            Item.WIRE -> { line(0xffd2803c.toInt(), 7f); for (i in 0 until 5) c.drawOval(30f + i * 8f, 30f, 66f + i * 8f, 98f, s); line(0xff8a4a1a.toInt(), 2f); c.drawLine(20f, 100f, 108f, 26f, s) }
            Item.ALLOY -> { path.reset(); path.moveTo(26f, 86f); path.lineTo(40f, 50f); path.lineTo(100f, 50f); path.lineTo(110f, 86f); path.close(); p.style = Paint.Style.FILL; p.shader = LinearGradient(26f, 50f, 110f, 86f, 0xffd0eef8.toInt(), 0xff6a9aaa.toInt(), Shader.TileMode.CLAMP); c.drawPath(path, p); p.shader = null; outline(c, path); line(0xccffffff.toInt(), 3f); c.drawLine(44f, 56f, 96f, 56f, s) }
            Item.CORE -> { glowOrb(c, 64f, 64f, 26f, 0xff4fe3d2.toInt()); path.reset(); for (i in 0 until 6) { val a = i / 6f * 6.283f; val x = 64 + cos(a) * 24; val y = 64 + sin(a) * 24; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }; path.close(); line(0xff1a6a60.toInt(), 5f); c.drawPath(path, s) }
            Item.LENS -> { fill(0xff2a3a48.toInt()); c.drawCircle(64f, 64f, 40f, p); p.style = Paint.Style.FILL; p.shader = RadialGradient(54f, 54f, 36f, 0xffe0f8ff.toInt(), 0xff3a8ad8.toInt(), Shader.TileMode.CLAMP); c.drawCircle(64f, 64f, 30f, p); p.shader = null; fill(0xccffffff.toInt()); c.drawCircle(52f, 52f, 7f, p) }
            Item.CRYO_SHARD -> { for ((i, a) in listOf(-20f, 0f, 22f).withIndex()) { c.save(); c.rotate(a, 64f, 110f); path.reset(); path.moveTo(64f, 16f + i * 8); path.lineTo(78f, 70f); path.lineTo(64f, 110f); path.lineTo(50f, 70f); path.close(); p.style = Paint.Style.FILL; p.shader = LinearGradient(50f, 20f, 78f, 110f, 0xfff0ffff.toInt(), 0xff60b8e8.toInt(), Shader.TileMode.CLAMP); c.drawPath(path, p); p.shader = null; outline(c, path); c.restore() } }
            Item.HERB -> { for (i in 0 until 5) { c.save(); c.rotate(-50f + i * 25f, 64f, 108f); path.reset(); path.moveTo(64f, 108f); path.quadTo(84f, 60f, 64f, 24f); path.quadTo(44f, 60f, 64f, 108f); fill(shade(0xffc23b4a.toInt(), 0.8f + i * 0.08f)); c.drawPath(path, p); outline(c, path); c.restore() } }
            Item.MUSHROOM -> { fill(0xffeee6d4.toInt()); r.set(54f, 60f, 74f, 108f); c.drawRoundRect(r, 8f, 8f, p); path.reset(); path.addArc(RectF(26f, 30f, 102f, 90f), 180f, 180f); path.close(); p.style = Paint.Style.FILL; p.shader = LinearGradient(26f, 30f, 102f, 60f, 0xff9ff0e0.toInt(), 0xff3a9a8a.toInt(), Shader.TileMode.CLAMP); c.drawPath(path, p); p.shader = null; outline(c, path) }
            Item.BERRIES -> { fill(0xff3a7a2a.toInt()); path.reset(); path.moveTo(64f, 30f); path.quadTo(90f, 10f, 104f, 26f); path.quadTo(84f, 40f, 64f, 30f); c.drawPath(path, p); for ((x, y) in listOf(50f to 60f, 76f to 58f, 62f to 82f, 40f to 86f, 86f to 84f)) { p.style = Paint.Style.FILL; p.shader = RadialGradient(x - 4, y - 4, 16f, 0xffff6a7a.toInt(), 0xff9a1028.toInt(), Shader.TileMode.CLAMP); c.drawCircle(x, y, 14f, p); p.shader = null } }
            Item.THORNFRUIT -> { p.style = Paint.Style.FILL; p.shader = RadialGradient(56f, 56f, 44f, 0xffffc070.toInt(), 0xffc05a14.toInt(), Shader.TileMode.CLAMP); c.drawOval(30f, 30f, 98f, 104f, p); p.shader = null; line(0xff5a3010.toInt(), 3f); for (i in 0 until 10) { val a = i / 10f * 6.283f; c.drawLine(64 + cos(a) * 34, 67 + sin(a) * 37, 64 + cos(a) * 44, 67 + sin(a) * 47, s) } }
            Item.RAW_MEAT, Item.COOKED_MEAT -> {
                val raw = item == Item.RAW_MEAT
                path.reset(); path.addOval(RectF(22f, 34f, 100f, 100f), Path.Direction.CW)
                p.style = Paint.Style.FILL; p.shader = RadialGradient(56f, 60f, 50f, if (raw) 0xffff9aa0.toInt() else 0xffc0703a.toInt(), if (raw) 0xffb0303a.toInt() else 0xff6a3418.toInt(), Shader.TileMode.CLAMP); c.drawPath(path, p); p.shader = null; outline(c, path)
                fill(0xffeee8d8.toInt()); c.drawCircle(92f, 44f, 10f, p); c.drawCircle(104f, 36f, 9f, p); r.set(80f, 40f, 102f, 52f); c.drawRect(r, p)
                if (!raw) { line(0xff3a1a08.toInt(), 4f); for (i in 0 until 3) c.drawLine(36f + i * 16, 86f, 56f + i * 16, 46f, s) }
            }
            Item.RAW_FISH, Item.COOKED_FISH -> {
                val raw = item == Item.RAW_FISH
                path.reset(); path.moveTo(18f, 64f); path.quadTo(50f, 30f, 90f, 60f); path.lineTo(112f, 40f); path.lineTo(108f, 88f); path.lineTo(90f, 68f); path.quadTo(50f, 98f, 18f, 64f)
                p.style = Paint.Style.FILL; p.shader = LinearGradient(18f, 40f, 90f, 90f, if (raw) 0xffd8e8ee.toInt() else 0xffe0a060.toInt(), if (raw) 0xff6a8a98.toInt() else 0xff8a4a1a.toInt(), Shader.TileMode.CLAMP); c.drawPath(path, p); p.shader = null; outline(c, path)
                fill(0xff101010.toInt()); c.drawCircle(34f, 58f, 4f, p)
                if (!raw) { line(0xff4a2008.toInt(), 3f); for (i in 0 until 3) c.drawLine(46f + i * 14, 76f, 58f + i * 14, 48f, s) }
            }
            Item.STEW -> { fill(0xff7a5434.toInt()); path.reset(); path.addArc(RectF(20f, 30f, 108f, 110f), 0f, 180f); path.close(); c.drawPath(path, p); outline(c, path); fill(0xffc07a3a.toInt()); c.drawOval(22f, 60f, 106f, 80f, p); fill(0xffe0a050.toInt()); c.drawCircle(50f, 68f, 6f, p); c.drawCircle(78f, 72f, 5f, p); line(0x99ffffff.toInt(), 3f); c.drawLine(52f, 50f, 48f, 30f, s); c.drawLine(72f, 52f, 76f, 30f, s) }
            Item.BANDAGE -> { fill(0xffeee6d4.toInt()); c.drawCircle(52f, 70f, 32f, p); fill(0xffd8cebc.toInt()); c.drawCircle(52f, 70f, 12f, p); fill(0xffeee6d4.toInt()); r.set(52f, 38f, 110f, 62f); c.drawRect(r, p); line(0xffc04040.toInt(), 6f); c.drawLine(84f, 44f, 84f, 58f, s); c.drawLine(77f, 51f, 91f, 51f, s) }
            Item.SALVE -> { fill(0xff6a4a3a.toInt()); r.set(38f, 26f, 90f, 42f); c.drawRoundRect(r, 4f, 4f, p); p.style = Paint.Style.FILL; p.shader = LinearGradient(30f, 40f, 98f, 106f, 0xffff7a8a.toInt(), 0xff8a1a2a.toInt(), Shader.TileMode.CLAMP); r.set(30f, 40f, 98f, 106f); c.drawRoundRect(r, 14f, 14f, p); p.shader = null; fill(0x66ffffff); r.set(40f, 50f, 50f, 90f); c.drawRoundRect(r, 4f, 4f, p) }
            Item.STONE_AXE, Item.SCRAP_AXE -> {
                handle(c, 34f, 110f, 88f, 22f)
                path.reset(); path.moveTo(70f, 30f); path.quadTo(108f, 20f, 112f, 56f); path.lineTo(80f, 52f); path.close()
                fill(if (item == Item.SCRAP_AXE) 0xffa8a8b0.toInt() else 0xff8a8a86.toInt()); c.drawPath(path, p); outline(c, path)
            }
            Item.STONE_PICK, Item.SCRAP_PICK -> {
                handle(c, 34f, 110f, 80f, 30f)
                path.reset(); path.moveTo(30f, 40f); path.quadTo(80f, 6f, 114f, 52f); path.quadTo(80f, 24f, 30f, 40f)
                fill(if (item == Item.SCRAP_PICK) 0xffa8a8b0.toInt() else 0xff8a8a86.toInt()); c.drawPath(path, p); outline(c, path)
            }
            Item.WOOD_SPEAR, Item.SCRAP_SPEAR, Item.ALLOY_SPEAR -> {
                handle(c, 20f, 112f, 96f, 32f)
                if (item == Item.ALLOY_SPEAR) glowOrb(c, 104f, 24f, 10f, 0xff6fd8ff.toInt())
                path.reset(); path.moveTo(92f, 36f); path.lineTo(118f, 10f); path.lineTo(100f, 42f); path.close()
                fill(when (item) { Item.WOOD_SPEAR -> 0xff6a6a66.toInt(); Item.SCRAP_SPEAR -> 0xffb8a898.toInt(); else -> 0xffbff0ff.toInt() }); c.drawPath(path, p); outline(c, path)
            }
            Item.HUNTING_BOW, Item.ALLOY_BOW -> {
                line(if (item == Item.ALLOY_BOW) 0xff7ab8c8.toInt() else 0xff8a5a30.toInt(), 9f)
                path.reset(); path.moveTo(40f, 14f); path.quadTo(110f, 64f, 40f, 114f); c.drawPath(path, s)
                line(0xffe8e0d0.toInt(), 2.5f); c.drawLine(40f, 14f, 40f, 114f, s)
            }
            Item.ARROW, Item.FIRE_ARROW, Item.SHOCK_ARROW -> {
                for (k in 0 until 3) {
                    val o = k * 14f
                    line(0xff9c7444.toInt(), 5f); c.drawLine(20f + o, 108f, 92f + o, 36f - o * 0.2f, s)
                    val tip = when (item) { Item.FIRE_ARROW -> 0xffff7a2c.toInt(); Item.SHOCK_ARROW -> 0xff6fd8ff.toInt(); else -> 0xff8a8a86.toInt() }
                    fill(tip); path.reset(); path.moveTo(98f + o, 30f - o * 0.2f); path.lineTo(84f + o, 36f - o * 0.2f); path.lineTo(92f + o, 44f - o * 0.2f); path.close(); c.drawPath(path, p)
                    fill(0xffe8e4d8.toInt()); path.reset(); path.moveTo(20f + o, 108f); path.lineTo(18f + o, 92f); path.lineTo(32f + o, 96f); path.close(); c.drawPath(path, p)
                }
                if (item == Item.FIRE_ARROW) glowOrb(c, 100f, 30f, 8f, 0xffff7a2c.toInt())
                if (item == Item.SHOCK_ARROW) glowOrb(c, 100f, 30f, 8f, 0xff6fd8ff.toInt())
            }
            Item.TORCH -> { handle(c, 50f, 114f, 64f, 50f); fill(0xffb8a070.toInt()); r.set(54f, 40f, 76f, 58f); c.drawRect(r, p); glowOrb(c, 66f, 30f, 18f, 0xffff9a3c.toInt()); fill(0xffffd27a.toInt()); path.reset(); path.moveTo(66f, 8f); path.quadTo(84f, 32f, 66f, 44f); path.quadTo(48f, 32f, 66f, 8f); c.drawPath(path, p) }
            Item.WATERSKIN -> { path.reset(); path.moveTo(50f, 24f); path.lineTo(78f, 24f); path.lineTo(78f, 38f); path.quadTo(108f, 60f, 98f, 96f); path.quadTo(64f, 116f, 30f, 96f); path.quadTo(20f, 60f, 50f, 38f); path.close(); p.style = Paint.Style.FILL; p.shader = LinearGradient(30f, 30f, 98f, 110f, 0xffc09a6a.toInt(), 0xff6a4a2a.toInt(), Shader.TileMode.CLAMP); c.drawPath(path, p); p.shader = null; outline(c, path); fill(0xff6aa8d8.toInt()); c.drawCircle(64f, 78f, 10f, p) }
            Item.FISHING_ROD -> { line(0xff8a6a42.toInt(), 6f); c.drawLine(24f, 112f, 104f, 14f, s); line(0xffe8e8e0.toInt(), 2f); path.reset(); path.moveTo(104f, 14f); path.quadTo(112f, 60f, 100f, 96f); c.drawPath(path, s); fill(0xffe0302a.toInt()); c.drawCircle(100f, 98f, 7f, p); fill(0xff5a4636.toInt()); c.drawCircle(40f, 92f, 9f, p) }
            Item.HIDE_COAT, Item.FUR_COAT, Item.SCRAP_PLATE, Item.FROST_CLOAK, Item.ALLOY_HARNESS -> {
                path.reset(); path.moveTo(40f, 20f); path.lineTo(88f, 20f); path.lineTo(112f, 48f); path.lineTo(98f, 58f); path.lineTo(92f, 50f); path.lineTo(92f, 110f); path.lineTo(36f, 110f); path.lineTo(36f, 50f); path.lineTo(30f, 58f); path.lineTo(16f, 48f); path.close()
                p.style = Paint.Style.FILL; p.shader = LinearGradient(30f, 20f, 100f, 110f, shade(col, 1.25f), shade(col, 0.65f), Shader.TileMode.CLAMP); c.drawPath(path, p); p.shader = null; outline(c, path)
                line(shade(col, 0.5f), 3f); c.drawLine(64f, 26f, 64f, 108f, s)
                if (item == Item.SCRAP_PLATE || item == Item.ALLOY_HARNESS) { fill(shade(col, 1.4f)); for (y in 0 until 3) { r.set(42f, 50f + y * 18, 86f, 62f + y * 18); c.drawRoundRect(r, 3f, 3f, p) } }
                if (item == Item.FROST_CLOAK) glowOrb(c, 64f, 40f, 9f, 0xff9fe0ff.toInt())
            }
            Item.SIGNAL_KEY -> { glowOrb(c, 40f, 64f, 24f, 0xff4fe3a0.toInt()); line(0xff2a8a6a.toInt(), 10f); c.drawCircle(40f, 64f, 20f, s); c.drawLine(60f, 64f, 112f, 64f, s); c.drawLine(100f, 64f, 100f, 84f, s); c.drawLine(86f, 64f, 86f, 78f, s) }
            Item.OVERLOAD_CORE -> { glowOrb(c, 64f, 64f, 32f, 0xffff4f6a.toInt()); line(0xff6a1020.toInt(), 6f); c.drawCircle(64f, 64f, 30f, s); line(0xffffd0d8.toInt(), 3f); c.drawLine(64f, 40f, 54f, 66f, s); c.drawLine(54f, 66f, 72f, 62f, s); c.drawLine(72f, 62f, 62f, 88f, s) }
        }
    }

    private fun drawBuild(c: Canvas, b: BuildType) {
        val wood = 0xff9a6a40.toInt(); val dark = 0xff6a4628.toInt()
        fun plank(x0: Float, y0: Float, x1: Float, y1: Float, col: Int = wood) { fill(col); r.set(x0, y0, x1, y1); c.drawRect(r, p); line(0x88000000.toInt(), 2.5f); c.drawRect(r, s) }
        when (b) {
            BuildType.FOUNDATION -> { path.reset(); path.moveTo(64f, 30f); path.lineTo(116f, 58f); path.lineTo(64f, 86f); path.lineTo(12f, 58f); path.close(); fill(wood); c.drawPath(path, p); outline(c, path); fill(dark); path.reset(); path.moveTo(12f, 58f); path.lineTo(64f, 86f); path.lineTo(64f, 100f); path.lineTo(12f, 72f); path.close(); c.drawPath(path, p); path.reset(); path.moveTo(116f, 58f); path.lineTo(64f, 86f); path.lineTo(64f, 100f); path.lineTo(116f, 72f); path.close(); fill(0xff7a5232.toInt()); c.drawPath(path, p) }
            BuildType.WALL -> { for (i in 0 until 5) plank(20f, 18f + i * 19, 108f, 36f + i * 19, if (i % 2 == 0) wood else 0xff8a5e38.toInt()) }
            BuildType.DOORWAY -> { for (i in 0 until 5) { plank(20f, 18f + i * 19, 48f, 36f + i * 19); plank(80f, 18f + i * 19, 108f, 36f + i * 19) }; plank(48f, 18f, 80f, 36f) }
            BuildType.ROOF -> { path.reset(); path.moveTo(64f, 18f); path.lineTo(118f, 82f); path.lineTo(10f, 82f); path.close(); fill(0xffa08850.toInt()); c.drawPath(path, p); outline(c, path); line(0xff7a6438.toInt(), 3f); for (i in 1 until 5) c.drawLine(64f - i * 11, 18f + i * 13, 64f + i * 11, 18f + i * 13, s) }
            BuildType.CAMPFIRE -> { for (i in 0 until 7) { fill(0xff7a7a76.toInt()); c.drawCircle(64 + cos(i / 7f * 6.283f) * 40, 92 + sin(i / 7f * 6.283f) * 12, 10f, p) }; log(c, 64f, 88f, 70f, 8f, 20f); log(c, 64f, 88f, 70f, 8f, -20f); glowOrb(c, 64f, 64f, 22f, 0xffff8a3c.toInt()); fill(0xffffd27a.toInt()); path.reset(); path.moveTo(64f, 24f); path.quadTo(88f, 66f, 64f, 84f); path.quadTo(40f, 66f, 64f, 24f); c.drawPath(path, p) }
            BuildType.WORKBENCH -> { plank(14f, 44f, 114f, 58f); plank(22f, 58f, 32f, 108f, dark); plank(96f, 58f, 106f, 108f, dark); fill(0xff8a8a86.toInt()); r.set(30f, 30f, 54f, 44f); c.drawRect(r, p); handle(c, 70f, 42f, 100f, 26f) }
            BuildType.TINKER_BENCH -> { plank(14f, 46f, 114f, 60f, 0xff5a5f66.toInt()); plank(20f, 60f, 36f, 108f, 0xff3b4046.toInt()); plank(92f, 60f, 108f, 108f, 0xff3b4046.toInt()); glowOrb(c, 64f, 32f, 12f, 0xff4fe3d2.toInt()); line(0xffd2803c.toInt(), 4f); c.drawLine(40f, 40f, 88f, 24f, s) }
            BuildType.BED -> { plank(14f, 70f, 114f, 96f, dark); fill(0xffa08060.toInt()); r.set(18f, 52f, 110f, 74f); c.drawRoundRect(r, 8f, 8f, p); fill(0xffe8e0d0.toInt()); r.set(20f, 46f, 50f, 66f); c.drawRoundRect(r, 8f, 8f, p); fill(0xff7a5a3a.toInt()); r.set(48f, 54f, 110f, 74f); c.drawRoundRect(r, 6f, 6f, p) }
            BuildType.CHEST -> { plank(20f, 50f, 108f, 104f); fill(dark); r.set(16f, 34f, 112f, 56f); c.drawRoundRect(r, 8f, 8f, p); fill(0xffc8c0a0.toInt()); r.set(56f, 48f, 72f, 66f); c.drawRect(r, p) }
            BuildType.TORCH_POST -> { handle(c, 64f, 118f, 64f, 36f); glowOrb(c, 64f, 28f, 16f, 0xffff9a3c.toInt()) }
            BuildType.RAIN_CATCHER -> { fill(0xffa0744e.toInt()); path.reset(); path.moveTo(14f, 30f); path.lineTo(114f, 30f); path.lineTo(80f, 54f); path.lineTo(48f, 54f); path.close(); c.drawPath(path, p); outline(c, path); plank(44f, 60f, 84f, 108f); fill(0xff3a6a8a.toInt()); r.set(48f, 62f, 80f, 72f); c.drawRect(r, p); line(0xff6aa8d8.toInt(), 3f); for (i in 0 until 4) c.drawLine(30f + i * 22, 10f, 26f + i * 22, 22f, s) }
            BuildType.SPIKES -> { plank(10f, 84f, 118f, 96f, dark); for (i in 0 until 6) { path.reset(); val x = 18f + i * 18; path.moveTo(x - 6, 90f); path.lineTo(x + 8, 22f); path.lineTo(x + 6, 90f); path.close(); fill(0xffb08a5a.toInt()); c.drawPath(path, p); outline(c, path) } }
            BuildType.SHOCK_PYLON -> { plank(54f, 34f, 74f, 112f, 0xff6a7178.toInt()); line(0xffd2803c.toInt(), 6f); for (i in 0 until 4) c.drawLine(46f, 50f + i * 14, 82f, 50f + i * 14, s); glowOrb(c, 64f, 26f, 14f, 0xff6fd8ff.toInt()) }
            BuildType.BOUNTY_BOARD -> { plank(20f, 20f, 108f, 84f); plank(26f, 84f, 36f, 116f, dark); plank(92f, 84f, 102f, 116f, dark); fill(0xffeee6d4.toInt()); r.set(30f, 28f, 58f, 62f); c.drawRect(r, p); r.set(66f, 34f, 98f, 74f); c.drawRect(r, p) }
        }
    }
}
