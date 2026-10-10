package com.videodown.douyin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGesturesTest {

    @Test
    fun `the screen splits into two halves for seeking`() {
        assertEquals(GestureSide.LEFT, PlayerGestures.sideFor(0f, 1000))
        assertEquals(GestureSide.LEFT, PlayerGestures.sideFor(499f, 1000))
        assertEquals(GestureSide.RIGHT, PlayerGestures.sideFor(500f, 1000))
        assertEquals(GestureSide.RIGHT, PlayerGestures.sideFor(999f, 1000))
    }

    @Test
    fun `an unknown width falls back to the left half instead of crashing`() {
        assertEquals(GestureSide.LEFT, PlayerGestures.sideFor(10f, 0))
    }

    @Test
    fun `a double tap seeks ten seconds in the tapped direction`() {
        assertEquals(40_000L, PlayerGestures.seekTarget(50_000, 120_000, GestureSide.LEFT))
        assertEquals(60_000L, PlayerGestures.seekTarget(50_000, 120_000, GestureSide.RIGHT))
        // Signed amounts; the screen adds the unit.
        assertEquals("-10", PlayerGestures.seekLabelSeconds(GestureSide.LEFT))
        assertEquals("+10", PlayerGestures.seekLabelSeconds(GestureSide.RIGHT))
    }

    @Test
    fun `seeking is clamped to the media bounds`() {
        assertEquals(0L, PlayerGestures.seekTarget(3_000, 120_000, GestureSide.LEFT))
        assertEquals(120_000L, PlayerGestures.seekTarget(115_000, 120_000, GestureSide.RIGHT))
        // An unknown duration must not invent an upper bound.
        assertEquals(30_000L, PlayerGestures.seekTarget(20_000, 0, GestureSide.RIGHT))
    }

    @Test
    fun `dragging up increases brightness and dragging down lowers it`() {
        val start = 0.5f
        assertEquals(0.7f, PlayerGestures.brightnessAfter(start, -200f, 1000), 0.001f)
        assertEquals(0.3f, PlayerGestures.brightnessAfter(start, 200f, 1000), 0.001f)
        assertEquals(start, PlayerGestures.brightnessAfter(start, 0f, 1000), 0.001f)
    }

    @Test
    fun `brightness never goes fully black or beyond full`() {
        assertEquals(0.02f, PlayerGestures.brightnessAfter(0.5f, 5_000f, 1000), 0.001f)
        assertEquals(1f, PlayerGestures.brightnessAfter(0.5f, -5_000f, 1000), 0.001f)
    }

    @Test
    fun `volume follows the same drag direction and stays in range`() {
        assertEquals(0.6f, PlayerGestures.volumeAfter(0.4f, -200f, 1000), 0.001f)
        assertEquals(0.2f, PlayerGestures.volumeAfter(0.4f, 200f, 1000), 0.001f)
        assertEquals(0f, PlayerGestures.volumeAfter(0.2f, 5_000f, 1000), 0.001f)
        assertEquals(1f, PlayerGestures.volumeAfter(0.8f, -5_000f, 1000), 0.001f)
    }

    @Test
    fun `a zero height drag is ignored rather than dividing by zero`() {
        assertEquals(0.5f, PlayerGestures.brightnessAfter(0.5f, -100f, 0), 0.001f)
        assertEquals(0.5f, PlayerGestures.volumeAfter(0.5f, -100f, 0), 0.001f)
    }

    @Test
    fun `the long press speed is the documented one`() {
        assertEquals(2f, PlayerGestures.LONG_PRESS_SPEED, 0.001f)
        assertEquals(10_000L, PlayerGestures.SEEK_STEP_MS)
    }
}
