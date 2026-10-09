package com.videodown.douyin.domain.model

import org.junit.Assert.*
import org.junit.Test

/** The boundary that decides what an automatic resume may touch. */
class AutoResumePolicyTest {

    private fun record(status: DownloadStatus, failureKind: DownloadFailureKind? = null,
        pauseCause: DownloadPauseCause? = null, errorMessage: String? = null) =
        DownloadRecord(id = "t", awemeId = "t", title = "t", status = status,
            failureKind = failureKind, pauseCause = pauseCause, errorMessage = errorMessage)

    @Test fun `network and process interruptions are eligible`() {
        assertTrue(record(DownloadStatus.PAUSED, pauseCause = DownloadPauseCause.NETWORK).canAutoResume)
        assertTrue(record(DownloadStatus.PAUSED, pauseCause = DownloadPauseCause.PROCESS).canAutoResume)
    }

    @Test fun `a deliberate pause is never eligible`() {
        assertFalse(record(DownloadStatus.PAUSED, pauseCause = DownloadPauseCause.USER).canAutoResume)
    }

    @Test fun `history written before pause causes existed stays manual`() {
        assertFalse(record(DownloadStatus.PAUSED).canAutoResume)
        assertFalse(record(DownloadStatus.PAUSED, errorMessage = WIFI_PAUSE_REASON_LEGACY).canAutoResume)
    }

    @Test fun `only self-clearing failures are eligible`() {
        assertTrue(record(DownloadStatus.FAILED, failureKind = DownloadFailureKind.NETWORK).canAutoResume)
        assertTrue(record(DownloadStatus.FAILED, failureKind = DownloadFailureKind.STORAGE_FULL).canAutoResume)
        assertTrue(record(DownloadStatus.FAILED, errorMessage = "网络连接超时，请重试").canAutoResume)
        listOf(DownloadFailureKind.LOGIN_REQUIRED, DownloadFailureKind.ACCESS_DENIED,
            DownloadFailureKind.LINK_EXPIRED, DownloadFailureKind.FORMAT_CHANGED,
            DownloadFailureKind.RATE_LIMITED, DownloadFailureKind.BACKGROUND_SERVICE,
            DownloadFailureKind.UNSUPPORTED, DownloadFailureKind.NO_AUDIO,
            DownloadFailureKind.UNKNOWN).forEach { kind ->
            assertFalse("$kind 不应自动继续", record(DownloadStatus.FAILED, failureKind = kind).canAutoResume)
        }
    }

    @Test fun `active and finished tasks are not candidates`() {
        listOf(DownloadStatus.PENDING, DownloadStatus.RUNNING, DownloadStatus.COMPLETED).forEach { status ->
            assertFalse(record(status, pauseCause = DownloadPauseCause.NETWORK).canAutoResume)
        }
    }

    private companion object {
        /** A legacy Wi-Fi pause message that must not be mistaken for a resumable network drop. */
        const val WIFI_PAUSE_REASON_LEGACY = "已暂停：仅在 Wi-Fi 下下载，请连接 Wi-Fi 后点击继续"
    }
}
