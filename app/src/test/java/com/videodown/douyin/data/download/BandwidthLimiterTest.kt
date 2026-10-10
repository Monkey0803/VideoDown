package com.videodown.douyin.data.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenBucketTest {

    @Test
    fun `an unlimited rate never asks the caller to wait`() {
        val bucket = TokenBucket()
        assertEquals(0L, bucket.consume(64 * 1024, 0, 0))
        assertEquals(0L, bucket.consume(64 * 1024, -1, 1_000))
        assertEquals(0L, bucket.consume(0, 1_000, 2_000))
    }

    @Test
    fun `one second of credit covers a first chunk that fits inside it`() {
        val bucket = TokenBucket()
        assertEquals(0L, bucket.consume(999, 1_000, now = 5_000))
    }

    @Test
    fun `a chunk beyond the credit waits just long enough for the difference`() {
        val bucket = TokenBucket()
        // 1 000 B/s with 1 000 B of credit: 500 B are free, the remaining 500 B take half a second.
        assertEquals(500L, bucket.consume(1_500, 1_000, now = 0))
    }

    @Test
    fun `the wait is rounded up and never zero when a wait is genuinely needed`() {
        // 1 B over a 1 000 B/s budget is 1 ms, not 0.
        assertEquals(1L, TokenBucket().consume(1_001, 1_000, now = 0))
        // A 1 B/s budget with a 2 B chunk waits one second for the missing byte.
        assertEquals(1_000L, TokenBucket().consume(2, 1, now = 0))
    }

    @Test
    fun `an idle bucket cannot bank more than one second of credit`() {
        val bucket = TokenBucket()
        bucket.consume(1_000, 1_000, now = 0)
        // Ten seconds pass, but only one second of credit is available, so 2 000 B still wait 1 s.
        assertEquals(1_000L, bucket.consume(2_000, 1_000, now = 10_000))
    }

    @Test
    fun `credit refills as time passes`() {
        val bucket = TokenBucket()
        bucket.consume(1_000, 1_000, now = 0)
        // 500 ms later half a second of credit is back.
        assertEquals(500L, bucket.consume(1_000, 1_000, now = 500))
    }

    @Test
    fun `a clock that moves backwards cannot create credit out of nothing`() {
        val bucket = TokenBucket()
        bucket.consume(1_000, 1_000, now = 1_000)
        assertEquals(1_000L, bucket.consume(1_000, 1_000, now = 500))
    }
}

class BandwidthLimiterTest {

    @Test
    fun `an unlimited limiter returns without consuming virtual time`() = runTest {
        val limiter = BandwidthLimiter(now = { testScheduler.currentTime })
        limiter.setRate(0)
        limiter.acquire(64 * 1024)
        limiter.acquire(64 * 1024)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a limit is enforced in virtual time`() = runTest {
        val limiter = BandwidthLimiter(now = { testScheduler.currentTime })
        limiter.setRate(1_000)
        // The first second of credit covers this chunk immediately.
        limiter.acquire(1_000)
        assertEquals(0L, testScheduler.currentTime)
        // Every further kilobyte costs exactly one virtual second.
        limiter.acquire(1_000)
        assertEquals(1_000L, testScheduler.currentTime)
        limiter.acquire(1_000)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun `raising the rate mid-transfer shortens the wait`() = runTest {
        val limiter = BandwidthLimiter(now = { testScheduler.currentTime })
        limiter.setRate(1_000)
        limiter.acquire(1_000)
        limiter.setRate(10_000)
        limiter.acquire(1_000)
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test
    fun `a negative rate is treated as unlimited`() = runTest {
        val limiter = BandwidthLimiter(now = { testScheduler.currentTime })
        limiter.setRate(-5)
        assertEquals(0L, limiter.bytesPerSecond)
        limiter.acquire(1_000_000)
        assertEquals(0L, testScheduler.currentTime)
    }
}
