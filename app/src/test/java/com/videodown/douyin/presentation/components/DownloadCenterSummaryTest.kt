package com.videodown.douyin.presentation.components

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadCenterSummaryTest {
    private fun record(id: String, status: DownloadStatus, fraction: Float = 0f, total: Long = 100) = DownloadRecord(
        id = id,
        awemeId = id,
        title = id,
        status = status,
        totalBytes = total,
        downloadedBytes = (fraction * total).toLong(),
    )

    @Test fun `active tasks win and expose aggregate progress`() {
        val records = listOf(
            record("running", DownloadStatus.RUNNING, .25f),
            record("pending", DownloadStatus.PENDING, .75f, total = 300),
            record("failed", DownloadStatus.FAILED),
        )
        assertEquals(
            DownloadCenterSummary(DownloadCenterKind.ACTIVE, 2, .625f, failedCount = 1, pausedCount = 1),
            downloadCenterSummary(records, failedCount = 1, pausedCount = 1),
        )
    }

    @Test fun `only unseen failed and paused tasks appear when nothing is active`() {
        val summary = downloadCenterSummary(listOf(
            record("failed", DownloadStatus.FAILED),
            record("paused", DownloadStatus.PAUSED),
        ), failedCount = 1, pausedCount = 1)
        assertEquals(DownloadCenterSummary(DownloadCenterKind.FAILED, 1, pausedCount = 1), summary)
    }

    @Test fun `historical paused tasks stay hidden until caller reports new attention`() {
        val history = listOf(record("a", DownloadStatus.PAUSED), record("b", DownloadStatus.PAUSED))
        assertNull(downloadCenterSummary(history))
        assertEquals(
            DownloadCenterSummary(DownloadCenterKind.PAUSED, 2),
            downloadCenterSummary(history, pausedCount = 2),
        )
        assertNull(downloadCenterSummary(listOf(record("done", DownloadStatus.COMPLETED, 1f))))
    }

    @Test fun `session completions appear only when the caller reports a new result`() {
        val history = listOf(record("done", DownloadStatus.COMPLETED, 1f))
        assertEquals(
            DownloadCenterSummary(DownloadCenterKind.COMPLETED, 2),
            downloadCenterSummary(history, completedCount = 2),
        )
    }

    @Test fun `unknown active total does not pretend to have aggregate progress`() {
        assertEquals(
            DownloadCenterSummary(DownloadCenterKind.ACTIVE, 1),
            downloadCenterSummary(listOf(record("pending", DownloadStatus.PENDING, total = 0))),
        )
    }
}
