package com.videodown.douyin

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.*
import com.videodown.douyin.data.download.*
import com.videodown.douyin.data.generic.EngineMediaDownloader
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.remote.*
import com.videodown.douyin.data.repository.*
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.*
import com.videodown.douyin.presentation.home.HomeScreen
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Actual Compose interactions, isolated history/files, and opt-in live network checks. */
@RunWith(AndroidJUnit4::class)
class XVideoDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "x-device-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
        DownloadPaths(directory), scope, DouyinApiClient.REFERER, DouyinApiClient.USER_AGENT)
    private lateinit var vm: HomeViewModel

    @After fun close() {
        compose.runOnUiThread { store.clear() }
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        directory.deleteRecursively()
    }

    @Test fun switchingVideosChangesQualityAndDownloadRecord() {
        val first = DouyinVideo("12345_11111", "第一个测试视频", platform = VideoPlatform.X, mediaLabel = "视频 1",
            variants = listOf(VideoVariant("https://video.twimg.com/one.mp4", "1080P", sizeBytes = 100)))
        val second = first.copy(awemeId = "12345_22222", title = "第二个测试视频", mediaLabel = "视频 2",
            variants = listOf(VideoVariant("https://video.twimg.com/two.mp4", "360P", sizeBytes = 200)))
        val repo = object : VideoRepository {
            override suspend fun parseShareText(shareText: String) = AppResult.Success(first)
            override suspend fun parseVideos(shareText: String) = AppResult.Success(ParsedVideos(listOf(first, second)))
        }
        // Record the chosen media without transferring a fixture URL.
        val chosen = CopyOnWriteArrayList<Pair<DouyinVideo, VideoVariant>>()
        val recorder = object : Downloader by engine {
            override suspend fun start(video: DouyinVideo, variant: VideoVariant): String {
                chosen.add(video to variant); return "fixture-task"
            }
        }
        show(repo, recorder)
        parseThroughUi()
        compose.onNodeWithText("当前 1/2 · 已选 0 个").assertIsDisplayed()
        compose.onNodeWithText("查看全部 ▾").performClick()
        compose.onNodeWithContentDescription("预览视频 2").performClick()
        compose.onNodeWithText("第二个测试视频").assertIsDisplayed()
        compose.onNodeWithText("360P").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1080P").assertDoesNotExist()
        compose.onNodeWithText("保存当前视频").performClick()
        compose.waitUntil(5_000) { chosen.isNotEmpty() }
        assertEquals(second.awemeId, chosen.single().first.awemeId)
        assertEquals(second.variants.single().url, chosen.single().second.url)
    }

    @Test fun liveGraphqlUiAndFull1080pDownload() {
        liveOnly()
        val requests = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request().url.encodedPath)
            chain.proceed(chain.request())
        }.build()
        show(MultiPlatformVideoRepository(ServiceLocator.douyinRepository, x = XVideoRepository(XVideoHttpClient(client))))
        parseThroughUi()
        assertEquals(VideoPlatform.X, vm.state.value.video!!.platform)
        assertEquals("azurlane_staff", vm.state.value.video!!.author.secUid)
        assertEquals(listOf("1080P", "720P", "360P", "270P"), vm.state.value.video!!.sortedVariants.map { it.label })
        assertTrue(requests.any { it.endsWith("TweetResultByRestId") })
        assertFalse("Primary should work for this sample", requests.any { it.contains("tweet-result") })
        compose.onNodeWithText("1080P").performScrollTo().performClick()
        screenshot("x-live-result.png")
        compose.onNodeWithText("保存到本地").performClick()
        val record = runBlocking {
            withTimeout(150_000) {
                while (vm.state.value.activeDownloadId == null) delay(30)
                history.observeRecord(vm.state.value.activeDownloadId!!).first {
                    it?.status in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.PAUSED)
                }!!
            }
        }
        assertEquals(record.errorMessage, DownloadStatus.COMPLETED, record.status)
        assertEquals(VideoPlatform.X, record.platform)
        assertEquals("https://x.com/", record.downloadReferer)
        assertTrue(record.sourcePageUrl.contains("/2096916397947257248/video/1"))
        val file = File(record.filePath!!)
        assertEquals(record.totalBytes, file.length())
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            assertEquals("1920", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("1080", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            report("X GraphQL UI/full download: bytes=${file.length()}, 1920x1080, audio=yes, durationMs=${reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)}")
        } finally { reader.release() }
    }

    @Test fun liveEmbedFallbackReturnsAllFourQualities() = runBlocking {
        liveOnly()
        val requests = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests.add(request.url.host)
            if (request.url.host == "api.x.com") Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(503).message("Simulated primary failure").body("{}".toResponseBody()).build()
            else chain.proceed(request)
        }.build()
        val result = XVideoRepository(XVideoHttpClient(client)).parse(URL)
        assertTrue(result.toString(), result is AppResult.Success)
        val video = (result as AppResult.Success).value.selectedVideo
        assertEquals(listOf("1080P", "720P", "360P", "270P"), video.sortedVariants.map { it.label })
        assertTrue(requests.contains("cdn.syndication.twimg.com"))
        val size = VideoSizeProbe(ServiceLocator.douyinApiClient.okHttpClient).sizeBytes(video.defaultVariant!!)
        assertTrue("Fallback must return a downloadable video", (size ?: 0) > 0)
        report("X live embed fallback: four qualities, 1080P size=$size")
    }

    @Test fun liveUnifiedXAddsEngineFormats() = runBlocking {
        assumeTrue("Pass -e live_x_engine true to enable X engine integration",
            InstrumentationRegistry.getArguments().getString("live_x_engine") == "true")
        val extracted = ServiceLocator.genericRepository.parse(URL)
        assertTrue(extracted.toString(), extracted is AppResult.Success)
        val unified = ServiceLocator.videoRepository.parseVideos(URL)
        assertTrue(unified.toString(), unified is AppResult.Success)
        val videos = (unified as AppResult.Success).value.videos
        assertEquals(VideoPlatform.X, videos.first().platform)
        assertTrue("extracted=${(extracted as AppResult.Success).value.videos.map { it.awemeId to it.variants.map { variant -> variant.media?.type } }}, unified=${videos.map { it.awemeId to it.variants.map { variant -> variant.media?.type } }}",
            videos.first().variants.any { it.media?.type == MediaSourceType.SEPARATE })
        report("X unified parse: videos=${videos.size}, engineFormats=${videos.sumOf { video -> video.variants.count { it.media != null } }}")
        if (InstrumentationRegistry.getArguments().getString("live_x_engine_download") == "true") {
            val separated = videos.first().variants.filter { it.media?.type == MediaSourceType.SEPARATE }
                .minBy { it.qualityRank }
            val transfer = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
                DownloadPaths(directory), scope, VideoPlatform.X.referer, DouyinApiClient.USER_AGENT,
                engineDownloader = EngineMediaDownloader(ServiceLocator.videoEngine,
                    ServiceLocator.browserSessions, File(directory, "sessions")))
            val task = transfer.start(videos.first(), separated)
            val record = withTimeout(180_000) { history.observeRecord(task).first {
                it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)
            }!! }
            assertEquals(record.errorMessage, DownloadStatus.COMPLETED, record.status)
            val media = MediaMetadataRetriever()
            try {
                media.setDataSource(record.filePath!!)
                assertEquals("yes", media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                assertEquals("yes", media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                report("X split-track download: bytes=${File(record.filePath).length()}")
            } finally { media.release() }
        }
    }

    private fun show(repo: VideoRepository, downloader: Downloader = engine) {
        compose.runOnUiThread {
            vm = HomeViewModel(repo, downloader, { it.defaultVariant })
            store.put("home", vm)
        }
        compose.setContent {
            VideoDownTheme { HomeScreen(null, {}, {}, {}, {}, emptyList(), vm) }
        }
    }
    private fun parseThroughUi() {
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(URL)
        compose.onNodeWithText("立即解析").performClick()
        compose.waitUntil(65_000) { vm.state.value.video != null || (!vm.state.value.isParsing && vm.state.value.errorMessage != null) }
        assertNotNull(vm.state.value.errorMessage, vm.state.value.video)
    }
    private fun liveOnly() = assumeTrue("Pass -e live_x true to enable X network checks",
        InstrumentationRegistry.getArguments().getString("live_x") == "true")
    private fun report(text: String) = InstrumentationRegistry.getInstrumentation()
        .sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "x-ui-test-artifacts").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    companion object { private const val URL = "https://x.com/azurlane_staff/status/2096916397947257248?s=20" }
}
