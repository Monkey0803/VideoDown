package com.videodown.douyin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.SubtitleSelection
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.presentation.adaptive.LocalWindowWidth
import com.videodown.douyin.presentation.adaptive.WindowWidth
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
import java.io.File
import java.util.UUID

/**
 * 已存 Feed 的列数随窗口尺寸类变化：Compact 2 列、Medium 3 列、Expanded 4 列（J2/J6）。
 *
 * 这里验证的是真实的 `LazyVerticalStaggeredGrid` 布局几何（同一行首项 top 相同、行内项数等于
 * 策略列数），不是只断言策略函数；尺寸类通过 `LocalWindowWidth` 注入，避免在设备上反复改分辨率。
 * 真实显示尺寸下的导航形态切换由 [AdaptiveLayoutDeviceTest] 覆盖。
 */
@RunWith(AndroidJUnit4::class)
class LibraryFeedColumnsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "feed-columns-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = ViewModelStore()
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private var previousLayout = LibraryLayout.WATERFALL

    @Before fun setup() {
        previousLayout = ServiceLocator.preferences.libraryLayout
        ServiceLocator.preferences.libraryLayout = LibraryLayout.WATERFALL
    }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        ServiceLocator.preferences.libraryLayout = previousLayout
        directory.deleteRecursively()
    }

    private fun record(index: Int): DownloadRecord {
        val file = File(directory, "rec-$index.bin").apply { writeBytes(ByteArray(64) { 1 }) }
        return DownloadRecord(
            id = "rec-$index", awemeId = "rec-$index",
            title = "列测试-${index.toString().padStart(2, '0')}",
            fileName = file.name, filePath = file.absolutePath,
            status = DownloadStatus.COMPLETED, mediaKind = MediaKind.VIDEO,
            updatedAt = 100L + index,
        )
    }

    private fun buildViewModel(downloader: Downloader): HistoryViewModel {
        lateinit var viewModel: HistoryViewModel
        compose.runOnUiThread {
            viewModel = HistoryViewModel(history, downloader)
            store.put("history", viewModel)
        }
        return viewModel
    }

    private fun stubDownloader() = object : Downloader {
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

    private fun rowCount(titles: List<String>): Int {
        val tops = titles.mapNotNull { title ->
            // 合并后的行语义是「标题, 相对时间」，按标题子串定位。
            runCatching {
                compose.onNode(hasText(title, substring = true)).fetchSemanticsNode().boundsInRoot.top
            }.getOrNull()
        }
        if (tops.isEmpty()) return 0
        val first = tops.first()
        return tops.count { kotlin.math.abs(it - first) < 2f }
    }

    @Test fun feedColumnsFollowWindowSizeClass() = runBlocking {
        (1..10).forEach { history.upsert(record(it)) }
        val viewModel = buildViewModel(stubDownloader())
        var width by mutableStateOf(WindowWidth.COMPACT)
        compose.setContent {
            VideoDownTheme {
                CompositionLocalProvider(LocalWindowWidth provides width) {
                    Box(Modifier.requiredSize(1200.dp, 1400.dp).testTag("feed-host")) {
                        LibraryScreen(viewModel, onPlay = {})
                    }
                }
            }
        }
        // 标题带序号且用子串匹配：合并语义是「标题, 相对时间」，不能用整串相等。
        // 按「最新在前」的显示顺序排列；懒加载只组合视口内的前几项，等待第一个可见标题即可。
        val titles = (10 downTo 1).map { "列测试-${it.toString().padStart(2, '0')}" }
        compose.waitUntil(10_000) { viewModel.state.value.hydrated }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(titles.first(), substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        assertEquals("Compact 应为 2 列", 2, rowCount(titles))

        compose.runOnUiThread { width = WindowWidth.MEDIUM }
        compose.waitForIdle()
        assertEquals("Medium 应为 3 列", 3, rowCount(titles))

        compose.runOnUiThread { width = WindowWidth.EXPANDED }
        compose.waitForIdle()
        assertEquals("Expanded 应为 4 列", 4, rowCount(titles))
    }

    @Test fun selectingFromExpandedFeedStillOpensTheRecord() = runBlocking {
        history.upsert(record(1))
        val viewModel = buildViewModel(stubDownloader())
        var played: String? = null
        compose.setContent {
            VideoDownTheme {
                CompositionLocalProvider(LocalWindowWidth provides WindowWidth.EXPANDED) {
                    Box(Modifier.requiredSize(1200.dp, 1200.dp)) {
                        LibraryScreen(viewModel, onPlay = { played = it })
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("列测试-01", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("列测试-01", substring = true)).performClick()
        compose.waitForIdle()
        assertEquals("rec-1", played)
        compose.onNodeWithTag("library-search").assertExists()
        Unit
    }
}
