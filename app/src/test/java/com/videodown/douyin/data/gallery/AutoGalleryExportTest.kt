package com.videodown.douyin.data.gallery

import com.videodown.douyin.domain.model.DownloadRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AutoGalleryExportTest {

    private val record = DownloadRecord(id = "id", awemeId = "aweme", title = "标题", resolutionLabel = "720P")

    private class Attempts {
        var export = 0
        var writeCheck = 0
    }

    private fun saver(
        enabled: Boolean,
        canWrite: Boolean,
        attempts: Attempts = Attempts(),
        export: suspend (DownloadRecord) -> Unit = {},
    ) = AutoGalleryExport(
        enabled = { enabled },
        canWrite = { attempts.writeCheck++; canWrite },
        export = { attempts.export++; export(it) },
    ) to attempts

    @Test
    fun `nothing is attempted while the preference is off`() = runTest {
        val (saver, attempts) = saver(enabled = false, canWrite = true)
        assertEquals(GalleryAutoSave.Skipped, saver.save(record))
        assertEquals(0, attempts.export)
        assertEquals(0, attempts.writeCheck)
    }

    @Test
    fun `a missing legacy storage permission is reported as a reason, not as a crash`() = runTest {
        val (saver, attempts) = saver(enabled = true, canWrite = false)
        val outcome = saver.save(record)
        assertTrue("Expected a permission reason, got $outcome", outcome is GalleryAutoSave.Failed)
        // A typed key now: the notification words it (see GalleryExportFailure).
        assertEquals(GalleryExportFailure.PERMISSION.storageKey, (outcome as GalleryAutoSave.Failed).reasonKey)
        assertEquals("The copy must not even be attempted", 0, attempts.export)
    }

    @Test
    fun `a successful copy is reported as saved`() = runTest {
        val (saver, attempts) = saver(enabled = true, canWrite = true)
        assertEquals(GalleryAutoSave.Saved, saver.save(record))
        assertEquals(1, attempts.export)
    }

    @Test
    fun `a copy failure carries the exporter reason`() = runTest {
        // The exporter's own type decides the reason; its message is never shown.
        val (saver, _) = saver(enabled = true, canWrite = true) {
            throw GalleryExportException(GalleryExportFailure.UNSUPPORTED_CONTAINER)
        }
        val outcome = saver.save(record)
        assertTrue(outcome is GalleryAutoSave.Failed)
        assertEquals(GalleryExportFailure.UNSUPPORTED_CONTAINER.storageKey, (outcome as GalleryAutoSave.Failed).reasonKey)
    }

    @Test
    fun `a failure without a message still explains itself`() = runTest {
        val (saver, _) = saver(enabled = true, canWrite = true) { throw IllegalStateException() }
        val outcome = saver.save(record)
        assertTrue(outcome is GalleryAutoSave.Failed)
        assertEquals(GalleryExportFailure.WRITE_FAILED.storageKey, (outcome as GalleryAutoSave.Failed).reasonKey)
    }

    @Test fun `a private record is never copied even when the switch is on`() = runTest {
        val (saver, attempts) = saver(enabled = true, canWrite = true)
        val result = saver.save(record.copy(isPrivate = true))
        assertTrue("Expected a refusal, got $result", result is GalleryAutoSave.Failed)
        assertEquals(com.videodown.douyin.domain.model.VaultPolicy.VAULT_GALLERY_KEY, (result as GalleryAutoSave.Failed).reasonKey)
        assertEquals("Nothing may be written for a private record", 0, attempts.export)
        assertEquals("The write check is not even reached", 0, attempts.writeCheck)
    }
}