package com.videodown.douyin

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses isolated files/history; never deletes or exports the user's downloaded videos. */
@RunWith(AndroidJUnit4::class)
class LibraryActionsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "library-actions-${UUID.randomUUID()}").apply { mkdirs() }
    private val file = File(directory, "fixture.mp4").apply { writeBytes(byteArrayOf(0)) }
    private val record = DownloadRecord(id = "fixture", awemeId = "fixture", title = "长按菜单测试视频",
        fileName = file.name, filePath = file.absolutePath, status = DownloadStatus.COMPLETED)
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val store = ViewModelStore()
    private val played = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private var previousLayout = LibraryLayout.WATERFALL

    @Before fun setup() {
        previousLayout = ServiceLocator.preferences.libraryLayout
        ServiceLocator.preferences.libraryLayout = LibraryLayout.WATERFALL
        runBlocking { history.upsert(record) }
        val downloader = object : Downloader {
            override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
            override suspend fun cancel(id: String, removeRecord: Boolean) {
                check(id == record.id && removeRecord)
                deleted += id
                file.delete()
                history.delete(id)
            }
            override fun observeRecord(id: String) = history.observeRecord(id)
            override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection): String = error("Unexpected start")
            override suspend fun pause(id: String): Unit = error("Unexpected pause")
            override suspend fun resume(id: String): Unit = error("Unexpected resume")
            override suspend fun resumeAll(): Unit = error("Unexpected resumeAll")
            override suspend fun pauseAll(): Unit = error("Unexpected pauseAll")
            override suspend fun restart(id: String): Unit = error("Unexpected restart")
        }
        lateinit var viewModel: HistoryViewModel
        compose.runOnUiThread {
            viewModel = HistoryViewModel(history, downloader)
            store.put("history", viewModel)
        }
        compose.setContent {
            VideoDownTheme { LibraryScreen(viewModel, onPlay = { played += it }) }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(record.title)).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        ServiceLocator.preferences.libraryLayout = previousLayout
        directory.deleteRecursively()
    }

    @Test fun longPressInBothLayoutsShowsActionsWithoutPlaying() {
        for (layout in LibraryLayout.entries) {
            if (layout == LibraryLayout.LIST) {
                compose.onNodeWithContentDescription("切换为列表布局").performClick()
            }
            openMenu()
            listOf("播放视频", "分享视频", "保存到相册", "删除视频").forEach {
                compose.onNodeWithText(it).assertIsDisplayed()
            }
            assertTrue(played.isEmpty())
            saveScreenshot("${layout.name.lowercase()}-actions.png")
            compose.onNodeWithText("取消").performClick()
            assertTrue(file.isFile)
        }
    }

    @Test fun normalTapAndMenuPlayUseTheSelectedVideo() {
        compose.onNode(hasText(record.title) and hasClickAction()).performTouchInput { click() }
        assertEquals(listOf(record.id), played)
        openMenu()
        compose.onNodeWithText("播放视频").performClick()
        compose.runOnIdle { assertEquals(listOf(record.id, record.id), played) }
    }

    @Test fun deletionRequiresConfirmationAndCancelPreservesTheVideo() {
        openMenu()
        compose.onNodeWithText("删除视频").performClick()
        compose.onNodeWithText("删除视频？").assertIsDisplayed()
        assertTrue(deleted.isEmpty())
        assertTrue(file.isFile)
        compose.onNodeWithText("取消").performClick()
        assertNotNull(runBlocking { history.getById(record.id) })

        openMenu()
        compose.onNodeWithText("删除视频").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.waitUntil(5_000) { runBlocking { history.getById(record.id) == null } }
        compose.onNodeWithText("还没有已保存的视频").assertIsDisplayed()
        assertEquals(listOf(record.id), deleted)
        assertFalse(file.exists())
        assertNull(runBlocking { history.getById(record.id) })
    }

    @Test fun missingFileExportReportsFailureWithoutCreatingAnAlbumEntry() {
        openMenu()
        file.delete()
        compose.onNodeWithText("保存到相册").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("保存失败：本地视频文件不存在")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("保存失败：本地视频文件不存在").assertIsDisplayed()
        assertTrue(deleted.isEmpty())
    }

    private fun openMenu() {
        compose.onNode(hasText(record.title) and hasClickAction()).performTouchInput { longClick() }
        compose.onNodeWithText("视频操作").assertIsDisplayed()
    }

    private fun saveScreenshot(name: String) {
        val output = File(context.cacheDir, "library-ui-test-artifacts").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
