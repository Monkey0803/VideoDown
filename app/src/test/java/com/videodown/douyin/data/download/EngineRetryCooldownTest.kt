package com.videodown.douyin.data.download

import com.videodown.douyin.data.generic.EngineTransfer
import com.videodown.douyin.data.generic.VideoEngineException
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.DownloadPauseCause
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.MediaSourceType
import com.videodown.douyin.domain.model.TransferPhase
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * I1：引擎任务（yt-dlp / HLS / DASH / 分轨）与直链共用一套有界重试与落库冷却。
 *
 * 引擎用 [EngineTransfer] 的假实现驱动，所以重试、冷却、重启后不提前请求、用户「立即重试」
 * 覆盖冷却这些行为都能在 JVM 上验证；真实 subprocess 仍由设备用例覆盖。
 */
class EngineRetryCooldownTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history by lazy { DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json"))) }
    private val paths by lazy { DownloadPaths(temp.root) }
    private val autoResume = MutableStateFlow(true)

    @After fun close() { scope.cancel() }

    /** 假引擎：第 N 次调用交给 [handler]，[calls] 记录真实请求次数。 */
    private class FakeEngine(val handler: suspend (DownloadRecord, DownloadPaths, Int) -> File) : EngineTransfer {
        val calls = AtomicInteger()
        override suspend fun download(
            record: DownloadRecord,
            paths: DownloadPaths,
            policy: DownloadNetworkPolicy,
            rateLimitBytesPerSecond: Long?,
            progress: suspend (Long, Long, Long, TransferPhase) -> Unit,
        ): File = handler(record, paths, calls.incrementAndGet())
    }

    private fun writeOutput(record: DownloadRecord, paths: DownloadPaths): File {
        val directory = paths.engineDirectory(record.id).apply { mkdirs() }
        return File(directory, "media.mp4").apply { writeBytes(ByteArray(2048) { 7 }) }
    }

    private fun engineWith(fake: EngineTransfer, autoResumeEnabled: Boolean = true) = DownloadEngine(
        history,
        ResumableDownloader(OkHttpClient.Builder().retryOnConnectionFailure(false).build()),
        paths, scope, "", "test", maxConcurrent = 1,
        engineDownloader = fake,
        autoResumeChanges = autoResume,
        autoResumeEnabled = { autoResumeEnabled && autoResume.value },
        autoResumeIntervalMs = 40,
    )

    private fun engineVariant(pageUrl: String = "https://example.test/post/1") = VideoVariant(
        url = pageUrl,
        label = "720P",
        media = MediaDownload(type = MediaSourceType.HLS, pageUrl = pageUrl, mediaId = "media-1", formatId = "720p"),
    )

    private fun engineRecord(id: String) = DownloadRecord(
        id = id,
        awemeId = "aweme-$id",
        title = "引擎冷却任务",
        fileName = "cooldown-$id.mp4",
        sourceUrl = "https://example.test/post/1",
        sourcePageUrl = "https://example.test/post/1",
        platform = VideoPlatform.GENERIC,
        mediaKind = MediaKind.VIDEO,
        media = MediaDownload(type = MediaSourceType.HLS, pageUrl = "https://example.test/post/1", mediaId = "media-1"),
        status = DownloadStatus.PAUSED,
        pauseCause = DownloadPauseCause.PROCESS,
        failureKind = DownloadFailureKind.NETWORK,
        // 进程死亡前服务端要求的冷却还没到点。
        nextRetryAtMillis = System.currentTimeMillis() + 60_000,
        retryAttempt = 1,
    )

    private suspend fun awaitStatus(id: String, status: DownloadStatus): DownloadRecord = withTimeout(8_000) {
        history.observeRecord(id).first { it?.status == status }!!
    }

    @Test fun `an engine 429 with retry after cools down then retries the same task`() = runBlocking {
        val persistedDuringCooldown = AtomicInteger(-1)
        val fake = FakeEngine { record, paths, call ->
            if (call == 1) {
                throw VideoEngineException("RATE", httpStatus = 429, retryAfter = "0")
            }
            // 第二次请求必须沿用上一次落库的重试预算，不能回到零。
            persistedDuringCooldown.set(history.getById(record.id)?.retryAttempt ?: -1)
            writeOutput(record, paths)
        }
        val engine = engineWith(fake)
        val id = engine.start(DouyinVideo("engine-retry", "engine-retry"), engineVariant())
        val completed = awaitStatus(id, DownloadStatus.COMPLETED)
        assertEquals(2, fake.calls.get())
        assertEquals(1, persistedDuringCooldown.get())
        assertEquals(0, completed.retryAttempt)
        assertNull(completed.nextRetryAtMillis)
        assertTrue(File(completed.filePath!!).isFile)
    }

