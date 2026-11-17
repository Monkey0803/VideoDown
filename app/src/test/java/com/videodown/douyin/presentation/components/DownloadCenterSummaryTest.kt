package com.videodown.douyin.presentation.components

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadCenterSummaryTest {
    private fun record(id: String, status: DownloadStatus, fraction: Float = 0f) = DownloadRecord(
        id = id,
        awemeId = id,
        title = id,
        status = status,
        totalBytes = 100,
        downloadedBytes = (fraction * 100).toLong(),
    )

    @Test fun `active tasks win and expose aggregate progress`() {
        val records = listOf(
            record("running", DownloadStatus.RUNNING, .25f),
            record("pending", DownloadStatus.PENDING, .75f),
            record("failed", DownloadStatus.FAILED),
        )
        assertEquals(DownloadCenterSummary(DownloadCenterKind.ACTIVE, 2, .5f), downloadCenterSummary(records))
    }

    @Test fun `failed tasks win over paused tasks when nothing is active`() {
        val summary = downloadCenterSummary(listOf(
            record("failed", DownloadStatus.FAILED),
            record("paused", DownloadStatus.PAUSED),
        ))
        assertEquals(DownloadCenterSummary(DownloadCenterKind.FAILED, 1), summary)
    }

    @Test fun `paused tasks remain actionable and completed-only history stays hidden`() {
        assertEquals(
            DownloadCenterSummary(DownloadCenterKind.PAUSED, 2),
            downloadCenterSummary(listOf(record("a", DownloadStatus.PAUSED), record("b", DownloadStatus.PAUSED))),
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
}
