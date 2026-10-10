package com.videodown.douyin.core

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchBadgeTest {

    @get:Rule val folder = TemporaryFolder()

    private fun recordWithFile(): Pair<DownloadRecord, java.io.File> {
        val file = folder.newFile("saved.mp4").apply { writeBytes(ByteArray(2_048)) }
        val record = DownloadRecord(id = "id", awemeId = "aweme", title = "标题", filePath = file.path, fileName = file.name)
        return record to file
    }

    private fun identity(file: java.io.File) = WatchProgress(
        positionMs = 30_000, durationMs = 120_000, fileSize = file.length(), modifiedAt = file.lastModified(),
    )

    @Test
    fun `a matching position yields a badge`() {
        val (record, file) = recordWithFile()
        val badge = watchBadge(record, mapOf("id" to identity(file)))
        // The badge carries the fraction; the screen words the percentage.
        assertEquals(0.25f, badge!!.fraction, 0.001f)
    }

    @Test
    fun `a record without a stored position has no badge`() {
        val (record, _) = recordWithFile()
        assertNull(watchBadge(record, emptyMap()))
    }

    @Test
    fun `a stale position for a replaced file has no badge`() {
        val (record, file) = recordWithFile()
        val stale = identity(file).copy(fileSize = file.length() + 1)
        assertNull(watchBadge(record, mapOf("id" to stale)))
        val reencoded = identity(file).copy(modifiedAt = file.lastModified() + 1)
        assertNull(watchBadge(record, mapOf("id" to reencoded)))
    }

    @Test
    fun `a record whose file is gone has no badge`() {
        val (record, file) = recordWithFile()
        val watch = identity(file)
        file.delete()
        assertNull(watchBadge(record, mapOf("id" to watch)))
    }

    @Test
    fun `a record without a file path has no badge`() {
        val (record, file) = recordWithFile()
        assertNull(watchBadge(record.copy(filePath = null), mapOf("id" to identity(file))))
    }

    @Test
    fun `a finished file has no badge`() {
        val (record, file) = recordWithFile()
        val finished = identity(file).copy(positionMs = 120_000)
        assertNull(watchBadge(record, mapOf("id" to finished)))
    }
}
