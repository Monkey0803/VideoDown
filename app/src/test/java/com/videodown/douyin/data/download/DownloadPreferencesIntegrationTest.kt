package com.videodown.douyin.data.download

import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class DownloadPreferencesIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val policy = object : DownloadNetworkPolicy {
        override val allowed = MutableStateFlow(true)
        override fun isAllowed() = allowed.value
    }
    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private val servers = mutableListOf<RangeAwareTestServer>()
    private val completions = CopyOnWriteArrayList<DownloadRecord>()
    private val history by lazy { DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json"))) }
    private val engine by lazy {
        DownloadEngine(history, ResumableDownloader(OkHttpClient.Builder().retryOnConnectionFailure(false).build()),
            DownloadPaths(temp.root), scope, "", "test", maxConcurrent = 1,
            networkPolicy = policy, onCompleted = { completions.add(it) })
    }

    @After fun close() { scope.cancel(); servers.forEach { it.close() } }

    private fun server(delayMs: Long = 0, statusCode: Int = 200) =
        RangeAwareTestServer(payload, statusCode = statusCode, chunkSize = 4096, chunkDelayMs = delayMs)
            .also { servers.add(it) }

    private suspend fun start(server: RangeAwareTestServer, name: String = "fixture") = engine.start(
        DouyinVideo(name, name), VideoVariant("http://127.0.0.1:${server.port}/video.mp4", "720P"))

    private suspend fun awaitStatus(id: String, status: DownloadStatus): DownloadRecord = withTimeout(6000) {
        val record = history.observeRecord(id).first { it?.status == status }!!
        if (status == DownloadStatus.COMPLETED || status == DownloadStatus.PAUSED || status == DownloadStatus.FAILED) {
            engine.progress.first { id !in it }
            // Wait for the launch completion callback to remove this task before resuming it.
            delay(30)
        }
        record
    }

    @Test fun `duplicate lookup ignores missing completed files and finds resumable task`() = runBlocking {
        val video = DouyinVideo("duplicate", "duplicate")
        val variant = VideoVariant("https://new-cdn/video", "720P")
        history.upsert(DownloadRecord("gone", video.awemeId, video.title, resolutionLabel = "720P",
            status = DownloadStatus.COMPLETED, filePath = File(temp.root, "missing.mp4").absolutePath))
        assertNull(engine.findDuplicate(video, variant))
        history.upsert(DownloadRecord("paused", video.awemeId, video.title, resolutionLabel = "720P", status = DownloadStatus.PAUSED))
        assertEquals("paused", engine.findDuplicate(video, variant)?.id)
        assertNull(engine.findDuplicate(video, variant.copy(label = "1080P")))
    }

    @Test fun `duplicate lookup prefers playable saved file and works after history reload`() = runBlocking {
        val video = DouyinVideo("duplicate", "duplicate")
        val variant = VideoVariant("https://new-cdn/video", "720P")
        val file = File(temp.root, "saved.mp4").apply { writeBytes(payload) }
        history.upsert(DownloadRecord("saved", video.awemeId, video.title, resolutionLabel = "720P",
            sourceUrl = "https://expired-cdn/video", status = DownloadStatus.COMPLETED, filePath = file.absolutePath, updatedAt = 1))
        history.upsert(DownloadRecord("active", video.awemeId, video.title, resolutionLabel = "720P", status = DownloadStatus.PENDING, updatedAt = 2))
        val reloaded = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json")))
        val freshEngine = DownloadEngine(reloaded, ResumableDownloader(OkHttpClient()), DownloadPaths(temp.root), scope, "", "test")
        assertEquals("saved", freshEngine.findDuplicate(video, variant)?.id)
    }

    @Test fun `blocked start resume and restart send no HTTP and keep partial data`() = runBlocking {
        policy.allowed.value = false
        val server = server()
        val id = start(server)
        val record = awaitStatus(id, DownloadStatus.PAUSED)
        assertEquals(WIFI_PAUSE_REASON, record.errorMessage)
        val partial = File(record.partialPath).apply { writeBytes(payload.copyOf(4096)) }
        engine.resume(id)
        awaitStatus(id, DownloadStatus.PAUSED)
        engine.restart(id)
        assertEquals(4096L, partial.length())
        assertEquals(0, server.requestCount)
        assertTrue(completions.isEmpty())
    }

    @Test fun `leaving wifi pauses with bytes preserved and resumes to one completion`() = runBlocking {
        val server = server(delayMs = 8)
        val id = start(server)
        withTimeout(3000) { engine.progress.first { (it[id]?.downloadedBytes ?: 0) > 0 } }
        policy.allowed.value = false
        val paused = awaitStatus(id, DownloadStatus.PAUSED)
        assertEquals(WIFI_PAUSE_REASON, paused.errorMessage)
        assertTrue(paused.downloadedBytes in 1 until payload.size)
        assertEquals(File(paused.partialPath).length(), paused.downloadedBytes)
        assertTrue(completions.isEmpty())
        policy.allowed.value = true
        engine.resume(id)
        val complete = awaitStatus(id, DownloadStatus.COMPLETED)
        assertArrayEquals(payload, File(complete.filePath!!).readBytes())
        assertEquals(1, completions.size)
        assertEquals("bytes=${paused.downloadedBytes}-", server.rangeHeadersSeen.last())
        engine.resume(id)
        assertEquals(1, completions.size)
    }

    @Test fun `policy change cancels a stalled socket without waiting for read timeout`() = runBlocking {
        val server = server(delayMs = 10_000)
        val id = start(server)
        withTimeout(3000) { engine.progress.first { (it[id]?.downloadedBytes ?: 0) > 0 } }
        policy.allowed.value = false
        withTimeout(2000) { awaitStatus(id, DownloadStatus.PAUSED) }
        assertTrue(completions.isEmpty())
    }

    @Test fun `queued tasks check policy before acquiring a network connection`() = runBlocking {
        val server = server(delayMs = 10_000)
        val first = start(server, "first")
        withTimeout(3000) { engine.progress.first { (it[first]?.downloadedBytes ?: 0) > 0 } }
        val second = start(server, "second")
        policy.allowed.value = false
        awaitStatus(first, DownloadStatus.PAUSED)
        awaitStatus(second, DownloadStatus.PAUSED)
        assertEquals(1, server.requestCount)
    }

    @Test fun `retry backoff cannot bypass wifi restriction`() = runBlocking {
        val server = server(statusCode = 503)
        val id = start(server)
        withTimeout(3000) { while (server.requestCount == 0) delay(10) }
        policy.allowed.value = false
        awaitStatus(id, DownloadStatus.PAUSED)
        assertEquals(1, server.requestCount)
        assertTrue(completions.isEmpty())
    }

    @Test fun `manual pause while socket is stalled remains paused without completion`() = runBlocking {
        val server = server(delayMs = 10_000)
        val id = start(server)
        withTimeout(3000) { engine.progress.first { (it[id]?.downloadedBytes ?: 0) > 0 } }
        withTimeout(2000) { engine.pause(id) }
        val paused = awaitStatus(id, DownloadStatus.PAUSED)
        assertNull(paused.errorMessage)
        assertTrue(paused.downloadedBytes > 0)
        assertTrue(completions.isEmpty())
    }

    @Test fun `notification failure does not change completed download status`() = runBlocking {
        val server = server()
        val isolated = DownloadEngine(history, ResumableDownloader(OkHttpClient()), DownloadPaths(temp.root),
            scope, "", "test", onCompleted = { throw SecurityException("permission revoked") })
        val id = isolated.start(DouyinVideo("notify", "notify"), VideoVariant("http://127.0.0.1:${server.port}/video.mp4", "720P"))
        withTimeout(3000) {
            history.observeRecord(id).first { it?.status == DownloadStatus.COMPLETED }
            isolated.progress.first { id !in it }
        }
        assertEquals(DownloadStatus.COMPLETED, history.getById(id)?.status)
    }
}
