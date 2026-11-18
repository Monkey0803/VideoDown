package com.videodown.douyin.presentation.viewer

import com.videodown.douyin.presentation.theme.AppMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageViewerTransitionPolicyTest {
    @Test fun `leaving the image viewer waits for its shared transition`() {
        assertEquals(
            AppMotion.DEFAULT_MS.toLong(),
            imageViewerSystemBarsRestoreDelayMillis(true, false, true),
        )
    }

    @Test fun `disabled animations restore system bars immediately`() {
        assertEquals(0L, imageViewerSystemBarsRestoreDelayMillis(true, false, false))
    }

    @Test fun `other navigation changes do not restore system bars`() {
        assertNull(imageViewerSystemBarsRestoreDelayMillis(false, false, true))
        assertNull(imageViewerSystemBarsRestoreDelayMillis(true, true, true))
    }
}
