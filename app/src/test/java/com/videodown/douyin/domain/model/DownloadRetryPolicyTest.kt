package com.videodown.douyin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 有界重试策略：直链、归档、Live Photo 与引擎任务共用这一套判定。
 *
 * 这里只断言「该不该重试、什么时候」，实际等待与落库由 DownloadEngine 负责，
 * 所以每条分支都能用固定时钟穷举，不需要 sleep。
 */
class DownloadRetryPolicyTest {

    @Test fun `retry after seconds becomes an absolute server deadline`() {
        val schedule = DownloadRetryPolicy.schedule(
            attemptsUsed = 0, failureKind = DownloadFailureKind.RATE_LIMITED, httpStatus = 429,
            retryAfterMillis = 7_000L, nowMillis = 1_000L,
        )
        assertEquals(1, schedule?.attempt)
        assertEquals(8_000L, schedule?.atMillis)
    }

    @Test fun `an absurd retry after is capped instead of waiting forever`() {
        val schedule = DownloadRetryPolicy.schedule(
            attemptsUsed = 0, failureKind = DownloadFailureKind.RATE_LIMITED, httpStatus = 429,
            retryAfterMillis = 48L * 60L * 60L * 1000L, nowMillis = 0L,
        )
        assertEquals(DownloadRetryPolicy.MAX_DELAY_MS, schedule?.atMillis)
    }

    @Test fun `a missing retry after falls back to exponential backoff`() {
        assertEquals(1_500L, DownloadRetryPolicy.backoffMillis(1))
        assertEquals(3_000L, DownloadRetryPolicy.backoffMillis(2))
        assertEquals(6_000L, DownloadRetryPolicy.backoffMillis(3))
        assertEquals(DownloadRetryPolicy.MAX_DELAY_MS, DownloadRetryPolicy.backoffMillis(30))
        val third = DownloadRetryPolicy.schedule(2, DownloadFailureKind.NETWORK, null, null, nowMillis = 100L)
        assertEquals(3, third?.attempt)
        assertEquals(6_100L, third?.atMillis)
    }

    @Test fun `the bounded budget stops after three retries`() {
        assertNull(DownloadRetryPolicy.schedule(3, DownloadFailureKind.NETWORK, null, null, 0L))
        assertNull(DownloadRetryPolicy.schedule(-1, DownloadFailureKind.NETWORK, null, null, 0L))
        assertTrue(DownloadRetryPolicy.schedule(2, DownloadFailureKind.NETWORK, null, null, 0L) != null)
    }

    @Test fun `client errors other than rate limiting need the user first`() {
        listOf(400, 401, 403, 404, 410, 422).forEach { status ->
            assertNull("HTTP $status must not be retried automatically", DownloadRetryPolicy.schedule(
                0, DownloadFailureKind.NETWORK, status, 5_000L, 0L,
            ))
        }
    }

    @Test fun `429 500 and 503 stay retryable`() {
        listOf(429, 500, 502, 503, 504).forEach { status ->
            assertTrue("HTTP $status must stay retryable", DownloadRetryPolicy.schedule(
                0, DownloadFailureKind.NETWORK, status, null, 0L,
            ) != null)
        }
    }

    @Test fun `a server side temporary failure reported by the engine is retried`() {
        // 引擎只给出分类时（没有状态码），NETWORK 与限流仍可重试。
        assertTrue(DownloadRetryPolicy.isRetryable(DownloadFailureKind.NETWORK, null))
        assertTrue(DownloadRetryPolicy.isRetryable(DownloadFailureKind.RATE_LIMITED, null))
        assertTrue(DownloadRetryPolicy.isRetryable(DownloadFailureKind.NETWORK, 503))
    }

    @Test fun `storage failures are never retried automatically`() {
        listOf(DownloadFailureKind.STORAGE_FULL, DownloadFailureKind.STORAGE_ACCESS).forEach { kind ->
            assertFalse(DownloadRetryPolicy.isRetryable(kind, null))
            assertNull(DownloadRetryPolicy.schedule(0, kind, 503, null, 0L))
        }
    }

    @Test fun `engine failures that need the user are not retried`() {
        listOf(
            DownloadFailureKind.LOGIN_REQUIRED,
            DownloadFailureKind.ACCESS_DENIED,
            DownloadFailureKind.LINK_EXPIRED,
            DownloadFailureKind.UNSUPPORTED,
            DownloadFailureKind.NO_AUDIO,
            DownloadFailureKind.FORMAT_CHANGED,
            DownloadFailureKind.RUNTIME,
            DownloadFailureKind.BACKGROUND_SERVICE,
            DownloadFailureKind.WIFI_REQUIRED,
        ).forEach { kind ->
            assertFalse("$kind must not be retried", DownloadRetryPolicy.isRetryable(kind, null))
        }
    }

    @Test fun `legacy records without a failure category are treated as a network interruption`() {
        assertTrue(DownloadRetryPolicy.isRetryable(null, null))
        assertTrue(DownloadRetryPolicy.schedule(0, null, null, null, 0L) != null)
    }

    @Test fun `cooldown remaining clamps at zero`() {
        assertEquals(0L, DownloadRetryPolicy.cooldownRemaining(null, 1_000L))
        assertEquals(0L, DownloadRetryPolicy.cooldownRemaining(500L, 1_000L))
        assertEquals(2_000L, DownloadRetryPolicy.cooldownRemaining(3_000L, 1_000L))
    }
}
