package com.videodown.douyin

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.*
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.home.*
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ResultSelectionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val models = ViewModelStore()
    private val saved = CopyOnWriteArrayList<Pair<String, String>>()
    private lateinit var model: HomeViewModel
    private val title = "这是一个很长的视频标题，用于检查小屏幕中标题是否能够完整展开，并且保持视频选择与批量下载相互独立。".repeat(3)
    private val videos = (1..12).map { index -> DouyinVideo("result-ui-$index", if (index == 1) title else "第 $index 个视频",
        mediaLabel = "动画 $index", variants = listOf(VideoVariant("https://example.com/$index.mp4", "720P", sizeBytes = 1000),
            VideoVariant("https://example.com/$index-low.mp4", "360P", sizeBytes = 500))) }
    @Before fun show() {
        val repository = object : VideoRepository { override suspend fun parseShareText(shareText: String) = AppResult.Success(videos.first()) }
        val downloader = object : Downloader by ServiceLocator.downloader {
            override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
            override suspend fun findDuplicate(video: DouyinVideo, variant: VideoVariant): DownloadRecord? = null
            override suspend fun start(video: DouyinVideo, variant: VideoVariant): String {
                saved += video.awemeId to variant.label
                return "result-task-${saved.size}"
            }
            override fun observeRecord(id: String) = flowOf<DownloadRecord?>(null)
        }
        compose.runOnUiThread {
            model = HomeViewModel(repository, downloader, { it.defaultVariant }, { null })
            models.put("result", model)
            model.acceptBrowserResult(ParsedVideos(videos))
            compose.activity.setContent { VideoDownTheme { HomeScreen(null, {}, {}, {}, {}, emptyList(), model) } }
        }
    }
    @After fun close() {
        compose.runOnUiThread { compose.activity.setContent { Text("已关闭") }; models.clear() }
        compose.waitForIdle()
    }
    private fun open() { compose.onNodeWithText("查看全部").performClick() }
    private fun preview(index: Int) {
        compose.onNodeWithTag("video-selection-grid").performScrollToNode(hasContentDescription("预览动画 $index"))
        compose.onNodeWithContentDescription("预览动画 $index").performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(instrumentation.targetContext.cacheDir, "result-ui-evidence").apply { mkdirs() }
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun compactHeaderAndExpandableTitleStayVisible() {
        compose.onNodeWithText("当前 1/12 · 已选 0 个").assertIsDisplayed()
        compose.onNodeWithText("保存当前视频").assertIsDisplayed()
        compose.onNodeWithText("展开标题").assertIsDisplayed()
        screenshot("result-summary.png")
        compose.onNodeWithText("展开标题").performClick()
        compose.onNodeWithText("收起标题").performScrollTo().performClick()
        compose.onNodeWithText("展开标题").assertIsDisplayed()
        open()
        compose.onNodeWithText("选择视频（12）").assertIsDisplayed()
        screenshot("result-selection.png")
    }
    @Test fun checkboxDoesNotChangePreviewAndBatchUsesChosenItems() {
        open()
        compose.onNodeWithContentDescription("勾选动画 1").performClick().assertIsOn()
        compose.onNodeWithContentDescription("勾选动画 2").performClick().assertIsOn()
        compose.onNodeWithText("完成选择").assertIsDisplayed().performClick()
        compose.onNodeWithText("当前 1/12 · 已选 2 个").assertIsDisplayed()
        compose.onNodeWithText("保存所选 2 个视频").performClick()
        compose.waitUntil(5000) { saved.size == 2 }
        assertEquals(listOf("result-ui-1" to "720P", "result-ui-2" to "720P"), saved)
    }
    @Test fun lastVideoPreviewPreservesQualityAndDoesNotSelectForBatch() {
        compose.onNodeWithContentDescription("画质 360P").performScrollTo().performClick()
        open(); preview(12)
        compose.onNodeWithText("当前 12/12 · 已选 0 个").assertIsDisplayed()
        open(); preview(1)
        compose.runOnIdle { assertEquals("360P", model.state.value.selectedVariant!!.label) }
        open(); preview(12)
        compose.onNodeWithText("保存当前视频").performClick()
        compose.waitUntil(5000) { saved.size == 1 }
        assertEquals("result-ui-12" to "720P", saved.single())
    }
    @Test fun selectAllCanBeCancelledAndLandscapeSheetCanReachLastItem() {
        open()
        compose.onNodeWithText("全选", substring = false).performClick()
        compose.onNodeWithText("取消全选").performClick()
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        preview(12)
        compose.onNodeWithText("当前 12/12 · 已选 0 个").assertIsDisplayed()
        compose.onNodeWithText("保存当前视频").assertIsDisplayed()
    }
}
