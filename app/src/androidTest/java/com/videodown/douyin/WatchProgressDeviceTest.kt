package com.videodown.douyin

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.LibraryLayout
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.playback.PlaybackPreferences
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.PlaybackCheckpoint
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 「继续观看」 on a saved card: a stored position for the current file shows a bar and a percentage,
 * a position that no longer describes the file shows nothing, and the list reacts as it changes.
 */
@RunWith(AndroidJUnit4::class)
class WatchProgressDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "watch-progress-${UUID.randomUUID()}").apply { mkdirs() }
    private val file = File(directory, "fixture.mp4").apply { writeBytes(ByteArray(4_096)) }
    private val record = DownloadRecord(id = "fixture", awemeId = "fixture", title = "继续观看测试视频",
        fileName = file.name, filePath = file.absolutePath, status = DownloadStatus.COMPLETED)
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val store = ViewModelStore()
    private val playbackName = "watch-progress-test-${UUID.randomUUID()}"
    private val playback = PlaybackPreferences(context, playbackName)
    private var previousLayout = LibraryLayout.WATERFALL

    private fun saveStoredPosition(size: Long, modified: Long, positionMs: Long, durationMs: Long) {
        playback.saveCheckpoint(record.id, size, modified, PlaybackCheckpoint(positionMs, durationMs))
    }

    @Before fun setup() {
        previousLayout = ServiceLocator.preferences.libraryLayout
        ServiceLocator.preferences.libraryLayout = LibraryLayout.WATERFALL
        runBlocking { history.upsert(record) }
        val downloader = object : Downloader {
            override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
            override suspend fun cancel(id: String, removeRecord: Boolean) {
                file.delete(); history.delete(id)
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
            val watch by playback.watchProgress().collectAsState()
            VideoDownTheme {
                LibraryScreen(viewModel, onPlay = {}, watchProgress = watch)
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(record.title)).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        ServiceLocator.preferences.libraryLayout = previousLayout
        directory.deleteRecursively()
    }

    @Test fun aPartlyWatchedFileShowsItsProgressAndAStalePositionDisappears() {
        // Nothing watched yet: no bar, no percentage.
        assertEquals(0, compose.onAllNodesWithTag("watch-progress-${record.id}").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("已看", substring = true).fetchSemanticsNodes().size)

        saveStoredPosition(file.length(), file.lastModified(), positionMs = 3_000, durationMs = 12_000)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("watch-progress-${record.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("已看 25%", substring = true).assertIsDisplayed()
        // The stored position is real: a fresh reader over the same file sees it too.
        assertTrue("A fresh reader must see the same stored position",
            PlaybackPreferences(context, playbackName).watchProgress().value.containsKey(record.id))

        // Finished files must not keep advertising a resume point.
        saveStoredPosition(file.length(), file.lastModified(), positionMs = 12_000, durationMs = 12_000)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("watch-progress-${record.id}").fetchSemanticsNodes().isEmpty() }

        // A position recorded against a different file must never be shown for this one.
        saveStoredPosition(file.length(), file.lastModified(), positionMs = 3_000, durationMs = 12_000)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("watch-progress-${record.id}").fetchSemanticsNodes().isNotEmpty() }
        saveStoredPosition(file.length() + 1, file.lastModified(), positionMs = 3_000, durationMs = 12_000)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已看", substring = true).fetchSemanticsNodes().isEmpty() }
        assertEquals(0, compose.onAllNodesWithTag("watch-progress-${record.id}").fetchSemanticsNodes().size)
    }
}
