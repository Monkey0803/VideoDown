package com.videodown.douyin.data.download

import com.videodown.douyin.data.generic.EngineTransfer
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.domain.model.MediaSourceType
import com.videodown.douyin.domain.model.TransferPhase
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 运行中的引擎任务修改限速后，进程预算和历史记录都必须立即使用新值。 */
class RunningTaskSpeedLimitTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history by lazy {
        DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json")))
    }
    private val paths by lazy { DownloadPaths(temp.root) }

    @After fun close() { scope.cancel() }

    @Test fun `changing a running engine task limit updates its live provider and survives progress writes`() = runBlocking {
        val receivedControl = CompletableDeferred<EngineRateControl>()
        val continueDownload = CompletableDeferred<Unit>()
        val fake = object : EngineTransfer {
            override suspend fun download(
                record: DownloadRecord,
                paths: DownloadPaths,
                policy: DownloadNetworkPolicy,
                rateLimitBytesPerSecond: Long?,
                rateControl: EngineRateControl?,
                progress: suspend (Long, Long, Long, TransferPhase) -> Unit,
            ): File {
                receivedControl.complete(requireNotNull(rateControl))
                continueDownload.await()
                // 强制一次落库，验证启动时 speedLimitKbps=0 的局部记录不会把新设置覆盖回去。
                progress(1_024L, 2_048L, 1_024L, TransferPhase.MERGING)
                return File(paths.engineDirectory(record.id).apply { mkdirs() }, "media.mp4")
                    .apply { writeBytes(ByteArray(2_048) { 7 }) }
            }
        }
        val engine = DownloadEngine(
            history,
            ResumableDownloader(okhttp3.OkHttpClient()),
            paths,
            scope,
            "",
            "test",
            maxConcurrent = 1,
            engineDownloader = fake,
        )
        val variant = VideoVariant(
            url = "https://example.test/post/1",
            label = "720P",
            media = MediaDownload(
                type = MediaSourceType.HLS,
                pageUrl = "https://example.test/post/1",
                mediaId = "media-1",
            ),
        )
        val id = engine.start(DouyinVideo("running-limit", "运行中限速"), variant)
        val control = withTimeout(5_000) { receivedControl.await() }
        assertEquals(0L, control.taskLimit())

        engine.setSpeedLimit(id, 512)
        assertEquals(512L * 1_024L, control.taskLimit())
        assertEquals(512, history.getById(id)?.speedLimitKbps)

        continueDownload.complete(Unit)
        val completed = withTimeout(5_000) {
            history.observeRecord(id).first { it?.status == DownloadStatus.COMPLETED }!!
        }
        assertEquals(512, completed.speedLimitKbps)
        assertTrue(File(completed.filePath!!).isFile)
    }
}
