package com.videodown.douyin.domain.model

import com.videodown.douyin.presentation.speedLimitRate
import com.videodown.douyin.presentation.speedLimitRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimitTest {

    @Test
    fun `zero always means unlimited and uses a resource, not a raw rate`() {
        assertNotNull("zero needs the unlimited wording", speedLimitRes(0))
        assertNotNull(speedLimitRes(-3))
        assertNull("a positive rate is shown as a number", speedLimitRes(512))
        assertEquals(0L, speedLimitBytesPerSecond(0))
        assertEquals(0L, speedLimitBytesPerSecond(-3))
    }

    @Test
    fun `the offered options are distinct and start at unlimited`() {
        assertEquals(speedLimitOptions.size, speedLimitOptions.distinct().size)
        assertTrue(speedLimitOptions.all { it >= 0 })
        assertEquals(0, speedLimitOptions.first())
    }

    @Test
    fun `known values render as their offered rate`() {
        assertEquals("512 KB/s", speedLimitRate(512))
        assertEquals("1 MB/s", speedLimitRate(1024))
        assertEquals("5 MB/s", speedLimitRate(5120))
    }

    @Test
    fun `an unlisted value is shown as its raw rate rather than hidden`() {
        assertEquals("64 KB/s", speedLimitRate(64))
        assertEquals("3000 KB/s", speedLimitRate(3000))
    }

    @Test
    fun `rates convert to bytes per second`() {
        assertEquals(512L * 1024, speedLimitBytesPerSecond(512))
        assertEquals(1024L * 1024, speedLimitBytesPerSecond(1024))
    }
}
