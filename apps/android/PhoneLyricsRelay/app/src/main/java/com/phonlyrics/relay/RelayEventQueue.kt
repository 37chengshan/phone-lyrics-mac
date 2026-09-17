package com.phonlyrics.relay

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A single-flight FIFO. Retries block later events, preserving wire ordering. */
class RelayEventQueue(
    private val sender: (PlaybackEnvelope) -> Boolean,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val maxAttempts: Int = 4,
) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phone-lyrics-relay").apply { isDaemon = true }
    }

    fun enqueue(envelope: PlaybackEnvelope) {
        executor.execute {
            var attempt = 0
            while (attempt < maxAttempts) {
                attempt++
                if (sender(envelope)) return@execute
                if (attempt < maxAttempts) sleeper((250L shl (attempt - 1)).coerceAtMost(2_000))
            }
        }
    }

    fun awaitIdle(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
