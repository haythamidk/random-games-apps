package scrapline.ui

import com.badlogic.gdx.graphics.Color
import scrapline.sim.Item

private val WOOD = Color.valueOf("8A6A4CFF")
private val WOOD_END = Color.valueOf("C9A77CFF")
private val STONE = Color.valueOf("C98F5EFF")
private val STONE_D = Color.valueOf("A8724AFF")
private val ORE = Color.valueOf("5A4A46FF")
private val RUST = Color.valueOf("D07A45FF")
private val COAL = Color.valueOf("1F2226FF")
private val COAL_L = Color.valueOf("4A545CFF")
private val STEEL = Color.valueOf("AAB6BDFF")
private val STEEL_L = Color.valueOf("D5DDE1FF")
private val DARK = Color.valueOf("30363CFF")
private val SHELL = Color.valueOf("E8E0CCFF")
private val TRIM = Color.valueOf("2F8F86FF")
private val OXIDE = Color.valueOf("B8432CFF")
private val GREEN = Color.valueOf("2F7D6DFF")
private val PAPER = Color.valueOf("D9C7A3FF")

/**
 * Item icons, drawn from a few shapes each inside a square of side `s` whose
 * top-left corner is (x, y). No image files are shipped.
 */
fun drawIcon(c: Canvas, item: Int, x: Float, y: Float, s: Float) {
    fun px(v: Float) = x + v * s
    fun py(v: Float) = y + v * s
    fun box(a: Float, b: Float, w: Float, h: Float, col: Color) = c.rect(px(a), py(b), w * s, h * s, col)
    fun dot(a: Float, b: Float, r: Float, col: Color) = c.circle(px(a), py(b), r * s, col)
    fun seg(a: Float, b: Float, d: Float, e: Float, w: Float, col: Color) = c.line(px(a), py(b), px(d), py(e), w * s, col)
    fun tri(a: Float, b: Float, d: Float, e: Float, f: Float, g: Float, col: Color) = c.tri(px(a), py(b), px(d), py(e), px(f), py(g), col)
    fun lump(col: Color, shade: Color) {
        tri(0.15f, 0.65f, 0.3f, 0.28f, 0.6f, 0.2f, col)
        tri(0.15f, 0.65f, 0.6f, 0.2f, 0.85f, 0.45f, col)
        tri(0.15f, 0.65f, 0.85f, 0.45f, 0.75f, 0.78f, shade)
        tri(0.15f, 0.65f, 0.75f, 0.78f, 0.32f, 0.82f, shade)
    }
    fun handle() = seg(0.25f, 0.85f, 0.65f, 0.3f, 0.1f, WOOD)

    when (item) {
        Item.WOOD -> {
            c.rrect(px(0.1f), py(0.33f), 0.8f * s, 0.34f * s, 0.17f * s, WOOD)
            dot(0.75f, 0.5f, 0.15f, WOOD_END)
        }
        Item.STONE -> lump(STONE, STONE_D)
        Item.IRON_ORE -> {
            lump(ORE, Color.valueOf("4A3D3AFF"))
            dot(0.38f, 0.45f, 0.08f, RUST); dot(0.6f, 0.62f, 0.07f, RUST); dot(0.62f, 0.36f, 0.05f, RUST)
        }
        Item.COAL -> {
            lump(COAL, Color.valueOf("15171AFF"))
            seg(0.35f, 0.4f, 0.55f, 0.52f, 0.05f, COAL_L)
        }
        Item.IRON_INGOT -> {
            tri(0.25f, 0.38f, 0.75f, 0.38f, 0.88f, 0.7f, STEEL); tri(0.25f, 0.38f, 0.88f, 0.7f, 0.12f, 0.7f, STEEL)
            tri(0.25f, 0.38f, 0.75f, 0.38f, 0.7f, 0.5f, STEEL_L); tri(0.25f, 0.38f, 0.7f, 0.5f, 0.3f, 0.5f, STEEL_L)
        }
        Item.GEAR -> {
            for (k in 0 until 6) c.bar(px(0.5f), py(0.5f), 0.78f * s, 0.16f * s, k * 0.5236f, STEEL)
            dot(0.5f, 0.5f, 0.27f, STEEL); dot(0.5f, 0.5f, 0.1f, DARK)
        }
        Item.CIRCUIT -> {
            c.rrect(px(0.16f), py(0.2f), 0.68f * s, 0.6f * s, 0.07f * s, GREEN)
            seg(0.28f, 0.38f, 0.5f, 0.38f, 0.05f, Ui.BONE); seg(0.5f, 0.38f, 0.5f, 0.55f, 0.05f, Ui.BONE); seg(0.5f, 0.55f, 0.7f, 0.55f, 0.05f, Ui.BONE)
            dot(0.7f, 0.55f, 0.06f, Ui.AMBER)
        }
        Item.AXE -> {
            handle()
            tri(0.52f, 0.18f, 0.82f, 0.26f, 0.84f, 0.5f, STEEL); tri(0.52f, 0.18f, 0.84f, 0.5f, 0.6f, 0.44f, STEEL)
        }
        Item.PICK -> {
            handle()
            c.curve(px(0.28f), py(0.3f), px(0.86f), py(0.4f), 0.09f * s, STEEL)
        }
        Item.RATION -> {
            c.rrect(px(0.27f), py(0.2f), 0.46f * s, 0.62f * s, 0.05f * s, PAPER)
            box(0.25f, 0.3f, 0.5f, 0.05f, Color.valueOf("9B8660FF"))
            box(0.47f, 0.45f, 0.06f, 0.24f, Ui.CORAL); box(0.38f, 0.54f, 0.24f, 0.06f, Ui.CORAL)
        }
        Item.CELL -> {
            c.rrect(px(0.3f), py(0.24f), 0.4f * s, 0.6f * s, 0.07f * s, STEEL)
            c.rrect(px(0.34f), py(0.28f), 0.32f * s, 0.52f * s, 0.05f * s, DARK)
            box(0.42f, 0.15f, 0.16f, 0.1f, STEEL)
            seg(0.55f, 0.36f, 0.43f, 0.55f, 0.06f, Ui.TEAL); seg(0.43f, 0.55f, 0.57f, 0.55f, 0.06f, Ui.TEAL); seg(0.57f, 0.55f, 0.46f, 0.74f, 0.06f, Ui.TEAL)
        }
        Item.KIT_WORKBENCH -> {
            box(0.12f, 0.38f, 0.76f, 0.12f, WOOD_END)
            box(0.18f, 0.5f, 0.08f, 0.34f, DARK); box(0.74f, 0.5f, 0.08f, 0.34f, DARK)
            box(0.2f, 0.66f, 0.6f, 0.05f, STEEL); box(0.58f, 0.24f, 0.16f, 0.14f, OXIDE)
        }
        Item.KIT_CRATE -> {
            box(0.17f, 0.38f, 0.66f, 0.45f, WOOD); box(0.13f, 0.26f, 0.74f, 0.15f, WOOD_END)
            box(0.17f, 0.38f, 0.08f, 0.45f, DARK); box(0.75f, 0.38f, 0.08f, 0.45f, DARK)
        }
        Item.KIT_WALL -> {
            box(0.22f, 0.15f, 0.56f, 0.7f, SHELL); box(0.22f, 0.4f, 0.56f, 0.12f, TRIM); box(0.22f, 0.75f, 0.56f, 0.1f, DARK)
        }
        Item.KIT_CAMPFIRE -> {
            seg(0.2f, 0.8f, 0.8f, 0.68f, 0.09f, WOOD); seg(0.2f, 0.68f, 0.8f, 0.8f, 0.09f, WOOD)
            tri(0.5f, 0.15f, 0.68f, 0.66f, 0.32f, 0.66f, Ui.AMBER)
            tri(0.5f, 0.38f, 0.58f, 0.66f, 0.42f, 0.66f, Color.valueOf("FFE9A8FF"))
        }
        Item.KIT_SMELTER -> {
            box(0.18f, 0.4f, 0.64f, 0.44f, Color.valueOf("8A4A38FF")); box(0.15f, 0.34f, 0.7f, 0.08f, DARK)
            box(0.42f, 0.13f, 0.16f, 0.22f, DARK); box(0.34f, 0.54f, 0.32f, 0.17f, Ui.AMBER)
        }
        Item.KIT_DRILL -> {
            box(0.16f, 0.64f, 0.68f, 0.2f, DARK); box(0.33f, 0.26f, 0.34f, 0.38f, SHELL)
            box(0.33f, 0.46f, 0.34f, 0.08f, TRIM); box(0.42f, 0.14f, 0.16f, 0.12f, OXIDE)
        }
        Item.KIT_BELT -> {
            c.rrect(px(0.1f), py(0.36f), 0.8f * s, 0.3f * s, 0.04f * s, DARK)
            box(0.1f, 0.33f, 0.8f, 0.07f, TRIM); box(0.1f, 0.62f, 0.8f, 0.07f, TRIM)
            tri(0.36f, 0.42f, 0.62f, 0.51f, 0.36f, 0.6f, SHELL)
        }
        Item.KIT_SENSOR -> {
            seg(0.5f, 0.88f, 0.5f, 0.42f, 0.07f, STEEL)
            dot(0.5f, 0.34f, 0.14f, TRIM)
            seg(0.24f, 0.2f, 0.24f, 0.48f, 0.05f, Ui.TEAL); seg(0.76f, 0.2f, 0.76f, 0.48f, 0.05f, Ui.TEAL)
        }
        Item.KIT_TURRET -> {
            tri(0.3f, 0.56f, 0.7f, 0.56f, 0.78f, 0.85f, DARK); tri(0.3f, 0.56f, 0.78f, 0.85f, 0.22f, 0.85f, DARK)
            box(0.32f, 0.36f, 0.34f, 0.22f, SHELL); seg(0.5f, 0.46f, 0.9f, 0.46f, 0.09f, DARK)
            box(0.42f, 0.25f, 0.11f, 0.11f, Ui.AMBER)
        }
        Item.KIT_LAMP -> {
            seg(0.5f, 0.88f, 0.5f, 0.38f, 0.07f, STEEL); seg(0.36f, 0.88f, 0.64f, 0.88f, 0.07f, DARK)
            tri(0.4f, 0.18f, 0.6f, 0.18f, 0.68f, 0.38f, Ui.AMBER); tri(0.4f, 0.18f, 0.68f, 0.38f, 0.32f, 0.38f, Ui.AMBER)
        }
        Item.KIT_SWITCH -> {
            c.rrect(px(0.3f), py(0.2f), 0.4f * s, 0.6f * s, 0.07f * s, STEEL)
            c.rrect(px(0.42f), py(0.32f), 0.16f * s, 0.36f * s, 0.08f * s, DARK)
            dot(0.5f, 0.41f, 0.08f, Ui.TEAL)
        }
        Item.LINKER -> {
            c.curve(px(0.26f), py(0.72f), px(0.74f), py(0.3f), 0.07f * s, Ui.BONE)
            dot(0.26f, 0.72f, 0.11f, Ui.AMBER); dot(0.74f, 0.3f, 0.11f, Ui.TEAL)
        }
    }
}
