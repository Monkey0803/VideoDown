package com.videodown.douyin

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppError
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.VideoDownApp
import com.videodown.douyin.presentation.history.HistoryViewModel
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadRetryStatusDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "retry-status-${UUID.randomUUID()}").apply { mkdirs() }
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val models = ViewModelStore()
    private val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    private val downloader = object : Downloader {
        override val progress = this@DownloadRetryStatusDeviceTest.progress
        override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection) = error("unused")
        override suspend fun pause(id: String) = Unit
        override suspend fun resume(id: String) = Unit
        override suspend fun cancel(id: String, removeRecord: Boolean) = Unit
        override suspend fun resumeAll() = Unit
        override suspend fun pauseAll() = Unit
        override suspend fun restart(id: String) = Unit
        override fun observeRecord(id: String) = history.observeRecord(id)
    }
    private val repository = object : VideoRepository {
        override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> =
            AppResult.Failure(AppError.Network("unused"))
    }

    @Before fun showApp() {
        val home = HomeViewModel(repository, downloader, { it.defaultVariant }, { null })
        val tasks = HistoryViewModel(history, downloader)
        models.put("home", home); models.put("tasks", tasks)
        compose.runOnUiThread {
            compose.activity.setContent { VideoDownTheme { VideoDownApp(null, {}, false, {}, tasks, home) } }
        }
    }

    @After fun cleanup() { compose.runOnUiThread { models.clear() }; directory.deleteRecursively() }

    @Test fun runningTaskShowsRetryCountdownInsteadOfZeroSpeed() = runBlocking {
        val record = DownloadRecord("retry", "retry", "重试倒计时测试", status = DownloadStatus.RUNNING,
            downloadedBytes = 1024, totalBytes = 4096)
        history.upsert(record)
        progress.value = mapOf(record.id to DownloadProgress(record.id, 1024, 4096, DownloadStatus.RUNNING,
            retryAtMillis = System.currentTimeMillis() + 10_000))
        compose.onNodeWithText("任务", substring = false).performClick()
        compose.onNodeWithText("等待重试", substring = true).assertIsDisplayed()
        Unit
    }
}
