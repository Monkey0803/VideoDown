package com.videodown.douyin.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E1：B 站链接识别与归一化。
 *
 * 表格覆盖分享文案、移动端、分 P、av 号与各种「看起来像但不是单个视频页」的地址。
 * 归一化只认 `/video/BV…` 与 `/video/av…`，番剧/直播/课程/音频/空间/频道一律不支持。
 */
class BilibiliLinkTest {

    private fun page(raw: String): BilibiliVideoLink {
        val result = BilibiliLink.parse(raw)
        assertTrue("expected a video page for $raw, got $result", result is BilibiliLinkResult.Page)
        return (result as BilibiliLinkResult.Page).link
    }

    private fun unsupported(raw: String) {
        assertEquals("expected $raw to be unsupported", BilibiliLinkResult.Unsupported, BilibiliLink.parse(raw))
    }

    @Test fun `a plain desktop video link is normalised to the canonical page`() {
        val link = page("https://www.bilibili.com/video/BV1GJ411x7h7")
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", link.url)
        assertEquals("BV1GJ411x7h7", link.videoId)
        assertNull(link.page)
    }

    @Test fun `a mobile link and an explicit part keep only what matters`() {
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7?p=3", page("https://m.bilibili.com/video/BV1GJ411x7h7?p=3").url)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", page("https://www.bilibili.com/video/BV1GJ411x7h7?p=1").url)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", page("https://www.bilibili.com/video/BV1GJ411x7h7/?p=0").url)
        assertEquals(3, page("https://www.bilibili.com/video/BV1GJ411x7h7?p=3").page)
    }

    @Test fun `tracking parameters from a share sheet are dropped`() {
        val link = page("https://www.bilibili.com/video/BV1GJ411x7h7/?spm_id_from=333.1007.0.0&vd_source=abcd&p=2")
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7?p=2", link.url)
    }

    @Test fun `av links are accepted and normalised to lowercase`() {
        assertEquals("https://www.bilibili.com/video/av80433022", page("https://www.bilibili.com/video/av80433022").url)
        assertEquals("https://www.bilibili.com/video/av80433022", page("https://www.bilibili.com/video/AV80433022").url)
    }

    @Test fun `a bv id keeps its case`() {
        // BV 号区分大小写，全小写不是同一个视频，按不支持处理。
        unsupported("https://www.bilibili.com/video/bv1gj411x7h7")
        assertEquals("BV1GJ411x7h7", page("https://www.bilibili.com/video/BV1GJ411x7h7").videoId)
    }

    @Test fun `content that is not a single video page is refused`() {
        listOf(
            "https://www.bilibili.com/bangumi/play/ss1001",
            "https://www.bilibili.com/bangumi/media/md2822",
            "https://live.bilibili.com/123456",
            "https://www.bilibili.com/audio/au123456",
            "https://www.bilibili.com/read/cv123456",
            "https://space.bilibili.com/123456",
            "https://www.bilibili.com/medialist/detail/ml123",
            "https://www.bilibili.com/festival/2024",
            "https://www.bilibili.com/video/",
            "https://www.bilibili.com/video/BV123",
            "https://example.com/video/BV1GJ411x7h7",
        ).forEach(::unsupported)
    }

    @Test fun `short links are recognised separately and only b23 hosts count`() {
        assertTrue(BilibiliLink.parse("https://b23.tv/abcd123") is BilibiliLinkResult.ShortLink)
        assertTrue(BilibiliLink.parse("https://www.b23.tv/abcd123") is BilibiliLinkResult.ShortLink)
        unsupported("https://bit.ly/abcd123")
    }

    @Test fun `a redirect target must be a trusted video page`() {
        assertEquals("BV1GJ411x7h7", BilibiliLink.trustedTarget("https://www.bilibili.com/video/BV1GJ411x7h7?p=2")?.videoId)
        assertNull(BilibiliLink.trustedTarget("https://evil.example/video/BV1GJ411x7h7"))
        assertNull(BilibiliLink.trustedTarget("https://www.bilibili.com/bangumi/play/ss1001"))
        assertNull(BilibiliLink.trustedTarget("https://b23.tv/abcd123"))
        assertTrue(BilibiliLink.isTrustedRedirectHost("m.bilibili.com"))
        assertTrue(BilibiliLink.isTrustedRedirectHost("b23.tv"))
        assertTrue(!BilibiliLink.isTrustedRedirectHost("evil.example"))
    }
}
