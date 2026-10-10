package com.videodown.douyin.data.download

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil

/**
 * Token-bucket accounting with an injected clock.
 *
 * Pure on purpose: pacing is the one part of a download that cannot be asserted by watching bytes
 * arrive, so the maths is exercised directly with a moving clock instead of sleeping in a test.
 */
class TokenBucket {
    private var tokens = 0.0
    private var lastRefillAt = 0L
    private var initialized = false

    /**
     * Accounts for [bytes] and returns how long the caller must wait before writing them.
     *
     * A [ratePerSecond] of `0` (or fewer) means unlimited and always returns `0`. The bucket starts
     * with one second of credit so a small file is never delayed by a limit it does not exceed, and
     * it never accumulates more than one second of credit, so an idle transfer cannot bank a burst.
     */
    fun consume(bytes: Long, ratePerSecond: Long, now: Long): Long {
        if (ratePerSecond <= 0L || bytes <= 0L) return 0L
        // An explicit flag: a clock that legitimately reports 0 must not look "uninitialised".
        if (!initialized) {
            initialized = true
            lastRefillAt = now
            tokens = ratePerSecond.toDouble()
        }
        val elapsed = (now - lastRefillAt).coerceAtLeast(0L)
        if (elapsed > 0L) {
            tokens = (tokens + ratePerSecond * elapsed / 1000.0).coerceAtMost(ratePerSecond.toDouble())
            lastRefillAt = now
        }
        if (tokens >= bytes.toDouble()) {
            tokens -= bytes.toDouble()
            return 0L
        }
        return ceil((bytes - tokens) / ratePerSecond * 1000.0).toLong().coerceAtLeast(1L)
    }
}

/**
 * Paces transfers to a byte rate.
 *
 * One instance is shared by every transfer in flight to enforce the global cap, and each task gets
 * its own instance for its personal cap; both are consulted for every chunk, so a global limit is
 * really global rather than a per-task default.
 *
 * `0` bytes per second means unlimited and never suspends.
 */
class BandwidthLimiter(private val now: () -> Long = System::currentTimeMillis) {
    private val bucket = TokenBucket()
    private val mutex = Mutex()

    @Volatile
    private var rateBytesPerSecond = 0L

    val bytesPerSecond: Long get() = rateBytesPerSecond

    fun setRate(bytesPerSecond: Long) {
        rateBytesPerSecond = bytesPerSecond.coerceAtLeast(0L)
    }

    /** Suspends until [bytes] may be written at the current rate. */
    suspend fun acquire(bytes: Int) {
        while (true) {
            val rate = rateBytesPerSecond
            if (rate <= 0L || bytes <= 0) return
            val waitMs = mutex.withLock { bucket.consume(bytes.toLong(), rate, now()) }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }
}
