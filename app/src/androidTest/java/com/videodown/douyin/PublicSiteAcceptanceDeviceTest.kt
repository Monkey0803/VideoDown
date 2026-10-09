package com.videodown.douyin

import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadEngine
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.download.ResumableDownloader
import com.videodown.douyin.data.generic.EngineMediaDownloader
import com.videodown.douyin.data.generic.validateVideo
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.data.remote.DouyinApiClient
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
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Opt-in public samples. No stored credentials are printed, cleared, or replaced. */
@RunWith(AndroidJUnit4::class)
class PublicSiteAcceptanceDeviceTest {
    @Test fun readableNegativeTimestampSampleIsNotAnEmptyVideo() {
        val path = InstrumentationRegistry.getArguments().getString("negative_pts_fixture_path")
        assumeTrue("Opt-in captured public Chrome clip regression", !path.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(path!!).canonicalFile
        require(file.path.startsWith(context.cacheDir.canonicalPath + File.separator))
        val bytes = file.readBytes()
        assertEquals("Only use the verified public 258137-byte clip", 258137, bytes.size)
        assertEquals("c8e755c8628fb75f33914b5c050c5c6ca4cf3fdc76190544a3607894503402db",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }
            extractor.selectTrack(track)
            report("NEGATIVE_PTS: timestamp=${extractor.sampleTime}, track=${extractor.sampleTrackIndex}")
            assertTrue("Must reproduce a negative first presentation timestamp", extractor.sampleTime < 0)
            assertEquals(track, extractor.sampleTrackIndex)
            assertTrue("Actual encoded video sample must be readable", extractor.readSampleData(ByteBuffer.allocate(bytes.size), 0) > 0)
        } finally { extractor.release() }
        validateVideo(file)
        report("NEGATIVE_PTS: production validation passed")
        val truncated = File(context.cacheDir, "invalid-clip-${UUID.randomUUID()}.mp4")
        try {
            truncated.writeBytes(bytes.copyOf(32))
            report("NEGATIVE_PTS: validating truncated container")
            try { validateVideo(truncated); fail("Truncated file must still be rejected") }
            catch (_: Exception) { /* Expected validation failure. */ }
        } finally { truncated.delete() }
    }

    @Test fun douyin() = verify("douyin", VideoPlatform.DOUYIN)
    @Test fun weibo() = verify("weibo", VideoPlatform.WEIBO)
    @Test fun kuaishou() = verify("kuaishou", VideoPlatform.KUAISHOU)
    @Test fun x() = verify("x", VideoPlatform.X)
    @Test fun ordinaryWebPage() = verify("web", VideoPlatform.GENERIC)

    private fun verify(key: String, platform: VideoPlatform) = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("public_${key}_url")
        assumeTrue("Pass public_${key}_url for an authorized public sample", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "public-acceptance-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(240_000) {
                report("PUBLIC_$key: production repository parse")
                val parsed = ServiceLocator.videoRepository.parseVideos(url!!)
                assertTrue("PUBLIC_$key parse: $parsed", parsed is AppResult.Success)
                val video = (parsed as AppResult.Success).value.selectedVideo
                assertEquals(platform, video.platform)
                assertTrue(video.title.isNotBlank())
                val variant = video.variants.minWith(compareBy({ it.qualityRank }, { it.sizeBytes ?: Long.MAX_VALUE }))
                report("PUBLIC_$key SELECTION: label=${variant.label}, type=${variant.media?.type}, extension=${variant.media?.extension}, formats=${video.variants.map { it.label }}")
                val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
                val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
                    DownloadPaths(File(directory, "downloads")), scope, platform.referer, DouyinApiClient.USER_AGENT,
                    engineDownloader = EngineMediaDownloader(ServiceLocator.videoEngine, ServiceLocator.browserSessions,
                        File(directory, "sessions")), browserCookie = { requestUrl -> runBlocking { ServiceLocator.browserSessions.cookie(requestUrl) } },
                    validateFile = { file ->
                        try { validateVideo(file) } catch (error: Exception) {
                            val evidence = File(context.cacheDir, "public-site-evidence").apply { mkdirs() }
                            file.copyTo(File(evidence, "$key-invalid.${file.extension}"), overwrite = true)
                            report("PUBLIC_$key VALIDATION_FAILED: ${error.javaClass.simpleName}: ${error.message}")
                            throw error
                        }
                    })
                val id = engine.start(video, variant)
                val completed = history.observeRecord(id).first {
                    it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.PAUSED)
                }!!
                assertEquals("PUBLIC_$key download: ${completed.errorMessage}", DownloadStatus.COMPLETED, completed.status)
                val file = File(completed.filePath!!)
                assertTrue(file.length() > 0)
                assertEquals(file.length(), completed.totalBytes)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                    report("PUBLIC_$key TRACKS: ${formats.map { it.getString(MediaFormat.KEY_MIME) }}")
                    assertTrue("A real video track must be present", formats.any { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") })
                } finally { extractor.release() }
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.absolutePath)
                    assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                    assertTrue((reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0)
                    report("PUBLIC_$key COMPLETE: bytes=${file.length()}, quality=${variant.label}, video track and duration verified")
                } finally { reader.release() }
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
