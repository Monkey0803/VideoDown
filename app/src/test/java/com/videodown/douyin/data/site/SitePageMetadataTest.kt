package com.videodown.douyin.data.site

import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.SitePageMetadata
import com.videodown.douyin.domain.model.OnlinePlaybackSession
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.model.isGenericSiteMediaTitle
import com.videodown.douyin.domain.model.toDownloadVideo
import com.videodown.douyin.domain.model.withSitePageMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePageMetadataTest {
    @Test fun `page title and episode replace manifest name without changing media identity`() {
        val original = DouyinVideo("media-id", "chunklist_b850000.m3u8", coverUrl = null)
        val enriched = original.withSitePageMetadata(SitePageMetadata(
            title = "  示例剧集  ",
            coverUrl = "https://img.example/poster.jpg",
            episodeLabel = " 第 2 集 ",
        ))

        assertEquals("media-id", enriched.awemeId)
        assertEquals("示例剧集 · 第 2 集", enriched.title)
        assertEquals("https://img.example/poster.jpg", enriched.coverUrl)
    }

    @Test fun `generic names and unsafe covers are rejected`() {
        assertTrue(isGenericSiteMediaTitle("chunklist_b123.m3u8"))
        assertTrue(isGenericSiteMediaTitle("https://cdn.example/master.m3u8?token=1"))
        assertFalse(isGenericSiteMediaTitle("Master Chef"))
        assertNull(SitePageMetadata(title = "chunklist", coverUrl = "file:///tmp/poster.jpg").displayTitle)
        assertNull(SitePageMetadata(coverUrl = "https://user:secret@example.com/poster.jpg").safeCoverUrl)
    }

    @Test fun `javascript result codec unwraps WebView string result`() {
        val encoded = "\"{\\\"title\\\":\\\"Fixture\\\",\\\"coverUrl\\\":\\\"https://img.example/a.jpg\\\",\\\"episodeLabel\\\":\\\"E03\\\",\\\"pageUrl\\\":\\\"https://www.kubb.tv/play/fixture?id=e3\\\"}\""
        val metadata = SitePageMetadataCodec.decodeJavascriptResult(encoded)!!
        assertEquals("Fixture · E03", metadata.displayTitle)
        assertEquals("https://img.example/a.jpg", metadata.safeCoverUrl)
        assertEquals("https://www.kubb.tv/play/fixture?id=e3", metadata.safePageUrl)
    }

    @Test fun `online session download keeps page url instead of temporary media url`() {
        val video = DouyinVideo("media-id", "示例剧集 · 第 2 集", shareUrl = "https://cdn.example/chunklist.m3u8")
        val session = OnlinePlaybackSession(
            id = "session",
            siteId = "kubb",
            pageUrl = "https://www.kubb.tv/play/show?id=episode-2",
            video = video,
            variant = VideoVariant("https://cdn.example/chunklist.m3u8", "原画"),
            referer = "https://www.kubb.tv/play/show?id=episode-2",
            userAgent = "fixture",
        )

        val downloadVideo = session.toDownloadVideo()

        assertEquals(video.title, downloadVideo.title)
        assertEquals(video.coverUrl, downloadVideo.coverUrl)
        assertEquals(session.pageUrl, downloadVideo.shareUrl)
    }
}
