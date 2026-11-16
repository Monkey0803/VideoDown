package com.videodown.douyin.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaKindTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `variant extension prefers the source extension and falls back to the kind`() {
        val image = VideoVariant("https://cdn.example/a", "原图", kind = MediaKind.IMAGE,
            media = MediaDownload(extension = "webp"))
        assertEquals("webp", image.extensionOrDefault)
        assertEquals("jpg", VideoVariant("https://cdn.example/a", "原图", kind = MediaKind.IMAGE).extensionOrDefault)
        assertEquals("mp4", VideoVariant("https://cdn.example/a", "720P").extensionOrDefault)
    }

    @Test fun `variant kind maps to the downloadable media kind`() {
        assertEquals(MediaKind.AUDIO, VideoVariant("u", "音频", media = MediaDownload(audioOnly = true)).mediaKind)
        assertEquals(MediaKind.IMAGE, VideoVariant("u", "原图", kind = MediaKind.IMAGE).mediaKind)
        assertEquals(MediaKind.VIDEO, VideoVariant("u", "720P").mediaKind)
    }

    @Test fun `an old record without a media kind still reads as video`() {
        val record = json.decodeFromString(DownloadRecord.serializer(), """{"id":"1","awemeId":"2","title":"旧记录"}""")
        assertEquals(MediaKind.VIDEO, record.mediaKind)
        assertTrue(record.isVideo)
        assertFalse(record.isImage)
        assertFalse(record.isArchive)
        assertTrue(record.archiveEntries.isEmpty())
    }

    @Test fun `an archive record round trips its entries`() {
        val record = DownloadRecord(
            id = "id", awemeId = "post", title = "图文", mediaKind = MediaKind.ARCHIVE,
            archiveEntries = listOf(ArchiveEntry("https://cdn/a.jpg", "jpg", "https://page", 1234L)),
            createdAt = 123L, updatedAt = 456L,
        )
        val decoded = json.decodeFromString(DownloadRecord.serializer(), json.encodeToString(DownloadRecord.serializer(), record))
        assertEquals(record, decoded)
        assertTrue(decoded.isArchive)
    }

    @Test fun `old audio history is neither a video nor extractable again`() {
        val audio = DownloadRecord("id", "media", "audio", status = DownloadStatus.COMPLETED,
            media = MediaDownload(audioOnly = true))
        assertTrue(audio.isAudio)
        assertFalse(audio.isVideo)
    }

    @Test fun `image posts are recognized only when every variant is an image`() {
        val image = DouyinVideo("p_img1", "图文",
            variants = listOf(VideoVariant("u", "原图", kind = MediaKind.IMAGE)))
        assertTrue(image.isImagePost)
        val video = DouyinVideo("p", "视频", variants = listOf(VideoVariant("u", "720P")))
        assertFalse(video.isImagePost)
        assertFalse(DouyinVideo("empty", "空").isImagePost)
    }

    @Test fun `mime types cover image and archive containers`() {
        assertEquals("image/jpeg", videoMimeType("jpg"))
        assertEquals("image/png", videoMimeType("PNG"))
        assertEquals("image/webp", videoMimeType("webp"))
        assertEquals("image/heic", videoMimeType("heic"))
        assertEquals("application/zip", videoMimeType("zip"))
        assertEquals("video/mp4", videoMimeType("mp4"))
    }
}
