package com.videodown.douyin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressTest {

    private val progress = WatchProgress(positionMs = 30_000, durationMs = 120_000, fileSize = 1_000, modifiedAt = 42)

    @Test
    fun `a partly watched file reports the watched fraction`() {
        assertEquals(0.25f, progress.fraction()!!, 0.001f)
    }

    @Test
    fun `an unknown duration cannot produce a fraction`() {
        assertNull(progress.copy(durationMs = 0).fraction())
    }

    @Test
    fun `a file with no recorded identity is not trusted`() {
        assertNull(progress.copy(fileSize = -1).fraction())
    }

    @Test
    fun `an untouched file shows nothing`() {
        assertNull(progress.copy(positionMs = 0).fraction())
    }

    @Test
    fun `a finished file shows nothing rather than a full bar`() {
        assertNull(progress.copy(positionMs = 120_000).fraction())
        assertNull(progress.copy(positionMs = 200_000).fraction())
    }

    @Test
    fun `a rounded-down percentage is stable at the extremes`() {
        // The fraction is the domain's business; the badge percentage is worded by the screen.
        assertEquals(0f, progress.copy(positionMs = 1, durationMs = 1_000).fraction()!!, 0.001f)
        assertEquals(0.999f, progress.copy(positionMs = 999, durationMs = 1_000).fraction()!!, 0.001f)
    }

    @Test
    fun `the stored identity must match the file exactly`() {
        assertTrue(progress.matchesFile(1_000, 42))
        assertFalse("A re-downloaded file keeps the id but changes size", progress.matchesFile(1_001, 42))
        assertFalse("A re-encoded file changes modification time", progress.matchesFile(1_000, 43))
    }
}
