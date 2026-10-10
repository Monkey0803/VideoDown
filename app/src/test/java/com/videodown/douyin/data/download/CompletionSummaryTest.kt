package com.videodown.douyin.data.download

import com.videodown.douyin.data.gallery.GalleryAutoSave
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.MediaDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionSummaryTest {

    private fun record(label: String = "720P", audio: Boolean = false) = DownloadRecord(
        id = "id",
        awemeId = "aweme",
        title = "海边的日落",
        resolutionLabel = label,
        media = if (audio) MediaDownload(audioOnly = true) else null,
    )

    // The summary reports values; the notification words them in the app language.

    @Test
    fun `without auto save the card keeps the plain destination hint`() {
        val summary = completionSummary(record(), GalleryAutoSave.Skipped)
        assertEquals("海边的日落", summary.title)
        assertEquals("720P", summary.qualityLabel)
        assertEquals(GalleryLanding.SKIPPED, summary.landing)
        assertEquals(null, summary.reasonKey)
    }

    @Test
    fun `a saved video says it reached the gallery, not the music library`() {
        val summary = completionSummary(record(), GalleryAutoSave.Saved)
        assertEquals(GalleryLanding.SAVED, summary.landing)
        assertFalse("A video must not claim the music library", summary.audio)
    }

    @Test
    fun `saved audio points at the music library instead`() {
        val summary = completionSummary(record(audio = true), GalleryAutoSave.Saved)
        assertEquals(GalleryLanding.SAVED, summary.landing)
        assertTrue(summary.audio)
    }

    @Test
    fun `a failed copy carries a typed reason on the same card`() {
        val summary = completionSummary(record(), GalleryAutoSave.Failed(com.videodown.douyin.data.gallery.GalleryExportFailure.PERMISSION.storageKey))
        assertEquals(GalleryLanding.FAILED, summary.landing)
        assertEquals(com.videodown.douyin.data.gallery.GalleryExportFailure.PERMISSION.storageKey, summary.reasonKey)
    }

    @Test
    fun `a missing quality label is reported as blank so the notification can name the kind`() {
        assertEquals("", completionSummary(record(label = ""), GalleryAutoSave.Skipped).qualityLabel)
        assertTrue(completionSummary(record(label = "", audio = true), GalleryAutoSave.Skipped).audio)
    }

    @Test fun `a private record is not named on the notification`() {
        val summary = completionSummary(record().copy(isPrivate = true), GalleryAutoSave.Skipped)
        // null means hidden; the notification substitutes its wording.
        assertNull(summary.title)
    }

    @Test fun `a private record says why it skipped the gallery`() {
        val summary = completionSummary(
            record().copy(isPrivate = true),
            GalleryAutoSave.Failed(com.videodown.douyin.domain.model.VaultPolicy.VAULT_GALLERY_KEY),
        )
        assertEquals(GalleryLanding.FAILED, summary.landing)
        assertEquals(com.videodown.douyin.domain.model.VaultPolicy.VAULT_GALLERY_KEY, summary.reasonKey)
    }
}
