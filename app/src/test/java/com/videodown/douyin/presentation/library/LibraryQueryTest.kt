package com.videodown.douyin.presentation.library

import com.videodown.douyin.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LibraryQueryTest {
    private val records = listOf(
        DownloadRecord("a", "a", "Travel 春天", authorNickname = "Alice", platform = VideoPlatform.X, updatedAt = 10, downloadedBytes = 300),
        DownloadRecord("b", "b", "夜景", authorNickname = "Alice", platform = VideoPlatform.WEIBO, updatedAt = 30, downloadedBytes = 100),
        DownloadRecord("c", "c", "春天", authorNickname = "Bob", platform = VideoPlatform.X, updatedAt = 20, downloadedBytes = 200))
    @Test fun `query trims space and searches title and author ignoring case`() {
        assertEquals(listOf("a"), LibraryQuery("  travel ALICE ").apply(records).map { it.id })
        assertEquals(listOf("b", "a"), LibraryQuery("alice").apply(records).map { it.id })
    }
    @Test fun `platform combines with search and unmatched query returns empty`() {
        assertEquals(listOf("a"), LibraryQuery("Alice", VideoPlatform.X).apply(records).map { it.id })
        assertTrue(LibraryQuery("夜景", VideoPlatform.X).apply(records).isEmpty())
    }
    @Test fun `sort orders by saved time or bytes with stable identity`() {
        assertEquals(listOf("b", "c", "a"), LibraryQuery().apply(records).map { it.id })
        assertEquals(listOf("a", "c", "b"), LibraryQuery(sort = LibrarySort.OLDEST).apply(records).map { it.id })
        assertEquals(listOf("a", "c", "b"), LibraryQuery(sort = LibrarySort.LARGEST).apply(records).map { it.id })
    }
    @Test fun `duplicate identity ignores expiring URL and keeps platform quality and format distinct`() {
        val video = DouyinVideo("id", "video", platform = VideoPlatform.X)
        val variant = VideoVariant("https://new", "1080P")
        val record = DownloadRecord("a", "id", "video", platform = VideoPlatform.X, resolutionLabel = "1080P", sourceUrl = "https://old")
        assertTrue(record.matchesSelection(video, variant))
        assertFalse(record.matchesSelection(video.copy(platform = VideoPlatform.WEIBO), variant))
        assertFalse(record.matchesSelection(video.copy(awemeId = "other"), variant))
        assertFalse(record.matchesSelection(video, variant.copy(label = "720P")))
        val media = MediaDownload(type = MediaSourceType.SEPARATE, mediaId = "id", formatId = "video", audioFormatId = "audio", extractor = "X")
        assertTrue(record.copy(media = media).matchesSelection(video, variant.copy(media = media.copy(pageUrl = "new"))))
        assertFalse(record.copy(media = media).matchesSelection(video, variant.copy(media = media.copy(formatId = "other"))))
    }
}
