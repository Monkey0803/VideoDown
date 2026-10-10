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

    @Test fun `the normal library never mixes in private records`() {
        val mixed = listOf(
            DownloadRecord("a", "a", "公开", updatedAt = 10),
            DownloadRecord("p", "p", "私密", updatedAt = 20, isPrivate = true),
        )
        assertEquals(listOf("a"), LibraryQuery().apply(mixed).map { it.id })
        // Even when the vault has been unlocked, the ordinary list stays ordinary.
        assertEquals(listOf("a"), LibraryQuery(vaultUnlocked = true).apply(mixed).map { it.id })
    }

    @Test fun `a locked vault yields nothing and an unlocked one yields only private records`() {
        val mixed = listOf(
            DownloadRecord("a", "a", "公开", updatedAt = 10),
            DownloadRecord("p", "p", "私密", updatedAt = 20, isPrivate = true),
        )
        assertTrue("A locked vault must not leak a single item", LibraryQuery(vaultOnly = true).apply(mixed).isEmpty())
        assertEquals(listOf("p"), LibraryQuery(vaultOnly = true, vaultUnlocked = true).apply(mixed).map { it.id })
    }

    @Test fun `vault search, sort and favourites apply to the private subset`() {
        val mixed = listOf(
            DownloadRecord("p1", "p1", "旅行 甲", authorNickname = "Alice", updatedAt = 10, downloadedBytes = 100, isPrivate = true),
            DownloadRecord("p2", "p2", "旅行 乙", authorNickname = "Bob", updatedAt = 30, downloadedBytes = 300, isPrivate = true, favorite = true),
            DownloadRecord("a", "a", "旅行 丙", updatedAt = 20),
        )
        val vault = LibraryQuery(vaultOnly = true, vaultUnlocked = true)
        assertEquals(listOf("p2", "p1"), vault.apply(mixed).map { it.id })
        assertEquals(listOf("p2"), vault.copy(text = "Bob").apply(mixed).map { it.id })
        assertEquals(listOf("p1", "p2"), vault.copy(sort = LibrarySort.OLDEST).apply(mixed).map { it.id })
        assertEquals(listOf("p2"), vault.copy(favoritesOnly = true).apply(mixed).map { it.id })
    }
}