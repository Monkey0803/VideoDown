package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.VideoPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardLinkPolicyTest {

    private val douyinShareText =
        "7.32 复制打开抖音，看看【某某的作品】很有意思的视频 https://v.douyin.com/iRabcDeF/ 复制此链接，打开Dou音搜索"

    @Test
    fun `a supported platform link is offered`() {
        val candidate = ClipboardLinkPolicy.candidate(douyinShareText, null)
        assertEquals(VideoPlatform.DOUYIN, candidate?.platform)
        assertEquals("https://v.douyin.com/iRabcDeF/", candidate?.url)
        assertEquals(douyinShareText, candidate?.text)
    }

    @Test
    fun `every supported platform is recognised`() {
        assertEquals(VideoPlatform.WEIBO, ClipboardLinkPolicy.candidate("https://m.weibo.cn/status/RklMe8iOM", null)?.platform)
        assertEquals(VideoPlatform.KUAISHOU, ClipboardLinkPolicy.candidate("https://v.kuaishou.com/X3uQi2UzJmqN2fq", null)?.platform)
        assertEquals(VideoPlatform.X, ClipboardLinkPolicy.candidate("https://x.com/u/status/2096916397947257248", null)?.platform)
        assertEquals(VideoPlatform.X, ClipboardLinkPolicy.candidate("https://t.co/abc", null)?.platform)
    }

    @Test
    fun `a plain web link is never offered`() {
        assertNull(ClipboardLinkPolicy.candidate("https://example.com/video.mp4", null))
        assertNull(ClipboardLinkPolicy.candidate("https://www.youtube.com/watch?v=abc", null))
    }

    @Test
    fun `text without a link or without content is ignored`() {
        assertNull(ClipboardLinkPolicy.candidate(null, null))
        assertNull(ClipboardLinkPolicy.candidate("", null))
        assertNull(ClipboardLinkPolicy.candidate("   ", null))
        assertNull(ClipboardLinkPolicy.candidate("中午吃什么", null))
    }

    @Test
    fun `a dismissed link is not offered again`() {
        val first = ClipboardLinkPolicy.candidate(douyinShareText, null)
        assertNull(ClipboardLinkPolicy.candidate(douyinShareText, first?.url))
        // The same video copied again with different surrounding copy stays suppressed.
        assertNull(ClipboardLinkPolicy.candidate("看看这个 https://v.douyin.com/iRabcDeF/", first?.url))
        // A different video is a new offer.
        assertEquals(
            "https://v.douyin.com/otherLink/",
            ClipboardLinkPolicy.candidate("https://v.douyin.com/otherLink/", first?.url)?.url,
        )
    }

    @Test
    fun `trailing chinese punctuation does not break detection`() {
        val candidate = ClipboardLinkPolicy.candidate("分享一个视频 https://v.douyin.com/iRabcDeF/。", null)
        assertEquals("https://v.douyin.com/iRabcDeF/", candidate?.url)
    }
}
