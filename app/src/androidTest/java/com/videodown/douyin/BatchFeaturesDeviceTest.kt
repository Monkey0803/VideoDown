package com.videodown.douyin

import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.LibraryLayout
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.gallery.GalleryExporter
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.history.HistoryScreen
import com.videodown.douyin.presentation.history.HistoryViewModel
import com.videodown.douyin.presentation.home.HomeScreen
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.library.LibraryScreen
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Isolated fixtures exercise real Compose controls, history/files and MediaStore export. */
@RunWith(AndroidJUnit4::class)
class BatchFeaturesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val directory = File(context.cacheDir, "batch-features-${UUID.randomUUID()}").apply { mkdirs() }
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val models = ViewModelStore()
    private val started = CopyOnWriteArrayList<Pair<String, String>>()
    private val played = CopyOnWriteArrayList<String>()
    private val paused = CopyOnWriteArrayList<String>()
    private val resumed = CopyOnWriteArrayList<String>()
    private var oldLayout = LibraryLayout.WATERFALL
    private val variant = VideoVariant("https://example.com/fixture.mp4", "1080P", sizeBytes = 1024)
    private val videos = listOf(
        DouyinVideo("one", "春天片段", platform = VideoPlatform.X, variants = listOf(variant, variant.copy(label = "360P")), author = DouyinAuthor(nickname = "Alice")),
        DouyinVideo("two", "夜景片段", platform = VideoPlatform.WEIBO, variants = listOf(variant), author = DouyinAuthor(nickname = "Bob")))
    private val downloader = object : Downloader {
        override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        override suspend fun findDuplicate(video: DouyinVideo, variant: VideoVariant) = history.snapshot().firstOrNull {
            it.matchesSelection(video, variant) && (it.status != DownloadStatus.COMPLETED || it.filePath?.let { path -> File(path).isFile } == true)
        }
        override suspend fun start(video: DouyinVideo, variant: VideoVariant): String {
            started += video.awemeId to variant.label
            val id = "new-${started.size}"
            history.upsert(DownloadRecord(id, video.awemeId, video.title, platform = video.platform, resolutionLabel = variant.label))
            return id
        }
        override suspend fun pause(id: String) { paused += id; history.getById(id)?.let { history.upsert(it.copy(status = DownloadStatus.PAUSED)) } }
        override suspend fun resume(id: String) { resumed += id; history.getById(id)?.let { history.upsert(it.copy(status = DownloadStatus.PENDING)) } }
        override suspend fun cancel(id: String, removeRecord: Boolean) { history.getById(id)?.filePath?.let { File(it).delete() }; history.delete(id) }
        override suspend fun resumeAll() { history.snapshot().filter { it.isResumable }.forEach { resume(it.id) } }
        override suspend fun pauseAll() { history.snapshot().filter { it.status in listOf(DownloadStatus.RUNNING, DownloadStatus.PENDING) }.forEach { pause(it.id) } }
        override suspend fun restart(id: String) = Unit
        override fun observeRecord(id: String) = history.observeRecord(id)
    }
    @Before fun setup() { oldLayout = ServiceLocator.preferences.libraryLayout; ServiceLocator.preferences.libraryLayout = LibraryLayout.WATERFALL }
    @After fun close() {
        compose.runOnUiThread { models.clear() }
        ServiceLocator.preferences.libraryLayout = oldLayout
        // Delete only the MediaStore copies created from this suite's unique fixture names.
        for (file in directory.listFiles().orEmpty().filter { it.extension == "mp4" }) {
            context.contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(file.name))
        }
        directory.deleteRecursively()
    }
    private fun record(index: Int, status: DownloadStatus = DownloadStatus.COMPLETED): DownloadRecord {
        val video = videos[index]
        val file = File(directory, "${directory.name}-${video.awemeId}.mp4")
        instrumentation.context.assets.open("landscape-fixture.mp4").use { source -> file.outputStream().use { source.copyTo(it) } }
        return DownloadRecord(video.awemeId, video.awemeId, video.title, authorNickname = video.author.nickname,
            platform = video.platform, resolutionLabel = "1080P", filePath = file.absolutePath,
            fileName = file.name, status = status, downloadedBytes = file.length(), updatedAt = index.toLong())
    }
    private fun showHome() {
        val repo = object : VideoRepository { override suspend fun parseShareText(shareText: String) = AppResult.Success(videos[0]) }
        compose.runOnUiThread {
            val model = HomeViewModel(repo, downloader, { it.defaultVariant }, { null })
            models.put("home", model)
            model.acceptBrowserResult(ParsedVideos(videos))
            compose.activity.setContent { VideoDownTheme { HomeScreen(null, {}, {}, {}, { played += it }, emptyList(), model) } }
        }
    }
    private fun showLibrary() {
        compose.runOnUiThread {
            val model = HistoryViewModel(history, downloader) { GalleryExporter(context).export(it).map { Unit } }
            models.put("history", model)
            compose.activity.setContent { VideoDownTheme { LibraryScreen(model, onPlay = { played += it }) } }
        }
        compose.waitUntil(5000) { compose.onAllNodes(hasText("春天片段")).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun duplicateDialogCanOpenExistingFileOrExplicitlyDownloadAgain() {
        runBlocking { history.upsert(record(0)) }; showHome()
        compose.onNodeWithText("保存当前视频").performClick()
        compose.onNodeWithText("已有相同视频和画质").assertIsDisplayed()
        assertTrue(started.isEmpty())
        compose.onNodeWithText("查看已保存视频").performClick()
        compose.runOnIdle { assertEquals(listOf("one"), played) }
        compose.onNodeWithText("保存当前视频").performClick()
        compose.onNodeWithText("仍然下载").performClick()
        compose.waitUntil(5000) { started.size == 1 }
        assertEquals(listOf("one" to "1080P"), started)
    }
    @Test fun batchDownloadKeepsIndividualQualityAndSkipsDuplicates() {
        runBlocking { history.upsert(record(1)) }; showHome()
        compose.onNodeWithText("360P", useUnmergedTree = false).performScrollTo().performClick()
        compose.onNodeWithText("查看全部").performClick()
        compose.onNodeWithText("全选", substring = false).performClick()
        compose.onNodeWithText("完成选择").performClick()
        compose.onNodeWithText("保存所选 2 个视频").performClick()
        compose.onNodeWithText("发现 1 个重复任务").assertIsDisplayed()
        compose.onNodeWithText("跳过重复项").performClick()
        compose.waitUntil(5000) { started.size == 1 }
        assertEquals(listOf("one" to "360P"), started)
    }
    @Test fun filteredSelectionDeletesOnlyVisibleResultAndCancelPreservesFiles() {
        val first = record(0); val second = record(1)
        runBlocking { history.upsert(first); history.upsert(second) }; showLibrary()
        compose.onNodeWithText("搜索标题或作者").performTextInput("Alice")
        compose.onNodeWithText("夜景片段").assertDoesNotExist()
        compose.onNodeWithText("多选", substring = false).performClick()
        compose.onNodeWithText("全选当前结果").performScrollTo().performClick()
        compose.onNodeWithTag("selection-toolbar")
            .performScrollToNode(hasText("删除所选"))
        compose.onNodeWithText("删除所选").performClick()
        compose.onNodeWithText("取消", substring = false).performClick()
        assertTrue(File(first.filePath!!).exists())
        compose.onNodeWithText("删除所选").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(5000) { runBlocking { history.getById(first.id) == null } }
        assertNotNull(runBlocking { history.getById(second.id) }); assertTrue(File(second.filePath!!).exists())
        compose.onNodeWithText("清除", substring = false).performClick()
        compose.onNodeWithText("夜景片段").assertIsDisplayed()
        assertTrue(played.isEmpty())
    }
    @Test fun platformFilterAndListLayoutRetainSearchAndBatchExportCopiesAllSelections() {
        val first = record(0); val second = record(1)
        runBlocking { history.upsert(first); history.upsert(second) }; showLibrary()
        compose.onNodeWithText("微博", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("春天片段").assertDoesNotExist()
        compose.onNodeWithText("夜景片段").assertIsDisplayed()
        compose.onNodeWithText("全部平台").performScrollTo().performClick()
        compose.onNodeWithContentDescription("切换为列表布局").performClick()
        compose.onNodeWithText("多选", substring = false).performClick()
        compose.onNodeWithText("全选当前结果").performScrollTo().performClick()
        compose.onNodeWithText("保存到相册", substring = false).performScrollTo().performClick()
        compose.waitUntil(10000) {
            listOf(first, second).all { record -> context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(record.fileName), null)?.use { it.count == 1 } == true }
        }
        assertTrue(File(first.filePath!!).exists()); assertTrue(File(second.filePath!!).exists())
    }
    @Test fun batchGalleryFailureIdentifiesVideoAndRetryDoesNotDuplicateSuccessfulCopy() {
        val first = record(0)
        val original = record(1)
        val unsupported = File(directory, "unsupported.mkv").apply { File(original.filePath!!).copyTo(this) }
        val second = original.copy(filePath = unsupported.absolutePath, fileName = unsupported.name)
        runBlocking { history.upsert(first); history.upsert(second) }; showLibrary()
        compose.onNodeWithText("多选", substring = false).performClick()
        compose.onNodeWithText("全选当前结果").performScrollTo().performClick()
        compose.onNodeWithText("保存到相册", substring = false).performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("查看失败 1 项")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("已选 1 项").assertIsDisplayed()
        compose.onNodeWithText("查看失败 1 项").performClick()
        compose.onNodeWithText("夜景片段：此容器暂不支持保存到相册，请使用分享文件").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("保存到相册", substring = false).performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("查看失败 1 项")).fetchSemanticsNodes().isNotEmpty() }
        val count = context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(first.fileName), null)?.use { it.count }
        assertEquals(1, count)
        assertTrue(File(first.filePath!!).exists()); assertTrue(unsupported.exists())
    }
    @Test fun selectedTaskPauseAndContinueDoNotTouchUnselectedTask() {
        val first = record(0, DownloadStatus.RUNNING); val second = record(1, DownloadStatus.PAUSED)
        runBlocking { history.upsert(first); history.upsert(second) }
        compose.runOnUiThread {
            val model = HistoryViewModel(history, downloader); models.put("history", model)
            compose.activity.setContent { VideoDownTheme { HistoryScreen({}, model) } }
        }
        compose.waitUntil(5000) { compose.onAllNodes(hasText(first.title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("全部暂停").assertIsDisplayed()
        compose.onNodeWithText("全部继续").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("多选任务").performClick()
        compose.onAllNodes(isToggleable()).filterToOne(hasStateDescription(first.title)).performClick()
        compose.onNodeWithText("暂停所选").performScrollTo().performClick()
        compose.waitUntil(5000) { paused.size == 1 }
        compose.onNodeWithText("继续所选").performScrollTo().performClick()
        compose.waitUntil(5000) { resumed.size == 1 }
        assertEquals(listOf(first.id), paused); assertEquals(listOf(first.id), resumed)
    }
}
