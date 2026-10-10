package com.videodown.douyin.data.generic

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import com.videodown.douyin.core.*
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.data.remote.VideoLinkExtractor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class GenericVideoRepository(
    private val runtime: VideoEngineRuntime,
    private val sessions: BrowserSessionStore,
    private val client: OkHttpClient,
    private val privateDirectory: File,
    /** When true, a collection URL is parsed into every entry the engine can see. */
    private val playlistEnabled: () -> Boolean = { false },
) : VideoRepository {
    override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> = parseVideos(shareText).map { it.selectedVideo }
    override suspend fun parseVideos(shareText: String): AppResult<ParsedVideos> = parse(shareText)

    suspend fun parse(text: String, browserSession: Boolean = false, referer: String? = null, userAgent: String? = null): AppResult<ParsedVideos> {
        val url = VideoLinkExtractor.extract(text)?.url ?: return AppResult.Failure(AppError.InvalidLink("", reasonKey = "input:generic_link"))
        return try {
            withTimeout(75_000) {
                probeDirect(url, browserSession, referer, userAgent)?.let { return@withTimeout AppResult.Success(ParsedVideos(listOf(it))) }
                AppResult.Success(extract(url, browserSession, referer, userAgent))
            }
        } catch (_: TimeoutCancellationException) {
            AppResult.Failure(AppError.Parse("", DownloadFailureKind.NETWORK, reasonKey = com.videodown.douyin.core.engineErrorKey("TIMEOUT")))
        } catch (e: CancellationException) { throw e
        } catch (e: VideoEngineException) { AppResult.Failure(AppError.Parse("", e.failureKind, reasonKey = com.videodown.douyin.core.engineErrorKey(e.kind)))
        } catch (_: Exception) { AppResult.Failure(AppError.Parse("", reasonKey = "generic:no_video")) }
    }

    private suspend fun extract(url: String, browser: Boolean, referer: String?, userAgent: String?): ParsedVideos = withContext(Dispatchers.IO) {
        privateDirectory.mkdirs()
        val cookieFile = File(privateDirectory, "session-${UUID.randomUUID()}.txt")
        try {
            if (browser) sessions.export(BrowserSessionStore.extractorCookieUrls(url, referer), cookieFile)
            var info: JsonObject? = null
            runtime.execute(
                EngineRequest.parse(
                    url = url,
                    browserSession = browser,
                    cookieFile = cookieFile.absolutePath,
                    referer = referer,
                    userAgent = userAgent,
                    playlist = playlistEnabled(),
                ),
            ) { event -> if (event.text("event") == "metadata") info = event["info"] as? JsonObject }
            val metadata = info ?: throw VideoEngineException("UNSUPPORTED")
            if (metadata.flag("has_drm")) throw VideoEngineException("DRM")
            if (metadata.flag("is_live") || metadata.text("live_status") in setOf("is_live", "is_upcoming")) throw VideoEngineException("LIVE")
            val parsed = GenericMetadataParser().parse(metadata, url, browser, userAgent)
            if (referer == null) parsed else ParsedVideos(parsed.videos.map { video ->
                video.copy(shareUrl = referer, variants = video.variants.map { it.copy(referer = referer) })
            })
        } finally { cookieFile.delete() }
    }

    private suspend fun probeDirect(url: String, browser: Boolean, referer: String?, userAgent: String?): DouyinVideo? = withContext(Dispatchers.IO) {
        // Resolve Cookie separately on every redirect; OkHttp must not forward a raw Cookie header.
        val probeClient = client.newBuilder().callTimeout(java.time.Duration.ofSeconds(12))
            .addNetworkInterceptor { chain ->
                val builder = chain.request().newBuilder().removeHeader("Cookie")
                if (browser) runBlocking { sessions.cookie(chain.request().url.toString()) }?.let { builder.header("Cookie", it) }
                chain.proceed(builder.build())
            }.build()
        val request = Request.Builder().url(url).header("Range", "bytes=0-511").apply {
            referer?.let { header("Referer", it) }; userAgent?.let { header("User-Agent", it) }
        }.build()
        val call = probeClient.newCall(request)
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return@use null
                val mime = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                val bytes = response.peekBody(512).bytes()
                val mp4 = bytes.size >= 8 && bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())
                val webm = bytes.size >= 4 && bytes.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte())
                val prefix = bytes.toString(Charsets.ISO_8859_1)
                val ext = when {
                    mp4 && bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "qt  " -> "mov"
                    mp4 -> "mp4"
                    webm && prefix.contains("matroska") -> "mkv"
                    webm && prefix.contains("webm") -> "webm"
                    else -> return@use null
                }
                if (mime.startsWith("text/")) return@use null
                val id = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
                val size = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: if (response.code == 200) response.header("Content-Length")?.toLongOrNull() else null
                DouyinVideo(id, response.request.url.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() } ?: AppText.get(R.string.fallback_title_web_video),
                    variants = listOf(VideoVariant(response.request.url.toString(), AppText.get(R.string.quality_original), sizeBytes = size,
                        referer = referer ?: url, media = MediaDownload(pageUrl = url, mediaId = id, extension = ext,
                            userAgent = userAgent, useBrowserSession = browser, extractor = "direct"))),
                    shareUrl = referer ?: url, platform = VideoPlatform.GENERIC)
            }
        } catch (e: java.io.IOException) { currentCoroutineContext().ensureActive(); null
        } finally { watcher.cancelAndJoin() }
    }
}
