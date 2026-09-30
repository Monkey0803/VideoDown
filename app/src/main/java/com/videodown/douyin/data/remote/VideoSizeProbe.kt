package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Reads the size advertised by a video CDN without downloading the video body. */
class VideoSizeProbe(private val client: OkHttpClient) {
    suspend fun sizeBytes(variant: VideoVariant): Long? = withContext(Dispatchers.IO) {
        if (variant.media != null) return@withContext variant.sizeBytes?.takeIf { it > 0L }
        variant.sizeBytes?.takeIf { it > 0L } ?: runCatching { probe(variant.url, variant.referer ?: DouyinApiClient.REFERER) }.getOrNull()
    }

    private fun probe(url: String, referer: String): Long? {
        val head = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", DouyinApiClient.USER_AGENT)
            .header("Referer", referer)
            .header("Accept-Encoding", "identity")
            .build()
        executeForSize(head)?.let { return it }

        // Some CDNs omit Content-Length for HEAD, but include the total in Content-Range.
        val range = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=0-0")
            .header("User-Agent", DouyinApiClient.USER_AGENT)
            .header("Referer", referer)
            .header("Accept-Encoding", "identity")
            .build()
        return executeForSize(range)
    }

    private fun executeForSize(request: Request): Long? = runCatching {
        val call = client.newCall(request)
        call.timeout().timeout(12, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful || response.header("Content-Type")?.startsWith("text/") == true) {
                return@use null
            }
            response.totalSizeFromHeaders()
        }
    }.getOrNull()

    private fun Response.totalSizeFromHeaders(): Long? {
        header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            ?.takeIf { it > 0L }?.let { return it }
        return if (code == 200) header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L } else null
    }
}
