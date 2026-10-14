package com.videodown.douyin.presentation.browser

import com.videodown.douyin.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 网页解析面板底部那句话的判定表。
 *
 * 重点钉两件事：**显式消息永不被扫描结论盖住**，以及**扫完却一个候选都没有时必须给结论**——
 * 后者曾经是空白（失败带的是 reasonKey，`message` 是空串，直接显示就什么都没有）。
 */
class BrowserStatusTest {

    private fun res(
        hasMessage: Boolean = false,
        scanSettled: Boolean = true,
        busy: Boolean = false,
        candidates: Int = 0,
        channels: Boolean = false,
    ) = browserStatusRes(hasMessage, scanSettled, busy, candidates, channels)

    @Test
    fun `an explicit message wins`() {
        // 正在读取、失败原因、取消、网页错误都走这条；扫描结论不能把它们盖掉。
        assertNull(res(hasMessage = true))
        assertNull(res(hasMessage = true, scanSettled = false, candidates = 3))
    }

    @Test
    fun `before a scan settles there is only the initial hint`() {
        assertEquals(R.string.browser_initial_hint, res(scanSettled = false))
        // 正在解析时同理：那时手上的 message 已经在显示"正在读取"，这里只是兜底。
        assertEquals(R.string.browser_initial_hint, res(scanSettled = true, busy = true))
    }

    @Test
    fun `a settled scan with candidates says what was checked`() {
        assertEquals(R.string.browser_scanned, res(candidates = 1))
        assertEquals(R.string.browser_scanned, res(candidates = 5, channels = true))
    }

    @Test
    fun `a settled scan with nothing found always says so`() {
        assertEquals(R.string.browser_nothing_found, res())
        // 视频号换一句更准的：通用兜底那句"打开网页登录或播放后查找"对这个网站是死的。
        assertEquals(R.string.browser_channels_no_media, res(channels = true))
    }

    @Test
    fun `a busy parse never shows a scan verdict`() {
        // 解析失败后 busy 变 false、message 由失败文案占住，所以这里只防"扫描结论串场"。
        assertNull(res(hasMessage = true, busy = true, channels = true))
        assertEquals(R.string.browser_initial_hint, res(busy = true, candidates = 2, channels = true))
    }
}
