package com.videodown.douyin

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.VaultAuthenticator
import com.videodown.douyin.data.image.ImageMetadataReader
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.LivePhotoMotion
import com.videodown.douyin.presentation.theme.VideoDownTheme
import com.videodown.douyin.presentation.viewer.ImageViewerScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** 图片中心：真实渲染、多图导航、控制栏、删除前进与保险箱分区。 */
@RunWith(AndroidJUnit4::class)
class ImageViewerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "image-viewer-${UUID.randomUUID()}",
    ).apply { mkdirs() }
    private var originalAuthenticator: VaultAuthenticator? = null

    @After fun cleanup() {
        originalAuthenticator?.let { ServiceLocator.vaultAuthenticator = it }
        ServiceLocator.preferences.imageSlideshowIntervalSeconds = 5
        directory.deleteRecursively()
    }

    private fun pngFile(name: String = "photo.png", color: Int = 0xFFFF7043.toInt()): File {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        return File(directory, name).apply { writeBytes(bytes) }
    }

    private fun motionFile(): File = File(directory, "photo_motion.mov").apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("landscape-fixture.mp4").use { input ->
            outputStream().use(input::copyTo)
        }
    }

    private fun jpegWithExif(): File {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF26A69A.toInt()) }
        val file = File(directory, "exif-photo.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Test Camera")
            setAttribute(ExifInterface.TAG_MODEL, "Model 2026")
            setLatLong(31.230416, 121.473701)
            saveAttributes()
        }
        return file
    }

    /** 证据截图落在应用私有目录，测试后用 `adb run-as ... cat` 取回。 */
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val target = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "image-evidence").apply { mkdirs() }
        java.io.FileOutputStream(File(target, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun record(
        file: File?,
        id: String = "viewer",
        title: String = "看图器的图片",
        updatedAt: Long = 1L,
        private: Boolean = false,
        motion: File? = null,
    ) = DownloadRecord(
        id = id,
        awemeId = "post_img1",
        title = title,
        fileName = file?.name.orEmpty(),
        filePath = file?.absolutePath,
        status = DownloadStatus.COMPLETED,
        mediaKind = MediaKind.IMAGE,
        updatedAt = updatedAt,
        isPrivate = private,
        livePhotoMotion = motion?.let {
            LivePhotoMotion("https://video.weibo.com/live.mov", fileName = it.name, filePath = it.absolutePath)
        },
    )

    @Test fun anImageRecordRendersInTheZoomableViewer() {
        val file = pngFile()
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(record = record(file), onBack = {})
            }
        }
        compose.onNodeWithTag("image-viewer-viewer").assertIsDisplayed()
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

    @Test fun allSavedImagesPageWithThumbnailsAndDeleteAdvances() {
        val first = record(pngFile("first.png"), id = "first", title = "第一张", updatedAt = 20L)
        val second = record(pngFile("second.png", 0xFF42A5F5.toInt()), id = "second", title = "第二张", updatedAt = 10L)
        val records = mutableStateOf(listOf(first, second))
        var backed = false
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = records.value,
                    initialRecordId = first.id,
                    onBack = { backed = true },
                    onDelete = { id, done ->
                        records.value = records.value.filterNot { it.id == id }
                        done(Result.success(Unit))
                    },
                    autoHideMillis = 30_000L,
                )
            }
        }

        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithTag("image-viewer-thumbnails").assertIsDisplayed()
        compose.onNodeWithTag("image-viewer-first").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-thumbnail-first").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("删除").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(5_000) { records.value.size == 1 }
        compose.onNodeWithText("1 / 1").assertIsDisplayed()
        compose.onNodeWithText("第二张").assertIsDisplayed()
        assertEquals(false, backed)
    }

    @Test fun controlsAutoHideAndReturnOnImageTap() {
        val item = record(pngFile(), title = "自动隐藏")
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = listOf(item),
                    initialRecordId = item.id,
                    onBack = {},
                    onDelete = { _, done -> done(Result.success(Unit)) },
                    autoHideMillis = 1_000L,
                )
            }
        }

        compose.onNodeWithText("自动隐藏").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("自动隐藏").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("image-viewer-viewer").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(350L)
        compose.waitForIdle()
        compose.onNodeWithText("自动隐藏").assertIsDisplayed()
    }

    @Test fun swipingToTheNextImageKeepsImmersiveControlsHidden() {
        val first = record(pngFile("immersive-first.png"), id = "immersive-first", title = "沉浸第一张", updatedAt = 20L)
        val second = record(
            pngFile("immersive-second.png", 0xFF42A5F5.toInt()),
            id = "immersive-second",
            title = "沉浸第二张",
            updatedAt = 10L,
        )
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = listOf(first, second),
                    initialRecordId = first.id,
                    onBack = {},
                    onDelete = { _, done -> done(Result.success(Unit)) },
                    autoHideMillis = 1_000L,
                )
            }
        }

        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("image-viewer-immersive-first").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(true, compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isEmpty())
    }

    @Test fun privateViewerAuthenticatesAndNeverMixesPublicImages() {
        class FakeGate : VaultAuthenticator {
            var calls = 0
            override fun isAvailable(context: Context) = true
            override fun authenticate(activity: Activity, onResult: (Boolean) -> Unit): Boolean {
                calls++
                onResult(true)
                return true
            }
        }
        originalAuthenticator = ServiceLocator.vaultAuthenticator
        val gate = FakeGate()
        ServiceLocator.vaultAuthenticator = gate
        val private = record(pngFile("private.png"), id = "private", title = "私密图片", updatedAt = 20L, private = true)
        val public = record(pngFile("public.png"), id = "public", title = "公开图片", updatedAt = 30L)

        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = listOf(public, private),
                    initialRecordId = private.id,
                    onBack = {},
                    onDelete = { _, done -> done(Result.success(Unit)) },
                    autoHideMillis = 30_000L,
                )
            }
        }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("私密图片").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 / 1").assertIsDisplayed()
        assertEquals(true, compose.onAllNodesWithText("公开图片").fetchSemanticsNodes().isEmpty())
        assertEquals(1, gate.calls)
    }

    @Test fun livePhotoAutomaticallyPlaysItsPairedMotionClipInALoop() {
        val item = record(pngFile(), title = "Live Photo", motion = motionFile())
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = listOf(item),
                    initialRecordId = item.id,
                    onBack = {},
                    onDelete = { _, done -> done(Result.success(Unit)) },
                    autoHideMillis = 30_000L,
                )
            }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("停止").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("live-photo-motion").assertIsDisplayed()
        compose.onNodeWithText("停止").assertIsDisplayed()
    }

    @Test fun slideshowLoopsAndDateGridJumpsToSelectedImage() {
        ServiceLocator.preferences.imageSlideshowIntervalSeconds = 3
        val first = record(pngFile("slide-first.png"), id = "slide-first", title = "幻灯第一张", updatedAt = 20L)
        val second = record(pngFile("slide-second.png"), id = "slide-second", title = "幻灯第二张", updatedAt = 10L)
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = listOf(first, second),
                    initialRecordId = first.id,
                    onBack = {},
                    onDelete = { _, done -> done(Result.success(Unit)) },
                    autoHideMillis = 30_000L,
                    slideshowSecondMillis = 50L,
                )
            }
        }

        compose.onNodeWithTag("image-slideshow-toggle").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-slideshow-toggle").performClick()
        compose.onNodeWithText("按日期查找").performClick()
        compose.onNodeWithTag("image-date-grid").assertIsDisplayed()
        compose.onNodeWithTag("date-grid-slide-first").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun infoSheetReadsExifAndGpsFromTheRealImageFile() {
        val file = jpegWithExif()
        val metadata = ImageMetadataReader.read(file)
        assertTrue(metadata.fields.any { it.label == "Make" && it.value == "Test Camera" })
        assertTrue(metadata.fields.any { it.label == "GPS" && it.value == "31.230416, 121.473701" })
        val item = record(file, title = "带 EXIF 的图片")
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = listOf(item),
                    initialRecordId = item.id,
                    onBack = {},
                    onDelete = { _, done -> done(Result.success(Unit)) },
                    autoHideMillis = 30_000L,
                )
            }
        }

        compose.onNodeWithContentDescription("图片信息").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("image-info-content").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-info-content").performScrollToIndex(1)
        compose.onNodeWithText("EXIF / XMP").assertIsDisplayed()
    }
}
