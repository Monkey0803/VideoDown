package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.VideoPlatform
import org.junit.Assert.*
import org.junit.Test

class WeiboGifParserTest {
    @Test fun `GIF MP4 preserves dimensions author cover and signed query`() {
        val video = WeiboJsonParser().parse(GIF_POST, "RkkIUuy8h")!!
        assertEquals("RkkIUuy8h_gif-one", video.awemeId)
        assertEquals("动图测试", video.title)
        assertEquals(VideoPlatform.WEIBO, video.platform)
        assertEquals("动图作者", video.author.nickname)
        assertEquals("https://weibo.com/u/2453184077", video.author.profileUrl)
        assertEquals("https://m.weibo.cn/status/RkkIUuy8h", video.shareUrl)
        assertEquals("https://wx4.sinaimg.cn/large/gif-one.gif", video.coverUrl)
        val variant = video.variants.single()
        assertEquals("https://g.us.sinaimg.cn/example.mp4?label=gif_mp4&Expires=123", variant.url)
        assertEquals("720P", variant.label)
        assertEquals(720, variant.width)
        assertEquals(1280, variant.height)
        assertEquals("https://weibo.com/", variant.referer)
        assertNull(variant.sizeBytes)
    }

    @Test fun `multiple GIFs retain picture order and independent media identities`() {
        val result = WeiboJsonParser().parseVideos(MULTI_GIF_POST, "RkkIUuy8h")!!
        assertEquals(listOf("RkkIUuy8h_second", "RkkIUuy8h_first"), result.videos.map { it.awemeId })
        assertEquals(listOf("动图 1", "动图 2"), result.videos.map { it.mediaLabel })
        assertEquals(listOf("360P", "720P"), result.videos.map { it.variants.single().label })
        assertEquals("https://video.example/second.mp4", result.selectedVideo.defaultVariant!!.url)
        assertEquals(result.selectedVideo, WeiboJsonParser().parse(MULTI_GIF_POST, "RkkIUuy8h"))
    }

    @Test fun `normal videos remain first when a post also contains a GIF`() {
        val body = GIF_POST.dropLast(1) + """, "page_info":{"page_pic":"https://img.example/video.jpg",
            "media_info":{"stream_url_hd":"https://video.example/normal.mp4","duration":12}}}"""
        val videos = WeiboJsonParser().parseVideos(body, "RkkIUuy8h")!!.videos
        assertEquals(2, videos.size)
        assertEquals("RkkIUuy8h", videos.first().awemeId)
        assertEquals(12_000, videos.first().durationMs)
        assertEquals("https://video.example/normal.mp4", videos.first().variants.single().url)
        assertEquals("RkkIUuy8h_gif-one", videos.last().awemeId)
    }

    @Test fun `reposted GIF keeps original post author and source URL`() {
        val body = """{"data":{"id":"outer","text_raw":"转发","retweeted_status":$GIF_POST}}"""
        val video = WeiboJsonParser().parse(body, "outer")!!
        assertEquals("RkkIUuy8h_gif-one", video.awemeId)
        assertEquals("动图作者", video.author.nickname)
        assertEquals("https://m.weibo.cn/status/RkkIUuy8h", video.shareUrl)
    }

    @Test fun `pictures without downloadable MP4 are not exposed as videos`() {
        listOf("", "https://img.example/original.gif", "https://video.example/a.m3u8",
            "file:///tmp/a.mp4", "https://user:password@video.example/a.mp4").forEach { url ->
            val body = """{"idstr":"123","pic_infos":{"gif":{"type":"gif","video":"$url"}}}"""
            assertNull(url, WeiboJsonParser().parse(body, "123"))
        }
    }

    @Test fun `missing picture order and invalid dimensions still allow an MP4`() {
        val body = """{"idstr":"123","pic_infos":{"broken":false,"gif":{"video":"//video.example/a.mp4",
            "largest":{"url":"https://img.example/a.gif","width":0,"height":-1}}}}"""
        val variant = WeiboJsonParser().parse(body, "123")!!.variants.single()
        assertEquals("https://video.example/a.mp4", variant.url)
        assertNull(variant.width)
        assertNull(variant.height)
        assertEquals("原画", variant.label)
    }

    @Test fun `picture order excludes unreferenced media and duplicate picture IDs`() {
        val body = MULTI_GIF_POST.replace("[\"second\",\"photo\",\"first\"]", "[\"second\",\"second\",\"missing\"]")
        val videos = WeiboJsonParser().parseVideos(body, "RkkIUuy8h")!!.videos
        assertEquals(1, videos.size)
        assertEquals("RkkIUuy8h_second", videos.single().awemeId)
    }

    companion object {
        // Sanitized shape of the supplied post; never store expiring CDN signatures in fixtures.
        const val GIF_POST = """{"idstr":"123","mblogid":"RkkIUuy8h","text_raw":"动图测试",
            "user":{"idstr":"2453184077","screen_name":"动图作者"},"pic_ids":["gif-one"],
            "pic_infos":{"gif-one":{"type":"gif","video":"http://g.us.sinaimg.cn/example.mp4?label=gif_mp4&amp;Expires=123",
                "largest":{"url":"https://wx4.sinaimg.cn/large/gif-one.gif","width":720,"height":1280}}}}"""

        const val MULTI_GIF_POST = """{"mblogid":"RkkIUuy8h","pic_ids":["second","photo","first"],"pic_infos":{
            "first":{"type":"gif","video":"https://video.example/first.mp4","largest":{"width":720,"height":1280}},
            "photo":{"type":"pic","largest":{"url":"https://img.example/photo.jpg"}},
            "second":{"type":"gif","video":"https://video.example/second.mp4","largest":{"width":360,"height":640}}
        }}"""
    }
}
