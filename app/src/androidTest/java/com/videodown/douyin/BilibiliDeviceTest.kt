package com.videodown.douyin

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppError
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.data.download.DownloadEngine
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.download.ResumableDownloader
import com.videodown.douyin.data.download.validateDownloadedFile
import com.videodown.douyin.data.generic.EngineMediaDownloader
import com.videodown.douyin.data.generic.SessionVideoParser
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.remote.BilibiliShortLinkResolver
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.data.repository.MultiPlatformVideoRepository
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.repository.DouyinRepository
import com.videodown.douyin.domain.repository.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * E1：B 站链接进入仓库后的行为。
 *
 * 引擎用假实现替换（设备上真实解析需要联网，由 `live_bilibili` 在线用例覆盖），这里验证的是
 * 仓库边界上的事：链接先归一化、b23.tv 只跟随可信跳转、番剧/直播等直接报告不支持、
 * 引擎结果按 BILIBILI 重新标注，以及 `?p=N` 原样交给引擎。
 */
@RunWith(AndroidJUnit4::class)
class BilibiliDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class FakeEngine : VideoRepository {
        val requested = mutableListOf<String>()
        var failure: AppResult<ParsedVideos>? = null
        override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> {
            requested += shareText
            failure?.let { return it as AppResult<DouyinVideo> }
            return AppResult.Success(
                DouyinVideo(
                    awemeId = "BV1GJ411x7h7",
                    title = "假引擎结果",
                    platform = VideoPlatform.GENERIC,
                    shareUrl = shareText,
                    variants = listOf(VideoVariant("https://example.test/v.mp4", "720P")),
                ),
            )
        }
        override suspend fun parseVideos(shareText: String): AppResult<ParsedVideos> {
            requested += shareText
            failure?.let { return it }
            return AppResult.Success(
                ParsedVideos(listOf(DouyinVideo(
                    awemeId = "BV1GJ411x7h7",
                    title = "假引擎结果",
                    platform = VideoPlatform.GENERIC,
                    shareUrl = shareText,
                    variants = listOf(VideoVariant("https://example.test/v.mp4", "720P")),
                ))),
            )
        }
    }

    private class UnusedDouyin : DouyinRepository {
        override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> =
            AppResult.Failure(AppError.InvalidLink("unused"))
        override suspend fun parseUrl(url: String): AppResult<DouyinVideo> = parseShareText(url)
    }

    /** 只回一跳 302 的本地服务，用来验证「短链只在可信目标下才被接受」。 */
    private class RedirectServer(private val location: String) : Closeable {
        private val socket = ServerSocket(0)
        private val running = AtomicBoolean(true)
        val baseUrl get() = "http://127.0.0.1:${socket.localPort}"
        private val thread = Thread {
            while (running.get()) {
                val client = try { socket.accept() } catch (_: Exception) { return@Thread }
                client.use { connection: Socket ->
                    val reader = connection.getInputStream().bufferedReader()
                    reader.readLine()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    connection.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                                .toByteArray(Charsets.ISO_8859_1),
                        )
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
            thread.interrupt()
        }
    }

    private fun repository(engine: FakeEngine, resolver: (suspend (String) -> com.videodown.douyin.data.remote.BilibiliVideoLink?)? = null) =
        MultiPlatformVideoRepository(
            douyin = UnusedDouyin(),
            fallback = engine,
            resolveBilibiliShortLink = resolver,
        )

    @Test fun aPartLinkIsNormalisedBeforeTheEngineSeesItAndRetaggedAsBilibili() = runBlocking {
        val engine = FakeEngine()
        val result = repository(engine).parseVideos("https://m.bilibili.com/video/BV1GJ411x7h7?p=3&vd_source=abc")
        assertTrue("parse failed: $result", result is AppResult.Success)
        val videos = (result as AppResult.Success).value
        assertEquals(listOf("https://www.bilibili.com/video/BV1GJ411x7h7?p=3"), engine.requested)
        assertEquals(VideoPlatform.BILIBILI, videos.selectedVideo.platform)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7?p=3", videos.selectedVideo.shareUrl)
        assertEquals(VideoPlatform.BILIBILI.referer, videos.selectedVideo.variants.first().referer)
    }

    @Test fun bangumiAndLiveLinksAreRefusedWithoutCallingTheEngine() = runBlocking {
        listOf(
            "https://www.bilibili.com/bangumi/play/ss1001",
            "https://live.bilibili.com/123456",
            "https://www.bilibili.com/audio/au123456",
        ).forEach { link ->
            val engine = FakeEngine()
            val result = repository(engine).parseVideos(link)
            assertTrue("expected refusal for $link, got $result", result is AppResult.Failure)
            assertEquals("bilibili:unsupported", (result as AppResult.Failure).error.let { (it as AppError.InvalidLink).reasonKey })
            assertTrue("engine must not run for unsupported content", engine.requested.isEmpty())
        }
    }

    @Test fun aShortLinkIsOnlyUsedWhenTheResolverReturnsATrustedVideoPage() = runBlocking {
        val good = com.videodown.douyin.data.remote.BilibiliVideoLink(
            "https://www.bilibili.com/video/BV1GJ411x7h7", "BV1GJ411x7h7",
        )
        val engine = FakeEngine()
        val result = repository(engine) { good }.parseVideos("https://b23.tv/${UUID.randomUUID().toString().take(6)}")
        assertTrue("short link should resolve: $result", result is AppResult.Success)
        assertEquals(listOf("https://www.bilibili.com/video/BV1GJ411x7h7"), engine.requested)

        val rejectedEngine = FakeEngine()
        val rejected = repository(rejectedEngine) { null }.parseVideos("https://b23.tv/x${UUID.randomUUID().toString().take(6)}")
        assertTrue("untrusted redirect must fail: $rejected", rejected is AppResult.Failure)
        assertEquals("bilibili:short_unresolved", (rejected as AppResult.Failure).error.let { (it as AppError.InvalidLink).reasonKey })
        assertTrue(rejectedEngine.requested.isEmpty())
    }

    /** 真机上的短链展开：只跟随可信跳转，OkHttp 的 302 处理与 JVM 用例一致。 */
    @Test fun theShortLinkResolverFollowsOnlyTrustedRedirectsOnDevice() = runBlocking {
        val resolver = BilibiliShortLinkResolver(OkHttpClient())
        RedirectServer("https://www.bilibili.com/video/BV1GJ411x7h7?p=2").use { good ->
            val resolved = resolver.resolve("${good.baseUrl}/s/good")
            assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7?p=2", resolved?.url)
            assertEquals(2, resolved?.page)
        }
        RedirectServer("https://evil.example/video/BV1GJ411x7h7").use { evil ->
            org.junit.Assert.assertNull(resolver.resolve("${evil.baseUrl}/s/evil"))
        }
    }

    /** E3：有登录态先试登录态，登录态被拒后退回匿名；两条路都要求授权才提示重新登录。 */
    @Test fun aSessionFailureFallsBackToTheAnonymousEngine() = runBlocking {
        val engine = FakeEngine()
        val session = SessionVideoParser {
            AppResult.Failure(AppError.Http(403, failureKind = DownloadFailureKind.LOGIN_REQUIRED))
        }
        val repository = MultiPlatformVideoRepository(
            douyin = UnusedDouyin(), fallback = engine, bilibiliSession = session,
        )
        val result = repository.parseVideos("https://www.bilibili.com/video/BV1GJ411x7h7")
        assertTrue("anonymous fallback must succeed: $result", result is AppResult.Success)
        assertEquals(VideoPlatform.BILIBILI, (result as AppResult.Success).value.selectedVideo.platform)
        assertEquals(listOf("https://www.bilibili.com/video/BV1GJ411x7h7"), engine.requested)
    }

    @Test fun whenBothPathsNeedAuthenticationTheUserIsAskedToSignInAgain() = runBlocking {
        val engine = FakeEngine().apply {
            failure = AppResult.Failure(AppError.Parse("", DownloadFailureKind.LOGIN_REQUIRED, reasonKey = "engine:AUTH"))
        }
        val session = SessionVideoParser {
            AppResult.Failure(AppError.Http(403, failureKind = DownloadFailureKind.LOGIN_REQUIRED))
        }
        val repository = MultiPlatformVideoRepository(
            douyin = UnusedDouyin(), fallback = engine, bilibiliSession = session,
        )
        val result = repository.parseVideos("https://www.bilibili.com/video/BV1GJ411x7h7")
        assertTrue("both paths failed: $result", result is AppResult.Failure)
        assertEquals("bilibili:session_auth_failed", (result as AppResult.Failure).error.let { (it as AppError.Parse).reasonKey })
        assertEquals("the anonymous path must still have been tried", 1, engine.requested.size)
    }

    @Test fun withoutASessionOnlyTheAnonymousEngineRuns() = runBlocking {
        val engine = FakeEngine()
        val repository = MultiPlatformVideoRepository(
            douyin = UnusedDouyin(), fallback = engine, bilibiliSession = SessionVideoParser { null },
        )
        val result = repository.parseVideos("https://www.bilibili.com/video/BV1GJ411x7h7")
        assertTrue("anonymous parse must succeed: $result", result is AppResult.Success)
        assertEquals(1, engine.requested.size)
    }

    /**
     * 在线验收（分 P 合集）：`-Pandroid.testInstrumentationRunnerArguments.live_bilibili_playlist=<多 P 链接>`。
     *
     * 默认跳过：需要一条真实的多 P 视频。开启「解析合集」后必须枚举出多个分 P，且不超过 20。
     */
    @Test fun liveBilibiliCollectionHonoursTheTwentyPartCap() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("live_bilibili_playlist")
        org.junit.Assume.assumeTrue("Pass live_bilibili_playlist to enable a real Bilibili collection check", !url.isNullOrBlank())
        val previous = com.videodown.douyin.core.ServiceLocator.preferences.playlistParsing
        com.videodown.douyin.core.ServiceLocator.preferences.playlistParsing = true
        try {
            val result = com.videodown.douyin.core.ServiceLocator.videoRepository.parseVideos(url!!)
            assertTrue("Bilibili collection parse failed: $result", result is AppResult.Success)
            val videos = (result as AppResult.Success).value.videos
            assertTrue("a multi-part video must enumerate several parts, got ${videos.size}", videos.size >= 2)
            assertTrue("the engine caps collections at 20 parts, got ${videos.size}", videos.size <= 20)
        } finally {
            com.videodown.douyin.core.ServiceLocator.preferences.playlistParsing = previous
        }
    }

    /** 在线验收：`-Pandroid.testInstrumentationRunnerArguments.live_bilibili=<公开视频链接>`。 */
    @Test fun liveBilibiliParseProducesDownloadableVariants() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("live_bilibili")
        org.junit.Assume.assumeTrue("Pass live_bilibili to enable a real Bilibili check", !url.isNullOrBlank())
        val result = com.videodown.douyin.core.ServiceLocator.videoRepository.parseVideos(url!!)
        assertTrue("Bilibili parse failed: $result", result is AppResult.Success)
        val videos = (result as AppResult.Success).value
        val video = videos.selectedVideo
        assertEquals(VideoPlatform.BILIBILI, video.platform)
        assertTrue("no variants for ${video.title}", video.variants.isNotEmpty())
        assertNotNull("variants must carry a media request", video.variants.first().media)
        val summary = "bilibili: ${video.title.take(40)} | ${video.variants.size} formats | ${video.variants.first().label}"
        println(summary)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$summary\n") })
    }

    /**
     * E2 在线验收：真实下载一个 B 站分轨结果，合并后必须同时有视频轨与音轨。
     *
     * `-Pandroid.testInstrumentationRunnerArguments.live_bilibili=<链接>` 且
     * `-Pandroid.testInstrumentationRunnerArguments.live_bilibili_download=true` 时执行，默认跳过。
     * 选可用画质里最低的一档带独立音轨的结果，尽量少花流量；下载完成后用 I2 的校验函数
     * 与 MediaExtractor 双重确认合并成功。
     */
    @Test fun liveBilibiliDownloadMergesVideoAndAudio() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("live_bilibili")
        val enabled = arguments.getString("live_bilibili_download") == "true"
        org.junit.Assume.assumeTrue(
            "Pass live_bilibili and live_bilibili_download=true to enable a real Bilibili download",
            !url.isNullOrBlank() && enabled,
        )
        val parsed = com.videodown.douyin.core.ServiceLocator.videoRepository.parseVideos(url!!)
        assertTrue("Bilibili parse failed: $parsed", parsed is AppResult.Success)
        val video = (parsed as AppResult.Success).value.selectedVideo
        // 优先选「必须合并」的低画质：DASH 分轨结果才真正走 E2 的合并路径。
        val variant = video.variants.filter { it.media?.audioFormatId != null }.minByOrNull { it.qualityRank }
            ?: video.variants.minByOrNull { it.qualityRank }!!
        assertNotNull("chosen variant must carry a media request", variant.media)
        if (variant.media?.audioFormatId != null) {
            assertTrue("a merged request must name both streams: ${variant.media?.formatSelector}",
                variant.media?.formatSelector?.contains('+') == true)
        }

        val directory = File(context.cacheDir, "bilibili-download-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
            val engine = DownloadEngine(
                history,
                ResumableDownloader(com.videodown.douyin.core.ServiceLocator.douyinApiClient.okHttpClient),
                DownloadPaths(directory), scope, "", "VideoDown-Test",
                engineDownloader = EngineMediaDownloader(
                    com.videodown.douyin.core.ServiceLocator.videoEngine,
                    com.videodown.douyin.core.ServiceLocator.browserSessions,
                    File(directory, "sessions"),
                ),
                validateFile = { file, record -> validateDownloadedFile(file, record) },
            )
            val id = engine.start(video, variant)
            val done = withTimeout(300_000) {
                history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
            }
            assertEquals(done.errorMessage, DownloadStatus.COMPLETED, done.status)
            val file = File(done.filePath!!)
            assertTrue("downloaded file must exist", file.isFile && file.length() > 0)
            val tracks = MediaExtractor().let { extractor ->
                try {
                    extractor.setDataSource(file.absolutePath)
                    (0 until extractor.trackCount).map {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
                    }
                } finally { extractor.release() }
            }
            assertTrue("merged output must contain a video track: $tracks", tracks.any { it.startsWith("video/") })
            if (variant.media?.audioFormatId != null) {
                assertTrue("merged output must contain the requested audio track: $tracks", tracks.any { it.startsWith("audio/") })
            }
            val summary = "bilibili download: ${variant.media?.formatSelector} ${file.length()} bytes tracks=$tracks"
            println(summary)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$summary\n") })
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
