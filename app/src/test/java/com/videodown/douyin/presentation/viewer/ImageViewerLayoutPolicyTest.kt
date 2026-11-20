package com.videodown.douyin.presentation.viewer

import com.videodown.douyin.presentation.adaptive.WindowWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageViewerLayoutPolicyTest {
    @Test fun `date grid grows with the window instead of staying at three columns`() {
        assertEquals(3, ImageViewerLayoutPolicy.dateGridColumns(WindowWidth.COMPACT))
        assertEquals(4, ImageViewerLayoutPolicy.dateGridColumns(WindowWidth.MEDIUM))
        assertEquals(6, ImageViewerLayoutPolicy.dateGridColumns(WindowWidth.EXPANDED))
    }

    @Test fun `thumbnail strip yields to the image on short windows`() {
        assertFalse(ImageViewerLayoutPolicy.showThumbnailStrip(479))
        assertTrue(ImageViewerLayoutPolicy.showThumbnailStrip(480))
        assertTrue(ImageViewerLayoutPolicy.showThumbnailStrip(900))
    }

    @Test fun `large fonts get a second label line`() {
        assertEquals(1, ImageViewerLayoutPolicy.actionLabelMaxLines(1f))
        assertEquals(1, ImageViewerLayoutPolicy.actionLabelMaxLines(1.29f))
        assertEquals(2, ImageViewerLayoutPolicy.actionLabelMaxLines(1.3f))
        assertEquals(2, ImageViewerLayoutPolicy.actionLabelMaxLines(2f))
    }
}
