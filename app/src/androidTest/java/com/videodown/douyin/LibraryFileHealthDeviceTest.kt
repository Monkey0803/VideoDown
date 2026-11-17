package com.videodown.douyin

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.download.LibraryFileInspector
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.StoredFileHealth
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * I4：真实媒体上的已存文件健康检查。
 *
 * 覆盖三层：快照一致（健康）、文件被删（缺失）、文件被截断或改过（变化 / 损坏）。
 * 检查器默认就是生产用的实现（`validateDownloadedFile`），所以这里验证的是用户点「检查文件」
 * 时真正会发生的事，而不是一份平行的测试逻辑。
 */
@RunWith(AndroidJUnit4::class)
class LibraryFileHealthDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val inspector = LibraryFileInspector()
    private val directory = File(context.cacheDir, "file-health-${UUID.randomUUID()}").apply { mkdirs() }

    private fun png(name: String = "health-${UUID.randomUUID()}.png"): File {
        val file = File(directory, name)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun videoFixture(): File {
        val file = File(directory, "health-${UUID.randomUUID()}.mp4")
        instrumentation.context.assets.open("aac-video-fixture.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun record(file: File, mediaKind: MediaKind = MediaKind.IMAGE, snapshot: Boolean = true) = DownloadRecord(
        id = "health-${UUID.randomUUID()}",
        awemeId = "post",
        title = "健康检查",
        fileName = file.name,
        filePath = file.absolutePath,
        status = DownloadStatus.COMPLETED,
        mediaKind = mediaKind,
        totalBytes = file.length(),
        downloadedBytes = file.length(),
    ).copy(
        fileSizeBytes = if (snapshot) file.length() else null,
        fileModifiedAt = if (snapshot) file.lastModified() else null,
    )

    @Test fun aHealthyImageAndVideoPassTheDeepCheck() {
        val image = png()
        assertEquals(StoredFileHealth.HEALTHY, inspector.inspect(record(image)))

        val video = videoFixture()
        assertEquals(StoredFileHealth.HEALTHY, inspector.inspect(record(video, MediaKind.VIDEO)))
    }

    @Test fun aDeletedFileIsReportedAsMissing() {
        val image = png()
        val record = record(image)
        check(image.delete())
        assertEquals(StoredFileHealth.MISSING, inspector.inspect(record))
    }

    @Test fun aTruncatedImageIsCorruptEvenAfterAPassingCheapCheck() {
        val image = png()
        val record = record(image)
        // 只保留前一半：大小变了（CHANGED），结构校验也会失败（CORRUPT）。
        val bytes = image.readBytes()
        image.writeBytes(bytes.copyOf(bytes.size / 2))
        assertEquals(StoredFileHealth.CHANGED, inspector.cheapCheck(record))
        assertEquals(StoredFileHealth.CORRUPT, inspector.inspect(record))
    }

    @Test fun aTruncatedVideoIsCorrupt() {
        val video = videoFixture()
        val record = record(video, MediaKind.VIDEO)
        val bytes = video.readBytes()
        video.writeBytes(bytes.copyOf((bytes.size * 6) / 10))
        assertEquals(StoredFileHealth.CORRUPT, inspector.inspect(record))
    }

    @Test fun anOldRecordWithoutASnapshotIsKnownToExistButNotTrustedAsHealthy() {
        val image = png()
        assertEquals(StoredFileHealth.UNKNOWN, inspector.inspect(record(image, snapshot = false)))
    }

    @Test fun aFileWithoutACompletionSnapshotStillDetectsCorruption() {
        val image = png()
        val record = record(image, snapshot = false)
        val bytes = image.readBytes()
        image.writeBytes(bytes.copyOf(bytes.size / 2))
        assertEquals(StoredFileHealth.CORRUPT, inspector.inspect(record))
    }
}
