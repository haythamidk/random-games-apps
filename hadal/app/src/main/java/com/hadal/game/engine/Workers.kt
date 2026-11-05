package com.hadal.game.engine

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared background threads sized to the device. The render thread, UI thread and audio thread always keep
 * their own cores; world generation gets what is left (3 workers on an 8-core phone like the Galaxy A54).
 */
object Workers {
    val cores: Int = Runtime.getRuntime().availableProcessors()
    val genThreads: Int = (cores - 3).coerceIn(1, 3)

    private fun factory(name: String, priority: Int): ThreadFactory {
        val n = AtomicInteger()
        return ThreadFactory { r ->
            Thread({
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
                r.run()
            }, "$name-${n.incrementAndGet()}").apply { isDaemon = true; this.priority = priority }
        }
    }

    /** CPU heavy generation work: terrain chunks, textures, map. */
    val gen: ExecutorService = Executors.newFixedThreadPool(genThreads, factory("gen", Thread.NORM_PRIORITY - 1))

    /** Disk writes (saves, caches). */
    val io: ExecutorService = Executors.newSingleThreadExecutor(factory("io", Thread.NORM_PRIORITY - 1))
}
