package com.videodown.douyin.data.download

import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExpiredSourceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `expired URL resumes only matching media with a validator`() = checkRefresh(true, true)
    @Test fun `changed media restarts instead of appending stale partial bytes`() = checkRefresh(false, true)
    @Test fun `matching media without a validator restarts safely`() = checkRefresh(true, false)

    private fun checkRefresh(sameMedia: Boolean, validator: Boolean) = runBlocking {
        val payload = ByteArray(30_000) { (it % 251).toByte() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val policy = object : DownloadNetworkPolicy {
            override val allowed = MutableStateFlow(false)
            override fun isAllowed() = allowed.value
        }
        val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json")))
        try {
            RangeAwareTestServer(payload, statusCode = 403).use { expired ->
                RangeAwareTestServer(payload).use { valid ->
                    val oldMedia = MediaDownload(pageUrl = "https://example.test/page", mediaId = "video", formatId = "720", extractor = "Generic")
                    val newMedia = if (sameMedia) oldMedia else oldMedia.copy(mediaId = "replacement")
                    var refreshes = 0
                    val engine = DownloadEngine(history, ResumableDownloader(OkHttpClient()), DownloadPaths(temp.root), scope,
                        "", "test", networkPolicy = policy, refreshSource = {
                            refreshes++
                            VideoVariant("http://127.0.0.1:${valid.port}/video.mp4", "720P", media = newMedia)
                        })
                    val id = engine.start(DouyinVideo("video", "fixture"),
                        VideoVariant("http://127.0.0.1:${expired.port}/video.mp4", "720P", media = oldMedia))
                    withTimeout(3_000) { history.observeRecord(id).first { it?.status == DownloadStatus.PAUSED } }
                    engine.pause(id)
                    val record = history.getById(id)!!
                    File(record.partialPath).writeBytes(if (sameMedia) payload.copyOf(4096) else ByteArray(4096) { 42 })
                    history.upsert(record.copy(downloadedBytes = 4096, etag = if (validator) "\"fixture-etag\"" else null))
                    policy.allowed.value = true
                    engine.resume(id)
                    val completed = withTimeout(5_000) { history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!! }
                    assertEquals(completed.errorMessage, DownloadStatus.COMPLETED, completed.status)
                    assertArrayEquals(payload, File(completed.filePath!!).readBytes())
                    assertEquals(1, refreshes)
                    assertEquals(1, expired.requestCount)
                    assertEquals(if (sameMedia && validator) "bytes=4096-" else "", valid.rangeHeadersSeen.single())
                }
            }
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
