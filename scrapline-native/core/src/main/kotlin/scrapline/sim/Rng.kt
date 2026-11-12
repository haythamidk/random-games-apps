package scrapline.sim

/** Small deterministic PRNG (mulberry32). All randomness in the sim goes through one of these. */
class Rng(seed: Int) {
    private var s = seed

    /** Uniform float in [0, 1). */
    fun next(): Float {
        s += 0x6d2b79f5
        var t = s
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL).toFloat() / 4294967296f
    }

    fun range(lo: Float, hi: Float): Float = lo + next() * (hi - lo)
}

/** Stateless 2D integer hash to [0, 1). Used for lattice noise. */
fun hash2(x: Int, y: Int, seed: Int): Float {
    var h = x * 374761393 + y * 668265263 + seed * -2048144777
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return ((h.toLong() and 0xffffffffL).toDouble() / 4294967296.0).toFloat()
}
