package com.videodown.douyin.data.generic

import com.videodown.douyin.domain.model.*
import com.videodown.douyin.data.remote.VideoLinkExtractor
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GenericMetadataParserTest {
    private fun parse(json: String) = GenericMetadataParser().parse(Json.parseToJsonElement(json).jsonObject, "https://example.org/watch")

    @Test fun `unsupported declared containers never masquerade as MP4`() {
        val video = parse("""{"id":"html5","formats":[
            {"format_id":"ogv","url":"https://cdn.org/trailer.ogv","ext":"ogv","vcodec":"theora"},
            {"format_id":"ogg","url":"https://cdn.org/trailer.ogg","ext":"ogg","vcodec":"theora"},
            {"format_id":"mp4","url":"https://cdn.org/trailer.mp4","ext":"mp4","vcodec":"h264"},
            {"format_id":"webm","url":"https://cdn.org/trailer.webm","ext":"webm","vcodec":"vp8"}]}""").selectedVideo
        assertEquals(listOf("mp4", "webm"), video.variants.map { it.media!!.formatId })
        assertEquals(listOf("mp4", "webm"), video.variants.map { it.media!!.extension })
    }

    @Test fun `missing container keeps opaque direct video support`() {
        val variant = parse("""{"id":"opaque","url":"https://cdn.org/opaque","format_id":"original"}""")
            .selectedVideo.variants.single()
        assertEquals("mp4", variant.media!!.extension)
        assertEquals(MediaSourceType.DIRECT, variant.media.type)
    }

    @Test fun `generic URLs accepted without weakening credentials rejection`() {
        assertEquals(VideoPlatform.GENERIC, VideoLinkExtractor.extract("分享 https://example.org/video，看看")?.platform)
        assertNull(VideoLinkExtractor.extract("https://user:password@example.org/video"))
        assertNull(VideoLinkExtractor.extract("file:///etc/passwd"))
        assertNull(VideoLinkExtractor.extract("javascript:alert(1)"))
    }

    @Test fun `video only DASH is paired with audio and format identity is retained`() {
        val result = parse("""{"id":"abc","title":"Test","extractor_key":"Youtube","formats":[
            {"format_id":"v","url":"https://cdn.org/v","protocol":"http_dash_segments","ext":"mp4","vcodec":"avc1","acodec":"none","width":1920,"height":1080},
            {"format_id":"a","url":"https://cdn.org/a","ext":"m4a","vcodec":"none","acodec":"aac"}]}""")
        val variant = result.selectedVideo.variants.single()
        assertEquals(MediaSourceType.SEPARATE, variant.media!!.type)
        assertEquals("v+a", variant.media.formatSelector)
        assertEquals("https://cdn.org/a", variant.media.audioUrl)
        assertNull(variant.sizeBytes)
    }

    @Test fun `multiple embedded videos remain independent choices`() {
        val result = parse("""{"entries":[{"id":"one","url":"https://cdn.org/one.mp4","ext":"mp4"},{"id":"two","url":"https://cdn.org/two.m3u8","protocol":"m3u8_native","ext":"mp4"}]}""")
        assertEquals(2, result.videos.size)
        assertEquals(MediaSourceType.DIRECT, result.videos[0].variants[0].media!!.type)
        assertEquals(MediaSourceType.HLS, result.videos[1].variants[0].media!!.type)
    }

    @Test(expected = IllegalArgumentException::class) fun `live and DRM results are rejected`() {
        parse("""{"id":"live","url":"https://cdn.org/live.m3u8","is_live":true}""")
    }

    @Test fun `DRM formats and non web URLs never become candidates`() {
        val result = parse("""{"id":"one","formats":[
            {"url":"file:///private/video.mp4"},{"url":"https://cdn.org/drm","has_drm":true},
            {"url":"https://cdn.org/good.mp4","format_id":"ok","ext":"mp4"}]}""")
        assertEquals(1, result.selectedVideo.variants.size)
        assertEquals("ok", result.selectedVideo.variants[0].media!!.formatId)
    }

    @Test fun `historical downloads default to direct transfer without authentication`() {
        val old = Json.decodeFromString<DownloadRecord>("""{"id":"old","awemeId":"123","title":"旧视频"}""")
        assertNull(old.media)
        assertEquals(VideoPlatform.DOUYIN, old.platform)
        assertEquals(TransferPhase.DOWNLOADING, old.phase)
        val updated = old.copy(media = MediaDownload(pageUrl = "https://example.org", useBrowserSession = true))
        val json = Json.encodeToString(DownloadRecord.serializer(), updated)
        assertFalse(json.contains("Cookie"))
        assertFalse(json.contains("Authorization"))
    }

    @Test fun `resume identity changes when media or audio selection changes`() {
        val original = MediaDownload(mediaId = "a", formatId = "720", audioFormatId = "aac", extractor = "Generic")
        assertTrue(original.sameMedia(original.copy(pageUrl = "https://new.url")))
        assertFalse(original.sameMedia(original.copy(mediaId = "b")))
        assertFalse(original.sameMedia(original.copy(audioFormatId = "opus")))
    }

    @Test fun `subtitle languages are surfaced and machine captions kept apart`() {
        val video = parse("""{"id":"subs","url":"https://cdn.org/a.mp4","ext":"mp4",
            "subtitles":{"zh-Hans":[{"url":"https://cdn.org/a.zh.vtt"}],"en":[{"url":"https://cdn.org/a.en.vtt"}]},
            "automatic_captions":{"ja":[{"url":"https://cdn.org/a.ja.vtt"}]}}""").selectedVideo
        assertEquals(listOf("zh-Hans", "en"), video.subtitleLanguages)
        assertEquals(listOf("ja"), video.autoCaptionLanguages)
    }

    @Test fun `a source without subtitles reports none`() {
        val video = parse("""{"id":"plain","url":"https://cdn.org/a.mp4","ext":"mp4"}""").selectedVideo
        assertTrue(video.subtitleLanguages.isEmpty())
        assertTrue(video.autoCaptionLanguages.isEmpty())
        assertFalse(SubtitleSelection.NONE.available)
    }

    @Test fun `each entry of a collection carries its own subtitle languages`() {
        val videos = parse("""{"entries":[
            {"id":"one","url":"https://cdn.org/one.mp4","ext":"mp4","subtitles":{"en":[{"url":"https://cdn.org/one.en.vtt"}]}},
            {"id":"two","url":"https://cdn.org/two.mp4","ext":"mp4"}]}""").videos
        assertEquals(listOf("en"), videos.first { it.awemeId == "one" }.subtitleLanguages)
        assertTrue(videos.first { it.awemeId == "two" }.subtitleLanguages.isEmpty())
    }
}
