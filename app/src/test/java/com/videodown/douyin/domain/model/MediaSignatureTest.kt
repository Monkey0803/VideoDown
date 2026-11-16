package com.videodown.douyin.domain.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSignatureTest {

    @Test fun `recognizes png and reads its dimensions`() {
        val bytes = png(800, 600)
        assertEquals("png", MediaSignature.imageExtension(bytes))
        assertEquals(MediaSignature.ImageSize(800, 600), MediaSignature.imageSize(bytes))
    }

    @Test fun `recognizes gif and reads its dimensions`() {
        val bytes = gif(320, 240)
        assertEquals("gif", MediaSignature.imageExtension(bytes))
        assertEquals(MediaSignature.ImageSize(320, 240), MediaSignature.imageSize(bytes))
    }

    @Test fun `recognizes jpeg and scans to the SOF segment for its dimensions`() {
        val bytes = jpeg(1920, 1080)
        assertEquals("jpg", MediaSignature.imageExtension(bytes))
        assertEquals(MediaSignature.ImageSize(1920, 1080), MediaSignature.imageSize(bytes))
    }

    @Test fun `recognizes webp VP8X canvas dimensions`() {
        val bytes = webp(1200, 900)
        assertEquals("webp", MediaSignature.imageExtension(bytes))
        assertEquals(MediaSignature.ImageSize(1200, 900), MediaSignature.imageSize(bytes))
    }

    @Test fun `recognizes avif and heic brands and bmp`() {
        assertEquals("avif", MediaSignature.imageExtension(ftyp("avif")))
        assertEquals("heic", MediaSignature.imageExtension(ftyp("heic")))
        assertEquals("bmp", MediaSignature.imageExtension(byteArrayOf(0x42, 0x4D, 0, 0, 0, 0)))
    }

    @Test fun `does not mistake mp4 or html for an image`() {
        assertNull(MediaSignature.imageExtension(ftyp("isom")))
        assertNull(MediaSignature.imageExtension("<html><body>login</body></html>".toByteArray()))
        assertNull(MediaSignature.imageSize("<html>".toByteArray()))
    }

    @Test fun `content type is only a fallback when the bytes are unknown`() {
        assertEquals("jpg", MediaSignature.imageExtension(ByteArray(0), "image/jpeg; charset=binary"))
        assertNull(MediaSignature.imageExtension(ByteArray(0), "video/mp4"))
        // 魔数优先于一个说错的 Content-Type。
        assertEquals("png", MediaSignature.imageExtension(png(1, 1), "image/jpeg"))
    }

    @Test fun `url extension is normalized and only matches image paths`() {
        assertEquals("jpg", MediaSignature.extensionFromUrl("https://cdn.example/a.JPEG?x=1"))
        assertEquals("webp", MediaSignature.extensionFromUrl("https://cdn.example/a.webp"))
        assertNull(MediaSignature.extensionFromUrl("https://cdn.example/a.mp4"))
        assertNull(MediaSignature.extensionFromUrl("https://cdn.example/a.jpg.exe"))
        assertTrue(MediaSignature.isImageExtension(".PNG"))
        assertTrue(!MediaSignature.isImageExtension("mp4"))
    }

    @Test fun `zip signature distinguishes archives from other bytes`() {
        assertTrue(MediaSignature.isZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)))
        assertTrue(!MediaSignature.isZip(png(1, 1)))
        assertTrue(!MediaSignature.isZip(ByteArray(2)))
    }

    companion object {
        fun png(width: Int, height: Int): ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
        ) + int32Be(width) + int32Be(height) + byteArrayOf(8, 6, 0, 0, 0)

        fun gif(width: Int, height: Int): ByteArray = "GIF89a".toByteArray(Charsets.ISO_8859_1) +
            int16Le(width) + int16Le(height) + byteArrayOf(0, 0, 0, 0)

        fun jpeg(width: Int, height: Int): ByteArray = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(),
            0x00, 0x11, 0x08,
        ) + int16Be(height) + int16Be(width) + byteArrayOf(0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01)

        fun webp(width: Int, height: Int): ByteArray {
            val canvasWidth = int24Le(width - 1)
            val canvasHeight = int24Le(height - 1)
            return "RIFF".toByteArray(Charsets.ISO_8859_1) + int32Le(22) +
                "WEBP".toByteArray(Charsets.ISO_8859_1) + "VP8X".toByteArray(Charsets.ISO_8859_1) +
                int32Le(10) + byteArrayOf(0, 0, 0, 0) + canvasWidth + canvasHeight
        }

        fun ftyp(brand: String): ByteArray =
            byteArrayOf(0, 0, 0, 24) + "ftyp".toByteArray(Charsets.ISO_8859_1) +
                brand.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0, 0, 0)

        private fun int16Be(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
        private fun int16Le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())
        private fun int24Le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte())
        private fun int32Be(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
        private fun int32Le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
    }
}
