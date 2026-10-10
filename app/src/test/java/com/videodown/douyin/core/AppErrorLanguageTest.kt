package com.videodown.douyin.core

import java.io.IOException
import com.videodown.douyin.core.galleryFailureKindOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class AppErrorLanguageTest {
    @Test fun networkExceptionsDoNotExposeEnglishSystemMessages() {
        val cause = IOException("Unable to resolve host example.com")
        val error = cause.toAppError() as AppError.Network
        // The wording is a resource now; what matters is that the OS text never becomes the message.
        assertEquals("generic:network", error.reasonKey)
        assertEquals("", error.message)
        assertSame(cause, error.cause)
    }

    @Test fun unknownExceptionsDoNotExposeClassNamesOrEnglishMessages() {
        assertEquals("generic:unknown", (IllegalStateException("Unexpected response").toAppError() as AppError.Unknown).reasonKey)
        assertEquals("generic:unknown", (IllegalStateException().toAppError() as AppError.Unknown).reasonKey)
        assertEquals("", IllegalStateException("Unexpected response").toAppError().message)
    }

    @Test fun galleryFailuresAreTypedAndExternalFailuresAreNeverShown() {
        // The exporter reports a typed failure; matching on the message is gone, so its wording can
        // change freely without changing which failure is reported.
        assertEquals(
            com.videodown.douyin.data.gallery.GalleryExportFailure.FILE_MISSING,
            galleryFailureKindOf(com.videodown.douyin.data.gallery.GalleryExportException(com.videodown.douyin.data.gallery.GalleryExportFailure.FILE_MISSING)),
        )
        assertEquals(com.videodown.douyin.data.gallery.GalleryExportFailure.PERMISSION, galleryFailureKindOf(SecurityException("Permission denied")))
        // An external message may contain OS text or a path, so it never becomes the reason.
        assertEquals(com.videodown.douyin.data.gallery.GalleryExportFailure.WRITE_FAILED, galleryFailureKindOf(IOException("No space left on device")))
        assertEquals(com.videodown.douyin.data.gallery.GalleryExportFailure.WRITE_FAILED, galleryFailureKindOf(IOException("/storage/emulated/0/DCIM/x.mp4")))
    }

    @Test fun everyXParseFailureIsWorded() {
        val ids = com.videodown.douyin.data.remote.XParseFailure.entries.map { com.videodown.douyin.core.xFailureRes(it) }
        assertEquals(com.videodown.douyin.data.remote.XParseFailure.entries.size, ids.distinct().size)
        assertTrue(ids.none { it == 0 })
        // A typed reason must round-trip through its stored key.
        com.videodown.douyin.data.remote.XParseFailure.entries.forEach {
            assertEquals(it, com.videodown.douyin.data.remote.xFailureOf(it.storageKey))
        }
    }

    @Test fun everyGalleryFailureIsWorded() {
        // A new failure kind cannot ship without a translation.
        val ids = com.videodown.douyin.data.gallery.GalleryExportFailure.entries.map { com.videodown.douyin.core.galleryFailureRes(it) }
        assertEquals(com.videodown.douyin.data.gallery.GalleryExportFailure.entries.size, ids.distinct().size)
        assertTrue(ids.none { it == 0 })
    }
}
