package com.videodown.douyin.core

import com.videodown.douyin.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResultInboxTest {
    @Test fun `historical status is never treated as a new result`() {
        val inbox = DownloadResultInbox().recordTransition("old", null, DownloadStatus.COMPLETED)
        assertTrue(inbox.isEmpty)
    }

    @Test fun `latest terminal status replaces the previous notice for the same task`() {
        val inbox = DownloadResultInbox()
            .recordTransition("one", DownloadStatus.RUNNING, DownloadStatus.PAUSED)
            .recordTransition("one", DownloadStatus.PAUSED, DownloadStatus.RUNNING)
            .recordTransition("one", DownloadStatus.RUNNING, DownloadStatus.COMPLETED)
        assertEquals(setOf("one"), inbox.completedIds)
        assertTrue(inbox.failedIds.isEmpty())
        assertTrue(inbox.pausedIds.isEmpty())
    }

    @Test fun `reconcile drops deleted and stale task ids`() {
        val inbox = DownloadResultInbox(
            failedIds = setOf("failed", "deleted"),
            pausedIds = setOf("resumed"),
            completedIds = setOf("done"),
        ).reconciled(
            mapOf(
                "failed" to DownloadStatus.FAILED,
                "resumed" to DownloadStatus.RUNNING,
                "done" to DownloadStatus.COMPLETED,
            ),
        )
        assertEquals(setOf("failed"), inbox.failedIds)
        assertTrue(inbox.pausedIds.isEmpty())
        assertEquals(setOf("done"), inbox.completedIds)
    }
}
