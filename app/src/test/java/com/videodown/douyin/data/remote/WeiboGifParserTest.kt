package com.videodown.douyin.data.remote

import com.videodown.douyin.core.TestText

import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.VideoPlatform
import org.junit.Assert.*
import org.junit.Test

class WeiboGifParserTest {
    @org.junit.Before
    fun installTextResolver() = com.videodown.douyin.core.TestText.install()

    @Test fun `GIF MP4 preserves dimensions author cover and signed query`() {
        val video = WeiboJsonParser().parse(GIF_POST, "RkkIUuy8h")!!
        assertEquals("RkkIUuy8h_gif-one", video.awemeId)
        assertEquals("动图测试", video.title)
        assertEquals(VideoPlatform.WEIBO, video.platform)
        assertEquals("动图作者", video.author.nickname)
        assertEquals("https://weibo.com/u/2453184077", video.author.profileUrl)
        assertEquals("https://m.weibo.cn/status/RkkIUuy8h", video.shareUrl)
        assertEquals("https://wx4.sinaimg.cn/large/gif-one.gif", video.coverUrl)
        val variant = video.variants.first { it.kind == MediaKind.VIDEO }
        assertEquals("https://g.us.sinaimg.cn/example.mp4?label=gif_mp4&Expires=123", variant.url)
        assertEquals("720P", variant.label)
        assertEquals(720, variant.width)
        assertEquals(1280, variant.height)
        assertEquals("https://weibo.com/", variant.referer)
        assertNull(variant.sizeBytes)
        val gif = video.variants.first { it.kind == MediaKind.IMAGE }
        assertEquals("GIF", gif.label)
        assertEquals("https://wx4.sinaimg.cn/large/gif-one.gif", gif.url)
        assertEquals("gif", gif.extensionOrDefault)
    }

    @Test fun `multiple GIFs and still pictures retain picture order and independent media identities`() {
        val result = WeiboJsonParser().parseVideos(MULTI_GIF_POST, "RkkIUuy8h")!!
        // 动图仍是 mp4 条目；帖子里的静态图（第 2 张）现在是独立的图片条目。
        assertEquals(listOf("RkkIUuy8h_second", "RkkIUuy8h_first", "RkkIUuy8h_img2"), result.videos.map { it.awemeId })
        // The label names the resource and carries its argument; the wording lives in values-en.
        assertEquals(listOf("media_label_animated(1)", "media_label_animated(2)", "media_label_image(2)"), result.videos.map { it.mediaLabel })
        assertEquals(listOf("360P", "720P", "quality_original"), result.videos.map { it.variants.single().label })
        assertEquals("https://video.example/second.mp4", result.selectedVideo.defaultVariant!!.url)
        assertTrue("the still picture is an image entry", result.videos.last().isImagePost)
        assertEquals("https://img.example/photo.jpg", result.videos.last().variants.single().url)
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
        val variant = WeiboJsonParser().parse(body, "123")!!.variants.first { it.kind == MediaKind.VIDEO }
        assertEquals("https://video.example/a.mp4", variant.url)
        assertNull(variant.width)
        assertNull(variant.height)
        assertEquals("quality_original", variant.label)
    }

    @Test fun `picture order excludes unreferenced media and duplicate picture IDs`() {
        val body = MULTI_GIF_POST.replace("[\"second\",\"photo\",\"first\"]", "[\"second\",\"second\",\"missing\"]")
        val videos = WeiboJsonParser().parseVideos(body, "RkkIUuy8h")!!.videos
        assertEquals(1, videos.size)
        assertEquals("RkkIUuy8h_second", videos.single().awemeId)
    }

