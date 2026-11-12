package scrapline.sim

import kotlin.math.min

/**
 * A fixed-size set of item slots. Player bag, crates and machine buffers all use
 * this one structure so every system moves items through the same functions.
 */
class Container(val size: Int) {
    val ids = IntArray(size)
    val counts = IntArray(size)

    /** Total number of `id` held across all slots. */
    fun count(id: Int): Int {
        var n = 0
        for (i in 0 until size) if (ids[i] == id) n += counts[i]
        return n
    }

    /** How many more of `id` would fit. */
    fun space(id: Int): Int {
        val max = stackMax(id)
        var n = 0
        for (i in 0 until size) {
            if (counts[i] == 0) n += max else if (ids[i] == id) n += max - counts[i]
        }
        return n
    }

    /**
     * Adds up to `n` of `id`: tops up existing stacks first, then fills empty slots
     * in order. Returns the amount that did not fit.
     */
    fun add(id: Int, n: Int): Int {
        if (id == 0 || n <= 0) return 0
        val max = stackMax(id)
        var left = n
        var i = 0
        while (i < size && left > 0) {
            if (ids[i] == id && counts[i] in 1 until max) {
                val take = min(left, max - counts[i])
                counts[i] += take
                left -= take
            }
            i++
        }
        i = 0
        while (i < size && left > 0) {
            if (counts[i] == 0) {
                val take = min(left, max)
                ids[i] = id
                counts[i] = take
                left -= take
            }
            i++
        }
        return left
    }

    /** Removes up to `n` of `id`, last slots first. Returns the amount actually removed. */
    fun remove(id: Int, n: Int): Int {
        var left = n
        var i = size - 1
        while (i >= 0 && left > 0) {
            if (ids[i] == id && counts[i] > 0) {
                val take = min(left, counts[i])
                counts[i] -= take
                left -= take
                if (counts[i] == 0) ids[i] = 0
            }
            i--
        }
        return n - left
    }

    /** Removes up to `n` from one slot. Returns the amount removed. */
    fun removeFromSlot(slot: Int, n: Int): Int {
        val take = min(n, counts[slot])
        counts[slot] -= take
        if (counts[slot] == 0) ids[slot] = 0
        return take
    }

    fun clear() {
        ids.fill(0)
        counts.fill(0)
    }
}

/**
 * Moves up to `n` items from slot `i` of `from` to slot `j` of `to`.
 * Empty target: moves up to a full stack. Same item: merges up to the stack limit.
 * Different item: swaps, but only when the whole source stack was requested.
 * Returns the number of items that left the source slot.
 */
fun moveSlot(from: Container, i: Int, to: Container, j: Int, n: Int): Int {
    if (from === to && i == j) return 0
    val id = from.ids[i]
    val have = from.counts[i]
    if (have == 0 || n <= 0) return 0
    val want = min(n, have)
    val dstCount = to.counts[j]

    if (dstCount == 0 || to.ids[j] == id) {
        val moved = min(want, stackMax(id) - dstCount)
        if (moved <= 0) return 0
        to.ids[j] = id
        to.counts[j] = dstCount + moved
        from.counts[i] = have - moved
        if (from.counts[i] == 0) from.ids[i] = 0
        return moved
    }
    if (want == have) {
        val dstId = to.ids[j]
        to.ids[j] = id
        to.counts[j] = have
        from.ids[i] = dstId
        from.counts[i] = dstCount
        return have
    }
    return 0
}