    @Test fun `a persisted cooldown stops automatic resume until the deadline`() = runBlocking {
        val fake = FakeEngine { record, paths, _ -> writeOutput(record, paths) }
        val id = "cooldown-gate"
        history.upsert(engineRecord(id))
        val engine = engineWith(fake)
        // 冷却远未到点：自动策略（构造时与每 40ms 一次的扫描）都不得发出请求。
        engine.autoResume()
        kotlinx.coroutines.delay(250)
        assertEquals(0, fake.calls.get())
        assertEquals(DownloadStatus.PAUSED, history.getById(id)?.status)

        // 用户明确「立即重试」可以覆盖冷却。
        engine.retryNow(id)
        val completed = awaitStatus(id, DownloadStatus.COMPLETED)
        assertEquals(1, fake.calls.get())
        assertEquals(DownloadStatus.COMPLETED, completed.status)
    }

    @Test fun `retry now releases an in-flight cooldown without rebuilding the task`() = runBlocking {
        val fake = FakeEngine { record, paths, call ->
            if (call == 1) {
                throw VideoEngineException("RATE", httpStatus = 429, retryAfter = "10")
            }
            writeOutput(record, paths)
        }
        val engine = engineWith(fake)
        val id = engine.start(DouyinVideo("engine-cooldown", "engine-cooldown"), engineVariant())
        // 等到记录真的进入冷却，而不是靠固定 sleep。
        val cooling = withTimeout(8_000) {
            history.observeRecord(id).first { it?.nextRetryAtMillis != null }!!
        }
        assertEquals(1, cooling.retryAttempt)
        assertEquals(429, cooling.retryHttpStatus)
        assertEquals(1, fake.calls.get())

        engine.retryNow(id)
        val completed = awaitStatus(id, DownloadStatus.COMPLETED)
        assertEquals(2, fake.calls.get())
        assertEquals(0, completed.retryAttempt)
        assertNull(completed.nextRetryAtMillis)
    }

    @Test fun `a rate limited direct transfer uses the server retry after header`() = runBlocking {
        val payload = ByteArray(30_000) { (it % 251).toByte() }
        RangeAwareTestServer(payload, failFirstStatusCode = 429, retryAfter = "0").use { server ->
            val engine = engineWith(FakeEngine { _, _, _ -> error("direct transfers must not touch the engine") }, autoResumeEnabled = false)
            val id = engine.start(
                DouyinVideo("direct-retry", "direct-retry"),
                VideoVariant("http://127.0.0.1:${server.port}/video.mp4", "720P", sizeBytes = payload.size.toLong()),
            )
            val completed = awaitStatus(id, DownloadStatus.COMPLETED)
            assertEquals(2, server.requestCount)
            assertEquals(0, completed.retryAttempt)
            assertNull(completed.nextRetryAtMillis)
            assertTrue(File(completed.filePath!!).readBytes().contentEquals(payload))
        }
    }

    @Test fun `a direct transfer persists the cooldown before retrying`() = runBlocking {
        val payload = ByteArray(30_000) { (it % 251).toByte() }
        RangeAwareTestServer(payload, failFirstStatusCode = 429, retryAfter = "60").use { server ->
            val engine = engineWith(FakeEngine { _, _, _ -> error("direct transfers must not touch the engine") }, autoResumeEnabled = false)
            val id = engine.start(
                DouyinVideo("direct-cooling", "direct-cooling"),
                VideoVariant("http://127.0.0.1:${server.port}/video.mp4", "720P", sizeBytes = payload.size.toLong()),
            )
            val cooling = withTimeout(8_000) {
                history.observeRecord(id).first { it?.nextRetryAtMillis != null }!!
            }
            assertEquals(1, cooling.retryAttempt)
            assertEquals(429, cooling.retryHttpStatus)
            // 冷却期间不得发出第二次请求。
            assertEquals(1, server.requestCount)
            // 用户暂停会清掉冷却，避免恢复一个早已过期的倒计时。
            engine.pause(id)
            val paused = awaitStatus(id, DownloadStatus.PAUSED)
            assertNull(paused.nextRetryAtMillis)
        }
    }
}