    @Test fun `live photos preserve the still frame and paired mov`() {
        val result = WeiboJsonParser().parseVideos(LIVE_PHOTO_POST, "Rrei7jysH")!!
        assertEquals(listOf("Rrei7jysH_img1", "Rrei7jysH_img2"), result.videos.map { it.awemeId })
        assertTrue(result.videos.all { it.isImagePost })
        assertEquals(listOf(MediaKind.IMAGE, MediaKind.IMAGE), result.videos.map { it.variants.single().kind })
        // Live Photo 的 original 被压到 1080，largest 才是最大静帧。
        assertEquals(
            listOf("https://wx4.sinaimg.cn/large/lp1.jpg", "https://wx5.sinaimg.cn/large/lp2.jpg"),
            result.videos.map { it.variants.single().url },
        )
        assertEquals(listOf("jpg", "jpg"), result.videos.map { it.variants.single().extensionOrDefault })
        assertEquals(
            listOf(
                "https://video.weibo.com/media/play?livephoto=lp1.mov",
                "https://video.weibo.com/media/play?livephoto=lp2.mov",
            ),
            result.videos.map { it.variants.single().livePhotoMotion?.sourceUrl },
        )
        assertTrue(result.videos.all { it.variants.single().isLivePhoto })
    }

    @Test fun `a gif and a live photo in one post stay one mp4 and one image`() {
        val body = """{"mblogid":"mixed","pic_ids":["gif","lp"],"pic_infos":{
            "gif":{"type":"gif","video":"https://video.example/mixed.mp4"},
            "lp":{"type":"livephoto","video":"https://video.weibo.com/media/play?livephoto=x.mov",
                  "largest":{"url":"https://wx4.sinaimg.cn/large/lp.jpg","width":1080,"height":1920}}}}"""
        val videos = WeiboJsonParser().parseVideos(body, "mixed")!!
        assertEquals(listOf("mixed_gif", "mixed_img2"), videos.videos.map { it.awemeId })
        assertFalse(videos.videos.first().isImagePost)
        assertTrue(videos.videos.last().isImagePost)
        assertEquals(MediaKind.IMAGE, videos.videos.last().variants.single().kind)
    }

    @Test fun `live photo without a motion resource never degrades to a still image`() {
        val body = """{"mblogid":"incomplete","pic_ids":["lp"],"pic_infos":{
            "lp":{"type":"livephoto","largest":{"url":"https://wx4.sinaimg.cn/large/lp.jpg"}}}}"""
        assertNull(WeiboJsonParser().parseVideos(body, "incomplete"))
    }

