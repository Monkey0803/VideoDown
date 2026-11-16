package com.videodown.douyin

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.LibraryLayout
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.domain.model.MediaKind
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * 「已存 → 类型筛选」：四类媒体混在一起时，选「图片」只剩图片，选回「全部类型」再全部出现。
 *
 * 这是「不给图片单独开 tab」的替代方案，所以设备上要真的验证筛选入口能改变列表，而不只是纯逻辑过滤。
 */
@RunWith(AndroidJUnit4::class)
class LibraryKindFilterDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "kind-filter-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = ViewModelStore()
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private var previousLayout = LibraryLayout.WATERFALL

    @Before fun setup() {
        previousLayout = ServiceLocator.preferences.libraryLayout
        ServiceLocator.preferences.libraryLayout = LibraryLayout.LIST
    }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        ServiceLocator.preferences.libraryLayout = previousLayout
        directory.deleteRecursively()
    }

    /** 证据截图：筛选后的「已存」，测试后用 adb run-as 取回。 */
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val target = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "library-evidence").apply { mkdirs() }
        java.io.FileOutputStream(File(target, name)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF26A69A.toInt())
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun record(id: String, title: String, kind: MediaKind, updatedAt: Long): DownloadRecord {
        val file = File(directory, "$id.bin").apply {
            writeBytes(if (kind == MediaKind.IMAGE) pngBytes() else ByteArray(64) { 1 })
        }
        return DownloadRecord(
            id = id, awemeId = id, title = title, fileName = file.name, filePath = file.absolutePath,
            status = DownloadStatus.COMPLETED, mediaKind = kind, updatedAt = updatedAt,
            media = if (kind == MediaKind.AUDIO) MediaDownload(audioOnly = true) else null,
        )
    }

    @Test fun theKindPickerNarrowsTheLibraryToImagesAndBack() = runBlocking {
        listOf(
            record("v", "视频记录", MediaKind.VIDEO, 10),
            record("i", "图片记录", MediaKind.IMAGE, 40),
            record("a", "音频记录", MediaKind.AUDIO, 30),
            record("z", "压缩包记录", MediaKind.ARCHIVE, 20),
        ).forEach { history.upsert(it) }

        val downloader = object : Downloader {
            override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
            override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection): String = error("Unexpected start")
            override suspend fun pause(id: String) = error("Unexpected pause")
            override suspend fun resume(id: String) = error("Unexpected resume")
            override suspend fun cancel(id: String, removeRecord: Boolean) = error("Unexpected cancel")
            override suspend fun resumeAll() = error("Unexpected resumeAll")
            override suspend fun pauseAll() = error("Unexpected pauseAll")
            override suspend fun restart(id: String) = error("Unexpected restart")
            override fun observeRecord(id: String) = history.observeRecord(id)
        }
        lateinit var viewModel: HistoryViewModel
        compose.runOnUiThread {
            viewModel = HistoryViewModel(history, downloader)
            store.put("history", viewModel)
        }
        compose.setContent {
            VideoDownTheme { LibraryScreen(viewModel, onPlay = {}) }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("图片记录").fetchSemanticsNodes().isNotEmpty() }
        listOf("视频记录", "音频记录", "压缩包记录").forEach { compose.onNodeWithText(it).assertIsDisplayed() }

        compose.onNodeWithTag("library-filter").performClick()
        compose.onNodeWithTag("library-controls").performScrollToNode(hasContentDescription("选择类型"))
        compose.onNodeWithContentDescription("选择类型").performClick()
        compose.onNodeWithText("图片").performClick()
        compose.onNodeWithText("查看结果").performClick()
        compose.waitUntil(5_000) { viewModel.state.value.libraryQuery.mediaKind == MediaKind.IMAGE }
        compose.waitForIdle()

        compose.onNodeWithText("图片记录").assertIsDisplayed()
        listOf("视频记录", "音频记录", "压缩包记录").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        capture("library-kind-image.png")
        assertEquals(MediaKind.IMAGE, viewModel.state.value.libraryQuery.mediaKind)

        // 选回「全部类型」应恢复完整列表。
        compose.onNodeWithTag("library-filter").performClick()
        compose.onNodeWithTag("library-controls").performScrollToNode(hasContentDescription("选择类型"))
        compose.onNodeWithContentDescription("选择类型").performClick()
        compose.onNodeWithText("全部类型").performClick()
        compose.onNodeWithText("查看结果").performClick()
        compose.waitUntil(5_000) { viewModel.state.value.libraryQuery.mediaKind == null }
        compose.waitForIdle()
        listOf("视频记录", "图片记录", "音频记录", "压缩包记录").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }
}
