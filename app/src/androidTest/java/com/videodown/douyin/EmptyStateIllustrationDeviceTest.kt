package com.videodown.douyin

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.presentation.components.EMPTY_STATE_ILLUSTRATION_TAG
import com.videodown.douyin.presentation.components.EmptyStateIllustration
import com.videodown.douyin.presentation.history.HistoryViewModel
import com.videodown.douyin.presentation.library.LibraryScreen
import com.videodown.douyin.presentation.theme.VideoDownTheme
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 已存空状态插图（圆 → 圆角方形的入场 morph）。
 *
 * 三条断言分别钉住：空库时插画出现、**终态确实是圆角方形而不是圆**（面积度量，时间无关）、
 * 以及**总开关关闭时静止后与动画落在同一形状**。
 *
 * 为什么不比"中间帧"、也不比较两张不同时刻的截图：真机上 `uiAutomation.takeScreenshot` 会等待
 * 界面稳定并推进动画，暂停的测试时钟也拦不住（模拟器上可行、真机上"起点"已经是终态）。
 * 所以断言全部改成时间无关的量，或只在同一张截图内比较。
 */
@RunWith(AndroidJUnit4::class)
class EmptyStateIllustrationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "empty-state-${System.nanoTime()}")
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val store = ViewModelStore()

    private fun downloader() = object : Downloader {
        override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        override fun observeRecord(id: String) = history.observeRecord(id)
        override suspend fun start(video: com.videodown.douyin.domain.model.DouyinVideo, variant: com.videodown.douyin.domain.model.VideoVariant, subtitles: com.videodown.douyin.domain.model.SubtitleSelection): String = error("Unexpected start")
        override suspend fun pause(id: String): Unit = error("Unexpected pause")
        override suspend fun resume(id: String): Unit = error("Unexpected resume")
        override suspend fun cancel(id: String, removeRecord: Boolean): Unit = error("Unexpected cancel")
        override suspend fun resumeAll(): Unit = error("Unexpected resumeAll")
        override suspend fun pauseAll(): Unit = error("Unexpected pauseAll")
        override suspend fun restart(id: String): Unit = error("Unexpected restart")
    }

    private fun frame(tag: String): ByteArray {
        val full = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val crop = Bitmap.createBitmap(
            full,
            bounds.left.toInt().coerceIn(0, full.width - 1),
            bounds.top.toInt().coerceIn(0, full.height - 1),
            bounds.width.toInt().coerceIn(1, full.width),
            bounds.height.toInt().coerceIn(1, full.height),
        )
        if (crop !== full) full.recycle()
        return ByteArrayOutputStream().use { out ->
            crop.compress(Bitmap.CompressFormat.PNG, 100, out)
            crop.recycle()
            out.toByteArray()
        }
    }

    /** 形状的度量：亮色（primaryContainer）像素数。圆 → 圆角方形是"四角被填满"，面积必须变大。 */
    private fun filledArea(tag: String): Int {
        val bytes = frame(tag)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        var filled = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val sum = android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
                if (sum > 240) filled++
            }
        }
        bitmap.recycle()
        return filled
    }

    private fun save(name: String, bytes: ByteArray) {
        val target = File(directory, "$name.png")
        target.writeBytes(bytes)
        // 同时写到应用私有目录，方便用 run-as 取回当证据。
        val evidence = File(context.filesDir, "empty-state-evidence").apply { mkdirs() }
        File(evidence, "$name.png").writeBytes(bytes)
    }

    @Before fun setup() {
        directory.mkdirs()
    }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        directory.deleteRecursively()
    }

    @Test fun anEmptyLibraryShowsTheIllustrationAndItSettles() {
        lateinit var viewModel: HistoryViewModel
        compose.runOnUiThread {
            viewModel = HistoryViewModel(history, downloader())
            store.put("history", viewModel)
        }
        compose.setContent { VideoDownTheme { LibraryScreen(viewModel, onPlay = {}) } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(EMPTY_STATE_ILLUSTRATION_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // 入场动画有限：等它走完必须能 idle（无限动画在这里会表现不同）。
        compose.waitForIdle()
        Thread.sleep(700)
        compose.waitForIdle()
        save("empty-library", frame(EMPTY_STATE_ILLUSTRATION_TAG))
        assertTrue(File(directory, "empty-library.png").length() > 0)
    }

    @Test fun theSettledShapeIsTheRoundedSquare() {
        compose.setContent {
            VideoDownTheme { EmptyStateIllustration(enabled = true) }
        }
        compose.waitForIdle()
        Thread.sleep(700)
        compose.waitForIdle()
        val settled = filledArea(EMPTY_STATE_ILLUSTRATION_TAG)
        save("empty-state-settled", frame(EMPTY_STATE_ILLUSTRATION_TAG))

        // 不比较两张不同时刻的截图（真机上 takeScreenshot 会等待界面稳定并推进动画，
        // 「起点」往往已经是终态）。改为量一个时间无关的性质：终态必须**不是圆**。
        // 形状内切于边长 = 控件边长的正方形：圆的面积约 π/4 ≈ 0.785，圆角方形明显更大。
        val side = compose.onNodeWithTag(EMPTY_STATE_ILLUSTRATION_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        val circleArea = (Math.PI / 4.0) * side * side
        assertTrue(
            "终态应该已经是圆角方形（四角被填满），不能停在圆上（settled=$settled 圆≈${circleArea.toInt()}）",
            settled > circleArea * 1.08,
        )
    }

    @Test fun theGateLandsOnTheSameFinalShape() {
        // 并排渲染：开着总开关的一张（时钟不停，走完动画）与关掉总开关的一张（直接落终态）。
        compose.setContent {
            VideoDownTheme {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.testTag("gate-on")) { EmptyStateIllustration(enabled = true) }
                    Box(Modifier.testTag("gate-off")) { EmptyStateIllustration(enabled = false) }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(700)
        compose.waitForIdle()

        // 关掉总开关不是"不做这个形状"，而是"不播放过程、直接给结果"：静止后两者必须一致。
        val on = filledArea("gate-on")
        val off = filledArea("gate-off")
        // 不要求逐像素相等：两块插画相隔 16dp，裁剪窗口的亚像素对齐会让抗锯齿边缘有几百像素出入。
        val tolerance = (on * 0.02).toInt()
        assertTrue(
            "总开关关闭时应当直接落在与动画相同的终态（on=$on off=$off）",
            kotlin.math.abs(on - off) <= tolerance,
        )
        save("empty-state-gate-off", frame("gate-off"))
    }
}
