package com.videodown.douyin.core

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    @Test
    fun `formats byte counts`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.0 M", formatBytes(1024 * 1024))
        assertEquals("1.50 G", formatBytes(1024L * 1024L * 1024L * 3 / 2))
        assertEquals("--", formatBytes(-1))
    }

    @Test
    fun `switches to M and G at their size boundaries`() {
        val megabyte = 1024L * 1024
        val gigabyte = megabyte * 1024
        assertEquals("1024 KB", formatBytes(megabyte - 1))
        assertEquals("1.0 M", formatBytes(megabyte))
        assertEquals("1.0 M", formatBytes(megabyte + 1))
        assertEquals("1024.0 M", formatBytes(gigabyte - 1))
        assertEquals("1.00 G", formatBytes(gigabyte))
        assertEquals("1.00 G", formatBytes(gigabyte + 1))
    }

    @Test
    fun `formats transfer speed`() {
        assertEquals("--", formatSpeed(0))
        assertEquals("1.0 M/s", formatSpeed(1024 * 1024))
    }

    @Test
    fun `formats durations`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:15", formatDuration(15_320))
        assertEquals("1:01", formatDuration(61_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test
    fun `estimates remaining time without guessing`() {
        // Parts, not sentences: the screen words them (and translates them).
        assertEquals(RemainingTime.Seconds(10), remainingTime(10L * 1024, 1024L))
        assertEquals(RemainingTime.MinutesSeconds(2, 10), remainingTime(130L * 1024, 1024L))
        assertEquals(RemainingTime.HoursMinutes(1, 0), remainingTime(3600L * 1024, 1024L))
    }

    @Test
    fun `no estimate is produced for an unknown size or a stalled transfer`() {
        assertEquals(null, remainingTime(0L, 1024L))
        assertEquals(null, remainingTime(-1L, 1024L))
        assertEquals(null, remainingTime(1024L, 0L))
        assertEquals(null, remainingTime(1024L, -5L))
    }

    @Test
    fun `a partial second still rounds up to one second`() {
        assertEquals(RemainingTime.Seconds(1), remainingTime(1L, 1024L))
        assertEquals(RemainingTime.Seconds(2), remainingTime(1025L, 1024L))
    }

    @Test
    fun `formats relative time`() {
        val now = 1_700_000_000_000L
        assertEquals(RelativeTime.JustNow, relativeTime(now - 30_000, now))
        assertEquals(RelativeTime.Minutes(5), relativeTime(now - 5 * 60_000, now))
        assertEquals(RelativeTime.Hours(3), relativeTime(now - 3 * 3_600_000, now))
        assertEquals(RelativeTime.Days(2), relativeTime(now - 2 * 86_400_000, now))
    }
}

class DownloadRecordTest {

    private fun record(
        downloaded: Long,
        total: Long,
        status: DownloadStatus,
    ) = DownloadRecord(
        id = "id",
        awemeId = "7123456789012345678",
        title = "标题",
        partialPath = "/tmp/x.part",
        downloadedBytes = downloaded,
        totalBytes = total,
        status = status,
    )

    @Test
    fun `progress fraction reflects downloaded bytes`() {
        assertEquals(0.5f, record(50, 100, DownloadStatus.RUNNING).progressFraction, 0.001f)
    }

    @Test
    fun `a completed record reads as fully downloaded even without a known total`() {
        assertEquals(1f, record(0, -1, DownloadStatus.COMPLETED).progressFraction, 0.001f)
    }

    @Test
    fun `an unknown total does not produce a bogus fraction`() {
        assertEquals(0f, record(500, -1, DownloadStatus.RUNNING).progressFraction, 0.001f)
    }

    @Test
    fun `paused and failed records are resumable`() {
        assertTrue(record(10, 100, DownloadStatus.PAUSED).isResumable)
        assertTrue(record(10, 100, DownloadStatus.FAILED).isResumable)
        assertFalse(record(100, 100, DownloadStatus.COMPLETED).isResumable)
        assertFalse(record(10, 100, DownloadStatus.RUNNING).isResumable)
    }

    @Test
    fun `a blank author name stays blank so the screen can substitute wording`() {
        assertEquals("", record(0, 0, DownloadStatus.PENDING).displayAuthor)
    }
}
