package com.videodown.douyin

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import com.videodown.douyin.domain.model.LivePhotoMotion
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.presentation.history.HistoryViewModel
import com.videodown.douyin.presentation.library.LibraryScreen
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * 「已存」里的图片浏览：同帖多图角标、动态照片标识，以及多选时的区间选择。
 *
 * 这三件事都只在真实列表/手势里才成立，纯逻辑用例覆盖不到长按与组合。
 */
@RunWith(AndroidJUnit4::class)
class LibraryImageBrowseDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "image-browse-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = ViewModelStore()
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private var previousLayout = LibraryLayout.LIST

    @Before fun setup() {
        previousLayout = ServiceLocator.preferences.libraryLayout
        ServiceLocator.preferences.libraryLayout = LibraryLayout.LIST
    }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        ServiceLocator.preferences.libraryLayout = previousLayout
        directory.deleteRecursively()
    }

    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val target = File(context.filesDir, "library-evidence").apply { mkdirs() }
        File(target, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun pngBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private var nextColor = 0xFF26A69A.toInt()

    private fun image(
        id: String,
        title: String,
        updatedAt: Long,
        page: String = "https://example.com/post/$id",
        live: Boolean = false,
    ): DownloadRecord {
        val file = File(directory, "$id.png").apply {
            nextColor = nextColor xor 0x003300
            writeBytes(pngBytes(nextColor))
        }
        val motion = if (live) File(directory, "$id.mov").apply { writeBytes(ByteArray(32) { 1 }) } else null
        return DownloadRecord(
            id = id,
            awemeId = id,
            title = title,
            fileName = file.name,
            filePath = file.absolutePath,
            status = DownloadStatus.COMPLETED,
            mediaKind = MediaKind.IMAGE,
            updatedAt = updatedAt,
            sourcePageUrl = page,
            livePhotoMotion = motion?.let {
                LivePhotoMotion("https://video.weibo.com/live.mov", fileName = it.name, filePath = it.absolutePath)
            },
        )
    }

    private fun show(vararg records: DownloadRecord) = runBlocking {
        records.forEach { history.upsert(it) }
        val downloader = object : Downloader by ServiceLocator.downloader {
            override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        }
        lateinit var viewModel: HistoryViewModel
        compose.runOnUiThread {
            viewModel = HistoryViewModel(history, downloader)
            store.put("history", viewModel)
            compose.setContent {
                VideoDownTheme { LibraryScreen(viewModel, onPlay = {}) }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(records.first().title).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun samePostImagesCarryOneGroupBadgeAndLivePhotoMark() {
        show(
            image("post-a", "同帖甲", 30, page = "https://example.com/post/1"),
            image("post-b", "同帖乙", 20, page = "https://example.com/post/1", live = true),
            image("solo", "单张作品", 10),
        )

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("2 张").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("动态照片").assertIsDisplayed()
        compose.onNodeWithText("单张作品").assertIsDisplayed()
        capture("library-image-group.png")
    }

    @Test fun longPressSelectsTheRangeBetweenTheAnchorAndTarget() {
        show(
            image("range-1", "区间一", 40),
            image("range-2", "区间二", 30),
            image("range-3", "区间三", 20),
            image("range-4", "区间四", 10),
        )

        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithTag("library-item-range-1").performClick()
        compose.onNodeWithTag("library-item-range-3").performTouchInput { longClick() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("已选 3 项").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithText("已选 3 项").fetchSemanticsNodes().isNotEmpty())
        capture("library-image-range-select.png")
        compose.onNodeWithTag("selection-done").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("已选 3 项").fetchSemanticsNodes().isEmpty()
        }
    }
}
