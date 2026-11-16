package com.videodown.douyin

import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
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
        val uri = result.getOrThrow()
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
}
