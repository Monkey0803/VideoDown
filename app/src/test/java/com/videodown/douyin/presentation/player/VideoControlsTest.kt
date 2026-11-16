package com.videodown.douyin.presentation.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoControlsTest {
    @Test fun `playback time formats short and long media`() {
        assertEquals("00:00", formatPlaybackTime(0))
        assertEquals("01:05", formatPlaybackTime(65_999))
        assertEquals("1:01:01", formatPlaybackTime(3_661_000))
    }
}
