package com.videodown.douyin.data.download

import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.DownloadPauseCause
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.model.canAutoResume
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

/**
 * End-to-end coverage for the user-facing automatic-resume policy.
 *
 * The policy switch is read from a flow, the network from a fake policy, free space from a mutable
 * counter and the sweep interval is shortened, so every trigger is exercised without a device.
 */
class AutoResumeDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val policy = object : DownloadNetworkPolicy {
        override val allowed = MutableStateFlow(true)
        override fun isAllowed() = allowed.value
    }
    private val autoResume = MutableStateFlow(true)
    private var available = 200L * 1024 * 1024
    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private val servers = mutableListOf<RangeAwareTestServer>()
    private val store by lazy { DownloadHistoryStore(File(temp.root, "history.json")) }
    private val history by lazy { DownloadHistoryRepositoryImpl(store) }
    private val paths by lazy { DownloadPaths(temp.root) }
    private val engine by lazy {
        DownloadEngine(
            history,
            ResumableDownloader(OkHttpClient.Builder().retryOnConnectionFailure(false).build()),
            paths, scope, "", "test", maxConcurrent = 1,
            networkPolicy = policy,
            autoResumeChanges = autoResume,
            autoResumeEnabled = { autoResume.value },
            autoResumeIntervalMs = 40,
            availableBytes = { available },
        )
    }

    @After fun close() { scope.cancel(); servers.forEach { it.close() } }

    private fun server(delayMs: Long = 0) =
        RangeAwareTestServer(payload, chunkSize = 4096, chunkDelayMs = delayMs).also { servers.add(it) }

    private fun url(server: RangeAwareTestServer) = "http://127.0.0.1:${server.port}/video.mp4"

    private suspend fun start(server: RangeAwareTestServer, name: String = "fixture") = engine.start(
        DouyinVideo(name, name),
        VideoVariant(url(server), "720P", sizeBytes = payload.size.toLong()),
    )

    private suspend fun awaitStatus(id: String, status: DownloadStatus): DownloadRecord = withTimeout(8000) {
        val record = history.observeRecord(id).first { it?.status == status }!!
        if (status != DownloadStatus.RUNNING) {
            engine.progress.first { id !in it }
            // Let the launch block release the slot before the next resume is asserted.
            delay(30)
        }
        record
    }

    private fun orphan(id: String, server: RangeAwareTestServer, fileName: String) = DownloadRecord(
        id = id, awemeId = id, title = id,
        sourceUrl = url(server), resolutionLabel = "720P", fileName = fileName,
        partialPath = File(paths.downloadDir, "$fileName.part").absolutePath,
        status = DownloadStatus.RUNNING, totalBytes = payload.size.toLong(),
    )

    @Test fun `network recovery continues the transfer without a tap`() = runBlocking {
        policy.allowed.value = false
        val server = server()
        val id = start(server)

        val paused = awaitStatus(id, DownloadStatus.PAUSED)
        assertEquals(WIFI_PAUSE_REASON, paused.errorMessage)
        assertEquals(DownloadPauseCause.NETWORK, paused.pauseCause)
        assertEquals(0, server.requestCount)

        policy.allowed.value = true
        val done = awaitStatus(id, DownloadStatus.COMPLETED)
        assertArrayEquals(payload, File(done.filePath!!).readBytes())
        assertNull(done.pauseCause)
    }

    @Test fun `a task that failed with a network error is retried from its saved offset`() = runBlocking {
        val server = server()
        val partial = File(paths.downloadDir, "net.mp4.part").apply { writeBytes(payload.copyOf(4096)) }
        history.upsert(orphan("net", server, "net.mp4").copy(
            status = DownloadStatus.FAILED,
            failureKind = DownloadFailureKind.NETWORK,
            errorMessage = "网络连接失败，请检查连接后重试",
            partialPath = partial.absolutePath,
            downloadedBytes = 4096,
        ))
        assertTrue(history.getById("net")!!.canAutoResume)

        engine.autoResume()
        val done = awaitStatus("net", DownloadStatus.COMPLETED)
        assertArrayEquals(payload, File(done.filePath!!).readBytes())
        // A retry continues from the saved offset instead of starting over.
        assertEquals("bytes=4096-", server.rangeHeadersSeen.last())
    }

    @Test fun `a pause the user asked for is never continued by the policy`() = runBlocking {
        val server = server(delayMs = 20)
        val id = start(server)
        withTimeout(4000) { engine.progress.first { (it[id]?.downloadedBytes ?: 0) > 0 } }
        engine.pause(id)

        val paused = awaitStatus(id, DownloadStatus.PAUSED)
        assertEquals(DownloadPauseCause.USER, paused.pauseCause)
        val partialBytes = File(paused.partialPath).length()
        assertTrue(partialBytes > 0)

        policy.allowed.value = false
        policy.allowed.value = true
        delay(300)
        assertEquals(DownloadStatus.PAUSED, history.getById(id)!!.status)
        assertEquals(partialBytes, File(paused.partialPath).length())
        assertTrue(history.getById(id)!!.downloadedBytes > 0)
    }

    @Test fun `turning the switch off keeps the task paused until it is turned on`() = runBlocking {
        autoResume.value = false
        policy.allowed.value = false
        val server = server()
        val id = start(server)
        awaitStatus(id, DownloadStatus.PAUSED)

        policy.allowed.value = true
        delay(300)
        assertEquals(DownloadStatus.PAUSED, history.getById(id)!!.status)
        assertEquals(0, server.requestCount)

        autoResume.value = true
        val done = awaitStatus(id, DownloadStatus.COMPLETED)
        assertArrayEquals(payload, File(done.filePath!!).readBytes())
    }

    @Test fun `a task orphaned by process death resumes on the next start`() = runBlocking {
        val server = server()
        history.upsert(orphan("orphan", server, "orphan.mp4"))
        store.markInterruptedAsPaused()

        val paused = history.getById("orphan")!!
        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertEquals(DownloadPauseCause.PROCESS, paused.pauseCause)
        assertTrue(paused.canAutoResume)

        assertEquals(0, server.requestCount)
        engine.autoResume()
        val done = awaitStatus("orphan", DownloadStatus.COMPLETED)
        assertArrayEquals(payload, File(done.filePath!!).readBytes())
    }

    @Test fun `older paused history without a cause stays manual`() = runBlocking {
        val server = server()
        history.upsert(orphan("legacy", server, "legacy.mp4").copy(
            status = DownloadStatus.PAUSED, pauseCause = null, errorMessage = null))
        assertFalse(history.getById("legacy")!!.canAutoResume)

        engine.autoResume()
        delay(250)
        assertEquals(DownloadStatus.PAUSED, history.getById("legacy")!!.status)
        assertEquals(0, server.requestCount)
    }

    @Test fun `a task blocked by a full disk waits and then continues`() = runBlocking {
        available = 0L
        val server = server()
        val id = start(server)

        val failed = awaitStatus(id, DownloadStatus.FAILED)
        assertEquals(DownloadFailureKind.STORAGE_FULL, failed.failureKind)
        assertEquals(0, server.requestCount)
        // Still no space: the policy must wait instead of spending its retry budget.
        delay(250)
        assertEquals(DownloadStatus.FAILED, history.getById(id)!!.status)
        assertEquals(0, server.requestCount)

        available = 200L * 1024 * 1024
        val done = awaitStatus(id, DownloadStatus.COMPLETED)
        assertArrayEquals(payload, File(done.filePath!!).readBytes())
    }
}
