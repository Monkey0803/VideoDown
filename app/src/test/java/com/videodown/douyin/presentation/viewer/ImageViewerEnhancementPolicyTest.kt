package com.videodown.douyin.presentation.viewer

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ImageViewerEnhancementPolicyTest {
    @Test fun `slideshow only accepts supported intervals`() {
        assertEquals(3, normalizedSlideshowIntervalSeconds(3))
        assertEquals(5, normalizedSlideshowIntervalSeconds(5))
        assertEquals(10, normalizedSlideshowIntervalSeconds(10))
        assertEquals(5, normalizedSlideshowIntervalSeconds(0))
        assertEquals(5, normalizedSlideshowIntervalSeconds(30))
    }

    @Test fun `slideshow pauses for every blocking condition`() {
        fun allowed(
            count: Int = 2,
            overlay: Boolean = false,
            started: Boolean = true,
            baseScale: Boolean = true,
        ) = canRunSlideshowTimer(true, count, overlay, started, baseScale)

        assertTrue(allowed())
        assertFalse(canRunSlideshowTimer(false, 2, false, true, true))
        assertFalse(allowed(count = 1))
        assertFalse(allowed(overlay = true))
        assertFalse(allowed(started = false))
        assertFalse(allowed(baseScale = false))
    }

    @Test fun `records are grouped by saved date in newest first order`() {
        val zone = ZoneId.of("UTC")
        val groups = imageDateGroups(
            listOf(
                record("new-a", 1_735_776_000_000L), // 2025-01-02
                record("old", 1_735_689_600_000L),   // 2025-01-01
                record("new-b", 1_735_779_600_000L),
            ),
            zone,
        )

        assertEquals(listOf("2025-01-02", "2025-01-01"), groups.map { it.date.toString() })
        assertEquals(listOf("new-a", "new-b"), groups.first().records.map { it.id })
    }

    private fun record(id: String, updatedAt: Long) = DownloadRecord(
        id = id,
        awemeId = id,
        title = id,
        sourceUrl = "https://example.com/$id.jpg",
        filePath = "/tmp/$id.jpg",
        status = DownloadStatus.COMPLETED,
        updatedAt = updatedAt,
    )
}