    @Test fun `specified Weibo animation offers both MP4 and original GIF formats`() {
        val video = WeiboJsonParser().parseVideos(ORIGINAL_GIF_POST, "RroaXt3As")!!.selectedVideo
        assertEquals("RroaXt3As_00652g1vly1ij1ttxd2xgg30fe0b4qva", video.awemeId)
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.IMAGE), video.variants.map { it.kind })
        assertEquals(listOf("400P", "GIF"), video.variants.map { it.label })
        assertTrue(video.variants.first().url.contains(".mp4?label=gif_mp4"))
        val gif = video.variants.last()
        assertEquals("https://wx1.sinaimg.cn/orj1080/00652g1vly1ij1ttxd2xgg30fe0b4qva.gif", gif.url)
        assertEquals("gif", gif.extensionOrDefault)
        assertEquals(MediaKind.IMAGE, gif.mediaKind)
    }

    @Test fun `specified long Weibo picture post exposes all eleven images`() {
        val pictureIds = (1..11).map { "photo-$it" }
        val pictures = pictureIds.joinToString(",") { id ->
            """"$id":{"type":"pic","original":{"url":"https://wx1.sinaimg.cn/orj1080/$id.jpg","width":1080,"height":1620},"largest":{"url":"https://wx1.sinaimg.cn/large/$id.jpg","width":1365,"height":2048}}"""
        }
        val body = """{"mblogid":"RrDD8EUZW","isLongText":true,"text_raw":"山田あい",
            "user":{"idstr":"2453184077","screen_name":"进击的阿光"},
            "pic_num":11,"pic_ids":${pictureIds.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")},
            "pic_infos":{$pictures}}"""

        val result = WeiboJsonParser().parseVideos(body, "RrDD8EUZW")!!

        assertEquals(11, result.videos.size)
        assertTrue(result.videos.all { it.isImagePost })
        assertEquals((1..11).map { "RrDD8EUZW_img$it" }, result.videos.map { it.awemeId })
        assertEquals((1..11).map { "media_label_image($it)" }, result.videos.map { it.mediaLabel })
        assertEquals("https://wx1.sinaimg.cn/orj1080/photo-1.jpg", result.selectedVideo.defaultVariant!!.url)
        assertEquals("https://m.weibo.cn/status/RrDD8EUZW", result.selectedVideo.shareUrl)
    }

    companion object {
        // Sanitized shape of the supplied post; never store expiring CDN signatures in fixtures.
        const val GIF_POST = """{"idstr":"123","mblogid":"RkkIUuy8h","text_raw":"动图测试",
            "user":{"idstr":"2453184077","screen_name":"动图作者"},"pic_ids":["gif-one"],
            "pic_infos":{"gif-one":{"type":"gif","video":"http://g.us.sinaimg.cn/example.mp4?label=gif_mp4&amp;Expires=123",
                "largest":{"url":"https://wx4.sinaimg.cn/large/gif-one.gif","width":720,"height":1280}}}}"""

        /** 真实 livephoto 载荷的脱敏形状：`video` 是 .mov 动作片段，静帧在 original/largest。 */
        const val LIVE_PHOTO_POST = """{"mblogid":"Rrei7jysH","text_raw":"选腿还是雷","user":{"idstr":"3288446905","screen_name":"作者"},
            "pic_ids":["lp1","lp2"],"pic_infos":{
                "lp1":{"type":"livephoto","video":"https://video.weibo.com/media/play?livephoto=lp1.mov",
                    "original":{"url":"https://wx4.sinaimg.cn/orj1080/lp1.jpg","width":1080,"height":1920},
                    "largest":{"url":"https://wx4.sinaimg.cn/large/lp1.jpg","width":2048,"height":2730}},
                "lp2":{"type":"livephoto","video":"https://video.weibo.com/media/play?livephoto=lp2.mov",
                    "original":{"url":"https://wx5.sinaimg.cn/orj1080/lp2.jpg","width":1080,"height":1439},
                    "largest":{"url":"https://wx5.sinaimg.cn/large/lp2.jpg","width":2048,"height":2730}}}}"""

        /** 指定帖子 `RroaXt3As` 的脱敏媒体结构，同时包含站方 MP4 转码与 GIF 原图。 */
        const val ORIGINAL_GIF_POST = """{"mblogid":"RroaXt3As","text_raw":"好好开车，好好工作",
            "user":{"idstr":"5571216925","screen_name":"单良污妖"},
            "pic_ids":["00652g1vly1ij1ttxd2xgg30fe0b4qva"],"pic_infos":{
                "00652g1vly1ij1ttxd2xgg30fe0b4qva":{"type":"gif",
                    "video":"http://g.us.sinaimg.cn/o0/animation.mp4?label=gif_mp4&amp;Expires=123",
                    "original":{"url":"https://wx1.sinaimg.cn/orj1080/00652g1vly1ij1ttxd2xgg30fe0b4qva.gif","width":554,"height":400},
                    "largest":{"url":"https://wx1.sinaimg.cn/large/00652g1vly1ij1ttxd2xgg30fe0b4qva.gif","width":554,"height":400}}}}"""

        const val MULTI_GIF_POST = """{"mblogid":"RkkIUuy8h","pic_ids":["second","photo","first"],"pic_infos":{
            "first":{"type":"gif","video":"https://video.example/first.mp4","largest":{"width":720,"height":1280}},
            "photo":{"type":"pic","largest":{"url":"https://img.example/photo.jpg"}},
            "second":{"type":"gif","video":"https://video.example/second.mp4","largest":{"width":360,"height":640}}
        }}"""
    }
}
