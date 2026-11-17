package com.videodown.douyin.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDensityPolicyTest {
    @Test fun queueSummaryOnlyAppearsWhenWorkExists() {
        assertFalse(UiDensityPolicy.showQueueSummary(running = 0, pending = 0))
        assertTrue(UiDensityPolicy.showQueueSummary(running = 1, pending = 0))
        assertTrue(UiDensityPolicy.showQueueSummary(running = 0, pending = 2))
    }

    @Test fun firstUseGuideDoesNotCompeteWithInputOrClipboardOffer() {
        assertTrue(UiDensityPolicy.showFirstUseGuide(false, "", false, dismissed = false))
        assertFalse(UiDensityPolicy.showFirstUseGuide(true, "", false, dismissed = false))
        assertFalse(UiDensityPolicy.showFirstUseGuide(false, "https://example.com", false, dismissed = false))
        assertFalse(UiDensityPolicy.showFirstUseGuide(false, "", true, dismissed = false))
        assertFalse(UiDensityPolicy.showFirstUseGuide(false, "", false, dismissed = true))
    }

    @Test fun emptyCollectionsHideManagementUntilAQueryNeedsClearing() {
        assertFalse(UiDensityPolicy.showManagementControls(itemCount = 0, hasActiveQuery = false))
        assertTrue(UiDensityPolicy.showManagementControls(itemCount = 1, hasActiveQuery = false))
        assertTrue(UiDensityPolicy.showManagementControls(itemCount = 0, hasActiveQuery = true))
    }

    @Test fun firstUseAndEmptyRecentSectionsStayProgressive() {
        assertFalse(UiDensityPolicy.showExpandedHomeSections(firstUseGuideVisible = true))
        assertTrue(UiDensityPolicy.showExpandedHomeSections(firstUseGuideVisible = false))
        assertFalse(UiDensityPolicy.showRecentSection(0))
        assertTrue(UiDensityPolicy.showRecentSection(1))
    }
}
