package com.videodown.douyin

import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.*
import com.videodown.douyin.data.download.*
import com.videodown.douyin.data.generic.*
import com.videodown.douyin.data.gallery.GalleryExporter
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class P2AudioDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var paths: DownloadPaths
    private lateinit var history: DownloadHistoryRepositoryImpl
    private lateinit var scope: CoroutineScope
    private lateinit var engine: DownloadEngine
    private val blocked = object : DownloadNetworkPolicy {
        override val allowed = MutableStateFlow(false)
        override fun isAllowed() = false
    }
    @Before fun setup() {
        directory = File(context.cacheDir, "p2-audio-${UUID.randomUUID()}").apply { mkdirs() }
        paths = DownloadPaths(File(directory, "downloads"))
        history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = engine(blocked)
    }
    private fun engine(policy: DownloadNetworkPolicy, repository: com.videodown.douyin.domain.repository.DownloadHistoryRepository = history): DownloadEngine {
        val gate = Mutex()
        return DownloadEngine(repository, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient), paths, scope, "", "VideoDown-Test",
            networkPolicy = policy, maintenanceMutex = gate,
            engineDownloader = EngineMediaDownloader(ServiceLocator.videoEngine, ServiceLocator.browserSessions, File(directory, "sessions"), repository, gate))
    }
    @After fun close() = runBlocking { scope.coroutineContext.job.cancelAndJoin(); directory.deleteRecursively(); Unit }
    private suspend fun saved(asset: String): DownloadRecord {
        val file = File(paths.downloadDir, asset)
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        return DownloadRecord("source-${UUID.randomUUID()}", "media", "音频验证", fileName = file.name, filePath = file.path,
            partialPath = file.path + ".part", totalBytes = file.length(), downloadedBytes = file.length(), status = DownloadStatus.COMPLETED)
            .also { history.upsert(it) }
    }
    private suspend fun extract(source: DownloadRecord): DownloadRecord {
        val (video, variant) = source.audioExtractionSelection()
        val id = engine.start(video, variant)
        return withTimeout(90000) { history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.PAUSED) }!! }
    }
    private fun audioDigest(file: File): ByteArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            extractor.selectTrack(index)
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(1024 * 1024)
            while (true) {
                buffer.clear(); val count = extractor.readSampleData(buffer, 0)
                if (count < 0) break
                hash.update(buffer.array(), 0, count)
                if (!extractor.advance()) break
            }
            return hash.digest()
        } finally { extractor.release() }
    }
    private fun assertAudio(file: File) {
        validateAudio(file)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertTrue(format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/"))
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) in 2_800_000L..3_500_000L)
        } finally { extractor.release() }
    }
    @Test fun localAacExtractionWorksOfflinePreservesEncodedSamplesAndOriginalAndSavesToMusic() = runBlocking {
        val source = saved("aac-video-fixture.mp4")
        val bytes = File(source.filePath!!).readBytes()
        val result = extract(source)
        assertEquals(result.errorMessage, DownloadStatus.COMPLETED, result.status)
        assertTrue(result.isAudio)
        val output = File(result.filePath!!); assertEquals("m4a", output.extension); assertAudio(output)
        assertArrayEquals(bytes, File(source.filePath!!).readBytes())
        assertArrayEquals(audioDigest(File(source.filePath!!)), audioDigest(output))
        val reopened = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json"))).getById(result.id)!!
        assertTrue(reopened.isAudio)
        val (video, variant) = source.audioExtractionSelection()
        assertEquals(result.id, engine.findDuplicate(video, variant)?.id)
        val exported = GalleryExporter(context).export(result).getOrThrow()
        try {
            context.contentResolver.query(exported, arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("audio/mp4", it.getString(0)); assertTrue(it.getString(1).startsWith("Music/"))
            }
            assertArrayEquals(output.readBytes(), context.contentResolver.openInputStream(exported)!!.use { it.readBytes() })
        } finally { context.contentResolver.delete(exported, null, null) }
    }
    @Test fun queuedLocalExtractionCanPauseThenResumeOfflineWithoutLosingOriginal() = runBlocking {
        val source = saved("aac-video-fixture.mp4")
        val original = File(source.filePath!!)
        val samples = audioDigest(original)
        // Preserve valid media and add padding so real source copying remains observable.
        java.io.FileOutputStream(original, true).use { output ->
            val padding = ByteArray(256 * 1024)
            repeat(8) { output.write(padding) }
        }
        val originalLength = original.length()
        val copying = CompletableDeferred<Unit>()
        val holdFirstCopy = java.util.concurrent.atomic.AtomicBoolean(true)
        val repository = object : com.videodown.douyin.domain.repository.DownloadHistoryRepository by history {
            override suspend fun upsert(record: DownloadRecord) {
                history.upsert(record)
                if (record.status == DownloadStatus.RUNNING && record.phase == TransferPhase.EXTRACTING &&
                    record.downloadedBytes > 0 && holdFirstCopy.compareAndSet(true, false)) {
                    copying.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        engine = engine(blocked, repository)
        val (video, variant) = source.audioExtractionSelection()
        val id = engine.start(video, variant)
        withTimeout(30000) { copying.await() }
        withTimeout(10000) { engine.pause(id) }
        assertEquals(DownloadStatus.PAUSED, history.getById(id)!!.status)
        assertFalse(File(paths.engineDirectory(id), "source.snapshot").exists())
        assertEquals(originalLength, original.length())
        engine.resume(id)
        val result = withTimeout(90000) { history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!! }
        assertEquals(result.errorMessage, DownloadStatus.COMPLETED, result.status)
        val output = File(result.filePath!!)
        assertAudio(output)
        assertArrayEquals(samples, audioDigest(output))
        assertEquals(originalLength, original.length())
    }
    @Test fun opusSourceFallsBackToAacAndKeepsOriginal() = runBlocking {
        val source = saved("opus-video-fixture.webm")
        val bytes = File(source.filePath!!).readBytes()
        val result = extract(source)
        assertEquals(result.errorMessage, DownloadStatus.COMPLETED, result.status)
        assertAudio(File(result.filePath!!)); assertArrayEquals(bytes, File(source.filePath!!).readBytes())
    }
    @Test fun silentVideoProducesExplicitNoAudioFailureAndKeepsOriginal() = runBlocking {
        val source = saved("landscape-fixture.mp4")
        val result = extract(source)
        assertEquals(DownloadStatus.FAILED, result.status)
        assertEquals(DownloadFailureKind.NO_AUDIO, result.failureKind)
        assertEquals("原视频没有音轨", failureGuidance(result.failureKind!!).title)
        assertFalse(result.canRetryDownload); assertTrue(File(source.filePath!!).isFile)
        assertFalse(paths.engineDirectory(result.id).listFiles().orEmpty().any { it.name == "source.snapshot" })
    }
    @Test fun actualDashParseOffersIndependentAudioAndDownloadsOnlyItsTrack() = runBlocking {
        engine = engine(DownloadNetworkPolicy.Unrestricted)
        val web = File(directory, "web").apply { mkdirs() }
        createMediaFixtures(context, web)
        FixtureServer(web).use { server ->
            val parsed = ServiceLocator.genericRepository.parseVideos(server.baseUrl + "/sample.mpd")
            assertTrue(parsed.toString(), parsed is AppResult.Success)
            val video = (parsed as AppResult.Success).value.selectedVideo
            assertTrue(video.audioVariants.isNotEmpty())
            val selected = video.audioVariants.first()
            assertTrue(selected.media!!.audioOnly)
            val id = engine.start(video, selected)
            val result = withTimeout(90000) { history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!! }
            assertEquals(result.errorMessage, DownloadStatus.COMPLETED, result.status)
            assertAudio(File(result.filePath!!)); assertTrue(result.isAudio)
            assertFalse(result.matchesSelection(video, video.defaultVariant!!))
        }
    }
    @Test fun cancellationAtLocalExtractionPhaseStopsProcessGroupAndCanRunAgain() = runBlocking {
        val source = saved("aac-video-fixture.mp4")
        val output = File(directory, "cancelled.m4a")
        val request = buildJsonObject { put("mode", "extract_audio"); put("input", source.filePath!!); put("output", output.path) }
        var group = 0; var completed = false; var extracting = false
        val job = launch {
            ServiceLocator.videoEngine.execute(request) { event ->
                when (event["event"]?.jsonPrimitive?.content) {
                    "pid" -> group = event["pid"]!!.jsonPrimitive.int
                    "phase" -> { extracting = true; currentCoroutineContext().cancel() }
                    "complete" -> completed = true
                }
            }
        }
        withTimeout(30000) { job.join() }
        assertTrue(extracting); assertFalse(completed); assertTrue(group > 0)
        withTimeout(3000) { while (runCatching { android.system.Os.kill(-group, 0) }.isSuccess) delay(30) }
        ServiceLocator.videoEngine.execute(request) { if (it["event"]?.jsonPrimitive?.content == "complete") completed = true }
        assertTrue(completed); assertAudio(output); assertTrue(File(source.filePath!!).isFile)
    }
}
