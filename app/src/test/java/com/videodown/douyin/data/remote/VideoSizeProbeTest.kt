package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.MediaSourceType
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSizeProbeTest {
    @Test
    fun `reads video size from a HEAD response`() = runBlocking {
        val payload = "v".repeat(4096)
        StubHttpServer(mapOf("/video.mp4" to StubResponse(body = payload, contentType = "video/mp4"))).use { server ->
            val probe = VideoSizeProbe(OkHttpClient())
            val size = probe.sizeBytes(VideoVariant(url = "${server.baseUrl}/video.mp4", label = "540P"))
            assertEquals(4096L, size)
            assertEquals(1, server.paths.size)
        }
    }

    @Test
    fun `keeps a size already supplied by the video metadata`() = runBlocking {
        val probe = VideoSizeProbe(OkHttpClient())
        val size = probe.sizeBytes(VideoVariant(url = "http://127.0.0.1:1/unreachable", label = "720P", sizeBytes = 12345L))
        assertEquals(12345L, size)
    }

    @Test
    fun `an image variant is probed even though it carries a media descriptor`() = runBlocking {
        val payload = "i".repeat(1234)
        StubHttpServer(mapOf("/image.jpg" to StubResponse(body = payload, contentType = "image/jpeg"))).use { server ->
            val variant = VideoVariant(
                url = "${server.baseUrl}/image.jpg",
                label = "原图",
                kind = MediaKind.IMAGE,
                media = MediaDownload(
                    type = MediaSourceType.DIRECT,
                    pageUrl = "https://page.example/post",
                    mediaId = "post_img1",
                    extension = "jpg",
                    extractor = "image",
                ),
            )
            assertEquals(1234L, VideoSizeProbe(OkHttpClient()).sizeBytes(variant))
        }
    }
}
