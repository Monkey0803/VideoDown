package com.videodown.douyin

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.presentation.theme.VideoDownTheme
import com.videodown.douyin.presentation.viewer.ImageViewerScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** 看图器：真实图片可缩放显示，文件缺失时给明确提示而不是空白。 */
@RunWith(AndroidJUnit4::class)
class ImageViewerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "image-viewer-${UUID.randomUUID()}",
    ).apply { mkdirs() }

    @After fun cleanup() {
        directory.deleteRecursively()
    }

    private fun pngFile(): File {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFF7043.toInt())
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        return File(directory, "photo.png").apply { writeBytes(bytes) }
    }

    /** 证据截图落在应用私有目录，测试后用 `adb run-as ... cat` 取回。 */
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val target = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "image-evidence").apply { mkdirs() }
        java.io.FileOutputStream(File(target, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun record(file: File?) = DownloadRecord(
        id = "viewer",
        awemeId = "post_img1",
        title = "看图器的图片",
        fileName = file?.name.orEmpty(),
        filePath = file?.absolutePath,
        status = DownloadStatus.COMPLETED,
        mediaKind = MediaKind.IMAGE,
    )

    @Test fun anImageRecordRendersInTheZoomableViewer() {
        val file = pngFile()
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(record = record(file), onBack = {})
            }
        }
        compose.onNodeWithTag("image-viewer").assertIsDisplayed()
        compose.onNodeWithText("保存到相册").assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsDisplayed()
        capture("image-viewer.png")
    }

    @Test fun aMissingImageSaysSoInsteadOfShowingAnEmptyCanvas() {
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(record = record(null), onBack = {})
            }
        }
        compose.onNodeWithText("图片文件不存在").assertIsDisplayed()
    }
}
