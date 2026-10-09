package com.videodown.douyin.data.download

import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class P1DownloadIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val payload = ByteArray(30000) { (it % 251).toByte() }
    private val history by lazy { DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json"))) }
    private fun variant(server: RangeAwareTestServer) = VideoVariant("http://127.0.0.1:${server.port}/video.mp4", "720P", sizeBytes = payload.size.toLong())

    @Test fun `queue priority and live concurrency control determine actual HTTP starts`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            RangeAwareTestServer(payload, chunkSize = 4096, chunkDelayMs = 10000).use { one ->
                RangeAwareTestServer(payload, chunkSize = 4096, chunkDelayMs = 10000).use { two ->
                    RangeAwareTestServer(payload, chunkSize = 4096, chunkDelayMs = 10000).use { three ->
                        val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()), DownloadPaths(temp.root), scope, "", "test", maxConcurrent = 1)
                        val first = engine.start(DouyinVideo("one", "one"), variant(one))
                        withTimeout(5000) { engine.progress.first { (it[first]?.downloadedBytes ?: 0L) > 0L } }
                        val second = engine.start(DouyinVideo("two", "two"), variant(two))
                        val third = engine.start(DouyinVideo("three", "three"), variant(three))
                        assertEquals(listOf(second, third), engine.queue.value.pendingIds)
                        assertEquals(0, two.requestCount); assertEquals(0, three.requestCount)
                        engine.moveInQueue(third, -1)
                        assertEquals(listOf(third, second), engine.queue.value.pendingIds)
                        assertTrue(history.getById(third)!!.queueOrder < history.getById(second)!!.queueOrder)
                        engine.setConcurrency(2)
                        withTimeout(5000) { engine.progress.first { (it[third]?.downloadedBytes ?: 0L) > 0L } }
                        assertEquals(0, two.requestCount); assertEquals(1, three.requestCount)
                        engine.setConcurrency(1)
                        assertEquals(2, engine.queue.value.runningIds.size)
                        engine.pause(first)
                        assertEquals(0, two.requestCount)
                        assertEquals(listOf(third), engine.queue.value.runningIds)
                        engine.cancel(second); engine.pause(third)
                        assertTrue(engine.queue.value.runningIds.isEmpty()); assertTrue(engine.queue.value.pendingIds.isEmpty())
                    }
                }
            }
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
    @Test fun `reordered and restarted queue retains its order after starts and history reload`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            RangeAwareTestServer(payload, chunkSize = 4096, chunkDelayMs = 10000).use { one ->
                RangeAwareTestServer(payload, chunkSize = 4096, chunkDelayMs = 10000).use { two ->
                    RangeAwareTestServer(payload, chunkSize = 4096, chunkDelayMs = 10000).use { three ->
                        val paths = DownloadPaths(temp.root)
                        val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()), paths, scope, "", "test", maxConcurrent = 1)
                        val first = engine.start(DouyinVideo("one", "one"), variant(one))
                        withTimeout(5000) { engine.progress.first { (it[first]?.downloadedBytes ?: 0L) > 0L } }
                        val second = engine.start(DouyinVideo("two", "two"), variant(two))
                        val third = engine.start(DouyinVideo("three", "three"), variant(three))
                        engine.restart(second)
                        assertEquals(listOf(third, second), engine.queue.value.pendingIds)
                        assertTrue(history.getById(third)!!.queueOrder < history.getById(second)!!.queueOrder)
                        engine.moveInQueue(second, -1)
                        val persistedOrder = history.getById(second)!!.queueOrder
                        engine.pause(first)
                        withTimeout(5000) { engine.progress.first { (it[second]?.downloadedBytes ?: 0L) > 0L } }
                        assertEquals(persistedOrder, history.getById(second)!!.queueOrder)
                        scope.coroutineContext.job.cancelAndJoin()
                        val reloaded = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json")))
                        reloaded.recoverInterruptedDownloads()
                        val expected = reloaded.snapshot().sortedBy { it.queueOrder }.map { it.id }
                        assertEquals(listOf(first, second, third), expected)
                        val restoredScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                        try {
                            val restored = DownloadEngine(reloaded, ResumableDownloader(OkHttpClient()), paths, restoredScope, "", "test", maxConcurrent = 1)
                            restored.resumeAll()
                            assertEquals(listOf(first), restored.queue.value.runningIds)
                            assertEquals(listOf(second, third), restored.queue.value.pendingIds)
                        } finally { restoredScope.coroutineContext.job.cancelAndJoin() }
                    }
                }
            }
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
    @Test fun `individual and all resume never schedule unsupported typed or legacy failures`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            history.upsert(DownloadRecord("drm", "drm", "drm", status = DownloadStatus.FAILED, failureKind = DownloadFailureKind.UNSUPPORTED))
            history.upsert(DownloadRecord("live", "live", "live", status = DownloadStatus.FAILED, errorMessage = "暂不支持直播"))
            var scheduled = 0
            val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()), DownloadPaths(temp.root), scope, "", "test", onTaskScheduled = { scheduled++ })
            engine.resumeAll(); engine.resume("drm"); engine.resume("live")
            assertEquals(0, scheduled)
            assertTrue(engine.queue.value.runningIds.isEmpty()); assertTrue(engine.queue.value.pendingIds.isEmpty())
            assertTrue(history.snapshot().all { it.status == DownloadStatus.FAILED })
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
    @Test fun `out of space fails before making HTTP request and retains typed recovery`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            RangeAwareTestServer(payload).use { server ->
                val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()), DownloadPaths(temp.root), scope, "", "test", availableBytes = { 0 })
                val id = engine.start(DouyinVideo("one", "one"), variant(server))
                val record = withTimeout(5000) { history.observeRecord(id).first { it?.status == DownloadStatus.FAILED }!! }
                assertEquals(DownloadFailureKind.STORAGE_FULL, record.failureKind)
                assertEquals(0, server.requestCount)
                assertFalse(File(record.partialPath).exists())
            }
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
    @Test fun `running downloads share space and pause releases budget for failed task resume`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val paths = DownloadPaths(temp.root)
        val capacity = 2 * STORAGE_RESERVE_BYTES + 40000L
        try {
            RangeAwareTestServer(payload, chunkSize = 1024, chunkDelayMs = 100).use { firstServer ->
                RangeAwareTestServer(payload).use { secondServer ->
                    val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()), paths, scope, "", "test", maxConcurrent = 2,
                        availableBytes = { capacity - taskDirectoryBytes(paths.downloadDir) })
                    val first = engine.start(DouyinVideo("one", "one"), variant(firstServer))
                    withTimeout(5000) { engine.progress.first { (it[first]?.downloadedBytes ?: 0) > 0 } }
                    val second = engine.start(DouyinVideo("two", "two"), variant(secondServer))
                    val failure = withTimeout(5000) { history.observeRecord(second).first { it?.status == DownloadStatus.FAILED }!! }
                    assertEquals(DownloadFailureKind.STORAGE_FULL, failure.failureKind)
                    assertEquals(0, secondServer.requestCount)
                    engine.pause(first)
                    engine.resume(second)
                    val completed = withTimeout(5000) { history.observeRecord(second).first { it?.status == DownloadStatus.COMPLETED }!! }
                    assertArrayEquals(payload, File(completed.filePath!!).readBytes())
                    assertEquals(1, secondServer.requestCount)
                    assertTrue(File(history.getById(first)!!.partialPath).length() > 0)
                    withTimeout(5000) { while (second in engine.activeTaskIds()) delay(10) }
                    val third = engine.start(DouyinVideo("three", "three"), variant(secondServer))
                    withTimeout(5000) { history.observeRecord(third).first { it?.status == DownloadStatus.COMPLETED } }
                    assertEquals(2, secondServer.requestCount)
                }
            }
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
    @Test fun `response enlargement cannot steal a running task reservation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val paths = DownloadPaths(temp.root)
        try {
            RangeAwareTestServer(payload, chunkSize = 1024, chunkDelayMs = 100).use { firstServer ->
                RangeAwareTestServer(payload).use { secondServer ->
                    val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()), paths, scope, "", "test", maxConcurrent = 2,
                        availableBytes = { 2 * STORAGE_RESERVE_BYTES + 40000L - taskDirectoryBytes(paths.downloadDir) })
                    val first = engine.start(DouyinVideo("one", "one"), variant(firstServer))
                    withTimeout(5000) { engine.progress.first { (it[first]?.downloadedBytes ?: 0) > 0 } }
                    val second = engine.start(DouyinVideo("two", "two"), variant(secondServer).copy(sizeBytes = 10))
                    val failure = withTimeout(5000) { history.observeRecord(second).first { it?.status == DownloadStatus.FAILED }!! }
                    assertEquals(DownloadFailureKind.STORAGE_FULL, failure.failureKind)
                    assertEquals(1, secondServer.requestCount)
                    assertEquals(0, File(failure.partialPath).length())
                    assertEquals(DownloadStatus.RUNNING, history.getById(first)!!.status)
                    engine.pause(first)
                }
            }
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
    @Test fun `unknown response is stopped before writing beyond available working space`() = runBlocking {
        RangeAwareTestServer(payload, chunkSize = 1024, chunkDelayMs = 5, sendContentLength = false).use { server ->
            val partial = File(temp.root, "unknown.part")
            val result = ResumableDownloader(OkHttpClient()).download(ResumableDownloader.DownloadRequest(
                variant(server).url, partial, File(temp.root, "unknown.mp4"),
                availableBytes = { STORAGE_RESERVE_BYTES + 4096 - partial.length() }))
            assertTrue(result is ResumableDownloader.Outcome.Failed)
            assertEquals(DownloadFailureKind.STORAGE_FULL, (result as ResumableDownloader.Outcome.Failed).error.failureKind)
            assertTrue(partial.length() in 1..4096)
            assertFalse(File(temp.root, "unknown.mp4").exists())
        }
    }
    @Test fun `known response size is checked again before writing a direct file`() = runBlocking {
        RangeAwareTestServer(payload).use { server ->
            val partial = File(temp.root, "download.part")
            val result = ResumableDownloader(OkHttpClient()).download(ResumableDownloader.DownloadRequest(
                variant(server).url, partial, File(temp.root, "final.mp4"), availableBytes = { STORAGE_RESERVE_BYTES + 10 }))
            assertTrue(result is ResumableDownloader.Outcome.Failed)
            assertEquals(DownloadFailureKind.STORAGE_FULL, (result as ResumableDownloader.Outcome.Failed).error.failureKind)
            assertTrue(!partial.exists() || partial.length() == 0L)
        }
    }
    @Test fun `HTTP status is persisted as actionable auth access expiration or rate category`() = runBlocking {
        for ((status, kind) in listOf(401 to DownloadFailureKind.LOGIN_REQUIRED, 403 to DownloadFailureKind.ACCESS_DENIED,
            410 to DownloadFailureKind.LINK_EXPIRED, 429 to DownloadFailureKind.RATE_LIMITED)) {
            RangeAwareTestServer(payload, statusCode = status).use { server ->
                val result = ResumableDownloader(OkHttpClient()).download(ResumableDownloader.DownloadRequest(
                    variant(server).url, File(temp.root, "$status.part"), File(temp.root, "$status.mp4"))) as ResumableDownloader.Outcome.Failed
                assertEquals(kind, result.error.failureKind)
            }
        }
    }
    @Test fun `logged in retry bypasses anonymous auth failure but saved files still prevent duplicates`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()), DownloadPaths(temp.root), scope, "", "test")
            val media = MediaDownload(type = MediaSourceType.HLS, mediaId = "id", formatId = "format", extractor = "Generic")
            val video = DouyinVideo("id", "video", platform = VideoPlatform.GENERIC)
            val record = DownloadRecord("failed", "id", "video", platform = VideoPlatform.GENERIC, resolutionLabel = "720P",
                media = media, status = DownloadStatus.FAILED, failureKind = DownloadFailureKind.LOGIN_REQUIRED)
            history.upsert(record)
            val loggedIn = VideoVariant("https://new-cdn", "720P", media = media.copy(useBrowserSession = true))
            assertNull(engine.findDuplicate(video, loggedIn))
            val file = File(temp.root, "saved.mp4").apply { writeBytes(payload) }
            history.upsert(record.copy(status = DownloadStatus.COMPLETED, filePath = file.path))
            assertEquals("failed", engine.findDuplicate(video, loggedIn)?.id)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
