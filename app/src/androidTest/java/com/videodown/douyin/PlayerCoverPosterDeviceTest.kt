package com.videodown.douyin

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModelStore
import androidx.media3.ui.PlayerView
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
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun findPlayerView(view: android.view.View): PlayerView? {
        if (view is PlayerView) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findPlayerView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

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

    @Test fun textureSurfaceCannotCoverPosterPixelsBeforeTheFirstFrame() {
        val cover = File(directory, "solid-cover.png").apply {
            val bitmap = android.graphics.Bitmap.createBitmap(240, 320, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xFFC2185B.toInt())
            outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        // 文件存在才能创建 PlayerView；空文件永远不会出首帧，正好稳定覆盖首帧前窗口。
        val invalidVideo = File(directory, "invalid.mp4").apply { writeBytes(byteArrayOf(0)) }
        val record = runBlocking {
            DownloadRecord(
                id = "pixel-cover", awemeId = "pixel-cover", title = "首帧像素验证",
                filePath = invalidVideo.path, fileName = invalidVideo.name,
                status = DownloadStatus.COMPLETED, coverUrl = cover.toURI().toString(),
            ).also { history.upsert(it) }
        }
        show(record, coverUrl = record.coverUrl)
        compose.waitUntil(5_000) {
            posters() == 1 && compose.runOnUiThread {
                findPlayerView(compose.activity.window.decorView)?.videoSurfaceView is android.view.TextureView
            }
        }
        val bounds: Rect = compose.onAllNodesWithTag("player-cover-poster").fetchSemanticsNodes().single().boundsInRoot
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val x = (bounds.left + bounds.width * .25f).roundToInt().coerceIn(0, screenshot.width - 1)
        val y = (bounds.top + bounds.height * .4f).roundToInt().coerceIn(0, screenshot.height - 1)
        val pixel = screenshot.getPixel(x, y)
        screenshot.recycle()
        assertTrue(
            "首帧前应看到洋红封面而不是黑色 surface，pixel=${Integer.toHexString(pixel)}",
            android.graphics.Color.red(pixel) > 20 &&
                android.graphics.Color.red(pixel) > android.graphics.Color.green(pixel) * 3 &&
                android.graphics.Color.blue(pixel) > android.graphics.Color.green(pixel) * 2,
        )
    }
}
