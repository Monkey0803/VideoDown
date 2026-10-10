package com.videodown.douyin.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubtitleSidecarResolverTest {

    @get:Rule val folder = TemporaryFolder()

    private fun video(name: String = "clip.mp4") = folder.newFile(name)

    @Test
    fun `a matching srt next to the video is found with its mime type`() {
        val video = video()
        folder.newFile("clip.srt").writeText("1\n00:00:00,000 --> 00:00:01,000\n你好\n")
        val sidecar = SubtitleSidecarResolver.find(video)
        assertEquals("clip.srt", sidecar?.file?.name)
        assertEquals("application/x-subrip", sidecar?.mimeType)
    }

    @Test
    fun `every supported extension is recognised`() {
        listOf("vtt" to "text/vtt", "ass" to "text/x-ssa", "ssa" to "text/x-ssa").forEach { (extension, mime) ->
            val directory = folder.newFolder("case-$extension")
            val video = directory.resolve("clip.mp4").apply { writeText("") }
            directory.resolve("clip.$extension").writeText("")
            assertEquals(mime, SubtitleSidecarResolver.find(video)?.mimeType)
        }
    }

    @Test
    fun `matching ignores case on both names`() {
        val video = video("Clip.MP4")
        folder.newFile("clip.SRT").writeText("")
        assertEquals("clip.SRT", SubtitleSidecarResolver.find(video)?.file?.name)
    }

    @Test
    fun `a subtitle for a different file is not used`() {
        val video = video("clip.mp4")
        folder.newFile("other.srt").writeText("")
        folder.newFile("clip.txt").writeText("")
        assertNull(SubtitleSidecarResolver.find(video))
    }

    @Test
    fun `a video with no neighbours has no subtitles`() {
        assertNull(SubtitleSidecarResolver.find(video()))
    }

    @Test
    fun `a video without a parent directory is handled`() {
        assertNull(SubtitleSidecarResolver.find(java.io.File("does-not-exist/clip.mp4")))
    }

    @Test
    fun `a file whose name is only an extension gets no guess`() {
        val directory = folder.newFolder("bare")
        val video = directory.resolve(".mp4").apply { writeText("") }
        directory.resolve(".srt").writeText("")
        assertNull(SubtitleSidecarResolver.find(video))
    }
}
