package com.videodown.douyin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.LibraryLayout
import com.videodown.douyin.core.LibrarySort
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.model.SubtitleSelection
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.presentation.history.HistoryViewModel
import com.videodown.douyin.presentation.library.LibraryScreen
import com.videodown.douyin.presentation.theme.VideoDownTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 已存页「自定义排序」的拖拽。
 *
 * 断言分两层：拖完之后界面上的先后真的换了；再用一个**全新的 store/repository** 读同一个文件，
 * 新顺序还在——只断言内存列表会让「其实没落盘」蒙混过关。
 */
@RunWith(AndroidJUnit4::class)
class LibraryReorderDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "library-reorder-${System.nanoTime()}")
    private val historyFile get() = File(directory, "history.json")
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(historyFile))
    private val store = ViewModelStore()
    private var previousSort = LibrarySort.NEWEST
    private var previousLayout = LibraryLayout.WATERFALL

    // 目录要等 setup 建好才能写文件，所以这里只描述记录，落盘在 setup 里做。
    private fun record(id: String, title: String, updatedAt: Long) = DownloadRecord(
        id = id, awemeId = id, title = title, status = DownloadStatus.COMPLETED,
        downloadedBytes = 8, updatedAt = updatedAt,
    )

    private val first = record("first", "第一个", 30)
    private val second = record("second", "第二个", 20)
    private val third = record("third", "第三个", 10)

    private fun withSavedFile(record: DownloadRecord) = record.copy(
        filePath = File(directory, "${record.id}.mp4").apply { writeBytes(ByteArray(8)) }.path,
    )

    private fun storedOrder(): List<String> = runBlocking {
        history.snapshot().sortedBy { it.sortIndex }.map { it.id }
    }

    private fun reopenedOrder(): List<String> = runBlocking {
        DownloadHistoryRepositoryImpl(DownloadHistoryStore(historyFile)).snapshot()
            .sortedBy { it.sortIndex }.map { it.id }
    }

    @Before fun setup() {
        previousSort = ServiceLocator.preferences.librarySort
        previousLayout = ServiceLocator.preferences.libraryLayout
        directory.mkdirs()
        // 直接把「自定义排序」当作持久化偏好设好：屏幕进入时会套用它（这正是重启后的情形）。
        ServiceLocator.preferences.librarySort = LibrarySort.CUSTOM
        ServiceLocator.preferences.libraryLayout = LibraryLayout.LIST
        runBlocking { listOf(first, second, third).forEach { history.upsert(withSavedFile(it)) } }
        val downloader = object : Downloader {
            override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
            override fun observeRecord(id: String) = history.observeRecord(id)
            override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection): String = error("Unexpected start")
            override suspend fun pause(id: String): Unit = error("Unexpected pause")
            override suspend fun resume(id: String): Unit = error("Unexpected resume")
            override suspend fun cancel(id: String, removeRecord: Boolean): Unit = error("Unexpected cancel")
            override suspend fun resumeAll(): Unit = error("Unexpected resumeAll")
            override suspend fun pauseAll(): Unit = error("Unexpected pauseAll")
            override suspend fun restart(id: String): Unit = error("Unexpected restart")
        }
        lateinit var viewModel: HistoryViewModel
        compose.runOnUiThread {
            viewModel = HistoryViewModel(history, downloader)
            store.put("history", viewModel)
        }
        compose.setContent { VideoDownTheme { LibraryScreen(viewModel, onPlay = {}) } }
        // 诊断：先确认列表本身渲染出来了，再确认把手在（否则无法判断是哪一步失败）。
        compose.waitUntil(5_000) { compose.onAllNodesWithText("第一个").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("library-drag-first", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // seed：第一次进入自定义排序时按当前顺序写一遍。
        compose.waitUntil(5_000) { runBlocking { history.snapshot().any { it.sortIndex > 0 } } }
    }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        ServiceLocator.preferences.librarySort = previousSort
        ServiceLocator.preferences.libraryLayout = previousLayout
        directory.deleteRecursively()
    }

    private fun top(id: String): Float =
        compose.onNodeWithTag("library-drag-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    @Test fun draggingTheFirstItemDownRepinsAndPersistsTheOrder() {
        assertEquals(listOf("first", "second", "third"), storedOrder())

        // 行高从两个把手的位置差量出来，避免写死 dp。
        val rowHeight = top("second") - top("first")
        assertTrue("行高应该为正", rowHeight > 0f)

        compose.onNodeWithTag("library-drag-first", useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, rowHeight * 2.4f / 6f)) }
            up()
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { storedOrder() != listOf("first", "second", "third") }
        Thread.sleep(300)
        compose.waitForIdle()

        val persisted = storedOrder()
        assertEquals("第一项应该被拖到最后", listOf("second", "third", "first"), persisted)
        // 界面顺序也跟着换了，不是只有存储变了。
        assertTrue("拖动后第一项应该排在最后", top("first") > top("third"))
        // 持久化：全新实例读同一个文件，新顺序必须还在。
        assertEquals(persisted, reopenedOrder())
    }

    @Test fun theHandleDisappearsWhileSearching() {
        assertEquals(3, compose.onAllNodesWithTag("library-drag-first", useUnmergedTree = true).fetchSemanticsNodes().size +
            compose.onAllNodesWithTag("library-drag-second", useUnmergedTree = true).fetchSemanticsNodes().size +
            compose.onAllNodesWithTag("library-drag-third", useUnmergedTree = true).fetchSemanticsNodes().size)

        compose.onNodeWithTag("library-search").performTextInput("第一个")
        compose.waitForIdle()

        // 搜索状态下顺序由过滤结果决定，拖拽把手必须消失（否则用户无法预测重排了什么）。
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("library-drag-first", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("library-drag-second", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        // 而且没有因为过滤而写坏顺序。
        assertEquals(listOf("first", "second", "third"), storedOrder())
    }

    @Test fun talkBackActionsReorderWithoutADragGesture() {
        assertEquals(listOf("first", "second", "third"), storedOrder())

        // 首项只有「后移」，末项只有「前移」，中间项两个都有——TalkBack 的菜单项就是它们。
        fun labels(id: String): List<String> =
            compose.onNodeWithTag("library-drag-$id", useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("后移"), labels("first"))
        assertEquals(listOf("前移", "后移"), labels("second"))

        val moveDown = compose.onNodeWithTag("library-drag-first", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "后移" }
        compose.runOnUiThread { moveDown.action() }
        compose.waitUntil(5_000) { storedOrder() != listOf("first", "second", "third") }

        // 不用任何拖拽手势也能改顺序，而且同样落盘。
        assertEquals(listOf("second", "first", "third"), storedOrder())
        assertEquals(storedOrder(), reopenedOrder())
    }
}
