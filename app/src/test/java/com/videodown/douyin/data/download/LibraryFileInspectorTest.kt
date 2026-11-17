package com.videodown.douyin.data.download

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.StoredFileHealth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * I4：检查器把「低成本比对」和「结构校验」串起来的顺序。
 *
 * 结构校验用注入的假实现，因此 JVM 上就能验证：文件不在时不打开文件、校验失败一律报损坏、
 * 通过时沿用快照结论。真实媒体结构由设备用例覆盖。
 */
class LibraryFileInspectorTest {
    @get:Rule val temp = TemporaryFolder()

    private var structureCalls = 0
    private var structurePasses = true
    private val inspector = LibraryFileInspector { _, _ ->
        structureCalls++
        if (!structurePasses) error("broken media")
    }

    private fun file(bytes: Int = 64): File = File(temp.root, "stored.mp4").apply { writeBytes(ByteArray(bytes) { 1 }) }

    private fun record(media: File, size: Long? = null, modifiedAt: Long? = null) = DownloadRecord(
        id = "health",
        awemeId = "post",
        title = "健康检查",
        fileName = media.name,
        filePath = media.absolutePath,
        status = DownloadStatus.COMPLETED,
        fileSizeBytes = size,
        fileModifiedAt = modifiedAt,
    )

    @Test fun `a snapshot that still matches passes the deep check`() {
        val media = file()
        val record = record(media, media.length(), media.lastModified())
        assertEquals(StoredFileHealth.HEALTHY, inspector.inspect(record))
        assertEquals(1, structureCalls)
    }

    @Test fun `a deleted file is reported without trying to open it`() {
        val media = file()
        val record = record(media, media.length(), media.lastModified())
        media.delete()
        assertEquals(StoredFileHealth.MISSING, inspector.inspect(record))
        assertEquals("no structure check for a missing file", 0, structureCalls)
    }

    @Test fun `a structure failure is corruption even when the snapshot matches`() {
        val media = file()
        structurePasses = false
        assertEquals(StoredFileHealth.CORRUPT, inspector.inspect(record(media, media.length(), media.lastModified())))
    }

    @Test fun `a changed file keeps the changed verdict after a passing structure check`() {
        val media = file()
        val record = record(media, size = media.length() + 100, modifiedAt = media.lastModified())
        assertEquals(StoredFileHealth.CHANGED, inspector.inspect(record))
    }

    @Test fun `an old record without a snapshot is checked but stays unknown`() {
        val media = file()
        assertEquals(StoredFileHealth.UNKNOWN, inspector.inspect(record(media, size = null, modifiedAt = null)))
        assertEquals(1, structureCalls)
    }

    @Test fun `a record without a path is missing`() {
        val record = DownloadRecord(id = "no-path", awemeId = "post", title = "无路径", status = DownloadStatus.COMPLETED)
        assertEquals(StoredFileHealth.MISSING, inspector.inspect(record))
    }
}
