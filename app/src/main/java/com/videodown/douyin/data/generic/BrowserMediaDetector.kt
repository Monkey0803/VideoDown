package com.videodown.douyin.data.generic

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request

/** Validates small response prefixes; extensions alone are not evidence of a video. */
class BrowserMediaDetector(private val client: OkHttpClient, private val sessions: BrowserSessionStore) {
    data class Candidate(val url: String, val type: String, val pageUrl: String, val userAgent: String)

    suspend fun inspect(url: String, page: String, userAgent: String): Candidate? = withContext(Dispatchers.IO) {
        val safeUrl = webUrl(url) ?: return@withContext null
        val probe = client.newBuilder().callTimeout(java.time.Duration.ofSeconds(8))
            .addNetworkInterceptor { chain ->
                val next = chain.request().newBuilder().removeHeader("Cookie")
                runBlocking { sessions.cookie(chain.request().url.toString()) }?.let { next.header("Cookie", it) }
                chain.proceed(next.build())
            }.build().newCall(Request.Builder().url(safeUrl).header("Range", "bytes=0-4095")
                .header("Referer", page).header("User-Agent", userAgent).build())
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { probe.cancel() }
        }
        try {
            probe.execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.peekBody(4096).bytes()
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
        fun mayBeMedia(url: String): Boolean {
            if (webUrl(url) == null) return false
            val path = url.substringBefore('?').lowercase()
            return !Regex("\\.(js|css|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|m4s|ts|aac|mp3)$").containsMatchIn(path)
        }
    }
}
