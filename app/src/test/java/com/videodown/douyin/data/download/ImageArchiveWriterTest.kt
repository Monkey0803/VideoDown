package com.videodown.douyin.data.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ImageArchiveWriterTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun `writes entries in order under the images directory`() {
        val one = File(temp.root, "one.jpg").apply { writeBytes("one".toByteArray()) }
        val two = File(temp.root, "two.webp").apply { writeBytes("two!".toByteArray()) }
        val output = File(temp.root, "archive.zip")

        val size = ImageArchiveWriter.write(
            listOf(ImageArchiveWriter.entryName(0, "jpg") to one, ImageArchiveWriter.entryName(1, "webp") to two),
            output,
        )

        assertTrue(size > 0L)
        assertEquals(size, output.length())
        ZipFile(output).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf("images/001.jpg", "images/002.webp"), entries.map { it.name })
            assertArrayEquals("one".toByteArray(), zip.getInputStream(entries[0]).use { it.readBytes() })
            assertArrayEquals("two!".toByteArray(), zip.getInputStream(entries[1]).use { it.readBytes() })
        }
        // 原子发布：临时文件不留在目录里。
        assertFalse(File(temp.root, "archive.zip.tmp").exists())
    }

    @Test fun `entry names are zero padded so a ten image post keeps its order`() {
        assertEquals("images/001.jpg", ImageArchiveWriter.entryName(0, "jpg"))
        assertEquals("images/010.png", ImageArchiveWriter.entryName(9, "png"))
        assertEquals("images/001.jpg", ImageArchiveWriter.entryName(0, ".JPG"))
        assertEquals("livephotos/001.mov", ImageArchiveWriter.livePhotoMotionEntryName(0, "mov"))
        assertEquals("livephotos/001.mov", ImageArchiveWriter.livePhotoMotionEntryName(0, "../../escape"))
        assertEquals("still_motion.mov", DownloadPaths(temp.root).buildLivePhotoMotionFileName("still.jpg", "../escape"))
    }

    @Test fun `a missing source file fails without leaving a half archive`() {
        val output = File(temp.root, "archive.zip")
        val missing = File(temp.root, "missing.jpg")
        assertThrows(IllegalArgumentException::class.java) {
            ImageArchiveWriter.write(listOf(ImageArchiveWriter.entryName(0, "jpg") to missing), output)
        }
        assertFalse(output.exists())
        assertFalse(File(temp.root, "archive.zip.tmp").exists())
    }

    @Test fun `an empty entry list is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageArchiveWriter.write(emptyList(), File(temp.root, "archive.zip"))
        }
    }
}
