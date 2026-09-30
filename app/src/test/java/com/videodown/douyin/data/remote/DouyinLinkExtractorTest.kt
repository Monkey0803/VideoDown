package com.videodown.douyin.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinLinkExtractorTest {

    @Test
    fun `extracts short link from a full share caption`() {
        val caption = "7.42 复制打开抖音，看看【某某某的作品】超好看的日落 https://v.douyin.com/iRNBho6u/ " +
            "复制此链接，打开Dou音搜索，直接观看视频！"

        assertEquals("https://v.douyin.com/iRNBho6u/", DouyinLinkExtractor.extractShareUrl(caption))
        assertTrue(DouyinLinkExtractor.isShortLink("https://v.douyin.com/iRNBho6u/"))
    }

    @Test
    fun `extracts short link that is glued to trailing punctuation`() {
        val caption = "看看这个https://v.douyin.com/AbC123x/，真的很不错"
        assertEquals("https://v.douyin.com/AbC123x/", DouyinLinkExtractor.extractShareUrl(caption))
    }

    @Test
    fun `extracts aweme id from a long video url`() {
        val url = "https://www.douyin.com/video/7123456789012345678"
        assertEquals("7123456789012345678", DouyinLinkExtractor.extractAwemeId(url))
    }

    @Test
    fun `extracts aweme id from an iesdouyin share url`() {
        val url = "https://www.iesdouyin.com/share/video/7123456789012345678/?region=CN"
        assertEquals("7123456789012345678", DouyinLinkExtractor.extractAwemeId(url))
    }

    @Test
    fun `extracts aweme id from a modal_id query parameter`() {
        val url = "https://www.douyin.com/user/MS4wLjABAAAAxyz?modal_id=7123456789012345678"
        assertEquals("7123456789012345678", DouyinLinkExtractor.extractAwemeId(url))
    }

    @Test
    fun `extracts a bare numeric aweme id`() {
        assertEquals("7123456789012345678", DouyinLinkExtractor.extractAwemeId("7123456789012345678"))
    }

    @Test
    fun `does not mistake a short numeric id for an aweme id`() {
        assertNull(DouyinLinkExtractor.extractAwemeId("订单号 12345678"))
    }

    @Test
    fun `returns null for unrelated text`() {
        assertNull(DouyinLinkExtractor.extractShareUrl("今天天气不错，去公园散步"))
        assertFalse(DouyinLinkExtractor.containsDouyinReference("https://example.com/video/1"))
    }

    @Test
    fun `prefers the douyin link when other urls are present`() {
        val text = "看 https://example.com/a 和这个 https://v.douyin.com/Zz9Yy8Xx/ 谢谢"
        assertEquals("https://v.douyin.com/Zz9Yy8Xx/", DouyinLinkExtractor.extractShareUrl(text))
    }

    @Test
    fun `extracts a sec_uid from a profile url`() {
        val url = "https://www.douyin.com/user/MS4wLjABAAAA1234567890abcdef"
        assertEquals("MS4wLjABAAAA1234567890abcdef", DouyinLinkExtractor.extractSecUid(url))
    }
}
