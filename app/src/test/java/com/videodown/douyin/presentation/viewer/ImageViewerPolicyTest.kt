package com.videodown.douyin.presentation.viewer

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageViewerPolicyTest {
    private fun record(
        id: String,
        updatedAt: Long,
        private: Boolean = false,
        kind: MediaKind = MediaKind.IMAGE,
        status: DownloadStatus = DownloadStatus.COMPLETED,
    ) = DownloadRecord(
        id = id,
        awemeId = id,
        title = id,
        filePath = "/images/$id.jpg",
        status = status,
        mediaKind = kind,
        isPrivate = private,
        createdAt = updatedAt - 1,
        updatedAt = updatedAt,
    )

    @Test fun `public entry sees every valid public image newest first`() {
        val records = listOf(
            record("older", 10),
            record("private", 40, private = true),
            record("video", 50, kind = MediaKind.VIDEO),
            record("failed", 60, status = DownloadStatus.FAILED),
            record("missing", 70),
            record("current", 30),
        )

        val result = imageViewerRecords(records, "current") { it.id != "missing" }

        assertEquals(listOf("current", "older"), result.map { it.id })
    }

    @Test fun `private entry only sees private images`() {
        val records = listOf(record("public", 30), record("private-old", 10, true), record("private", 20, true))

        val result = imageViewerRecords(records, "private") { true }

        assertEquals(listOf("private", "private-old"), result.map { it.id })
    }

    @Test fun `missing initial image stays in missing state instead of jumping elsewhere`() {
        val records = listOf(record("current", 20), record("other", 10))

        assertTrue(imageViewerRecords(records, "current") { it.id != "current" }.isEmpty())
    }

    @Test fun `fixed privacy scope survives removal of initial record`() {
        val recordsAfterDelete = listOf(record("next", 20), record("private", 30, private = true))

        val result = imageViewerRecords(recordsAfterDelete, privateScope = false) { true }

        assertEquals(listOf("next"), result.map { it.id })
    }

    @Test fun `deletion prefers next then previous and exits for last image`() {
        val records = listOf(record("a", 30), record("b", 20), record("c", 10))

        assertEquals("b", imageAfterDelete(records, "a"))
        assertEquals("c", imageAfterDelete(records, "b"))
        assertEquals("b", imageAfterDelete(records, "c"))
        assertEquals(null, imageAfterDelete(listOf(records.first()), "a"))
    }
}
