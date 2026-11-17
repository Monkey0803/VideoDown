package com.videodown.douyin

import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.ArchivePublisher
import com.videodown.douyin.data.download.DownloadEngine
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.download.ResumableDownloader
import com.videodown.douyin.data.gallery.GalleryExportFailure
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.ArchiveEntry
import com.videodown.douyin.domain.model.ArchiveRequest
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.data.gallery.GalleryAutoSave
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * 打包下载：一次任务产出 ZIP、条目齐全、发布到公共下载目录，重复检查能命中同帖同张数。
 */
@RunWith(AndroidJUnit4::class)
class ImageArchiveDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val directory = File(context.cacheDir, "image-archive-${UUID.randomUUID()}").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
        DownloadPaths(directory), scope, "", "VideoDown-Test", validateFile = { file, record ->
        com.videodown.douyin.data.download.validateDownloadedFile(file, record)
    })

    @After fun cleanup() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        directory.deleteRecursively()
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF00897B.toInt())
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun request(port: Int) = ArchiveRequest(
        title = "九宫格打包",
        postId = "post-archive",
        platform = VideoPlatform.WEIBO,
        postPageUrl = "https://weibo.com/1/post-archive",
        entries = (0 until 3).map { index ->
            ArchiveEntry("http://127.0.0.1:$port/image-$index.png", "png", referer = "https://weibo.com/")
        },
    )

    @Test fun oneArchiveTaskProducesACompleteZipAndPublishesItToDownloads() = runBlocking {
        ImageFixtureServer(png()).use { server ->
            val id = engine.startArchive(request(server.port))
            val completed = withTimeout(60_000) {
                history.observeRecord(id).first { it?.status == DownloadStatus.COMPLETED }!!
            }
            assertEquals(MediaKind.ARCHIVE, completed.mediaKind)
            assertTrue(completed.fileName.endsWith(".zip"))
            val zip = File(completed.filePath!!)
            ZipFile(zip).use { archive ->
                assertEquals(3, archive.entries().toList().size)
            }
            assertEquals("same post and count is a duplicate", id, engine.findArchiveDuplicate(request(server.port))?.id)

            // 发布到 Download/拾影：API 29+ 走 MediaStore.Downloads。
            val landing = ArchivePublisher(context, ServiceLocator.exportReceipts).publish(completed)
            assertEquals(GalleryAutoSave.Saved, landing)
            val row = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(completed.fileName),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
            }
            assertNotNull("the zip should be visible in Downloads", row)
            context.contentResolver.delete(
                android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row!!.first),
                null,
                null,
            )
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "archive: ${completed.fileName} ${zip.length()} bytes, 3 entries\n")
            })
        }
    }

    @Test fun anArchiveRequiresTheSameEntryCountForDuplicateDetection() = runBlocking {
        ImageFixtureServer(png()).use { server ->
            val id = engine.startArchive(request(server.port))
            withTimeout(60_000) { history.observeRecord(id).first { it?.status == DownloadStatus.COMPLETED } }
            assertEquals(id, engine.findArchiveDuplicate(request(server.port))?.id)
            val three = request(server.port)
            val two = three.copy(entries = three.entries.take(2))
            assertEquals(null, engine.findArchiveDuplicate(two))
        }
    }

    @Test fun aBrokenImageAddressFailsTheArchiveWithoutPublishingAZip() = runBlocking {
        val request = ArchiveRequest(
            title = "坏地址",
            postId = "post-broken",
            platform = VideoPlatform.WEIBO,
            postPageUrl = "https://weibo.com/1/post-broken",
            entries = listOf(ArchiveEntry("http://127.0.0.1:1/missing.png", "png")),
        )
        val id = engine.startArchive(request)
        val failed = withTimeout(60_000) { history.observeRecord(id).first { it?.status == DownloadStatus.FAILED }!! }
        assertEquals(null, failed.filePath)
        assertTrue(
            "no zip may be published for a failed archive",
            File(directory, "Douyin").listFiles().orEmpty().none { it.name.endsWith(".zip") },
        )
    }

    @Test fun imageGalleryExportStillRejectsArchives() = runBlocking {
        val record = DownloadRecord(
            id = "archive", awemeId = "post", title = "压缩包", fileName = "a.zip",
            filePath = File(directory, "a.zip").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }.absolutePath,
            status = DownloadStatus.COMPLETED, mediaKind = MediaKind.ARCHIVE,
        )
        val result = ServiceLocator.galleryExporter.export(record)
        assertTrue(result.isFailure)
        assertEquals(
            GalleryExportFailure.UNSUPPORTED_CONTAINER,
            (result.exceptionOrNull() as? com.videodown.douyin.data.gallery.GalleryExportException)?.failure,
        )
    }
}
