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
    /// 重试**全部用尽**时回调一次(2026-09-17)。
    ///
    /// 为什么需要它:发送统计原来挂在 sender 上,而 sender 每重试一次就被调一次 —— 网络抖
    /// 一下就在界面上留下"失败 3",而那条事件其实最终送出去了。计数必须按"这一条到底有没有
    /// 送达"来算,那个判断只有队列知道(它才知道重试还有没有下一次)。
    ///
    /// 默认空实现:既有的单元测试直接构造这个类,sender 契约不该因为这次改动而变。
    private val onExhausted: (PlaybackEnvelope) -> Unit = {},
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
            onExhausted(envelope)
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
