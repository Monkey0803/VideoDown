package com.videodown.douyin.data.download

import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.ArchiveEntry
import com.videodown.douyin.domain.model.ArchiveRequest
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.VideoPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class DownloadEngineArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) +
        ByteArray(19_995) { (it % 241).toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val history by lazy {
        DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json")))
    }

    private fun request(port: Int, count: Int = 2) = ArchiveRequest(
        title = "周末的九宫格",
        postId = "post-1",
        platform = VideoPlatform.WEIBO,
        postPageUrl = "https://weibo.com/1/post-1",
        entries = List(count) { index ->
            ArchiveEntry("http://127.0.0.1:$port/image-$index.jpg", "jpg",
                referer = "https://weibo.com/", sizeBytes = payload.size.toLong())
        },
        coverUrl = "http://127.0.0.1:$port/image-0.jpg",
    )

    @Test fun `downloads every image and publishes one zip`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            RangeAwareTestServer(payload).use { server ->
                val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()),
                    DownloadPaths(temp.root), scope, "", "test", validateFile = { file, record ->
                    com.videodown.douyin.data.download.validateDownloadedFile(file, record)
                })
                val id = engine.startArchive(request(server.port))
                val completed = withTimeout(10_000) {
                    history.observeRecord(id).first { it?.status == DownloadStatus.COMPLETED }!!
                }
                assertEquals(MediaKind.ARCHIVE, completed.mediaKind)
                assertTrue(completed.fileName.endsWith(".zip"))
                assertEquals(2, completed.archiveEntries.size)
                val zip = File(completed.filePath!!)
                assertTrue(zip.isFile)
                ZipFile(zip).use { archive ->
                    val entries = archive.entries().toList()
                    assertEquals(listOf("images/001.jpg", "images/002.jpg"), entries.map { it.name })
                    entries.forEach { entry ->
                        assertArrayEquals(payload, archive.getInputStream(entry).use { it.readBytes() })
                    }
                }
                // 任务目录清空，不留半成品；完成回调与 activeJobs 清理是同一收尾的两步，等它落定。
                withTimeout(5_000) { while (id in engine.activeTaskIds()) delay(10) }
                // 同帖同张数再次打包会命中重复；张数不同则不命中。
                assertEquals(id, engine.findArchiveDuplicate(request(server.port))?.id)
                assertNull(engine.findArchiveDuplicate(request(server.port, count = 3)))
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test fun `a paused archive resumes and still produces a complete zip`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            RangeAwareTestServer(payload, chunkSize = 1024, chunkDelayMs = 20).use { server ->
                val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()),
                    DownloadPaths(temp.root), scope, "", "test")
                val id = engine.startArchive(request(server.port))
                withTimeout(10_000) { engine.progress.first { (it[id]?.downloadedBytes ?: 0L) > 0L } }
                engine.pause(id)
                val paused = history.getById(id)!!
                assertEquals(DownloadStatus.PAUSED, paused.status)
                assertTrue(paused.downloadedBytes > 0L)

                engine.resume(id)
                val completed = withTimeout(10_000) {
                    history.observeRecord(id).first { it?.status == DownloadStatus.COMPLETED }!!
                }
                ZipFile(File(completed.filePath!!)).use { archive ->
                    assertEquals(2, archive.entries().toList().size)
                }
                assertNotNull(completed.filePath)
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test fun `a failing source marks the task failed instead of publishing a partial zip`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            RangeAwareTestServer(payload, statusCode = 404).use { server ->
                val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()),
                    DownloadPaths(temp.root), scope, "", "test")
                val id = engine.startArchive(request(server.port))
                val failed = withTimeout(10_000) {
                    history.observeRecord(id).first { it?.status == DownloadStatus.FAILED }!!
                }
                assertNull(failed.filePath)
                assertTrue(File(temp.root, "Douyin").listFiles().orEmpty().none { it.name.endsWith(".zip") })
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test fun `a successful html response is rejected instead of being packed as an image`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            RangeAwareTestServer("<html>expired</html>".toByteArray()).use { server ->
                val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()),
                    DownloadPaths(temp.root), scope, "", "test")
                val id = engine.startArchive(request(server.port, count = 1))
                val failed = withTimeout(10_000) {
                    history.observeRecord(id).first { it?.status == DownloadStatus.FAILED }!!
                }
                assertNull(failed.filePath)
                assertEquals(com.videodown.douyin.domain.model.DownloadFailureKind.FORMAT_CHANGED, failed.failureKind)
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
