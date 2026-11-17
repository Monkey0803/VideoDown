package com.videodown.douyin

import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.LivePhotoMotion
import com.videodown.douyin.domain.model.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * 图片记录存进系统相册：目录是 `Pictures/拾影`，MIME 与扩展名一致；压缩包不走相册。
 */
@RunWith(AndroidJUnit4::class)
class ImageGalleryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val directory = File(context.cacheDir, "image-gallery-${UUID.randomUUID()}").apply { mkdirs() }

    @After fun cleanup() {
        directory.deleteRecursively()
    }

    private fun pngFile(): File {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFB8C00.toInt())
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        return File(directory, "gallery-${UUID.randomUUID()}.png").apply { writeBytes(bytes) }
    }

    @Test fun anImageRecordLandsInPicturesShiyingWithoutAskingForAStoragePermission() = runBlocking {
        val source = pngFile()
        val record = DownloadRecord(
            id = "image", awemeId = "post_img1", title = "图片", fileName = source.name,
            filePath = source.absolutePath, status = DownloadStatus.COMPLETED, mediaKind = MediaKind.IMAGE,
        )
        val result = ServiceLocator.galleryExporter.export(record)
        assertTrue(result.exceptionOrNull()?.toString().orEmpty(), result.isSuccess)
        val uri = result.getOrThrow().uri
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH),
            null, null, null,
        )?.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(source.name, cursor.getString(0))
            assertEquals("image/png", cursor.getString(1))
            assertEquals("Pictures/拾影/", cursor.getString(2))
        } ?: throw AssertionError("the saved image is not queryable")
        assertNotNull(uri)
        context.contentResolver.delete(uri, null, null)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "gallery image: ${source.name} saved under Pictures/拾影\n")
        })
    }

    @Test fun aLivePhotoExportsItsStillAndMotionToTheirMediaCollections() = runBlocking {
        val still = pngFile()
        val motion = File(directory, "${still.nameWithoutExtension}_motion.mov").apply {
            writeBytes(instrumentation.context.assets.open("landscape-fixture.mp4").use { it.readBytes() })
        }
        val record = DownloadRecord(
            id = "live", awemeId = "post_live", title = "实况照片", fileName = still.name,
            filePath = still.absolutePath, status = DownloadStatus.COMPLETED, mediaKind = MediaKind.IMAGE,
            livePhotoMotion = LivePhotoMotion(
                sourceUrl = "https://example.test/live.mov",
                fileName = motion.name,
                filePath = motion.absolutePath,
            ),
        )

        val stillUri = ServiceLocator.galleryExporter.export(record).getOrThrow().uri
        val motionUri = context.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(motion.name),
            null,
        )?.use { cursor ->
            assertTrue(cursor.moveToFirst())
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                .buildUpon().appendPath(cursor.getLong(0).toString()).build()
        } ?: throw AssertionError("the live photo motion is not queryable")
        context.contentResolver.query(
            motionUri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            null, null, null,
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(motion.name, cursor.getString(0))
            // 测试素材内容是 MP4，MediaStore 会覆盖写入时声明的 video/quicktime。
            assertTrue(cursor.getString(1).startsWith("video/"))
            assertEquals("Movies/拾影/", cursor.getString(2))
        }
        context.contentResolver.delete(stillUri, null, null)
        context.contentResolver.delete(motionUri, null, null)
        Unit
    }

    @Test fun anIncompleteLivePhotoNeverExportsOnlyItsStill() = runBlocking {
        val still = pngFile()
        val record = DownloadRecord(
            id = "incomplete-live", awemeId = "post_incomplete", title = "不完整实况照片",
            fileName = still.name, filePath = still.absolutePath, status = DownloadStatus.COMPLETED,
            mediaKind = MediaKind.IMAGE,
            livePhotoMotion = LivePhotoMotion(sourceUrl = "https://example.test/missing.mov"),
        )

        assertTrue(ServiceLocator.galleryExporter.export(record).isFailure)
        val count = context.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(still.name),
            null,
        )?.use { it.count } ?: 0
        assertEquals(0, count)
    }
}
