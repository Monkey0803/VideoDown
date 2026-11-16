package com.videodown.douyin

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.site.SiteSourceRegistry
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.MediaSourceType
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * KUBB.TV 的真实站点验收。默认跳过，避免 CI 依赖站点可用性或产生媒体流量。
 * 只访问匿名公开页面，不携带 WebView Cookie，也不尝试登录、会员、付费或 DRM 内容。
 */
@RunWith(AndroidJUnit4::class)
class KubbLiveDeviceTest {
    @Test fun representativeCategoriesParseAndOneHlsStartsPlaying() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass live_kubb=true to run the public KUBB.TV check", arguments.getString("live_kubb") == "true")
        val source = SiteSourceRegistry.KUBB
        val userAgent = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36"
        val representatives = listOf("movie", "drama", "anime", "svideo").map { channelId ->
            val channel = source.channels.single { it.id == channelId }
            val categoryUrl = source.channelUrl(channel)
            val html = ServiceLocator.douyinApiClient.okHttpClient.newCall(
                Request.Builder().url(categoryUrl).header("User-Agent", userAgent).build(),
            ).execute().use { response ->
                check(response.isSuccessful) { "KUBB category failed: $categoryUrl HTTP ${response.code}" }
                response.body.string()
            }
            val relative = Regex("""href=[\"'](/play/[^\"']+)[\"']""")
                .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
                ?: error("KUBB category contains no public play page: $categoryUrl")
            val pageUrl = Uri.parse(categoryUrl).buildUpon().path(relative.substringBefore('?'))
                .encodedQuery(relative.substringAfter('?', "").takeIf(String::isNotBlank)).build().toString()
            val result = ServiceLocator.genericRepository.parse(pageUrl, browserSession = false, referer = pageUrl, userAgent = userAgent)
            check(result is AppResult.Success) { "KUBB parse failed: $pageUrl -> $result" }
            val video = result.value.videos.firstOrNull { it.variants.isNotEmpty() }
                ?: error("KUBB page has no playable public variant: $pageUrl")
            val variant = video.variants.first { it.media?.audioOnly != true }
            assertTrue("Unexpected media host: ${variant.url}", source.acceptsMediaUrl(variant.url))
            Triple(pageUrl, video, variant)
        }

        val (pageUrl, video, variant) = representatives.first()
        assertStartsPlaying(pageUrl, variant, userAgent)

        if (arguments.getString("live_kubb_download") == "true") {
            val id = ServiceLocator.downloader.start(video, variant)
            try {
                ServiceLocator.historyRepository.observeRecord(id).first { it != null }
            } finally {
                ServiceLocator.downloader.cancel(id, true)
            }
        }
    }

    private fun assertStartsPlaying(pageUrl: String, variant: VideoVariant, userAgent: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        var player: ExoPlayer? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            val headers = mapOf("Referer" to pageUrl, "User-Agent" to userAgent)
            val dataSource = OkHttpDataSource.Factory(ServiceLocator.douyinApiClient.okHttpClient)
                .setDefaultRequestProperties(headers)
            player = ExoPlayer.Builder(instrumentation.targetContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) ready.countDown()
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            failure = error
                            ready.countDown()
                        }
                    })
                    val mime = when (variant.media?.type) {
                        MediaSourceType.HLS -> MimeTypes.APPLICATION_M3U8
                        MediaSourceType.DASH -> MimeTypes.APPLICATION_MPD
                        else -> null
                    }
                    setMediaItem(MediaItem.Builder().setUri(variant.url).apply { mime?.let(::setMimeType) }.build())
                    prepare()
                    playWhenReady = true
                }
        }
        try {
            assertTrue("KUBB media did not become ready: ${failure?.message}", ready.await(45, TimeUnit.SECONDS) && failure == null)
            Thread.sleep(2_000)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
        }
    }
}
