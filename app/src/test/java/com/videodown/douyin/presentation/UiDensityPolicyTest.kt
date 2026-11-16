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
        assertTrue(UiDensityPolicy.showFirstUseGuide(false, "", false))
        assertFalse(UiDensityPolicy.showFirstUseGuide(true, "", false))
        assertFalse(UiDensityPolicy.showFirstUseGuide(false, "https://example.com", false))
        assertFalse(UiDensityPolicy.showFirstUseGuide(false, "", true))
    }
}
