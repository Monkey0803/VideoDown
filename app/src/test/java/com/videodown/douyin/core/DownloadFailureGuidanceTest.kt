package com.videodown.douyin.core

import com.videodown.douyin.domain.model.*
import com.videodown.douyin.data.generic.VideoEngineException
import org.junit.Assert.*
import org.junit.Test

class DownloadFailureGuidanceTest {
    @Test fun `typed HTTP and engine failures have concrete actions`() {
        assertEquals(RecoveryAction.BROWSER, failureActions(VideoEngineException("AUTH").failureKind).primary)
        assertEquals(RecoveryAction.STORAGE, failureActions(VideoEngineException("SPACE").failureKind).primary)
        assertEquals(DownloadFailureKind.STORAGE_ACCESS, VideoEngineException("STORAGE_ACCESS").failureKind)
        assertEquals(RecoveryAction.STORAGE, failureActions(VideoEngineException("STORAGE_ACCESS").failureKind).primary)
        assertEquals(DownloadFailureKind.RUNTIME, VideoEngineException("RUNTIME").failureKind)
        assertEquals(RecoveryAction.REPARSE, failureActions(VideoEngineException("CHANGED").failureKind).primary)
        assertEquals(RecoveryAction.NONE, failureActions(VideoEngineException("DRM").failureKind).primary)
        assertEquals(RecoveryAction.RETRY, failureActions(AppError.Network("no details", failureKind = DownloadFailureKind.NETWORK).downloadFailureKind()).primary)
    }
    @Test fun `legacy HTTP 403 remains ambiguous while expired and auth codes are distinguished`() {
        assertEquals(DownloadFailureKind.ACCESS_DENIED, legacyFailureKind("服务器返回 HTTP 403"))
        assertEquals(DownloadFailureKind.LINK_EXPIRED, legacyFailureKind("服务器返回 HTTP 410"))
        assertEquals(DownloadFailureKind.LOGIN_REQUIRED, legacyFailureKind("服务器返回 HTTP 401"))
        assertEquals(RecoveryAction.BROWSER, failureActions(DownloadFailureKind.ACCESS_DENIED).secondary)
        assertEquals(DownloadFailureKind.WIFI_REQUIRED, legacyFailureKind("仅在 Wi-Fi 下下载"))
        assertNull(legacyFailureKind(null))
    }

    @Test fun `every failure kind has a title, a body and an action`() {
        // The wording lives in the presentation layer, so completeness is asserted through the
        // resource mapping: a new kind cannot ship without both strings.
        val titles = DownloadFailureKind.entries.map { com.videodown.douyin.presentation.failureTitleRes(it) }
        val bodies = DownloadFailureKind.entries.map { com.videodown.douyin.presentation.failureDescriptionRes(it) }
        assertEquals(DownloadFailureKind.entries.size, titles.distinct().size)
        assertEquals(DownloadFailureKind.entries.size, bodies.distinct().size)
        assertTrue(titles.none { it == 0 })
        assertTrue(bodies.none { it == 0 })
        DownloadFailureKind.entries.forEach { assertNotNull(failureActions(it).primary) }
        assertEquals(RecoveryAction.entries.size, RecoveryAction.entries.map { com.videodown.douyin.presentation.recoveryActionRes(it) }.distinct().size)
    }
}