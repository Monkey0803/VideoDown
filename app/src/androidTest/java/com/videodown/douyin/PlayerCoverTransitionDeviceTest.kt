package com.videodown.douyin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.MainActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「已存封面 → 播放器封面」共享元素的中途取帧。
 *
 * 形变好不好看只有肉眼能判，所以这条用例的价值在证据而不在断言：把 Compose 动画时钟停住，
 * 在 60 / 150 / 270 ms 三个时刻各截一张图，交给人看。同时断言最终确实进了播放器，
 * 免得导航坏掉时用例静默通过。
 *
 * 夹具是真的：真封面图（运行时画一张显眼的绿色图，`file://` 给 Coil）、真视频文件、
 * 写进真实 history store，所以走的是与用户完全相同的「已存 → 播放器」路径。用例结束会清理干净。
 */
@RunWith(AndroidJUnit4::class)
class PlayerCoverTransitionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val title = "封面过渡验证"
    private val id = "cover-transition-${UUID.randomUUID()}"
    private lateinit var cover: File
    private lateinit var video: File

    @Before fun dropLeftoverFixtures() = purgeFixtures()

    @After fun cleanup() {
        purgeFixtures()
        runCatching { video.delete() }
        runCatching { cover.delete() }
    }

    /** 上一次运行若中途失败会留下同名记录，它同样带封面，会污染取帧与颜色统计。 */
    private fun purgeFixtures() {
        runCatching {
            runBlocking {
                ServiceLocator.historyRepository.snapshot()
                    .filter { it.title == title }
                    .forEach { ServiceLocator.historyRepository.delete(it.id) }
            }
        }
    }

    private fun seed() {
        cover = File(context.cacheDir, "$id.png").apply {
            val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(0xFFC2185B.toInt())
                drawCircle(120f, 160f, 70f, Paint().apply { color = 0xFF00BCD4.toInt() })
            }
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        video = File(ServiceLocator.downloadPaths.downloadDir, "$id.mp4").apply {
            instrumentation.context.assets.open("landscape-fixture.mp4").use { input ->
                outputStream().use { input.copyTo(it) }
            }
        }
        runBlocking {
            ServiceLocator.historyRepository.upsert(
                DownloadRecord(
                    id = id, awemeId = id, title = title, fileName = video.name, filePath = video.path,
                    status = DownloadStatus.COMPLETED, coverUrl = "file://${cover.path}",
                ),
            )
        }
    }

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(context.filesDir, "cover-transition").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun sharedCoverMorphIsCapturedMidFlight() {
        seed()
        compose.onNodeWithTag("tab-library").performClick()
        Thread.sleep(1200)
        compose.waitForIdle()
        capture("library-before-tap")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        // 等 Coil 把封面解码出来，否则形变里只有一块占位底色。
        Thread.sleep(700)
        compose.waitForIdle()

        // 停住动画时钟：点进去之后，由我们决定停在第几毫秒取帧。
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(title).performClick()
        compose.mainClock.advanceTimeBy(60); capture("frame-060ms")
        compose.mainClock.advanceTimeBy(90); capture("frame-150ms")
        compose.mainClock.advanceTimeBy(120); capture("frame-270ms")
        compose.mainClock.autoAdvance = true

        // 过渡走完必须真的离开「已存」：搜索框只在这一屏出现。
        // 这里的文案用字面量而不用 getString：targetContext 的资源配置走系统语言（en-US），
        // 而界面按应用语言渲染中文，用 getString 会比出一串英文。
        compose.waitUntil(10_000) { compose.onAllNodesWithText("搜索标题或作者").fetchSemanticsNodes().isEmpty() }
        capture("after-transition")
        assertTrue(compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
    }
}
