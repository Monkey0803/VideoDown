package com.videodown.douyin

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollToNode
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
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.VaultAuthenticator
import com.videodown.douyin.data.image.ImageMetadataReader
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.LivePhotoMotion
import com.videodown.douyin.presentation.library.LibraryQuery
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
        favorite: Boolean = false,
        page: String = "",
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
        favorite = favorite,
        sourcePageUrl = page,
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
        compose.onNodeWithContentDescription("保险箱").assertIsDisplayed()
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
        onView(isAssignableFrom(android.view.TextureView::class.java)).check(matches(isDisplayed()))
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

    @Test fun filteredViewerOnlyPagesWithinTheActiveQuery() {
        val first = record(pngFile("fav-first.png"), id = "fav-first", title = "收藏一", updatedAt = 30L, favorite = true)
        val second = record(pngFile("fav-second.png", 0xFF42A5F5.toInt()), id = "fav-second", title = "收藏二", updatedAt = 20L, favorite = true)
        val other = record(pngFile("unfav.png", 0xFF66BB6A.toInt()), id = "unfav", title = "未收藏", updatedAt = 10L)
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = listOf(first, second, other),
                    initialRecordId = first.id,
                    onBack = {},
                    onDelete = { _, done -> done(Result.success(Unit)) },
                    query = LibraryQuery(favoritesOnly = true),
                    autoHideMillis = 30_000L,
                )
            }
        }

        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithTag("image-viewer-fav-first").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("收藏二").assertIsDisplayed()
        assertEquals(true, compose.onAllNodesWithText("未收藏").fetchSemanticsNodes().isEmpty())
        capture("image-viewer-filtered.png")
    }

    @Test fun corruptImageSaysItCannotBeShownInsteadOfStayingBlack() {
        val broken = File(directory, "broken.png").apply { writeBytes(ByteArray(64) { 0x5A }) }
        val item = record(broken, title = "损坏的图片")
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

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("这张图片无法显示").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("用其他应用打开").assertIsDisplayed()
        capture("image-viewer-failed.png")
    }

    @Test fun moreMenuSurfacesTheSameLibraryActions() {
        val item = record(pngFile(), title = "操作菜单")
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

        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("收藏图片").assertIsDisplayed()
        compose.onNodeWithText("重命名与分类").assertIsDisplayed()
        compose.onNodeWithText("移入保险箱").assertIsDisplayed()
        compose.onNodeWithText("导出到文件夹").assertIsDisplayed()
    }

    @Test fun dateGridCanSelectSeveralImagesAndCountsThem() {
        val first = record(pngFile("multi-first.png"), id = "multi-first", title = "多选一", updatedAt = 20L)
        val second = record(pngFile("multi-second.png", 0xFF42A5F5.toInt()), id = "multi-second", title = "多选二", updatedAt = 10L)
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = listOf(first, second),
                    initialRecordId = first.id,
                    onBack = {},
                    onDelete = { _, done -> done(Result.success(Unit)) },
                    autoHideMillis = 30_000L,
                )
            }
        }

        compose.onNodeWithText("按日期查找").performClick()
        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithTag("date-grid-multi-first").performClick()
        compose.onNodeWithTag("date-grid-multi-second").performClick()
        compose.onNodeWithText("已选 2 张").assertIsDisplayed()
        // 等系统剪贴板浮层等覆盖 UI 消失，截图里只留应用自己的界面。
        Thread.sleep(1_200)
        capture("image-viewer-date-grid-select.png")
        compose.onNodeWithText("取消多选").performClick()
        compose.onNodeWithText("已选 2 张").assertDoesNotExist()
    }

    @Test fun tappingALivePhotoOnlyTogglesControls() {
        val item = record(pngFile(), title = "实况图片", motion = motionFile())
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

        compose.waitUntil(5_000) { compose.onAllNodesWithText("停止").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("live-photo-tap").performClick()
        compose.onNodeWithText("实况图片").assertDoesNotExist()
        compose.onNodeWithTag("live-photo-tap").performClick()
        compose.onNodeWithText("实况图片").assertIsDisplayed()
        compose.onNodeWithText("停止").assertIsDisplayed()
        capture("image-viewer-live-photo.png")
    }

    @Test fun animatedGifKeepsAnimatingInTheViewer() {
        val file = File(directory, "animated.gif").apply { writeBytes(ANIMATED_GIF_BYTES) }
        val item = record(file, id = "animated-gif", title = "动图")
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
        compose.waitForIdle()
        Thread.sleep(800)
        // 收起控制栏，屏幕里只剩动图；静态图时连续截图应完全一致。
        compose.onNodeWithTag("image-viewer-animated-gif").performTouchInput { click() }
        Thread.sleep(500)
        val target = InstrumentationRegistry.getInstrumentation().uiAutomation
        var differing = false
        val deadline = System.currentTimeMillis() + 5_000
        while (!differing && System.currentTimeMillis() < deadline) {
            val first = target.takeScreenshot() ?: break
            Thread.sleep(120)
            val second = target.takeScreenshot() ?: break
            differing = !first.sameAs(second)
            first.recycle()
            second.recycle()
        }
        assertTrue("GIF 应当逐帧刷新，而不是停在首帧", differing)
    }

    @Test fun infoSheetCopiesThePathAndOffersAMapLink() {
        val file = jpegWithExif()
        val item = record(file, title = "可操作信息")
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
        compose.onNodeWithTag("image-info-content").performScrollToNode(hasText("复制文件路径"))
        compose.onNodeWithText("复制文件路径").performClick()
        compose.onNodeWithText("已复制路径").assertIsDisplayed()
        compose.onNodeWithTag("image-info-content").performScrollToNode(hasText("在地图中打开"))
        compose.onNodeWithText("在地图中打开").assertIsDisplayed()
        // 清掉剪贴板，避免 Android 13+ 的剪贴板浮层出现在后续用例的截图里。
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.content.ClipboardManager::class.java)?.clearPrimaryClip()
    }

    @Test fun imageSemanticsAnnouncePositionInsideTheCollection() {
        val first = record(pngFile("position-first.png"), id = "position-first", title = "第一张", updatedAt = 20L)
        val second = record(pngFile("position-second.png", 0xFF42A5F5.toInt()), id = "position-second", title = "第二张", updatedAt = 10L)
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = listOf(first, second),
                    initialRecordId = first.id,
                    onBack = {},
                    onDelete = { _, done -> done(Result.success(Unit)) },
                    autoHideMillis = 30_000L,
                )
            }
        }

        compose.onNodeWithContentDescription("第 1 张，共 2 张：第一张").assertIsDisplayed()
    }

    @Test fun postPositionIsShownWhenTheImagesBelongToOnePost() {
        val first = record(pngFile("post-first.png"), id = "post-first", title = "同帖一", updatedAt = 20L, page = "https://example.com/post/1")
        val second = record(pngFile("post-second.png", 0xFF42A5F5.toInt()), id = "post-second", title = "同帖二", updatedAt = 10L, page = "https://example.com/post/1")
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = listOf(first, second),
                    initialRecordId = first.id,
                    onBack = {},
                    onDelete = { _, done -> done(Result.success(Unit)) },
                    autoHideMillis = 30_000L,
                )
            }
        }

        compose.onNodeWithText("1 / 2 · 本作品 1 / 2").assertIsDisplayed()
    }

    @Test fun dateGridOpensAtTheCurrentImageInsteadOfTheTop() {
        // 6 天 × 每天 4 张：3 列网格共 8 行，加上日期标题远超一屏。
        val records = (0 until 24).map { index ->
            val day = index / 4
            val slot = index % 4
            record(
                pngFile("located-$index.png", 0xFF000000.toInt() or (index * 0x000B0D11)),
                id = "located-$index",
                title = "定位 $index",
                updatedAt = 1_700_000_000_000L + day * 86_400_000L + slot * 60_000L,
            )
        }
        val current = records.first()
        compose.setContent {
            VideoDownTheme {
                ImageViewerScreen(
                    records = records,
                    initialRecordId = current.id,
                    onBack = {},
                    onDelete = { _, done -> done(Result.success(Unit)) },
                    autoHideMillis = 30_000L,
                )
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("24 / 24").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("按日期查找").performClick()
        compose.onNodeWithTag("image-date-grid").assertIsDisplayed()
        // 网格远超一屏；打开即滚到当前项，否则这项根本不会被组合出来。
        compose.onNodeWithTag("date-grid-located-0").assertIsDisplayed()
        capture("image-viewer-date-grid-located.png")
    }
}
