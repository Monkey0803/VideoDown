package com.videodown.douyin.data.generic

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import com.videodown.douyin.domain.model.MediaSignature
import okhttp3.Request

/** Validates small response prefixes; extensions alone are not evidence of a video or image. */
class BrowserMediaDetector(private val client: OkHttpClient, private val sessions: BrowserSessionStore) {
    /**
     * A media resource the page actually loaded.
     *
     * 图片候选多带扩展名与宽高/大小，界面据此过滤图标噪音并直接构造下载条目；视频候选只要类型。
     */
    data class Candidate(
        val url: String,
        val type: String,
        val pageUrl: String,
        val userAgent: String,
        val extension: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val sizeBytes: Long? = null,
    ) {
        val isImage: Boolean get() = type == IMAGE
    }

    suspend fun inspect(url: String, page: String, userAgent: String): Candidate? = withContext(Dispatchers.IO) {
        val safeUrl = webUrl(url) ?: return@withContext null
        val probe = client.newBuilder().callTimeout(java.time.Duration.ofSeconds(8))
            .addNetworkInterceptor { chain ->
                val next = chain.request().newBuilder().removeHeader("Cookie")
                runBlocking { sessions.cookie(chain.request().url.toString()) }?.let { next.header("Cookie", it) }
                chain.proceed(next.build())
            }.build().newCall(Request.Builder().url(safeUrl).header("Range", "bytes=0-${MediaSignature.SIZE_PROBE_BYTES - 1}")
                .header("Referer", page).header("User-Agent", userAgent).build())
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { probe.cancel() }
        }
        try {
            probe.execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.peekBody(MediaSignature.SIZE_PROBE_BYTES.toLong()).bytes()
                // 图片先判：网页里图片比视频多得多，能拿到扩展名和尺寸才能挡掉图标与头像。
                val imageExtension = MediaSignature.imageExtension(bytes, response.header("Content-Type"))
                if (imageExtension != null) {
                    val size = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: if (response.code == 200) response.header("Content-Length")?.toLongOrNull() else null
                    val dimensions = MediaSignature.imageSize(bytes)
                    if (ImageMedia.isNoiseUrl(safeUrl) || ImageMedia.isNoise(dimensions?.width, dimensions?.height, size)) return@use null
                    return@use Candidate(safeUrl, IMAGE, page, userAgent, imageExtension,
                        dimensions?.width, dimensions?.height, size)
                }
                val prefix = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r')
                val kind = when {
                    prefix.startsWith("#EXTM3U") -> "HLS"
                    (prefix.startsWith("<?xml") || prefix.startsWith("<MPD")) && prefix.contains("<MPD") -> "DASH"
                    bytes.size > 8 && bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()) -> if (bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "qt  ") "MOV" else "MP4"
                    bytes.size >= 4 && bytes.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte()) -> if (prefix.contains("matroska")) "MKV" else "WebM"
                    else -> return@use null
                }
                Candidate(safeUrl, kind, page, userAgent)
            }
        } catch (e: java.io.IOException) { currentCoroutineContext().ensureActive(); null
        } finally { watcher.cancelAndJoin() }
    }

    companion object {
        const val IMAGE = "IMAGE"

        /**
         * 网页资源是否值得探测。
         *
         * 图片不再被排除（这正是「网页抓图」要的）；仍排除脚本、样式、字体、分片与音轨——
         * 它们不是可保存的图片/视频。
         */
        fun mayBeMedia(url: String): Boolean {
            if (webUrl(url) == null) return false
            val path = url.substringBefore('?').lowercase()
            return !Regex("\\.(js|css|svg|ico|woff2?|ttf|m4s|ts|aac|mp3)$").containsMatchIn(path)
        }
    }
}
