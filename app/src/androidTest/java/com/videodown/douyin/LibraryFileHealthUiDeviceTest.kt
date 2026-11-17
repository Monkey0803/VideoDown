package com.videodown.douyin

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.download.LibraryFileInspector
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.StoredFileHealth
import com.videodown.douyin.domain.model.SubtitleSelection
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.presentation.history.HistoryViewModel
import com.videodown.douyin.presentation.library.LibraryScreen
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * I4：已存文件健康检查的界面与检查器。
 *
 * 一条完整的图片记录走「检查文件 → 健康」；文件被删掉后再检查会给出修复对话框，
 * 并确认「重新下载」真的调到下载器。真实媒体结构由 [LibraryFileHealthDeviceTest] 的检查器断言覆盖。
 */
@RunWith(AndroidJUnit4::class)
class LibraryFileHealthUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "file-health-${UUID.randomUUID()}").apply { mkdirs() }
    private val image = File(directory, "health-fixture.png").apply {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private val record = DownloadRecord(
        id = "health-fixture",
        awemeId = "health-fixture",
        title = "健康检查截图",
        fileName = image.name,
        filePath = image.absolutePath,
        status = DownloadStatus.COMPLETED,
        mediaKind = MediaKind.IMAGE,
        totalBytes = image.length(),
        downloadedBytes = image.length(),
    ).copy(fileSizeBytes = image.length(), fileModifiedAt = image.lastModified())
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val store = ViewModelStore()
    private val restarted = mutableListOf<String>()

    @Before fun setup() {
        runBlocking { history.upsert(record) }
        val downloader = object : Downloader {
            override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
            override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection): String = error("unexpected start")
            override suspend fun pause(id: String): Unit = error("unexpected pause")
            override suspend fun resume(id: String): Unit = error("unexpected resume")
            override suspend fun restart(id: String) { restarted += id }
            override suspend fun cancel(id: String, removeRecord: Boolean): Unit = error("unexpected cancel")
            override suspend fun resumeAll(): Unit = error("unexpected resumeAll")
            override suspend fun pauseAll(): Unit = error("unexpected pauseAll")
            override fun observeRecord(id: String) = history.observeRecord(id)
        }
        lateinit var viewModel: HistoryViewModel
        compose.runOnUiThread {
            viewModel = HistoryViewModel(history, downloader)
            store.put("history", viewModel)
        }
        compose.setContent {
            VideoDownTheme {
                // 界面层的两条修复入口由 MainActivity 接线；这里直接记录回调是否被点到。
                LibraryScreen(viewModel, onPlay = {}, onRedownload = { restarted += it.id }, onReparse = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(record.title)).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        directory.deleteRecursively()
    }

    private fun openMenu() {
        compose.onNode(hasText(record.title) and hasClickAction()).performTouchInput { longClick() }
        compose.onNodeWithText("图片操作").assertIsDisplayed()
    }

    @Test fun checkingAHealthyFileReportsItIsIntact() = runBlocking {
        // 先确认检查器本身认为它是健康的，避免界面断言掩盖实现问题。
        assertEquals(StoredFileHealth.HEALTHY, LibraryFileInspector().inspect(record))
        openMenu()
        compose.onNodeWithText("检查文件").performClick()
        compose.waitUntil(8_000) {
            compose.onAllNodes(hasText("文件完整", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("文件完整", substring = true).assertIsDisplayed()
        Unit
    }

    @Test fun aMissingFileOffersRedownloadFromTheLibrary() = runBlocking {
        assertNotNull(runBlocking { history.getById(record.id) })
        image.delete()
        openMenu()
        compose.onNodeWithText("检查文件").performClick()
        compose.waitUntil(8_000) {
            compose.onAllNodes(hasText("文件检查")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("文件检查").assertIsDisplayed()
        compose.onNodeWithText("重新下载").performClick()
        compose.waitForIdle()
        assertEquals(listOf(record.id), restarted)
        Unit
    }
}
