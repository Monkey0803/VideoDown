package com.videodown.douyin.presentation.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页解析面板收盘状态行的判定表。
 *
 * 重点钉三件事：**显式消息永不被扫描结论盖住**、**扫描中不许冒充结论**、**扫完却一个候选都没有
 * 时必须给结论**——最后一条曾经是空白（失败带的是 reasonKey，`message` 是空串）。
 */
class BrowserStatusTest {

    private fun status(
        message: String? = null,
        pageLoading: Boolean = false,
        parsing: Boolean = false,
        scanStarted: Boolean = true,
        scanSettled: Boolean = true,
        videos: Int = 0,
        images: Int = 0,
        channels: Boolean = false,
    ) = browserPanelStatus(message, pageLoading, parsing, scanStarted, scanSettled, videos, images, channels)

    @Test
    fun `an explicit message wins over every scan verdict`() {
        // 正在读取、失败原因、取消、网页错误都走这条；扫描结论不能把它们盖掉。
        assertEquals(BrowserPanelStatus.Message("正在读取视频和画质…"), status(message = "正在读取视频和画质…"))
        assertEquals(
            BrowserPanelStatus.Message("需要登录或验证"),
            status(message = "需要登录或验证", pageLoading = true, scanSettled = false, videos = 3),
        )
    }

    @Test
    fun `the page still loading says so before anything about scanning`() {
        assertEquals(BrowserPanelStatus.Loading, status(pageLoading = true, scanStarted = false, scanSettled = false))
    }

    @Test
    fun `a running parse or scan never pretends to know the result`() {
        assertEquals(BrowserPanelStatus.Scanning, status(parsing = true))
        assertEquals(BrowserPanelStatus.Scanning, status(scanSettled = false))
        // 扫描中即使已经有候选，也不该先报结论：候选还在陆续探测。
        assertEquals(BrowserPanelStatus.Scanning, status(scanSettled = false, videos = 2, images = 1))
    }

    @Test
    fun `before the first scan there is only the hint`() {
        assertEquals(BrowserPanelStatus.Hint, status(scanStarted = false, scanSettled = false))
    }

    @Test
    fun `a settled scan with candidates reports the grouped counts`() {
        val found = status(videos = 2, images = 3)
        assertEquals(BrowserPanelStatus.Found(videos = 2, images = 3), found)
        assertEquals(5, found.total)
        assertTrue((status(images = 1) as BrowserPanelStatus.Found).total == 1)
    }

    @Test
    fun `a settled scan with nothing found always says so`() {
        assertEquals(BrowserPanelStatus.Empty, status())
        // 视频号换一句更准的：通用兜底那句「打开网页登录或播放后查找」对这个网站是死的。
        assertEquals(BrowserPanelStatus.ChannelsOnly, status(channels = true))
    }

    @Test
    fun `candidates win over the channels-only explanation`() {
        // 视频号页面将来若真给出直链，就按「找到了」说，不再断言扫不到。
        assertEquals(BrowserPanelStatus.Found(videos = 0, images = 1), status(images = 1, channels = true))
    }

    @Test
    fun `logical priority is message then loading then parsing then hint then scan`() {
        // message > loading
        assertEquals(BrowserPanelStatus.Message("失败"), status(message = "失败", pageLoading = true))
        // loading > parsing
        assertEquals(BrowserPanelStatus.Loading, status(pageLoading = true, parsing = true))
        // parsing > hint
        assertEquals(BrowserPanelStatus.Scanning, status(parsing = true, scanStarted = false))
    }
}
