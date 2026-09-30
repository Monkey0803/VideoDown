package com.videodown.douyin

import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.*
import com.videodown.douyin.data.download.*
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.remote.WeiboJsonParser
import com.videodown.douyin.data.remote.DouyinApiClient
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.*
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.home.HomeScreen
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.player.PlayerScreen
import com.videodown.douyin.presentation.player.PlayerViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** No user history mutations: downloads and playback use isolated cache files and view models. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WeiboGifDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val directory = File(instrumentation.targetContext.cacheDir, "weibo-gif-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
        DownloadPaths(directory), scope, DouyinApiClient.REFERER, DouyinApiClient.USER_AGENT)
    private lateinit var vm: HomeViewModel
    private val playbackId = mutableStateOf<String?>(null)

    @After fun cleanup() {
        compose.runOnUiThread {
            playbackId.value = null
            store.clear()
        }
        compose.waitForIdle()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        directory.deleteRecursively()
    }

    @Test fun selectingAnotherGifDownloadsThatMediaRatherThanAnotherQuality() {
        val parsed = WeiboJsonParser().parseVideos("""{"mblogid":"fixture","text_raw":"多动图测试",
            "pic_ids":["one","two"],"pic_infos":{
                "one":{"video":"https://video.example/one.mp4","largest":{"width":720,"height":1280}},
                "two":{"video":"https://video.example/two.mp4","largest":{"width":360,"height":640}}
            }}""", "fixture")!!
        val repository = object : VideoRepository {
            override suspend fun parseShareText(shareText: String) = AppResult.Success(parsed.selectedVideo)
            override suspend fun parseVideos(shareText: String) = AppResult.Success(parsed)
        }
        val downloads = CopyOnWriteArrayList<Pair<DouyinVideo, VideoVariant>>()
        val recorder = object : Downloader by engine {
            override suspend fun start(video: DouyinVideo, variant: VideoVariant): String {
                downloads += video to variant
                return "fixture-task"
            }
        }
        show(repository, recorder)
        parseThroughUi()
        compose.onNodeWithText("选择视频（2）").assertIsDisplayed()
        compose.onNodeWithText("动图 2").performClick()
        compose.onNodeWithText("360P").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("720P").assertDoesNotExist()
        compose.onNodeWithText("保存到本地").performClick()
        compose.waitUntil(5_000) { downloads.isNotEmpty() }
        assertEquals("fixture_two", downloads.single().first.awemeId)
        assertEquals("https://video.example/two.mp4", downloads.single().second.url)
    }

    @Test fun liveGifParsesDownloadsAndPlaysThroughTheUi() {
        assumeTrue("Pass -e live_weibo_gif true to enable the real Weibo download",
            InstrumentationRegistry.getArguments().getString("live_weibo_gif") == "true")
        show(ServiceLocator.videoRepository)
        parseThroughUi()
        assertEquals(VideoPlatform.WEIBO, vm.state.value.video!!.platform)
        assertEquals(1, vm.state.value.videos.size)
        assertTrue(vm.state.value.video!!.awemeId.startsWith("RkkIUuy8h_"))
        compose.onNodeWithText("720P").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存到本地").performClick()
        compose.waitUntil(60_000) {
            vm.state.value.activeRecord?.status in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.PAUSED)
        }
        val record = vm.state.value.activeRecord!!
        assertEquals(record.errorMessage, DownloadStatus.COMPLETED, record.status)
        assertEquals("https://weibo.com/", record.downloadReferer)
        assertEquals("https://m.weibo.cn/status/RkkIUuy8h", record.sourcePageUrl)
        val file = File(record.filePath!!)
        assertTrue(file.length() > 0)
        assertEquals(record.totalBytes, file.length())
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            assertEquals("720", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("1280", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue(duration in 5_000..6_500)
            report("Weibo GIF complete: bytes=${file.length()}, 720x1280, durationMs=$duration")
        } finally { reader.release() }
        // The queue snackbar temporarily covers the bottom action; click only after it dismisses.
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        compose.onNodeWithText("已加入下载队列").assertDoesNotExist()
        compose.onNodeWithText("查看已保存视频").performClick()
        var playbackDetails = "PlayerView not attached"
        try {
            compose.waitUntil(10_000) {
                var decoded = false
                compose.runOnUiThread {
                    val player = findPlayerView(compose.activity.window.decorView)?.player as? ExoPlayer
                    val counters = player?.videoDecoderCounters
                    counters?.ensureUpdated()
                    playbackDetails = "id=${playbackId.value}, state=${player?.playbackState}, " +
                        "position=${player?.currentPosition}, frames=${counters?.renderedOutputBufferCount}, " +
                        "error=${player?.playerError}"
                    decoded = (counters?.renderedOutputBufferCount ?: 0) > 0 && (player?.currentPosition ?: 0) > 0
                }
                decoded
            }
        } catch (error: Throwable) {
            throw AssertionError("GIF playback did not render: $playbackDetails", error)
        }
        compose.onNodeWithContentDescription("横屏播放").assertIsDisplayed()
        report("Weibo GIF playback: ExoPlayer rendered video frames and advanced playback position")
    }

    private fun show(repository: VideoRepository, downloader: Downloader = engine) {
        compose.runOnUiThread {
            vm = HomeViewModel(repository, downloader, { it.defaultVariant }, fetchSize = { null })
            val player = PlayerViewModel(history)
            store.put("home", vm)
            store.put("player", player)
            compose.activity.setContent {
                VideoDownTheme {
                    val id = playbackId.value
                    if (id == null) HomeScreen(null, {}, {}, {}, { playbackId.value = it }, emptyList(), vm)
                    else PlayerScreen(id, onBack = { playbackId.value = null }, viewModel = player)
                }
            }
        }
    }

    private fun parseThroughUi() {
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(URL)
        compose.onNodeWithText("立即解析").performClick()
        compose.waitUntil(60_000) { vm.state.value.video != null || (!vm.state.value.isParsing && vm.state.value.errorMessage != null) }
        assertNotNull(vm.state.value.errorMessage, vm.state.value.video)
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findPlayerView(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })

    companion object { private const val URL = "https://weibo.com/2453184077/RkkIUuy8h" }
}
