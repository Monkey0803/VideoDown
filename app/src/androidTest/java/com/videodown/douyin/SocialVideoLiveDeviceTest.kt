package com.videodown.douyin

import android.media.MediaMetadataRetriever
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadEngine
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.download.ResumableDownloader
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.remote.DouyinApiClient
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.VideoPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in live checks: parse the supplied URLs, download a whole MP4, and inspect its media tracks. */
@RunWith(AndroidJUnit4::class)
class SocialVideoLiveDeviceTest {
    @Test fun weiboFullDownload() = verify("https://weibo.com/1234567890/RklMe8iOM", VideoPlatform.WEIBO)
    @Test fun kuaishouFullDownload() = verify("https://www.kuaishou.com/f/X3uQi2UzJmqN2fq", VideoPlatform.KUAISHOU)

    private fun verify(url: String, platform: VideoPlatform) = runBlocking {
        assumeTrue("Pass -e live_platforms true to enable real network downloads",
            InstrumentationRegistry.getArguments().getString("live_platforms") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "social-live-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(180_000) {
                report("${context.getString(com.videodown.douyin.presentation.videoPlatformLabelRes(platform))}: parsing")
                val result = ServiceLocator.videoRepository.parseUrl(url)
                assertTrue("Parse failed: $result", result is AppResult.Success)
                val video = (result as AppResult.Success).value
                assertEquals(platform, video.platform)
                assertTrue(video.author.nickname.isNotBlank())
                val variant = video.sortedVariants.last()
                assertEquals(platform.referer, variant.referer)
                report("${context.getString(com.videodown.douyin.presentation.videoPlatformLabelRes(platform))}: ${video.title.take(45)}, downloading ${variant.label}")
                val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
                val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
                    DownloadPaths(directory), scope, DouyinApiClient.REFERER, DouyinApiClient.USER_AGENT)
                val id = engine.start(video, variant)
                val record = history.observeRecord(id).first { it?.status in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.PAUSED) }!!
                assertEquals("Download error: ${record.errorMessage}", DownloadStatus.COMPLETED, record.status)
                assertEquals(platform, record.platform)
                assertEquals(platform.referer, record.downloadReferer)
                val file = File(record.filePath!!)
                assertEquals(record.totalBytes, file.length())
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    assertTrue("MP4 needs a real video track", duration > 0 && width > 0 && height > 0)
                    report("${context.getString(com.videodown.douyin.presentation.videoPlatformLabelRes(platform))}: COMPLETE bytes=${file.length()}, video=${width}x$height, durationMs=$duration")
                } finally { retriever.release() }
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
}
