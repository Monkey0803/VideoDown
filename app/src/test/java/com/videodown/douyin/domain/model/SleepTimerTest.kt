package com.videodown.douyin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `the offered choices are the documented ones`() {
        assertEquals(listOf(15, 30, 60), SleepTimer.minutes)
    }

    @Test
    fun `zero or negative minutes mean no timer`() {
        assertEquals(SleepTimer.OFF, SleepTimer.deadlineIn(0, now))
        assertEquals(SleepTimer.OFF, SleepTimer.deadlineIn(-5, now))
        assertFalse(SleepTimer.expired(SleepTimer.OFF, now))
        assertNull(SleepTimer.remainingRounded(SleepTimer.OFF, now))
    }

    @Test
    fun `a deadline is an absolute wall-clock instant`() {
        assertEquals(now + 15 * 60_000L, SleepTimer.deadlineIn(15, now))
        assertEquals(now + 60 * 60_000L, SleepTimer.deadlineIn(60, now))
    }

    @Test
    fun `a deadline expires exactly at its instant, not before`() {
        val deadline = SleepTimer.deadlineIn(15, now)
        assertFalse(SleepTimer.expired(deadline, deadline - 1))
        assertTrue(SleepTimer.expired(deadline, deadline))
        assertTrue(SleepTimer.expired(deadline, deadline + 1))
    }

    @Test
    fun `remaining time never goes negative`() {
        val deadline = SleepTimer.deadlineIn(15, now)
        assertEquals(15 * 60_000L, SleepTimer.remainingMs(deadline, now))
        assertEquals(0L, SleepTimer.remainingMs(deadline, deadline + 60_000L))
    }

    @Test
    fun `the remaining time counts down from minutes to seconds`() {
        val deadline = SleepTimer.deadlineIn(15, now)
        assertEquals(SleepRemaining.Minutes(15), SleepTimer.remainingRounded(deadline, now))
        assertEquals(SleepRemaining.Minutes(1), SleepTimer.remainingRounded(deadline, now + 14 * 60_000L))
        assertEquals(SleepRemaining.Seconds(59), SleepTimer.remainingRounded(deadline, now + 14 * 60_000L + 1_000L))
        assertEquals(SleepRemaining.Seconds(1), SleepTimer.remainingRounded(deadline, deadline - 1))
    }

    @Test
    fun `the remaining time never claims zero seconds`() {
        val deadline = SleepTimer.deadlineIn(15, now)
        // The amount is reported; the player words it ("about 1 s left").
        assertEquals(SleepRemaining.Seconds(1), SleepTimer.remainingRounded(deadline, deadline))
    }
}
