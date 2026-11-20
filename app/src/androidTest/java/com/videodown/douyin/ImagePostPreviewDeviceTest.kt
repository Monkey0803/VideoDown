package com.videodown.douyin

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.model.SubtitleSelection
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.home.HomeScreen
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * 图片作品在「保存前」就可以在应用内翻页预览并勾选，而不是先去原帖或先下载再回来挑。
 */
class ImagePostPreviewDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val models = ViewModelStore()
    private val directory = File(instrumentation.targetContext.cacheDir, "image-preview-${UUID.randomUUID()}").apply { mkdirs() }
    private lateinit var model: HomeViewModel

    private fun png(color: Int): File = File(directory, "image-$color.png").apply {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            writeBytes(out.toByteArray())
        }
    }

    @Before fun show() {
        val files = listOf(png(0xFFC62828.toInt()), png(0xFF1565C0.toInt()), png(0xFF2E7D32.toInt()))
        val images = files.mapIndexed { index, file ->
            DouyinVideo(
                awemeId = "image-post-${index + 1}",
                title = "图片作品标题",
                mediaLabel = "第 ${index + 1} 张",
                variants = listOf(
                    VideoVariant(file.toURI().toString(), "原图", kind = MediaKind.IMAGE),
                ),
            )
        }
        val repository = object : VideoRepository {
            override suspend fun parseShareText(shareText: String) = AppResult.Success(images.first())
        }
        val downloader = object : Downloader by com.videodown.douyin.core.ServiceLocator.downloader {
            override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
            override suspend fun findDuplicate(video: DouyinVideo, variant: VideoVariant) = null
            override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection): String = "task"
            override fun observeRecord(id: String) = flowOf(null)
        }
        compose.runOnUiThread {
            model = HomeViewModel(repository, downloader, { it.defaultVariant }, { null })
            models.put("preview", model)
            model.acceptBrowserResult(ParsedVideos(images))
            compose.activity.setContent {
                VideoDownTheme { HomeScreen(null, {}, {}, {}, {}, emptyList(), model) }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("图片作品标题").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val target = File(instrumentation.targetContext.filesDir, "image-preview-evidence").apply { mkdirs() }
        File(target, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @After fun close() {
        compose.runOnUiThread { models.clear() }
        directory.deleteRecursively()
    }

    @Test fun previewPagesImagesAndTogglesTheSaveSelection() {
        compose.onNodeWithContentDescription("预览图片").performClick()
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        compose.onNodeWithText("第 1 张").assertIsDisplayed()

        capture("image-post-preview.png")
        compose.onNodeWithContentDescription("加入保存列表").performClick()
        capture("image-post-preview-selected.png")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("当前 1/3 · 已选 1 个").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithText("当前 1/3 · 已选 1 个").fetchSemanticsNodes().isNotEmpty())
    }
}
