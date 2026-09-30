package com.videodown.douyin.core

import com.videodown.douyin.domain.model.*
import com.videodown.douyin.data.generic.VideoEngineException
import org.junit.Assert.*
import org.junit.Test

class DownloadFailureGuidanceTest {
    @Test fun `typed HTTP and engine failures have concrete actions`() {
        assertEquals(RecoveryAction.BROWSER, failureGuidance(VideoEngineException("AUTH").failureKind).action)
        assertEquals(RecoveryAction.STORAGE, failureGuidance(VideoEngineException("SPACE").failureKind).action)
        assertEquals(DownloadFailureKind.STORAGE_ACCESS, VideoEngineException("STORAGE_ACCESS").failureKind)
        assertEquals(RecoveryAction.STORAGE, failureGuidance(VideoEngineException("STORAGE_ACCESS").failureKind).action)
        assertEquals(DownloadFailureKind.RUNTIME, VideoEngineException("RUNTIME").failureKind)
        assertEquals(RecoveryAction.REPARSE, failureGuidance(VideoEngineException("CHANGED").failureKind).action)
        assertEquals(RecoveryAction.NONE, failureGuidance(VideoEngineException("DRM").failureKind).action)
        assertEquals(RecoveryAction.RETRY, failureGuidance(AppError.Network("no details", failureKind = DownloadFailureKind.NETWORK).downloadFailureKind()).action)
    }
    @Test fun `legacy HTTP 403 remains ambiguous while expired and auth codes are distinguished`() {
        assertEquals(DownloadFailureKind.ACCESS_DENIED, legacyFailureKind("服务器返回 HTTP 403"))
        assertEquals(DownloadFailureKind.LINK_EXPIRED, legacyFailureKind("服务器返回 HTTP 410"))
        assertEquals(DownloadFailureKind.LOGIN_REQUIRED, legacyFailureKind("服务器返回 HTTP 401"))
        assertEquals(RecoveryAction.BROWSER, failureGuidance(DownloadFailureKind.ACCESS_DENIED).secondaryAction)
        assertEquals(DownloadFailureKind.WIFI_REQUIRED, legacyFailureKind("仅在 Wi-Fi 下下载"))
        assertNull(legacyFailureKind(null))
    }
}
