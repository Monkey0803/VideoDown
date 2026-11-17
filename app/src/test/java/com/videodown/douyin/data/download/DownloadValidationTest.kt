package com.videodown.douyin.data.download

import com.videodown.douyin.domain.model.ArchiveEntry
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadValidationTest {

    @get:Rule val temp = TemporaryFolder()

    private fun record(kind: MediaKind, entries: Int = 0) = DownloadRecord(
        id = "id", awemeId = "post", title = "t", mediaKind = kind,
        archiveEntries = List(entries) { ArchiveEntry("https://cdn/$it.jpg") },
    )

    private fun file(name: String, bytes: ByteArray = MediaSignatureTestBytes.png) =
        File(temp.root, name).apply { writeBytes(bytes) }

    @Test fun `a real png passes image validation`() {
        validateDownloadedFile(file("a.jpg"), record(MediaKind.IMAGE))
    }

    @Test fun `html or mp4 bytes are rejected as images`() {
        assertEquals(DOWNLOAD_NOT_AN_IMAGE, assertThrows(DownloadContentException::class.java) {
            validateDownloadedFile(file("a.jpg", "<html>error</html>".toByteArray()), record(MediaKind.IMAGE))
        }.reasonKey)
        assertEquals(DOWNLOAD_NOT_AN_IMAGE, assertThrows(DownloadContentException::class.java) {
            validateDownloadedFile(file("b.jpg", MediaSignatureTestBytes.mp4()), record(MediaKind.IMAGE))
        }.reasonKey)
    }

    @Test fun `an empty image file is rejected`() {
        assertThrows(DownloadContentException::class.java) {
            validateDownloadedFile(file("empty.jpg", ByteArray(0)), record(MediaKind.IMAGE))
        }
    }

    @Test fun `known image containers reject an unfinished tail`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        assertThrows(DownloadContentException::class.java) {
            validateDownloadedFile(file("cut.jpg", jpeg), record(MediaKind.IMAGE))
        }
        assertThrows(DownloadContentException::class.java) {
            validateDownloadedFile(file("cut.png", MediaSignatureTestBytes.png.copyOfRange(0, 29)), record(MediaKind.IMAGE))
        }
    }

    @Test fun `a complete avif box container is accepted without requiring a system decoder`() {
        val avif = byteArrayOf(0, 0, 0, 16, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0, 0, 0)
        validateDownloadedFile(file("image.avif", avif), record(MediaKind.IMAGE))
    }

    @Test fun `an archive must contain exactly the listed number of entries`() {
        val zip = File(temp.root, "ok.zip")
        ImageArchiveWriter.write(
            listOf(ImageArchiveWriter.entryName(0, "jpg") to file("one.png"),
                ImageArchiveWriter.entryName(1, "jpg") to file("two.png")),
            zip,
        )
        validateDownloadedFile(zip, record(MediaKind.ARCHIVE, entries = 2))
        assertEquals(DOWNLOAD_INVALID_ARCHIVE, assertThrows(DownloadContentException::class.java) {
            validateDownloadedFile(zip, record(MediaKind.ARCHIVE, entries = 3))
        }.reasonKey)
    }

    @Test fun `an archive rejects the right count with a wrong name or non image body`() {
        val wrongName = File(temp.root, "wrong-name.zip")
        ImageArchiveWriter.write(listOf("images/999.jpg" to file("named.png")), wrongName)
        assertEquals(DOWNLOAD_INVALID_ARCHIVE, assertThrows(DownloadContentException::class.java) {
            validateDownloadedFile(wrongName, record(MediaKind.ARCHIVE, entries = 1))
        }.reasonKey)

        val html = File(temp.root, "html.zip")
        ImageArchiveWriter.write(listOf(ImageArchiveWriter.entryName(0, "jpg") to
            file("error.jpg", "<html>expired</html>".toByteArray())), html)
        assertEquals(DOWNLOAD_INVALID_ARCHIVE, assertThrows(DownloadContentException::class.java) {
            validateDownloadedFile(html, record(MediaKind.ARCHIVE, entries = 1))
        }.reasonKey)
    }

    @Test fun `a truncated or non zip file is not a valid archive`() {
        assertEquals(DOWNLOAD_INVALID_ARCHIVE, assertThrows(DownloadContentException::class.java) {
            validateDownloadedFile(file("broken.zip", MediaSignatureTestBytes.png), record(MediaKind.ARCHIVE, entries = 0))
        }.reasonKey)
    }
}

/** Shared magic-byte fixtures; kept here so validation tests don't depend on the domain test file. */
internal object MediaSignatureTestBytes {
    val png: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0, 0, 0, 13,
        'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
        0, 0, 0, 1,
        0, 0, 0, 1,
        8, 6, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(),
        0, 0, 0, 0,
    )

    fun mp4(): ByteArray = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
        'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 0, 0)
}
