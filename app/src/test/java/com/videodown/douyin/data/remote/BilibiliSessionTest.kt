package com.videodown.douyin.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E3：B 站登录态的判断。
 *
 * 只看 Cookie 头里是否有一个非空的 `SESSDATA`：不缓存、不自己算过期时间，也不把别的站点
 * Cookie 混进来。登录态只导出给 B 站主机，页面属于别处时一律不附带。
 */
class BilibiliSessionTest {

    @Test fun `a non-blank sessdata means signed in`() {
        assertTrue(BilibiliSession.signedIn("SESSDATA=abc%2C123; bili_jct=xyz"))
        assertTrue(BilibiliSession.signedIn("buvid3=1; SESSDATA=abc"))
    }

    @Test fun `a blank or missing sessdata means signed out`() {
        assertFalse(BilibiliSession.signedIn(null))
        assertFalse(BilibiliSession.signedIn(""))
        assertFalse(BilibiliSession.signedIn("bili_jct=xyz; buvid3=1"))
        assertFalse(BilibiliSession.signedIn("SESSDATA=; bili_jct=xyz"))
        assertFalse(BilibiliSession.signedIn("SESSDATA=   "))
    }

    @Test fun `only bilibili hosts receive the session`() {
        assertTrue(BilibiliSession.appliesTo("https://www.bilibili.com/video/BV1GJ411x7h7"))
        assertTrue(BilibiliSession.appliesTo("https://api.bilibili.com/x/player/playurl"))
        assertTrue(BilibiliSession.appliesTo("https://b23.tv/abc"))
        assertTrue(BilibiliSession.appliesTo("https://m.bilibili.com/video/BV1GJ411x7h7"))
        assertFalse(BilibiliSession.appliesTo("https://evil-bilibili.com/video/BV1"))
        assertFalse("a Bilibili referer alone must not attach the session", BilibiliSession.appliesTo("https://example.test/"))
        assertFalse(BilibiliSession.appliesTo("not a url"))
    }

    @Test fun `the session is preferred only when one exists`() {
        assertTrue(BilibiliSession.preferSession(signedIn = true))
        assertFalse(BilibiliSession.preferSession(signedIn = false))
        assertEquals("bilibili:session_auth_failed", BilibiliSession.SESSION_AUTH_FAILED)
        assertEquals(listOf("https://www.bilibili.com/", "https://api.bilibili.com/"), BilibiliSession.cookieOrigins)
    }
}
