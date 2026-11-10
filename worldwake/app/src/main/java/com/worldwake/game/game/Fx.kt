package com.worldwake.game.game

/**
 * A small pool of CPU particles (steam, dust, sparks, spores) drawn as soft sprites.
 * Written by the simulation and read by the renderer on the same thread.
 */
class Fx(val max: Int = 420) {
    val x = FloatArray(max); val y = FloatArray(max); val z = FloatArray(max)
    private val vx = FloatArray(max); private val vy = FloatArray(max); private val vz = FloatArray(max)
    val life = FloatArray(max); private val maxLife = FloatArray(max)
    val size = FloatArray(max); private val grow = FloatArray(max)
    val r = FloatArray(max); val g = FloatArray(max); val b = FloatArray(max)
    val alpha = FloatArray(max); private val a0 = FloatArray(max)
    val add = FloatArray(max)
    private val drag = FloatArray(max)
    private var next = 0
    private var seed = 7717

    fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xffff) / 65535f }
    fun rs(): Float = rnd() * 2f - 1f

    fun emit(px: Float, py: Float, pz: Float, ax: Float, ay: Float, az: Float, lifeSec: Float, sz: Float, growth: Float,
             cr: Float, cg: Float, cb: Float, a: Float, additive: Boolean, dragK: Float = 0.6f) {
        val i = next
        next = (next + 1) % max
        x[i] = px; y[i] = py; z[i] = pz
        vx[i] = ax; vy[i] = ay; vz[i] = az
        life[i] = lifeSec; maxLife[i] = lifeSec
        size[i] = sz; grow[i] = growth
        r[i] = cr; g[i] = cg; b[i] = cb
        a0[i] = a; alpha[i] = 0f
        add[i] = if (additive) 1f else 0f
        drag[i] = dragK
    }

    fun update(dt: Float, windX: Float, windZ: Float) {
        for (i in 0 until max) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            if (life[i] <= 0f) { alpha[i] = 0f; continue }
            val k = 1f - drag[i] * dt
            vx[i] = vx[i] * k + windX * dt * (1f - add[i]); vz[i] = vz[i] * k + windZ * dt * (1f - add[i])
            vy[i] *= k
            x[i] += vx[i] * dt; y[i] += vy[i] * dt; z[i] += vz[i] * dt
            size[i] += grow[i] * dt
            val t = 1f - life[i] / maxLife[i]
            val fade = if (t < 0.15f) t / 0.15f else 1f - (t - 0.15f) / 0.85f
            alpha[i] = a0[i] * fade
        }
    }

    fun clear() { life.fill(0f); alpha.fill(0f) }
}
