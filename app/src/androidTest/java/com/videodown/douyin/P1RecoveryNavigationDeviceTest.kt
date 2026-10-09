package com.videodown.douyin

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppError
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.VideoDownApp
import com.videodown.douyin.presentation.history.HistoryViewModel
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Uses the production navigation graph with isolated history and a local webpage. */
@RunWith(AndroidJUnit4::class)
class P1RecoveryNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "p1-navigation-${UUID.randomUUID()}").apply { mkdirs() }
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val models = ViewModelStore()
    private val parsed = CopyOnWriteArrayList<String>()
    private val downloader = object : Downloader {
        override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        override suspend fun start(video: DouyinVideo, variant: VideoVariant) = error("Unexpected download")
        override suspend fun pause(id: String) {}
        override suspend fun resume(id: String) {}
        override suspend fun cancel(id: String, removeRecord: Boolean) {}
        override suspend fun resumeAll() {}
        override suspend fun pauseAll() {}
        override suspend fun restart(id: String) {}
        override fun observeRecord(id: String) = history.observeRecord(id)
    }
    private val repository = object : VideoRepository {
        override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> {
            parsed += shareText
            return AppResult.Failure(AppError.Network("网站暂时限制请求", failureKind = DownloadFailureKind.RATE_LIMITED))
        }
    }
    private lateinit var home: HomeViewModel
    @Before fun showApp() {
        compose.runOnUiThread {
            home = HomeViewModel(repository, downloader, { it.defaultVariant }, { null })
            val tasks = HistoryViewModel(history, downloader)
            models.put("home", home); models.put("tasks", tasks)
            compose.activity.setContent {
                VideoDownTheme { VideoDownApp(null, {}, false, {}, tasks, home) }
            }
        }
    }
    @After fun cleanup() { compose.runOnUiThread { models.clear() }; directory.deleteRecursively() }
    private fun addFailure(kind: DownloadFailureKind, source: String = "http://127.0.0.1/original") {
        runBlocking { history.upsert(DownloadRecord("failure", "failure", "恢复测试", status = DownloadStatus.FAILED,
            failureKind = kind, sourcePageUrl = source, sourceUrl = "http://127.0.0.1/expired-cdn.mp4")) }
        compose.onNodeWithText("任务", substring = false).performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("task-failure")).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun loginRecoveryOpensOriginalPageInActualBrowser() {
        LocalPage().use { page ->
            addFailure(DownloadFailureKind.LOGIN_REQUIRED, page.url)
            compose.onNode(hasText(RecoveryAction.BROWSER.label) and hasAnyAncestor(hasTestTag("task-failure"))).performClick()
            compose.onNodeWithContentDescription("关闭网页").assertIsDisplayed()
            compose.waitUntil(5000) { page.requests.get() > 0 }
            compose.runOnIdle { assertEquals(page.url, home.state.value.input) }
            assertTrue(parsed.isEmpty())
            compose.onNodeWithContentDescription("关闭网页").performClick()
        }
    }
    @Test fun storageRecoveryOpensActualSettingsStorageDialog() {
        addFailure(DownloadFailureKind.STORAGE_FULL)
        compose.onNode(hasText(RecoveryAction.STORAGE.label) and hasAnyAncestor(hasTestTag("task-failure"))).performClick()
        compose.onNodeWithText("存储管理", substring = false).assertIsDisplayed()
        compose.onNodeWithText("清理残留与无效记录").assertIsDisplayed()
        // Read-only inspection: never clean the app's real download directory.
        assertTrue(parsed.isEmpty())
    }
    @Test fun expiredDownloadReparsesOriginalPageRatherThanCdn() {
        val original = "http://127.0.0.1/source-page?media=one"
        addFailure(DownloadFailureKind.LINK_EXPIRED, original)
        compose.onNode(hasText(RecoveryAction.REPARSE.label) and hasAnyAncestor(hasTestTag("task-failure"))).performClick()
        compose.waitUntil(5000) { parsed.isNotEmpty() }
        assertEquals(listOf(original), parsed)
        compose.onNode(hasSetTextAction()).assertTextContains(original)
    }
    @Test fun rateLimitedParseOpensSettingsWithoutRepeatingRequest() {
        compose.runOnUiThread { home.onInputChange("http://127.0.0.1/source-page"); home.parse() }
        compose.waitUntil(5000) { home.state.value.errorKind == DownloadFailureKind.RATE_LIMITED }
        compose.onNodeWithText(RecoveryAction.SETTINGS.label, substring = false).performScrollTo().performClick()
        // The settings page hero copy was replaced by the current page title; assert what is
        // actually rendered, since the intent here is only that the settings page opened.
        compose.waitUntil(5000) { compose.onAllNodes(hasText("我的设置")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("同时下载数量", substring = false).performScrollTo().assertIsDisplayed()
        assertEquals(1, parsed.size)
    }
    private class LocalPage : java.io.Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val url = "http://127.0.0.1:${socket.localPort}/original-page"
        private val thread = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        requests.incrementAndGet()
                        val body = "<html><body>Local login fixture</body></html>".toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                } catch (_: Exception) { }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); thread.join(1000) }
    }
}
