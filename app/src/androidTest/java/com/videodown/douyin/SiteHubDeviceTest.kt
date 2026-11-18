package com.videodown.douyin

import android.webkit.WebView
import android.view.TextureView
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.site.OnlinePlaybackSessionStore
import com.videodown.douyin.data.site.KUBB_PAGE_METADATA_JAVASCRIPT
import com.videodown.douyin.data.site.KUBB_INTERACTION_JAVASCRIPT
import com.videodown.douyin.data.site.SitePageMetadataCodec
import com.videodown.douyin.data.site.SiteSourceAdapter
import com.videodown.douyin.data.site.SiteSourceRegistry
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.domain.model.MediaSourceType
import com.videodown.douyin.domain.model.SiteCapabilities
import com.videodown.douyin.domain.model.SiteChannel
import com.videodown.douyin.domain.model.SiteSource
import com.videodown.douyin.domain.model.SiteWatchRecord
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.model.siteWatchId
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.SiteWatchHistoryRepository
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.sites.OnlinePlayerScreen
import com.videodown.douyin.presentation.sites.OnlinePlayerViewModel
import com.videodown.douyin.presentation.sites.SiteBrowserScreen
import com.videodown.douyin.presentation.sites.SiteBrowserViewModel
import com.videodown.douyin.presentation.sites.SiteHubScreen
import com.videodown.douyin.presentation.sites.SiteHubViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class SiteHubDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun parseHomeOpensSiteHubAndLocalChannelsSwitchInsideWebView() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("site-kubb-card").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.sites_title)).assertIsDisplayed()
        compose.onNodeWithTag("site-source-kubb").assertIsDisplayed()

        val directory = File(context.cacheDir, "site-browser-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            FixtureServer(directory).use { server ->
                val source = SiteSource(
                    id = "fixture",
                    homeUrl = server.baseUrl,
                    channels = listOf(SiteChannel("movie", "/plain"), SiteChannel("drama", "/dynamic")),
                    navigationHosts = setOf("127.0.0.1"),
                    capabilities = SiteCapabilities(onlinePlayback = true, download = true),
                    mediaHostSuffixes = setOf("127.0.0.1"),
                )
                val browserModel = SiteBrowserViewModel()
                val adapter = SiteSourceAdapter(source)
                try {
                    fun showBrowser() = compose.runOnUiThread {
                        compose.activity.setContent {
                            VideoDownTheme { SiteBrowserScreen(adapter, onBack = {}, onOpenPlayer = {}, model = browserModel) }
                        }
                    }
                    showBrowser()
                    waitForWebTitle("Plain fixture")
                    compose.onNodeWithTag("site-channel-drama").performClick()
                    waitForWebTitle("Dynamic fixture")

                    var before: WebView? = null
                    onView(isAssignableFrom(WebView::class.java)).check { view, _ -> before = view as WebView }
                    compose.runOnUiThread { compose.activity.setContent { Text("away") } }
                    compose.waitForIdle()
                    showBrowser()
                    waitForWebTitle("Dynamic fixture")
                    onView(isAssignableFrom(WebView::class.java)).check { view, _ ->
                        assertTrue("返回站点页应复用同一个 WebView", view === before)
                    }
                } finally {
                    compose.runOnUiThread { browserModel.destroyRetainedWebView() }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun localHlsPlaysWritesProgressAndDownloadsTheExactVariant() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "site-player-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            createMediaFixtures(context, directory)
            FixtureServer(directory).use { server ->
                val variant = VideoVariant(
                    url = server.baseUrl + "/sample.m3u8",
                    label = "Fixture HLS",
                    referer = server.baseUrl + "/page",
                    media = MediaDownload(
                        type = MediaSourceType.HLS,
                        pageUrl = server.baseUrl + "/page",
                        mediaId = "fixture-hls",
                        extension = "mp4",
                        userAgent = "SiteHubDeviceTest/1.0",
                    ),
                )
                val video = DouyinVideo(
                    awemeId = "fixture-hls",
                    title = "本地 HLS",
                    durationMs = 3_000,
                    variants = listOf(variant),
                    shareUrl = server.baseUrl + "/page",
                )
                val sessions = OnlinePlaybackSessionStore()
                val session = sessions.create(
                    siteId = "fixture",
                    pageUrl = server.baseUrl + "/page?id=episode-1",
                    video = video,
                    variant = variant,
                    referer = server.baseUrl + "/page?id=episode-1",
                    userAgent = "SiteHubDeviceTest/1.0",
                    resumePositionMs = 0,
                )
                val history = MemorySiteHistory()
                var downloaded: Pair<DouyinVideo, VideoVariant>? = null
                val downloader = object : Downloader by ServiceLocator.downloader {
                    override suspend fun start(
                        video: DouyinVideo,
                        variant: VideoVariant,
                        subtitles: com.videodown.douyin.domain.model.SubtitleSelection,
                    ): String {
                        downloaded = video to variant
                        return "fixture-download"
                    }
                }
                val model = OnlinePlayerViewModel(sessions, history, downloader)
                compose.runOnUiThread {
                    compose.activity.setContent {
                        VideoDownTheme { OnlinePlayerScreen(session.id, onBack = {}, model = model) }
                    }
                }
                compose.waitUntil(30_000) {
                    var ready = false
                    compose.runOnUiThread {
                        val player = findPlayer(compose.activity.window.decorView)?.player
                        ready = player?.playbackState == androidx.media3.common.Player.STATE_READY &&
                            player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
                    }
                    ready
                }
                compose.runOnUiThread {
                    assertTrue(
                        "在线播放器必须使用 TextureView，避免首帧前独立黑色 surface 盖住海报",
                        findPlayer(compose.activity.window.decorView)?.videoSurfaceView is TextureView,
                    )
                }
                compose.waitUntil(8_000) { history.records.value.isNotEmpty() }
                val downloadLabel = context.getString(R.string.sites_download)
                if (compose.onAllNodesWithContentDescription(downloadLabel).fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithTag("online-player-surface").performClick()
                }
                compose.onNodeWithContentDescription(downloadLabel).performClick()
                compose.waitUntil(5_000) { downloaded != null }
                assertTrue(history.records.value.single().pageUrl.endsWith("?id=episode-1"))
                assertEquals(video.copy(shareUrl = session.pageUrl), downloaded!!.first)
                assertEquals(variant, downloaded!!.second)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun pageMetadataReplacesGenericHistoryTitleAndAddsCoverAndEpisode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "site-metadata-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            FixtureServer(directory).use { server ->
                val pageUrl = server.baseUrl + "/play/fixture?id=e2"
                val source = SiteSource(
                    id = "fixture",
                    homeUrl = server.baseUrl,
                    channels = listOf(SiteChannel("movie", "/play/fixture?id=e2")),
                    navigationHosts = setOf("127.0.0.1"),
                    capabilities = SiteCapabilities(onlinePlayback = true, download = false),
                    mediaHostSuffixes = setOf("127.0.0.1"),
                )
                val record = SiteWatchRecord(
                    id = siteWatchId(source.id, pageUrl),
                    siteId = source.id,
                    pageUrl = pageUrl,
                    title = "chunklist_b123.m3u8",
                )
                val history = MemorySiteHistory(listOf(record))
                val model = SiteBrowserViewModel(history = history)
                try {
                    compose.runOnUiThread {
                        compose.activity.setContent {
                            VideoDownTheme {
                                SiteBrowserScreen(
                                    SiteSourceAdapter(source, KUBB_PAGE_METADATA_JAVASCRIPT),
                                    onBack = {},
                                    onOpenPlayer = {},
                                    model = model,
                                )
                            }
                        }
                    }
                    waitForWebTitle("Metadata fixture")
                    val observedUrl = AtomicReference<String>()
                    val observedMetadata = AtomicReference<com.videodown.douyin.domain.model.SitePageMetadata>()
                    onView(isAssignableFrom(WebView::class.java)).check { view, _ ->
                        observedUrl.set((view as WebView).url)
                        view.evaluateJavascript(KUBB_PAGE_METADATA_JAVASCRIPT) { encoded ->
                            observedMetadata.set(SitePageMetadataCodec.decodeJavascriptResult(encoded))
                        }
                    }
                    compose.waitUntil(3_000) { observedMetadata.get() != null }
                    assertEquals(pageUrl, observedUrl.get())
                    compose.waitUntil(8_000) {
                        history.records.value.singleOrNull()?.title != "chunklist_b123.m3u8"
                    }
                    assertEquals("Fixture Series · Episode 2", history.records.value.single().title)
                    assertEquals(server.baseUrl + "/poster.jpg", history.records.value.single().coverUrl)
                } finally {
                    compose.runOnUiThread { model.destroyRetainedWebView() }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun historyCardsUseFallbackProgressAndDeleteWithoutOpening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val generic = SiteWatchRecord(
            id = "generic",
            siteId = "kubb",
            pageUrl = "https://www.kubb.tv/play/generic",
            title = "chunklist_b123.m3u8",
            durationMs = 100_000,
        )
        val resumed = SiteWatchRecord(
            id = "resumed",
            siteId = "kubb",
            pageUrl = "https://www.kubb.tv/play/resumed",
            title = "一部有真实标题的测试影片",
            durationMs = 100_000,
            positionMs = 33_000,
        )
        val history = MemorySiteHistory(listOf(generic, resumed))
        val model = SiteHubViewModel(SiteSourceRegistry(), history)
        var opened = false
        compose.runOnUiThread {
            compose.activity.setContent {
                VideoDownTheme {
                    SiteHubScreen(
                        onBack = {},
                        onOpenSite = { _, _ -> opened = true },
                        model = model,
                    )
                }
            }
        }
        compose.waitUntil(5_000) { history.records.value.size == 2 }
        compose.onNodeWithText(context.getString(R.string.sites_unknown_video_title)).assertIsDisplayed()
        compose.onNodeWithText("一部有真实标题的测试影片").assertIsDisplayed()
        compose.onNodeWithTag("site-watch-progress-generic").assertDoesNotExist()
        compose.onNodeWithTag("site-watch-progress-resumed").assertIsDisplayed()

        compose.onAllNodesWithContentDescription(context.getString(R.string.sites_delete_history))
            .onFirst().performClick()
        compose.waitUntil(5_000) { history.records.value.size == 1 }
        assertTrue("删除观看记录不应打开站点", !opened)
    }

    @Test fun listCardClickCapturesRealTitleCoverAndPlayPageBeforeNavigation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "site-selection-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            FixtureServer(directory).use { server ->
                val source = SiteSource(
                    id = "fixture",
                    homeUrl = server.baseUrl,
                    channels = listOf(SiteChannel("movie", "/list-selection")),
                    navigationHosts = setOf("127.0.0.1"),
                    capabilities = SiteCapabilities(onlinePlayback = true, download = true),
                    mediaHostSuffixes = setOf("127.0.0.1"),
                )
                val model = SiteBrowserViewModel()
                try {
                    compose.runOnUiThread {
                        compose.activity.setContent {
                            VideoDownTheme {
                                SiteBrowserScreen(
                                    SiteSourceAdapter(source, KUBB_PAGE_METADATA_JAVASCRIPT, KUBB_INTERACTION_JAVASCRIPT),
                                    onBack = {},
                                    onOpenPlayer = {},
                                    model = model,
                                )
                            }
                        }
                    }
                    waitForWebTitle("List selection fixture")
                    val metadata = AtomicReference<com.videodown.douyin.domain.model.SitePageMetadata>()
                    onView(isAssignableFrom(WebView::class.java)).check { view, _ ->
                        (view as WebView).evaluateJavascript("document.querySelector('.video-teaser > a').click()") {
                            view.evaluateJavascript(KUBB_PAGE_METADATA_JAVASCRIPT) { encoded ->
                                metadata.set(SitePageMetadataCodec.decodeJavascriptResult(encoded))
                            }
                        }
                    }
                    compose.waitUntil(3_000) { metadata.get() != null }
                    assertEquals("真实影片名称", metadata.get().displayTitle)
                    assertEquals(server.baseUrl + "/poster.jpg", metadata.get().safeCoverUrl)
                    assertEquals(server.baseUrl + "/play/selected", metadata.get().safePageUrl)
                } finally {
                    compose.runOnUiThread { model.destroyRetainedWebView() }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun waitForWebTitle(title: String) {
        compose.waitUntil(20_000) {
            var loaded = false
            onView(isAssignableFrom(WebView::class.java)).check { view, _ ->
                loaded = (view as WebView).title == title && view.progress == 100
            }
            loaded
        }
    }

    private fun findPlayer(view: android.view.View): PlayerView? {
        if (view is PlayerView) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}

private class MemorySiteHistory(initial: List<SiteWatchRecord> = emptyList()) : SiteWatchHistoryRepository {
    private val mutable = MutableStateFlow(initial)
    override val records: StateFlow<List<SiteWatchRecord>> = mutable
    override suspend fun load(): List<SiteWatchRecord> = mutable.value
    override suspend fun upsert(record: SiteWatchRecord) {
        mutable.value = listOf(record) + mutable.value.filterNot { it.id == record.id }
    }
    override suspend fun delete(id: String) { mutable.value = mutable.value.filterNot { it.id == id } }
    override suspend fun clear() { mutable.value = emptyList() }
    override suspend fun find(id: String): SiteWatchRecord? = mutable.value.firstOrNull { it.id == id }
}
