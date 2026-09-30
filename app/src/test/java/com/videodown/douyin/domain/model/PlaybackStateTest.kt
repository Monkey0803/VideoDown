package com.videodown.douyin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStateTest {
    @Test fun `unfinished media resumes even near its end`() {
        assertEquals(58_999L, PlaybackCheckpoint(58_999, 60_000).resumePosition())
        assertEquals(10_000L, PlaybackCheckpoint(10_000, 0).resumePosition())
    }
    @Test fun `ended invalid and out of bounds checkpoints restart`() {
        assertEquals(0L, PlaybackCheckpoint(5000, 6000, ended = true).resumePosition())
        assertEquals(0L, PlaybackCheckpoint(-1, 6000).resumePosition())
        assertEquals(0L, PlaybackCheckpoint(6000, 6000).resumePosition())
    }
    @Test fun `only supported speeds survive invalid persisted settings`() {
        playbackSpeeds.forEach { assertEquals(it, PlaybackOptions(it).sanitized().speed) }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 8f).forEach { assertEquals(1f, PlaybackOptions(it).sanitized().speed) }
    }
}
