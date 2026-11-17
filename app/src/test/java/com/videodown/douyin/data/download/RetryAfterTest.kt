package com.videodown.douyin.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RetryAfterTest {
    @Test fun `delta seconds and dates become bounded delays`() {
        assertEquals(12_000L, retryAfterDelayMillis("12", 0L))
        val now = Instant.parse("2015-10-21T07:27:50Z").toEpochMilli()
        assertEquals(10_000L, retryAfterDelayMillis("Wed, 21 Oct 2015 07:28:00 GMT", now))
        assertEquals(MAX_RETRY_AFTER_MS, retryAfterDelayMillis("999999", 0L))
    }

    @Test fun `invalid retry after is absent`() {
        assertNull(retryAfterDelayMillis(null, 0L))
        assertNull(retryAfterDelayMillis("later", 0L))
    }
}
