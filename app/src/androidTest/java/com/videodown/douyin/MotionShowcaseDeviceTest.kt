package com.videodown.douyin

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.LibraryLayout
import com.videodown.douyin.core.LibrarySort
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.MainActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 动效展示：**不控时钟**，让动画按自然节奏走一遍，专门用来录屏给人看手感。
 *
 * 存在的理由：其它用例为了稳定都停了测试时钟或只截少数几帧，看不出"动得好不好看"。
 * 这条把已存空状态 morph、共享元素形变、列表拖拽三件事连起来放一遍。
 *
 * 录屏方法（真机动画缩放保持 1.0）：
 * ```
 * adb shell screenrecord --time-limit 18 --bit-rate 2000000 /sdcard/motion.mp4 &
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.MotionShowcaseDeviceTest
 * adb pull /sdcard/motion.mp4
 * ```
 */
@RunWith(AndroidJUnit4::class)
class MotionShowcaseDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val directory = File(context.cacheDir, "motion-showcase-${System.nanoTime()}")
    // 必须写**全局**仓库：界面读的是 ServiceLocator 那一份，隔离 store 界面根本看不到。
    private val history get() = ServiceLocator.historyRepository
    private var previousSort = LibrarySort.NEWEST
    private var previousLayout = LibraryLayout.WATERFALL

    private val title = "动效展示"

    @Before fun setup() {
        previousSort = ServiceLocator.preferences.librarySort
        previousLayout = ServiceLocator.preferences.libraryLayout
        directory.mkdirs()
        // 拖拽把手只在自定义排序 + 列表布局下出现。
        ServiceLocator.preferences.librarySort = LibrarySort.CUSTOM
        ServiceLocator.preferences.libraryLayout = LibraryLayout.LIST
    }

    @After fun cleanup() {
        runCatching {
            runBlocking {
                history.snapshot().filter { it.title == title }.forEach { history.delete(it.id) }
            }
        }
        ServiceLocator.preferences.librarySort = previousSort
        ServiceLocator.preferences.libraryLayout = previousLayout
        directory.deleteRecursively()
    }

    private fun seed(id: String, cover: File, video: File) = runBlocking {
        history.upsert(
            DownloadRecord(
                id = id, awemeId = id, title = title, fileName = video.name, filePath = video.path,
                status = DownloadStatus.COMPLETED, coverUrl = "file://${cover.path}",
            ),
        )
    }

    @Test fun playTheMotionSetOnceForRecording() {
        // 1) 已存空状态：圆 → 圆角方形的入场 morph（真实节奏，约 400ms）。
        compose.onNodeWithTag("tab-library").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("还没有已保存的视频").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(1_500)

        // 2) 放进一条带封面的已存记录：封面加载期间的骨架屏扫光也顺便录进去。
        val id = "showcase-${UUID.randomUUID()}"
        val cover = File(directory, "$id.png").apply {
            val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).apply {
                drawColor(0xFF2E7D32.toInt())
                drawCircle(120f, 160f, 70f, android.graphics.Paint().apply { color = 0xFFFFD54F.toInt() })
            }
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val video = File(ServiceLocator.downloadPaths.downloadDir, "$id.mp4").apply {
            instrumentation.context.assets.open("landscape-fixture.mp4").use { input ->
                outputStream().use { input.copyTo(it) }
            }
        }
        seed(id, cover, video)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1_500)

        // 3) 点封面进播放器：共享元素形变（自然 250ms）。
        compose.onNodeWithText(title).performClick()
        // 播放器没有稳定的常驻 tag（player-surface 是条件性的），用"已离开已存页"判断。
        compose.waitUntil(10_000) { compose.onAllNodesWithText("搜索标题或作者").fetchSemanticsNodes().isEmpty() }
        Thread.sleep(2_500)

        // 4) 回到已存拖一次封面：列表重排动画。
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library-drag-$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val handle = compose.onNodeWithTag("library-drag-$id", useUnmergedTree = true).fetchSemanticsNode()
        compose.onNodeWithTag("library-drag-$id", useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(0f, -handle.boundsInRoot.height / 8f)) }
            up()
        }
        Thread.sleep(1_500)
        assertTrue("展示流程应当走完并停在已存页", compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
    }
}
