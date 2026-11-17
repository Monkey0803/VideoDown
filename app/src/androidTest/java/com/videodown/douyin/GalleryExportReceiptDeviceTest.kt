package com.videodown.douyin

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.download.ArchivePublisher
import com.videodown.douyin.data.gallery.GalleryExportOutcome
import com.videodown.douyin.data.gallery.GalleryExportReceiptStore
import com.videodown.douyin.data.gallery.GalleryExporter
import com.videodown.douyin.data.gallery.exportTargetExists
import com.videodown.douyin.data.gallery.GalleryAutoSave
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.LivePhotoMotion
import com.videodown.douyin.domain.model.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * I3：相册与公共下载目录的导出幂等化。
 *
 * 全部走真实 MediaStore：第一次导出写一份、第二次返回「已保存」且不新增条目、用户删掉目标或
 * 把源文件换掉之后回执自动失效、Live Photo 两半按事务记录。用唯一的显示名统计条目数量，
 * 避免和同一台设备上其它用例留下的媒体混在一起。
 */
@RunWith(AndroidJUnit4::class)
class GalleryExportReceiptDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val ownedNames = mutableSetOf<String>()
    private val store by lazy {
        GalleryExportReceiptStore(
            file = File(context.cacheDir, "export-receipts-${UUID.randomUUID()}.json"),
            targetExists = { target -> exportTargetExists(resolver, target) },
        )
    }
    private val exporter by lazy { GalleryExporter(context, store) }

    @After fun cleanup() {
        ownedNames.forEach { name -> ownedEntries(name).forEach { resolver.delete(it, null, null) } }
    }

    private fun png(name: String): File {
        ownedNames += name
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun binary(name: String, bytes: Int = 256): File {
        ownedNames += name
        return File(context.cacheDir, name).apply { writeBytes(ByteArray(bytes) { 9 }) }
    }

    private fun zip(name: String): File {
        ownedNames += name
        val file = File(context.cacheDir, name)
        ZipOutputStream(file.outputStream()).use { archive ->
            archive.putNextEntry(ZipEntry("001.jpg"))
            archive.write(ByteArray(64) { 1 })
            archive.closeEntry()
        }
        return file
    }

    private fun imageEntry(name: String): Uri? = resolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(name), null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        } else null
    }

    private fun videoEntry(name: String): Uri? = resolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),
        "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name), null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        } else null
    }

    private fun downloadEntry(name: String): Uri? = resolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Downloads._ID),
        "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        } else null
    }

    /**
     * MediaStore 对同一目录下的同名文件会自动改名（`x.png` → `x (1).png`），
     * 所以「又写了一份」只能按去掉扩展名的前缀数，不能按精确文件名数。
     */
    private fun stem(name: String): String = name.substringBeforeLast('.')

    private fun ownedEntries(name: String): List<Uri> = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
    ).flatMap { collection ->
        resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("${stem(name)}%"), null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)))
                }
            }
        }.orEmpty()
    }

    private fun countImages(name: String): Int = resolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(name), null,
    )?.use { it.count } ?: 0

    private fun countImagesLike(name: String): Int = resolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("${stem(name)}%"), null,
    )?.use { it.count } ?: 0

    private fun countVideosLike(name: String): Int = resolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),
        "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?", arrayOf("${stem(name)}%"), null,
    )?.use { it.count } ?: 0

    private fun record(id: String, image: File, motion: LivePhotoMotion? = null) = DownloadRecord(
        id = id,
        awemeId = "post-$id",
        title = "回执用例",
        fileName = image.name,
        filePath = image.absolutePath,
        status = DownloadStatus.COMPLETED,
        mediaKind = if (motion != null) MediaKind.IMAGE else MediaKind.IMAGE,
        livePhotoMotion = motion,
    )

    @Test fun aSecondSaveIsReportedAsAlreadySavedAndWritesNothing() = runBlocking {
        val id = "receipt-image-${UUID.randomUUID()}"
        val image = png("receipt-still-${UUID.randomUUID()}.png")
        val record = record(id, image)

        val first = exporter.export(record).getOrThrow()
        assertTrue(first is GalleryExportOutcome.Saved)
        assertEquals(1, countImages(image.name))

        val second = exporter.export(record).getOrThrow()
        assertTrue("第二次保存必须认出来", second is GalleryExportOutcome.AlreadySaved)
        assertEquals(first.uri, second.uri)
        assertEquals("相册里不能出现第二份", 1, countImages(image.name))

        // 用户从系统相册删掉目标：回执自动失效，下一次真的重新写入。
        resolver.delete(first.uri, null, null)
        val third = exporter.export(record).getOrThrow()
        assertTrue(third is GalleryExportOutcome.Saved)
        assertNotEquals(first.uri, third.uri)
        assertEquals(1, countImages(image.name))

        // 同名不同内容不能被当成「已保存」：不动相册里的副本，直接改源文件。
        image.appendBytes(ByteArray(64) { 4 })
        val fourth = exporter.export(record).getOrThrow()
        assertTrue("源文件换了内容就是新的一份", fourth is GalleryExportOutcome.Saved)
        assertNotEquals(third.uri, fourth.uri)
        assertEquals("相册里应有两份内容不同的副本", 2, countImagesLike(image.name))
    }

    @Test fun aLivePhotoReceiptCoversBothHalvesAndExpiresWithEither() = runBlocking {
        val id = "receipt-live-${UUID.randomUUID()}"
        val stillName = "receipt-live-${UUID.randomUUID()}.jpg"
        val motionName = "receipt-live-${UUID.randomUUID()}.mov"
        val still = png(stillName) // 内容无关；导出只看容器扩展名
        val motionFile = binary(motionName)
        val motion = LivePhotoMotion(
            sourceUrl = "https://example.test/motion.mov",
            fileName = motionName,
            filePath = motionFile.absolutePath,
        )
        val record = record(id, still, motion)

        val first = exporter.export(record).getOrThrow()
        assertTrue(first is GalleryExportOutcome.Saved)
        assertEquals(1, countImages(stillName))
        assertEquals(1, countVideos(motionName))
        val firstTargets = checkNotNull(store.rawReceipt(id)).targets
        assertEquals(2, firstTargets.size)

        assertTrue(exporter.export(record).getOrThrow() is GalleryExportOutcome.AlreadySaved)
        assertEquals(1, countImages(stillName))

        // 只删掉 MOV：整对回执失效，下一次把成对的两份重新写一遍（不留半套回执）。
        videoEntry(motionName)?.let { resolver.delete(it, null, null) }
        assertTrue(exporter.export(record).getOrThrow() is GalleryExportOutcome.Saved)
        // 旧静帧还在，新的一份被 MediaStore 改名；MOV 的旧条目是用户删掉的，所以只有新的一份。
        assertEquals(2, countImagesLike(stillName))
        assertEquals(1, countVideosLike(motionName))
        val secondTargets = checkNotNull(store.rawReceipt(id)).targets
        assertEquals(2, secondTargets.size)
        assertTrue("成对重写：两个目标都必须换新", secondTargets.none { it in firstTargets })
        secondTargets.forEach { assertTrue("回执目标必须真的存在: $it", exportTargetExists(resolver, it)) }
    }

    @Test fun aFailedMotionPublishLeavesNoStillAndNoReceipt() = runBlocking {
        val id = "receipt-half-${UUID.randomUUID()}"
        val stillName = "receipt-half-${UUID.randomUUID()}.jpg"
        val badMotionName = "receipt-half-${UUID.randomUUID()}.txt"
        val still = png(stillName)
        val motion = LivePhotoMotion(
            sourceUrl = "https://example.test/bad.txt",
            extension = "txt",
            fileName = badMotionName,
            filePath = binary(badMotionName).absolutePath,
        )
        val record = record(id, still, motion)

        val result = exporter.export(record)
        assertTrue("不支持的 MOV 容器必须失败，而不是只写静帧", result.isFailure)
        assertEquals("失败时静帧也要删掉", 0, countImages(stillName))
        assertNull("不能留下半套回执", store.rawReceipt(id))
    }

    @Test fun publishingTheSameArchiveTwiceKeepsOneDownloadCopy() = runBlocking {
        assumeTrue("公共下载目录走 MediaStore.Downloads 需要 API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val id = "receipt-archive-${UUID.randomUUID()}"
        val archiveName = "receipt-archive-${UUID.randomUUID()}.zip"
        val archive = zip(archiveName)
        val record = DownloadRecord(
            id = id,
            awemeId = "post-$id",
            title = "回执打包用例",
            fileName = archive.name,
            filePath = archive.absolutePath,
            status = DownloadStatus.COMPLETED,
            mediaKind = MediaKind.ARCHIVE,
        )
        val publisher = ArchivePublisher(context, store)

        assertEquals(GalleryAutoSave.Saved, publisher.publish(record))
        val first = downloadEntry(archiveName)
        assertTrue("ZIP 必须落在公共下载目录", first != null)
        assertEquals(1, countDownloads(archiveName))

        assertEquals(GalleryAutoSave.Saved, publisher.publish(record))
        assertEquals("重复发布不能再写一份", 1, countDownloads(archiveName))

        // 用户把下载目录里的 ZIP 删掉：回执失效，重新发布一次。
        resolver.delete(first!!, null, null)
        assertEquals(GalleryAutoSave.Saved, publisher.publish(record))
        assertEquals(1, countDownloads(archiveName))
    }

    private fun countVideos(name: String): Int = resolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),
        "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name), null,
    )?.use { it.count } ?: 0

    private fun countDownloads(name: String): Int = resolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Downloads._ID),
        "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null,
    )?.use { it.count } ?: 0
}
