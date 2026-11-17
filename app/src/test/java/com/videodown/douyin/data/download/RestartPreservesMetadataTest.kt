package com.videodown.douyin.data.download

import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.StoredFileHealth
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * I4：文件丢失后的「重新下载」入口。
 *
 * 重新下载复用同一条记录，所以收藏、分类、隐私和手动排序必须原样保留；完成时的大小/修改时间
 * 快照要刷新，否则下一次健康检查会一直报「已变化」。这里用本地 HTTP 服务模拟原地址仍可用的场景，
 * 链接失效后的「重新解析」分支在界面层接线，由设备用例覆盖。
 */
class RestartPreservesMetadataTest {
    @get:Rule val temp = TemporaryFolder()
    private val payload = ByteArray(20_000) { (it % 251).toByte() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history by lazy { DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json"))) }
    private val paths by lazy { DownloadPaths(temp.root) }
    private val engine by lazy {
        DownloadEngine(
            history,
            ResumableDownloader(OkHttpClient.Builder().retryOnConnectionFailure(false).build()),
            paths, scope, "", "test", maxConcurrent = 1,
        )
    }

    @After fun close() { scope.cancel() }

    private suspend fun awaitCompleted(id: String): DownloadRecord = withTimeout(8_000) {
        history.observeRecord(id).first { it?.status == DownloadStatus.COMPLETED }!!
    }

    @Test fun `restarting a missing file keeps metadata and refreshes the completion snapshot`() = runBlocking {
        RangeAwareTestServer(payload).use { server ->
            val id = engine.start(
                DouyinVideo("meta", "meta"),
                VideoVariant("http://127.0.0.1:${server.port}/video.mp4", "720P", sizeBytes = payload.size.toLong()),
            )
            val completed = awaitCompleted(id)
            assertEquals(payload.size.toLong(), completed.fileSizeBytes)
            assertNotNull(completed.fileModifiedAt)

            // 用户在已存页整理过这条内容：重新下载不能把这些元数据弄丢。
            val annotated = completed.copy(favorite = true, folder = "旅行", isPrivate = true, sortIndex = 7)
            history.upsert(annotated)
            val file = File(completed.filePath!!)
            assertTrue(file.delete())
            assertEquals(StoredFileHealth.MISSING, LibraryFileInspector().cheapCheck(history.getById(id)!!))

            engine.restart(id)
            val restarted = awaitCompleted(id)
            assertEquals("旅行", restarted.folder)
            assertTrue(restarted.favorite)
            assertTrue(restarted.isPrivate)
            assertEquals(7L, restarted.sortIndex)
            assertEquals("重新下载必须刷新完成快照", payload.size.toLong(), restarted.fileSizeBytes)
            assertNotNull(restarted.fileModifiedAt)
            assertArrayEquals(payload, File(restarted.filePath!!).readBytes())
            assertNotEquals("健康检查不应再报缺失", StoredFileHealth.MISSING, LibraryFileInspector().cheapCheck(restarted))
        }
    }
}
