package com.videodown.douyin.domain.model

/**
 * 下一次重试的安排：已经用掉第几次、以及从什么时候开始允许再发请求。
 *
 * [attempt] 是**累计**已用次数（不是本次的下标），落库到 `DownloadRecord.retryAttempt` 后，
 * 进程被杀或应用重启也不会把预算重置成无限重试。
 */
data class RetrySchedule(val attempt: Int, val atMillis: Long)

/**
 * 直链、归档、Live Photo 与 yt-dlp / HLS / DASH / 分轨任务共用的有界重试策略。
 *
 * 纯 Kotlin：不读时钟、不 sleep、不落库——调用方负责拿 [schedule] 的结果去排等待并写记录，
 * 因此 JVM 用例可以穷举每一条分支。判定保持与 H5 一致的语义：
 *  - 存储类失败（空间不足、写入受限）不重试，条件和上次一样，重试只会再失败一次；
 *  - 4xx 只有在 429（限流）时重试，其余需要用户先处理登录、权限或链接；
 *  - 429 / 503 有 `Retry-After` 时按服务端时间等，缺失时用有上限的指数退避。
 */
object DownloadRetryPolicy {
    /** 一轮传输最多用掉几次重试（首轮不算）。 */
    const val MAX_ATTEMPTS = 3

    /** 第一次重试的等待时间；之后翻倍。 */
    const val BASE_DELAY_MS = 1_500L

    /** 单次等待上限，与服务端 `Retry-After` 的上限保持一致（15 分钟）。 */
    const val MAX_DELAY_MS = 15L * 60L * 1000L

    /** 这些分类重试没有任何意义：条件没变，再请求也只会失败一次。 */
    private val fatalKinds = setOf(
        DownloadFailureKind.STORAGE_FULL,
        DownloadFailureKind.STORAGE_ACCESS,
        DownloadFailureKind.UNSUPPORTED,
        DownloadFailureKind.NO_VIDEO,
        DownloadFailureKind.NO_AUDIO,
        DownloadFailureKind.FORMAT_CHANGED,
        DownloadFailureKind.LOGIN_REQUIRED,
        DownloadFailureKind.ACCESS_DENIED,
        DownloadFailureKind.LINK_EXPIRED,
        DownloadFailureKind.WIFI_REQUIRED,
        DownloadFailureKind.BACKGROUND_SERVICE,
        DownloadFailureKind.RUNTIME,
    )

    /** 没有 HTTP 状态可参考时，只有这两类还值得再试一次。 */
    private val retryableKinds = setOf(DownloadFailureKind.NETWORK, DownloadFailureKind.RATE_LIMITED)

    /**
     * 这次失败该不该自动重试。
     *
     * [httpStatus] 为 null 表示失败不是由一次 HTTP 响应直接给出的（连接中断、引擎进程退出等）；
     * 旧历史里没有分类（`failureKind == null`）同样按网络中断处理，与 H5 之前的行为一致。
     */
    fun isRetryable(failureKind: DownloadFailureKind?, httpStatus: Int?): Boolean = when {
        failureKind in fatalKinds -> false
        httpStatus != null -> httpStatus == 429 || httpStatus == 503 || httpStatus !in 400..499
        else -> failureKind == null || failureKind in retryableKinds
    }

    /**
     * 安排下一次重试；返回 null 表示已经用满预算或这类失败不该重试。
     *
     * [retryAfterMillis] 来自 `Retry-After`（秒数或 RFC 1123 日期都已归一成毫秒），
     * 超过 [MAX_DELAY_MS] 的等待会被截断，避免服务端给出一个近乎永久的时间。
     */
    fun schedule(
        attemptsUsed: Int,
        failureKind: DownloadFailureKind?,
        httpStatus: Int?,
        retryAfterMillis: Long?,
        nowMillis: Long,
        maxAttempts: Int = MAX_ATTEMPTS,
    ): RetrySchedule? {
        if (attemptsUsed < 0 || attemptsUsed >= maxAttempts) return null
        if (!isRetryable(failureKind, httpStatus)) return null
        val attempt = attemptsUsed + 1
        val wait = retryAfterMillis?.coerceIn(0L, MAX_DELAY_MS) ?: backoffMillis(attempt)
        return RetrySchedule(attempt, nowMillis + wait)
    }

    /** 第 [attempt] 次重试的指数退避（1.5s、3s、6s…），封顶 [MAX_DELAY_MS]。 */
    fun backoffMillis(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 30)
        return minOf(BASE_DELAY_MS shl shift, MAX_DELAY_MS)
    }

    /** 记录里剩下的冷却时间；没有冷却或已经到期都是 0。 */
    fun cooldownRemaining(nextRetryAtMillis: Long?, nowMillis: Long): Long =
        nextRetryAtMillis?.let { (it - nowMillis).coerceAtLeast(0L) } ?: 0L
}
