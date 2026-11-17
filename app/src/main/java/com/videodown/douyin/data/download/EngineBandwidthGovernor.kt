package com.videodown.douyin.data.download

import java.util.concurrent.ConcurrentHashMap

/**
 * 一次引擎会话的限速来源。
 *
 * 两个 provider 每次被调用都重新读取：全局/单任务上限在任务运行中改动后立即生效，
 * 不需要重启引擎进程。
 */
class EngineRateControl(
    val sessionId: String,
    /** 当前全局限速（字节/秒），`0` 表示不限速。 */
    val globalLimit: () -> Long,
    /** 当前单任务限速（字节/秒），`0` 表示跟随全局。 */
    val taskLimit: () -> Long,
)

/**
 * I5：多个 yt-dlp 进程共享的全局带宽预算。
 *
 * 一个引擎任务就是一个独立进程，yt-dlp 的 `ratelimit` 只能限制单个进程，因此两个任务同时下载时
 * 总量会是上限的两倍。这里在应用侧维护**共享的令牌桶**：引擎上报已下载字节时，桶算出「按上限
 * 应该等多长时间」，调用方用 `SIGSTOP`/`SIGCONT` 让整个子进程组暂停这么久，从而把多个进程的
 * 总速率压回全局限速之内。
 *
 * 约定：
 *  - 桶允许透支（负令牌），暂停结束时债务不会消失；下一次上报继续偿还，长期平均速率因此准确，
 *    单次暂停封顶 [MAX_PAUSE_MS]，避免一次大突发把任务卡住几十秒；
 *  - 全局桶跨会话共享，单任务桶按会话独立；
 *  - 上限为 `0` 时不做任何等待，行为与引入本类之前完全一致。
 *
 * 纯 Kotlin：时间由 [now] 注入，进程暂停由调用方执行，所以 JVM 用例可以穷举每种组合。
 */
class EngineBandwidthGovernor(
    private val now: () -> Long = System::currentTimeMillis,
    /** 单次暂停上限：既保证预算准确，也不让界面上的任务看起来「卡死」。 */
    private val maxPauseMillis: Long = MAX_PAUSE_MS,
) {
    private class Bucket(
        var rate: Long,
        var tokens: Double,
        var lastRefill: Long,
        var capacity: Double,
        /** 第一次使用时桶是满的：小突发不必等待，只有持续超速才会被暂停。 */
        var initialized: Boolean = false,
    )

    private val global = Bucket(rate = 0L, tokens = 0.0, lastRefill = now(), capacity = 0.0)
    private val tasks = ConcurrentHashMap<String, Bucket>()
    private val lock = Any()

    /** 注册一个会话；同一 id 复用同一个单任务桶。 */
    fun session(id: String): Session = Session(id)

    /** 任务结束（完成、失败、取消）时释放它的桶，防止长期运行后无限增长。 */
    fun release(id: String) {
        tasks.remove(id)
    }

    inner class Session internal constructor(val id: String) {
        /**
         * 上报一段新写入的字节，返回该进程应当被暂停的毫秒数。
         *
         * [bytes] 是自上次上报以来新增的字节；引擎每次进度事件报的是累计字节，所以调用方要先做差。
         */
        fun onBytes(bytes: Long, globalRate: Long, taskRate: Long): Long = synchronized(lock) {
            if (bytes <= 0L) return 0L
            val at = now()
            var pause = 0L
            if (globalRate > 0L) pause = maxOf(pause, consume(global, globalRate, bytes, at))
            if (taskRate > 0L) {
                val bucket = tasks.getOrPut(id) { Bucket(taskRate, 0.0, at, capacity(taskRate)) }
                bucket.rate = taskRate
                pause = maxOf(pause, consume(bucket, taskRate, bytes, at))
            } else {
                tasks.remove(id)
            }
            pause.coerceAtMost(maxPauseMillis)
        }
    }

    /** 令牌桶记账：返回还清本次透支所需的时间（毫秒，未截断）。 */
    private fun consume(bucket: Bucket, rate: Long, bytes: Long, at: Long): Long {
        bucket.rate = rate
        bucket.capacity = capacity(rate)
        if (!bucket.initialized) {
            bucket.tokens = bucket.capacity
            bucket.lastRefill = at
            bucket.initialized = true
        } else {
            val elapsed = (at - bucket.lastRefill).coerceAtLeast(0L)
            bucket.tokens = (bucket.tokens + elapsed * rate / 1000.0).coerceAtMost(bucket.capacity)
            bucket.lastRefill = at
        }
        bucket.tokens = bucket.tokens.coerceAtMost(bucket.capacity)
        bucket.tokens -= bytes
        if (bucket.tokens >= 0.0) return 0L
        val waitMillis = (-bucket.tokens) / rate * 1000.0
        return waitMillis.toLong() + if (waitMillis > waitMillis.toLong()) 1L else 0L
    }

    private fun capacity(rate: Long): Double = maxOf(MIN_CAPACITY_BYTES, rate / 8.0)

    companion object {
        /**
         * 单次暂停上限（安全阀）。
         *
         * 不设成几百毫秒：进度事件可能一次就报上 MB，债务远大于小暂停能偿还的量，
         * 这样会持续超速。上限只用于防止病态参数把任务卡成「假死」，正常情况下暂停就是债务 ÷ 上限。
         */
        const val MAX_PAUSE_MS = 15_000L

        /** 桶的最小容量，避免低上限时一次进度事件就欠下过多时间。 */
        const val MIN_CAPACITY_BYTES = 16.0 * 1024.0
    }
}
