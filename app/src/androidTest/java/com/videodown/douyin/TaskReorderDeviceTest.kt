package com.videodown.douyin

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.SubtitleSelection
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.presentation.history.HistoryScreen
import com.videodown.douyin.presentation.history.HistoryViewModel
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
 * 任务页拖拽：把手只出现在**待下载**任务上，拖动后交给引擎的顺序是新的待下载顺序。
 *
 * 引擎侧的落盘（写 queueOrder）由 JVM 的 `QueueReorderTest` 端到端覆盖；
 * 这里覆盖的是界面这一半：拖拽手势 → 传给 `Downloader.reorderQueue` 的到底是哪一串 id。
 */
@RunWith(AndroidJUnit4::class)
class TaskReorderDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "task-reorder-${System.nanoTime()}")
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val store = ViewModelStore()
    private val reorders = mutableListOf<List<String>>()

    private fun record(id: String, title: String, status: DownloadStatus, updatedAt: Long) =
        DownloadRecord(id = id, awemeId = id, title = title, status = status, downloadedBytes = 1, updatedAt = updatedAt)

    private val first = record("first", "待下载甲", DownloadStatus.PENDING, 30)
    private val second = record("second", "待下载乙", DownloadStatus.PENDING, 20)
    private val done = record("done", "已完成", DownloadStatus.COMPLETED, 10)

    @Before fun setup() {
        directory.mkdirs()
        runBlocking { listOf(first, second, done).forEach { history.upsert(it) } }
        val downloader = object : Downloader {
            override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
            override fun observeRecord(id: String) = history.observeRecord(id)
            override suspend fun reorderQueue(orderedPendingIds: List<String>) { reorders += orderedPendingIds }
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
        compose.setContent { VideoDownTheme { HistoryScreen({}, viewModel) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("task-drag-first", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        directory.deleteRecursively()
    }

    private fun top(id: String): Float =
        compose.onNodeWithTag("task-drag-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    @Test fun draggingAPendingTaskReportsTheNewPendingOrder() {
        // 已完成的任务没有把手：队列顺序对它没有意义。
        assertTrue(compose.onAllNodesWithTag("task-drag-done", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals(2, compose.onAllNodesWithTag("task-drag-first", useUnmergedTree = true).fetchSemanticsNodes().size +
            compose.onAllNodesWithTag("task-drag-second", useUnmergedTree = true).fetchSemanticsNodes().size)

        // 两条待下载任务的显示顺序由任务排序决定，测试不假设它：先按实际位置读出来。
        val initial = listOf("first", "second").sortedBy { top(it) }
        val upper = initial[0]; val lower = initial[1]
        val rowHeight = top(lower) - top(upper)
        assertTrue("rowHeight=$rowHeight", rowHeight > 0f)

        // 把下面那条拖到上面那条之前。
        compose.onNodeWithTag("task-drag-$lower", useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -rowHeight * 1.2f / 6f)) }
            up()
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { reorders.isNotEmpty() }
        Thread.sleep(200)
        compose.waitForIdle()

        // 交给引擎的必须只有待下载任务，而且顺序真的交换了。
        assertEquals(listOf(listOf(lower, upper)), reorders)
        // 松手之后界面顺序由引擎队列决定（待下载项按 queuePosition 排），所以拿到的是假实现时
        // 它会回到原顺序——这是设计如此：真顺序由引擎持有，落盘那一半由 JVM 的 QueueReorderTest 覆盖。
    }
}
