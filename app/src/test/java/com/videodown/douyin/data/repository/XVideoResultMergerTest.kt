package com.videodown.douyin.data.repository

import com.videodown.douyin.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class XVideoResultMergerTest {
    private val page = "https://x.com/author/status/2096916397947257248"
    private fun video(id: String, platform: VideoPlatform, vararg variants: VideoVariant) =
        DouyinVideo(id, "标题", variants = variants.toList(), shareUrl = page, platform = platform)
    private fun format(url: String, type: MediaSourceType) = VideoVariant(url, "720P · ${type.name}",
        media = MediaDownload(type = type, pageUrl = page, mediaId = "222", formatId = "hls-720", extractor = "Twitter"))

    @Test fun `adds hls to exact media and preserves selected native index`() {
        val mp4 = VideoVariant("https://video.twimg.com/222.mp4", "720P")
        val hls = format("https://video.twimg.com/222.m3u8", MediaSourceType.HLS)
        val native = ParsedVideos(listOf(video("2096916397947257248_111", VideoPlatform.X, mp4),
            video("2096916397947257248_222", VideoPlatform.X, mp4)), 1)
        val extracted = ParsedVideos(listOf(video("222", VideoPlatform.GENERIC, mp4, hls)))
        val result = XVideoResultMerger.merge(page, native, extracted)!!
        assertEquals(2, result.videos.size)
        assertEquals("2096916397947257248_222", result.selectedVideo.awemeId)
        assertEquals(listOf(mp4, hls), result.selectedVideo.variants)
        assertEquals(listOf(mp4), result.videos.first().variants)
    }

    @Test fun `retains native items and appends card video once`() {
        val mp4 = VideoVariant("https://video.twimg.com/111.mp4", "720P")
        val card = format("https://video.twimg.com/card.m3u8", MediaSourceType.HLS)
        val native = ParsedVideos(listOf(video("2096916397947257248_111", VideoPlatform.X, mp4)))
        val extracted = ParsedVideos(listOf(video("111", VideoPlatform.GENERIC, mp4),
            video("card42", VideoPlatform.GENERIC, card)))
        val result = XVideoResultMerger.merge(page, native, extracted)!!
        assertEquals(2, result.videos.size)
        assertEquals(VideoPlatform.X, result.videos.last().platform)
        assertEquals("2096916397947257248_card42", result.videos.last().awemeId)
        assertEquals(card.media, result.videos.last().variants.single().media)
    }

    @Test fun `generic only post keeps engine media identity for download`() {
        val hls = format("https://video.twimg.com/222.m3u8", MediaSourceType.HLS)
        val result = XVideoResultMerger.merge(page, null,
            ParsedVideos(listOf(video("222", VideoPlatform.GENERIC, hls))))!!
        assertEquals("2096916397947257248_222", result.selectedVideo.awemeId)
        assertEquals("222", result.selectedVideo.variants.single().media?.mediaId)
    }
}
