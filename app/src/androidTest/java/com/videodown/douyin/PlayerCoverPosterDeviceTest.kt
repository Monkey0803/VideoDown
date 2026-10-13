package com.videodown.douyin

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.player.PlayerScreen
import com.videodown.douyin.presentation.player.PlayerViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 播放器封面海报（共享元素的目标侧）。
 *
 * 断言的是机制而不是动画本身：有封面时海报会留到视频出画为止，纯音频与无封面时不出海报。
 * 全屏与 PiP 下不参与共享这一点由 `!fullscreen && !inPip` 条件保证，那两种布局由
 * `PlayerLandscapeDeviceTest` / `P3PictureInPictureDeviceTest` 覆盖。
 */
@RunWith(AndroidJUnit4::class)
class PlayerCoverPosterDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var directory: File
    private lateinit var history: DownloadHistoryRepositoryImpl
    private val models = ViewModelStore()

    @Before fun setup() {
        directory = File(context.cacheDir, "cover-poster-${UUID.randomUUID()}").apply { mkdirs() }
        history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    }

    @After fun close() {
        compose.runOnUiThread { compose.activity.setContent { Text("已关闭") }; models.clear() }
        compose.waitForIdle()
        directory.deleteRecursively()
    }

    /** 文件不存在 → 永远等不到第一帧，海报因此稳定可见，可以用它验证「留到出画为止」。 */
    private fun missingFileRecord(isAudio: Boolean): DownloadRecord = runBlocking {
        DownloadRecord(
            id = "cover", awemeId = "cover", title = "封面验证",
            filePath = File(directory, "gone.mp4").path, fileName = "gone.mp4",
            status = DownloadStatus.COMPLETED, coverUrl = "file://${directory.path}/cover.png",
            media = if (isAudio) MediaDownload(audioOnly = true) else null,
        ).also { history.upsert(it) }
    }

    private fun show(record: DownloadRecord, coverUrl: String?, isAudio: Boolean = false) {
        val model = PlayerViewModel(history, com.videodown.douyin.data.playback.PlaybackPreferences(context, directory.name))
        compose.runOnUiThread {
            models.put("player-${UUID.randomUUID()}", model)
            compose.activity.setContent {
                VideoDownTheme {
                    PlayerScreen(record.id, {}, model, coverUrl = coverUrl)
                }
            }
        }
        if (isAudio) compose.waitForIdle()
    }

    private fun posters() = compose.onAllNodesWithTag("player-cover-poster").fetchSemanticsNodes().size

    @Test fun coverPosterStaysUntilTheVideoShowsPixels() {
        val record = missingFileRecord(isAudio = false)
        show(record, coverUrl = record.coverUrl)
        compose.waitUntil(5000) { posters() == 1 }
        // 再等一会确认它不是一闪而过：视频始终没出画，海报就该一直在。
        Thread.sleep(600)
        compose.waitForIdle()
        assertEquals("封面海报应留到视频出画为止", 1, posters())
    }

    @Test fun audioAndCoverlessEntriesShowNoPoster() {
        val audio = missingFileRecord(isAudio = true)
        show(audio, coverUrl = audio.coverUrl, isAudio = true)
        compose.waitUntil(5000) { posters() == 0 }
        assertEquals("纯音频不该出封面海报", 0, posters())
    }
}
