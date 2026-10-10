package com.videodown.douyin

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videodown.douyin.core.*
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.*
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.home.*
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class P2BatchImportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store = ViewModelStore()
    private val starts = CopyOnWriteArrayList<Pair<String, String>>()
    private val calls = CopyOnWriteArrayList<String>()
    private var duplicate = false
    private var failing = false
    private lateinit var model: BatchImportViewModel
    private lateinit var repository: VideoRepository
    private val low = VideoVariant("https://cdn.test/low.mp4", "360P")
    private val high = VideoVariant("https://cdn.test/high.mp4", "1080P")
    private val downloader = object : Downloader {
        override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        override suspend fun findDuplicate(video: DouyinVideo, variant: VideoVariant) =
            if (duplicate) DownloadRecord("existing", video.awemeId, video.title) else null
        override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection): String {
            if (failing) error("queue failed")
            starts += video.awemeId to variant.label; return video.awemeId
        }
        override suspend fun pause(id: String) {}
        override suspend fun resume(id: String) {}
        override suspend fun cancel(id: String, removeRecord: Boolean) {}
        override suspend fun resumeAll() {}
        override suspend fun pauseAll() {}
        override suspend fun restart(id: String) {}
        override fun observeRecord(id: String) = MutableStateFlow<DownloadRecord?>(null)
    }
    @Before fun setup() {
        repository = object : VideoRepository {
            override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> {
                calls += shareText
                if (shareText.contains("bad")) return AppResult.Failure(AppError.Network("该链接网络失败"))
                return AppResult.Success(DouyinVideo(shareText.substringAfterLast('/'), "测试视频 " + shareText.substringAfterLast('/'),
                    variants = listOf(low, high), platform = VideoPlatform.GENERIC))
            }
        }
        compose.runOnUiThread {
            model = BatchImportViewModel(repository, downloader, { it.variants.first() }); store.put("batch", model)
            compose.activity.setContent { VideoDownTheme { BatchImportDialog(model, {}, {}) } }
        }
    }
    @After fun close() { compose.runOnUiThread { store.clear() } }
    private fun parse(input: String) {
        compose.onNode(hasSetTextAction() and hasText("粘贴多条分享文案或网址")).performTextReplacement(input)
        compose.onNodeWithText("开始批量解析").performClick()
        compose.waitUntil(5000) { model.state.value.rows.isNotEmpty() && !model.state.value.parsing }
    }
    @Test fun pastedMultipleUrlsAreDeduplicatedAndQualitySelectionControlsQueue() {
        parse("分享 https://example.test/one https://example.test/bad https://example.test/one")
        assertEquals(2, calls.size)
        compose.onNodeWithTag("batch-import-list").performScrollToNode(hasTestTag("import-https://example.test/one"))
        compose.onNodeWithText("1080P").performClick()
        compose.onNodeWithText("下载所选").performClick()
        compose.waitUntil(5000) { starts.isNotEmpty() }
        assertEquals(listOf("one" to "1080P"), starts)
        assertEquals(ImportStatus.FAILED, model.state.value.rows.last().status)
        assertEquals(0, model.state.value.selectedCount)
    }
    @Test fun duplicateConfirmationCancelSkipAndExplicitAgainAreActualControls() {
        duplicate = true; parse("https://example.test/one")
        compose.onNodeWithText("下载所选").performClick()
        compose.onNodeWithText("发现 1 个重复任务").assertIsDisplayed()
        compose.onNodeWithText("取消", substring = false).performClick(); assertTrue(starts.isEmpty())
        compose.onNodeWithText("下载所选").performClick()
        compose.onNodeWithText("跳过重复项").performClick()
        compose.waitUntil(5000) { !model.state.value.submitting && model.state.value.selectedCount == 0 }
        assertTrue(starts.isEmpty())
        compose.onNodeWithText("全选视频").performClick(); compose.onNodeWithText("下载所选").performClick()
        compose.onNodeWithText("仍然全部下载").performClick()
        compose.waitUntil(5000) { starts.size == 1 }; assertEquals("one" to "360P", starts.first())
    }
    @Test fun homeBatchDialogRemainsVisibleAcrossActualRotationWithSelectionsIntact() {
        val home = HomeViewModel(repository, downloader, { it.variants.first() }, { null })
        compose.runOnUiThread {
            store.put("home", home)
            compose.activity.setContent { VideoDownTheme {
                HomeScreen(null, {}, {}, {}, {}, emptyList(), viewModel = home, batchImportModel = model)
            } }
        }
        compose.onNodeWithText("批量导入多个链接").performScrollTo().performClick()
        parse("https://example.test/one")
        compose.onNodeWithTag("batch-import-list").performScrollToNode(hasTestTag("import-https://example.test/one"))
        compose.onNodeWithText("1080P").performClick()
        val previous = compose.activity.requestedOrientation
        try {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText("关闭", substring = false).assertIsDisplayed()
            compose.onNodeWithTag("batch-import-list").performScrollToNode(hasTestTag("import-https://example.test/one"))
            compose.onNodeWithText("1080P").assertIsDisplayed()
            assertEquals("1080P", model.state.value.rows.first().media.first().choice!!.label)
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithText("下载所选").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { starts.size == 1 }
            assertEquals("one" to "1080P", starts.single())
            assertEquals(1, calls.size)
        } finally { compose.runOnUiThread { compose.activity.requestedOrientation = previous } }
    }
    @Test fun fiftyFailuresKeepBottomActionsAccessibleAndFailedSelectionsIntact() {
        failing = true
        parse((1..50).joinToString(" ") { "https://example.test/long-title-$it" })
        compose.onNodeWithText("下载所选").performClick()
        // The state reports counts; the dialog words them.
        compose.waitUntil(5000) {
            val notice = model.state.value.notice
            !model.state.value.submitting && notice is com.videodown.douyin.presentation.home.ImportNotice.Queued && notice.failedTitles.size == 50
        }
        compose.onNodeWithText("关闭", substring = false).assertIsDisplayed()
        compose.onNodeWithText("下载所选").assertIsDisplayed().assertIsEnabled()
        assertEquals(50, model.state.value.selectedCount); assertTrue(starts.isEmpty())
    }
}
