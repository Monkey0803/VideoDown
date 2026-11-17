package com.videodown.douyin

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.remote.BilibiliSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * E3：B 站登录态真的由系统 WebView 的 CookieManager 承载。
 *
 * 不驱动真实登录页（需要人工输入账号）：直接写入一条 `SESSDATA`，验证「本机是否登录」的识别、
 * 导出给引擎的 Cookie 文件内容，以及「清除网页登录态」之后立刻变成未登录。
 */
@RunWith(AndroidJUnit4::class)
class BilibiliLoginDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun setCookie(value: String) = instrumentation.runOnMainSync {
        CookieManager.getInstance().setCookie("https://www.bilibili.com/", value)
        CookieManager.getInstance().flush()
    }

    private suspend fun awaitSignedIn(expected: Boolean) {
        withTimeout(5_000) {
            while (ServiceLocator.browserSessions.bilibiliSignedIn() != expected) delay(50)
        }
    }

    @After fun cleanup() = runBlocking { ServiceLocator.browserSessions.clear() }

    @Test fun aWebCookieMakesTheSessionVisibleAndIsExportedForTheEngine() = runBlocking {
        assertFalse("the test must start signed out", ServiceLocator.browserSessions.bilibiliSignedIn())
        setCookie("SESSDATA=test-sessdata; path=/; domain=.bilibili.com")
        awaitSignedIn(true)

        val file = File(context.cacheDir, "bilibili-cookies-${UUID.randomUUID()}.txt")
        try {
            ServiceLocator.browserSessions.export(BilibiliSession.cookieOrigins, file)
            val text = file.readText()
            assertTrue("SESSDATA must reach the engine:\n$text", text.contains("SESSDATA"))
            assertTrue(
                "cookies must be scoped to Bilibili hosts:\n$text",
                text.lines().any { it.startsWith("www.bilibili.com") || it.startsWith("api.bilibili.com") },
            )
        } finally {
            file.delete()
        }
    }

    @Test fun clearingTheWebSessionSignsBilibiliOut() = runBlocking {
        setCookie("SESSDATA=temp-sessdata; path=/")
        awaitSignedIn(true)
        ServiceLocator.browserSessions.clear()
        assertFalse("clearing the web session must sign Bilibili out", ServiceLocator.browserSessions.bilibiliSignedIn())
    }
}
