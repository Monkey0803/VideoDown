package com.videodown.douyin

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppPreferences
import com.videodown.douyin.data.download.*
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.local.DownloadStorageRepository
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.history.HistoryScreen
import com.videodown.douyin.presentation.history.HistoryViewModel
import com.videodown.douyin.presentation.settings.SettingsScreen
import com.videodown.douyin.presentation.settings.StorageViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Isolated directories/preferences; actual scheduler, sockets, files and Compose UI. */
@RunWith(AndroidJUnit4::class)
class P1FeaturesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "p1-${UUID.randomUUID()}").apply { mkdirs() }
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val models = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val paths = DownloadPaths(directory)
    private val preferenceName = directory.name
    private val isolated = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferenceName, mode)
    }
    private val preferences = AppPreferences(isolated)
    private val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()), paths, scope, "", "test", maxConcurrent = 1,
        maintenanceMutex = gate, concurrencyChanges = preferences.maxConcurrentChanges)
    private val storage = DownloadStorageRepository({ paths.downloadDir }, history, gate, { engine.activeTaskIds() })
    @Before fun setup() { preferences.maxConcurrentDownloads = 1 }
    @After fun cleanup() {
        compose.runOnUiThread { models.clear() }
        runBlocking { engine.pauseAll(); scope.coroutineContext.job.cancelAndJoin() }
        context.deleteSharedPreferences(preferenceName); directory.deleteRecursively()
    }
    private fun showSettings() {
        compose.runOnUiThread {
            val model = StorageViewModel(storage); models.put("storage", model)
            compose.activity.setContent { VideoDownTheme { SettingsScreen(storageViewModel = model, preferences = preferences) } }
        }
    }
    @Test fun concurrencySettingPersistsAndUpdatesTheActualEngine() {
        showSettings()
        compose.onNodeWithText("同时下载数量", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("2 个任务", substring = false).performClick()
        compose.onNodeWithText("确定", substring = false).performClick()
        compose.waitUntil(5000) { engine.queue.value.maxConcurrent == 2 }
        assertEquals(2, AppPreferences(isolated).maxConcurrentDownloads)
        compose.onNodeWithText("同时下载数量").performScrollTo().performClick()
        compose.onNodeWithText("4 个任务", substring = false).performClick()
        compose.onNodeWithText("取消", substring = false).performClick()
        assertEquals(2, preferences.maxConcurrentDownloads)
    }
    @Test fun cleanupRequiresConfirmationAndPreservesVideosPausedAndActiveArtifacts() {
        val saved = File(paths.downloadDir, "saved.mp4").apply { writeBytes(ByteArray(100)) }
        val partial = File(paths.downloadDir, "paused.mp4.part").apply { writeBytes(ByteArray(30)) }
        val orphan = File(paths.downloadDir, "orphan.part").apply { writeBytes(ByteArray(20)) }
        val id = UUID.randomUUID().toString()
        val fragments = paths.engineDirectory(id).apply { mkdirs(); resolve("segment.part").writeBytes(ByteArray(40)) }
        runBlocking {
            history.upsert(DownloadRecord("saved", "saved", "saved", filePath = saved.path, status = DownloadStatus.COMPLETED))
            history.upsert(DownloadRecord("paused", "paused", "paused", partialPath = partial.path, status = DownloadStatus.PAUSED))
            history.upsert(DownloadRecord(id, id, "fragments", status = DownloadStatus.RUNNING))
            history.upsert(DownloadRecord("missing", "missing", "missing", filePath = directory.resolve("gone.mp4").path, status = DownloadStatus.COMPLETED))
        }
        showSettings()
        compose.onNodeWithText("存储空间", substring = false).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasText("文件已丢失的记录：1 条")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("清理残留与无效记录").performClick()
        compose.onNodeWithText("取消", substring = false).performClick()
        assertTrue(orphan.exists()); assertNotNull(runBlocking { history.getById("missing") })
        compose.onNodeWithText("清理残留与无效记录").performClick()
        compose.onNodeWithText("确认清理").performClick()
        compose.waitUntil(5000) { !orphan.exists() && runBlocking { history.getById("missing") == null } }
        assertTrue(saved.exists()); assertTrue(partial.exists()); assertTrue(fragments.exists())
        assertNotNull(runBlocking { history.getById("paused") }); assertNotNull(runBlocking { history.getById(id) })
        compose.waitUntil(5000) { compose.onAllNodes(hasText("文件已丢失的记录：0 条")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("文件已丢失的记录：0 条").assertIsDisplayed()
    }
    @Test fun storageDialogVisualizesTheRunningSpaceBudget() {
        HoldingServer().use { server ->
            val budgeted = DownloadStorageRepository({ paths.downloadDir }, history, gate, { engine.activeTaskIds() },
                budget = { engine.storageBudget() })
            val id = runBlocking { engine.start(DouyinVideo("budget", "预算任务"), VideoVariant(server.url, "720P")) }
            compose.waitUntil(10000) { engine.storageBudget().taskCount == 1 }
            compose.runOnUiThread {
                val model = StorageViewModel(budgeted); models.put("storage", model)
                compose.activity.setContent { VideoDownTheme { SettingsScreen(storageViewModel = model, preferences = preferences) } }
            }
            compose.onNodeWithText("存储空间", substring = false).performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodes(hasText("运行任务尚需", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("预算任务", substring = true)).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("预算在可用空间内", substring = true)).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("预算占用", substring = true)).performScrollTo().assertIsDisplayed()
            captureScreenshot("budget-running")
            runBlocking { engine.pause(id) }
            compose.waitUntil(5000) { engine.storageBudget().isEmpty }
            compose.onNodeWithText("刷新", substring = false).performClick()
            compose.waitUntil(5000) { compose.onAllNodes(hasText("运行任务空间预算：无")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("当前没有运行中的下载任务占用空间预算。").performScrollTo().assertIsDisplayed()
            captureScreenshot("budget-empty")
        }
    }
    @Test fun priorityControlChangesVisibleQueueAndTheNextActualHttpRequest() {
        HoldingServer().use { server ->
            val ids = runBlocking {
                (1..3).map { index -> engine.start(DouyinVideo("$index", "任务 $index"), VideoVariant(server.url, "720P")) }
            }
            compose.waitUntil(5000) { server.requests.get() == 1 }
            compose.runOnUiThread {
                val model = HistoryViewModel(history, engine); models.put("history", model)
                compose.activity.setContent { VideoDownTheme { HistoryScreen({}, model) } }
            }
            compose.onNodeWithTag("task-list").performScrollToNode(hasTestTag("task-${ids[2]}"))
            compose.onNode(hasText("优先下载") and hasAnyAncestor(hasTestTag("task-${ids[2]}"))).performClick()
            compose.waitUntil(5000) { engine.queue.value.pendingIds == listOf(ids[2], ids[1]) }
            compose.onNodeWithTag("task-list").performScrollToNode(hasTestTag("task-${ids[2]}"))
            compose.onNode(hasText("排队第 1 个 · 等待空闲下载名额") and hasAnyAncestor(hasTestTag("task-${ids[2]}"))).performScrollTo().assertIsDisplayed()
            runBlocking { engine.pause(ids[0]) }
            compose.waitUntil(5000) { server.requests.get() == 2 && ids[2] in engine.queue.value.runningIds }
            assertEquals(listOf(ids[1]), engine.queue.value.pendingIds)
        }
    }
    @Test fun distinctFailureActionsUseTheChosenRecordsAndNetworkRetryResumesOnlyItsTask() {
        HoldingServer().use { server ->
        runBlocking {
            listOf(DownloadFailureKind.LOGIN_REQUIRED, DownloadFailureKind.LINK_EXPIRED, DownloadFailureKind.STORAGE_FULL, DownloadFailureKind.NETWORK)
                .forEachIndexed { index, kind -> history.upsert(DownloadRecord("$index", "$index", "失败任务 $index", status = DownloadStatus.FAILED,
                    failureKind = kind, sourcePageUrl = "https://example.test/page-$index", sourceUrl = server.url,
                    fileName = "$index.mp4", partialPath = File(paths.downloadDir, "$index.mp4.part").path)) }
        }
        val recovered = mutableListOf<Pair<String, RecoveryAction>>()
        compose.runOnUiThread {
            val model = HistoryViewModel(history, engine); models.put("history", model)
            compose.activity.setContent { VideoDownTheme { HistoryScreen({}, model) { record, action -> recovered += record.id to action } } }
        }
        for ((id, action) in listOf("0" to RecoveryAction.BROWSER, "1" to RecoveryAction.REPARSE, "2" to RecoveryAction.STORAGE)) {
            compose.onNodeWithTag("task-list").performScrollToNode(hasTestTag("task-$id"))
            compose.onNode(hasText(actionText(action)) and hasAnyAncestor(hasTestTag("task-$id"))).performClick()
        }
        compose.runOnIdle { assertEquals(listOf("0" to RecoveryAction.BROWSER, "1" to RecoveryAction.REPARSE, "2" to RecoveryAction.STORAGE), recovered) }
        compose.onNodeWithTag("task-list").performScrollToNode(hasTestTag("task-3"))
        compose.onNode(hasText(actionText(RecoveryAction.RETRY)) and hasAnyAncestor(hasTestTag("task-3"))).performClick()
        compose.waitUntil(5000) { server.requests.get() == 1 && "3" in engine.queue.value.runningIds }
        assertEquals(setOf("3"), engine.activeTaskIds())
        runBlocking { (0..2).forEach { assertEquals(DownloadStatus.FAILED, history.getById("$it")?.status) } }
        }
    }

    @Test fun missingOriginalPageAndUnsupportedContentDoNotOfferUnusableRecovery() {
        runBlocking {
            history.upsert(DownloadRecord("missing-page", "missing-page", "缺少原网址", status = DownloadStatus.FAILED,
                failureKind = DownloadFailureKind.LINK_EXPIRED))
            history.upsert(DownloadRecord("unsupported", "unsupported", "不支持的内容", status = DownloadStatus.FAILED,
                failureKind = DownloadFailureKind.UNSUPPORTED))
        }
        compose.runOnUiThread {
            val model = HistoryViewModel(history, engine); models.put("history", model)
            compose.activity.setContent { VideoDownTheme { HistoryScreen({}, model) } }
        }
        compose.onNodeWithTag("task-list").performScrollToNode(hasTestTag("task-missing-page"))
        compose.onNode(hasText(actionText(RecoveryAction.REPARSE)) and hasAnyAncestor(hasTestTag("task-missing-page"))).assertIsNotEnabled()
        compose.onNodeWithText("原始网址未保留，请在解析页重新粘贴视频链接。").assertExists()
        compose.onNodeWithTag("task-list").performScrollToNode(hasTestTag("task-unsupported"))
        compose.onNode(hasContentDescription(actionText(RecoveryAction.NONE)) and hasAnyAncestor(hasTestTag("task-unsupported"))).assertIsNotEnabled()
        compose.onNode(hasContentDescription("重新下载") and hasAnyAncestor(hasTestTag("task-unsupported"))).assertDoesNotExist()
        runBlocking { history.delete("missing-page"); engine.resumeAll(); engine.resume("unsupported") }
        assertTrue(engine.activeTaskIds().isEmpty())
    }

    private fun captureScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "space-budget").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private class HoldingServer : java.io.Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val url = "http://127.0.0.1:${server.localPort}/fixture.mp4"
        private val thread = Thread {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    Thread {
                        socket.use { client ->
                            try {
                                val reader = client.getInputStream().bufferedReader()
                                while (!reader.readLine().isNullOrEmpty()) { }
                                requests.incrementAndGet()
                                val output = client.getOutputStream()
                                output.write("HTTP/1.1 200 OK\r\nContent-Length: 65536\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n".toByteArray())
                                output.write(ByteArray(4096)); output.flush()
                                release.await(20, TimeUnit.SECONDS)
                                output.write(ByteArray(65536 - 4096)); output.flush()
                            } catch (_: Exception) { }
                        }
                    }.apply { isDaemon = true; start() }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        override fun close() { release.countDown(); server.close(); thread.join(1000) }
    }

    /** The caption lives in resources now, so ask the app for the string it renders. */
    private fun actionText(action: RecoveryAction): String =
        context.getString(com.videodown.douyin.presentation.recoveryActionRes(action))
}
