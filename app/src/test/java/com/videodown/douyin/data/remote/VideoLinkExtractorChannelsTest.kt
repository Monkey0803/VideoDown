package com.videodown.douyin.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这是不是视频号链接」的判定。
 *
 * 为什么值得单测：它决定的是一句**给用户的解释**（"内容只能在微信里播放、网页没有可下载地址"），
 * 判错了就会对着一个能下载的公众号文章页说视频号的事，或者反过来把视频号当普通网页、
 * 让用户去走那条根本走不通的"打开网页播放"。
 */
class VideoLinkExtractorChannelsTest {

    @Test
    fun `real share links are recognised`() {
        // 用户手机里复制出来的就是这两种形态：短链，以及它跳转后的网页地址。
        assertTrue(VideoLinkExtractor.isWeChatChannelsLink("https://weixin.qq.com/sph/AhMkUsjhOF"))
        assertTrue(VideoLinkExtractor.isWeChatChannelsLink("http://weixin.qq.com/sph/AhMkUsjhOF"))
        assertTrue(VideoLinkExtractor.isWeChatChannelsLink("https://www.weixin.qq.com/sph/AhMkUsjhOF"))
        assertTrue(VideoLinkExtractor.isWeChatChannelsLink("https://channels.weixin.qq.com/web/pages/feed?eid=x"))
    }

    @Test
    fun `share text with tracking parameters still counts`() {
        assertTrue(VideoLinkExtractor.isWeChatChannelsLink("https://weixin.qq.com/sph/AhMkUsjhOF?from=timeline&isappinstalled=0"))
        assertTrue(VideoLinkExtractor.isWeChatChannelsLink("https://weixin.qq.com/sph/AhMkUsjhOF#wechat_redirect"))
    }

    @Test
    fun `other weixin pages are not channels`() {
        // 公众号文章往往内嵌普通视频，通用路径能正常处理，不能按视频号来解释失败。
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink("https://mp.weixin.qq.com/s/AbCdEfGh"))
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink("https://weixin.qq.com/"))
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink("https://weixin.qq.com/cgi-bin/readtemplate"))
        // 只认第一段路径是 sph 的，子路径混进来的不算。
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink("https://weixin.qq.com/cgi-bin/sph/x"))
    }

    @Test
    fun `other hosts are not channels`() {
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink("https://www.douyin.com/video/123"))
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink("https://example.com/sph/AhMkUsjhOF"))
        // 形似但不是微信的域名。
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink("https://weixin.qq.com.evil.example/sph/x"))
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink("https://channels.weixin.qq.com.evil.example/x"))
    }

    @Test
    fun `garbage is not a channel and never throws`() {
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink(""))
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink("不是链接"))
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink("weixin.qq.com/sph/x"))
        assertFalse(VideoLinkExtractor.isWeChatChannelsLink("ftp://weixin.qq.com/sph/x"))
    }

    @Test
    fun `channels links stay on the generic platform`() {
        // 视频号不是一个新的解析平台：它照旧走通用兜底，这里只影响失败时说的话。
        assertTrue(VideoLinkExtractor.extractAll("https://weixin.qq.com/sph/AhMkUsjhOF")
            .all { it.platform == com.videodown.douyin.domain.model.VideoPlatform.GENERIC })
    }
}
