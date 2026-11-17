package com.videodown.douyin.data.local

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadHistoryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 只给「旧版本 JSON」用例用：不要写出默认值，模拟缺少新字段的历史文件。 */
    private val leanJson = kotlinx.serialization.json.Json { encodeDefaults = false }

    private fun newStore(): DownloadHistoryStore =
        DownloadHistoryStore(File(tempFolder.root, "download_history.json"))

    private fun record(
        id: String,
        status: DownloadStatus = DownloadStatus.COMPLETED,
        createdAt: Long = 1_000L,
    ) = DownloadRecord(
        id = id,
        awemeId = "7123456789012345678",
        title = "测试视频 $id",
        authorNickname = "作者",
        authorSecUid = "MS4wLjABAAAAtest",
        resolutionLabel = "1080P",
        fileName = "$id.mp4",
        filePath = File(tempFolder.root, "$id.mp4").absolutePath,
        partialPath = File(tempFolder.root, "$id.mp4.part").absolutePath,
        sourceUrl = "https://v3-web.douyinvod.com/video/x",
        totalBytes = 1024L,
        downloadedBytes = 1024L,
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
        rangeSupported = true,
        etag = "\"abc\"",
    )

    @Test
    fun `persists records across store instances`() = runBlocking {
        val first = newStore()
        first.upsert(record("a", createdAt = 2_000L))
        first.upsert(record("b", createdAt = 1_000L))

        val reopened = newStore()
        val loaded = reopened.load()

        assertEquals(2, loaded.size)
        assertEquals("newest first", listOf("a", "b"), loaded.map { it.id })

        val restored = loaded.first { it.id == "a" }
        assertEquals("测试视频 a", restored.title)
        assertEquals(DownloadStatus.COMPLETED, restored.status)
        assertEquals(1024L, restored.totalBytes)
        assertTrue(restored.rangeSupported)
        assertEquals("\"abc\"", restored.etag)
        assertEquals("MS4wLjABAAAAtest", restored.authorSecUid)
    }

    @Test
    fun `upsert updates an existing record in place`() = runBlocking {
        val store = newStore()
        store.upsert(record("a"))
        store.upsert(record("a").copy(downloadedBytes = 500L, status = DownloadStatus.PAUSED))

        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals(500L, loaded.single().downloadedBytes)
        assertEquals(DownloadStatus.PAUSED, loaded.single().status)
    }

    @Test
    fun `delete removes a single record`() = runBlocking {
        val store = newStore()
        store.upsert(record("a"))
        store.upsert(record("b"))
        store.delete("a")

        assertEquals(listOf("b"), store.load().map { it.id })
    }

    @Test
    fun `interrupted downloads are demoted to paused so they can be resumed`() = runBlocking {
        val store = newStore()
        store.upsert(record("running", status = DownloadStatus.RUNNING))
        store.upsert(record("pending", status = DownloadStatus.PENDING))
        store.upsert(record("done", status = DownloadStatus.COMPLETED))
        store.upsert(record("paused", status = DownloadStatus.PAUSED))

        store.markInterruptedAsPaused()
        val byId = store.load().associateBy { it.id }

        assertEquals(DownloadStatus.PAUSED, byId.getValue("running").status)
        assertEquals(DownloadStatus.PAUSED, byId.getValue("pending").status)
        assertEquals("completed records must not be touched", DownloadStatus.COMPLETED, byId.getValue("done").status)
        assertEquals(DownloadStatus.PAUSED, byId.getValue("paused").status)
        assertTrue(byId.getValue("running").isResumable)
    }

    @Test
    fun `clear with deleteFiles removes downloaded and partial files`() = runBlocking {
        val store = newStore()
        val completed = record("a")
        val paused = record("b", status = DownloadStatus.PAUSED)

        File(completed.filePath!!).writeText("video")
        File(paused.partialPath).writeText("partial")

        store.upsert(completed)
        store.upsert(paused)
        store.clear(deleteFiles = true)

        assertTrue(store.load().isEmpty())
        assertFalse(File(completed.filePath!!).exists())
        assertFalse(File(paused.partialPath).exists())
    }

    @Test
    fun `clear without deleteFiles keeps the media`() = runBlocking {
        val store = newStore()
        val completed = record("a")
        val file = File(completed.filePath!!)
        file.writeText("video")

        store.upsert(completed)
        store.clear(deleteFiles = false)

        assertTrue(store.load().isEmpty())
        assertTrue("media must survive", file.exists())
    }

    @Test
    fun `an unreadable history file is preserved instead of destroyed`() = runBlocking {
        val historyFile = File(tempFolder.root, "download_history.json")
        historyFile.writeText("{ this is not valid json")

        val store = DownloadHistoryStore(historyFile)
        assertTrue(store.load().isEmpty())

        val quarantined = File(tempFolder.root, "download_history.json.corrupt")
        assertTrue("corrupt file should be kept for the user", quarantined.exists())
    }

    @Test
    fun `retry cooldown survives a store instance restart`() = runBlocking {
        val cooling = record("cooling", status = DownloadStatus.PAUSED).copy(
            nextRetryAtMillis = 1_700_000_123_000L,
            retryAttempt = 2,
            retryHttpStatus = 503,
        )
        newStore().upsert(cooling)

        // 新进程：自动继续必须能读到同一个冷却时间，而不是当成没有冷却立刻请求。
        val reopened = newStore().load().single { it.id == "cooling" }
        assertEquals(1_700_000_123_000L, reopened.nextRetryAtMillis)
        assertEquals(2, reopened.retryAttempt)
        assertEquals(503, reopened.retryHttpStatus)
    }

    @Test
    fun `legacy history without retry fields loads as no cooldown`() = runBlocking {
        val legacy = record("legacy")
        // 旧版本写下的 JSON 没有这三个字段：encodedDefaults=true 会补齐默认值。
        val json = leanJson.encodeToString(DownloadRecord.serializer(), legacy)
        assertFalse("fixture must not carry the new fields", json.contains("nextRetryAtMillis"))
        File(tempFolder.root, "download_history.json").writeText("[$json]")

        val restored = newStore().load().single()
        assertEquals(null, restored.nextRetryAtMillis)
        assertEquals(0, restored.retryAttempt)
        assertEquals(null, restored.retryHttpStatus)
    }

    @Test
    fun `a missing history file yields an empty list`() = runBlocking {
        val store = DownloadHistoryStore(File(tempFolder.root, "does_not_exist.json"))
        assertNotNull(store.load())
        assertTrue(store.load().isEmpty())
    }
}
