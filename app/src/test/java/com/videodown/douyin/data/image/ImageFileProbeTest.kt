package com.videodown.douyin.data.image

import com.videodown.douyin.domain.model.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageFileProbeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun png(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes)
        "IHDR".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 12)
        listOf(width, height).forEachIndexed { index, value ->
            val offset = 16 + index * 4
            bytes[offset] = (value shr 24).toByte()
            bytes[offset + 1] = (value shr 16).toByte()
            bytes[offset + 2] = (value shr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        return bytes
    }

    @Test fun `dimensions come from the file header without decoding`() {
        val file = temporary.newFile("photo.png")
        file.writeBytes(png(3024, 4032))

        assertEquals(MediaSignature.ImageSize(3024, 4032), ImageFileProbe.dimensions(file))
    }

    @Test fun `missing or unknown files report null`() {
        assertNull(ImageFileProbe.dimensions(File(temporary.root, "gone.png")))
        val text = temporary.newFile("note.txt").apply { writeText("not an image") }
        assertNull(ImageFileProbe.dimensions(text))
    }
}
