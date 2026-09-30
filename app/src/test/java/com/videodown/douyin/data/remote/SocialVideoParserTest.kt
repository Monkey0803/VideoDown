package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.VideoPlatform
import org.junit.Assert.*
import org.junit.Test

class SocialVideoParserTest {
    @Test fun `recognizes the supplied URLs and shared captions`() {
        assertEquals(VideoPlatform.WEIBO, VideoLinkExtractor.extract("分享 https://weibo.com/1234567890/RklMe8iOM，推荐")?.platform)
        assertEquals("RklMe8iOM", VideoLinkExtractor.weiboId("https://weibo.com/1234567890/RklMe8iOM"))
        assertEquals(VideoPlatform.KUAISHOU, VideoLinkExtractor.extract("视频 https://www.kuaishou.com/f/X3uQi2UzJmqN2fq。")?.platform)
        assertEquals(VideoPlatform.DOUYIN, VideoLinkExtractor.extract("https://v.douyin.com/Abcd/")?.platform)
    }

    @Test fun `recognizes mobile detail and redirected kuaishou URLs`() {
        assertEquals("5348502741978964", VideoLinkExtractor.weiboId("https://m.weibo.cn/detail/5348502741978964"))
        assertEquals(VideoPlatform.KUAISHOU, VideoLinkExtractor.extract("https://m.gifshow.com/fw/photo/3xs8rvdz44kheme")?.platform)
        assertEquals("3xs8rvdz44kheme", VideoLinkExtractor.kuaishouId("https://m.gifshow.com/fw/photo/3xs8rvdz44kheme?cc=share"))
    }

    @Test fun `lookalike hosts stay generic and credentials are rejected`() {
        listOf("https://weibo.com.evil.example/123/Abcdef", "https://evil.example/?url=weibo.com/123/Abcdef",
            "https://kuaishou.com.evil.example/f/token").forEach {
            assertEquals(VideoPlatform.GENERIC, VideoLinkExtractor.extract(it)?.platform)
        }
        listOf("https://weibo.com@evil.example/123/Abcdef", "https://x@weibo.com/123/Abcdef", "file:///weibo.com/123/Abcdef").forEach {
            assertNull(it, VideoLinkExtractor.extract(it))
        }
        assertNull(VideoLinkExtractor.weiboId("https://weibo.com/u/123456"))
    }

    @Test fun `weibo exposes MP4 quality choices but excludes storyboard images`() {
        val video = WeiboJsonParser().parse(WEIBO, "RklMe8iOM")!!
        assertEquals(VideoPlatform.WEIBO, video.platform)
        assertEquals("测试作者", video.author.nickname)
        assertEquals("https://weibo.com/u/5867737487", video.author.profileUrl)
        assertEquals(listOf("1080P", "720P"), video.sortedVariants.map { it.label })
        assertEquals(66_500, video.durationMs)
        assertEquals(123456L, video.sortedVariants.first().sizeBytes)
        assertTrue(video.variants.all { it.referer == "https://weibo.com/" })
        assertEquals("https://img.example/cover.jpg", video.coverUrl)
    }

    @Test fun `weibo handles legacy direct streams and wrapped mobile JSON`() {
        val video = WeiboJsonParser().parse("""{"data":{"id":"12345678","page_info":{"media_info":{
            "stream_url_hd":"//video.example/legacy.mp4?x=1&amp;y=2","duration":12}},"user":{"screen_name":"旧视频"}}}""", "12345678")!!
        assertEquals("https://video.example/legacy.mp4?x=1&y=2", video.variants.single().url)
        assertEquals(12_000, video.durationMs)
    }

    @Test fun `weibo rejects login HTML and image only posts`() {
        assertNull(WeiboJsonParser().parse("<html>请登录</html>", "12345678"))
        assertNull(WeiboJsonParser().parse("""{"id":"12345678","pics":[]}""", "12345678"))
    }

    @Test fun `kuaishou extracts assigned JSON without executing it`() {
        val video = KuaishouJsonParser().parse(KUAISHOU, "https://m.gifshow.com/fw/photo/3xs8rvdz44kheme")!!
        assertEquals(VideoPlatform.KUAISHOU, video.platform)
        assertEquals("括号 } 和引号 \" 保留", video.title)
        assertEquals("3xs8rvdz44kheme", video.awemeId)
        assertEquals("快手作者", video.author.nickname)
        assertEquals(261_000, video.durationMs)
        assertEquals(1, video.sortedVariants.size)
        assertEquals("720P", video.sortedVariants.single().label)
        assertEquals("https://video.example/main.mp4", video.sortedVariants.single().url)
        assertEquals("https://www.kuaishou.com/profile/user_eid", video.author.profileUrl)
    }

    @Test fun `kuaishou never substitutes a recommended video with a different id`() {
        assertNull(KuaishouJsonParser().parse(KUAISHOU, "https://m.gifshow.com/fw/photo/another_video"))
    }

    @Test fun `kuaishou leaves HLS only content without an MP4 download choice`() {
        val html = """window.INIT_STATE={"photo":{"photoId":"12345678","mainMvUrls":[{"url":"https://video.example/a.m3u8"}]}};"""
        assertTrue(KuaishouJsonParser().parse(html, "https://m.gifshow.com/fw/photo/12345678")!!.variants.isEmpty())
    }

    companion object {
        const val WEIBO = """{"idstr":"5348502741978964","mblogid":"RklMe8iOM","text_raw":"测试视频",
          "user":{"idstr":"5867737487","screen_name":"测试作者"},
          "page_info":{"page_pic":"//img.example/cover.jpg","media_info":{"duration":66.5,"playback_list":[
            {"play_info":{"type":1,"mime":"video/mp4","url":"https://video.example/720.mp4","width":720,"height":1280}},
            {"play_info":{"type":1,"mime":"video/mp4","url":"https://video.example/1080.mp4","width":1080,"height":1920,"size":123456}},
            {"play_info":{"type":3,"mime":"image/jpeg","url":"https://img.example/story.jpg","width":180,"height":320}}
          ]}}}"""
        const val KUAISHOU = """<script>window.INIT_STATE = {"obfuscated-route-key":{"photo":{
          "photoId":"5211509403980672754","share_info":"userId=user_eid&photoId=3xs8rvdz44kheme",
          "caption":"括号 } 和引号 \" 保留","userName":"快手作者","userEid":"user_eid","userId":"5232633762",
          "width":720,"height":1280,"duration":261000,"mainMvUrls":[{"url":"https://video.example/main.mp4"}],
          "manifest":{"adaptationSet":[{"representation":[{"url":"https://video.example/stream.m3u8","width":720,"height":1280}]}]}
        }}}; window.untrustedCode();</script>"""
    }
}
