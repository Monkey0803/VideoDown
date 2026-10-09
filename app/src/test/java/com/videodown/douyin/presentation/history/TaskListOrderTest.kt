package com.videodown.douyin.presentation.history

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskListOrderTest {

    private fun record(
        id: String,
        title: String = "标题",
        author: String = "",
        status: DownloadStatus = DownloadStatus.COMPLETED,
        updatedAt: Long = 0L,
        totalBytes: Long = 0L,
        downloadedBytes: Long = 0L,
    ) = DownloadRecord(
        id = id,
        awemeId = id,
        title = title,
        authorNickname = author,
        status = status,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
    )

    private fun order(
        records: List<DownloadRecord>,
        text: String = "",
        sort: TaskSort = TaskSort.STATUS,
        positions: Map<String, Int> = emptyMap(),
    ) = TaskListOrder.apply(records, text, sort,
        statusOf = { it.status },
        queuePosition = { positions[it] },
    ).map { it.id }

    @Test
    fun `status order puts transferring tasks first in queue order`() {
        val records = listOf(
            record("done", status = DownloadStatus.COMPLETED, updatedAt = 300),
            record("pending-b", status = DownloadStatus.PENDING, updatedAt = 100),
            record("running", status = DownloadStatus.RUNNING, updatedAt = 50),
            record("pending-a", status = DownloadStatus.PENDING, updatedAt = 200),
            record("paused", status = DownloadStatus.PAUSED, updatedAt = 400),
        )
        val positions = mapOf("pending-b" to 2, "pending-a" to 1)
        // Running first, then waiting tasks by queue position, then everything else newest-first.
        assertEquals(listOf("running", "pending-a", "pending-b", "paused", "done"), order(records, positions = positions))
    }

    @Test
    fun `without a queue position waiting tasks fall back to newest first`() {
        val records = listOf(
            record("old", status = DownloadStatus.PENDING, updatedAt = 100),
            record("new", status = DownloadStatus.PENDING, updatedAt = 200),
        )
        assertEquals(listOf("new", "old"), order(records))
    }

    @Test
    fun `search matches title and author and ignores case`() {
        val records = listOf(
            record("a", title = "海边的日落", author = "摄影师小王"),
            record("b", title = "City Walk", author = "Alice"),
            record("c", title = "做饭教程", author = "小王"),
        )
        assertEquals(listOf("a", "c"), order(records, text = "小王", sort = TaskSort.RECENT).sorted())
        assertEquals(listOf("b"), order(records, text = "city"))
        assertEquals(listOf("b"), order(records, text = "ALICE"))
    }

    @Test
    fun `multiple search words all have to match`() {
        val records = listOf(
            record("a", title = "海边日落 4K", author = "小王"),
            record("b", title = "海边日出 4K", author = "小王"),
            record("c", title = "城市日落", author = "小李"),
        )
        // "海边" alone matches two, "海边 日落" only one, and a word nobody has matches nothing.
        assertEquals(listOf("a"), order(records, text = "海边 日落"))
        assertEquals(listOf("a", "b"), order(records, text = "海边 4K", sort = TaskSort.RECENT).sorted())
        assertEquals(emptyList<String>(), order(records, text = "海边 雪山"))
    }

    @Test
    fun `blank search keeps every task`() {
        val records = listOf(record("a"), record("b"))
        assertEquals(listOf("a", "b"), order(records, text = "   ").sorted())
    }

    @Test
    fun `recent and oldest orders use the update time`() {
        val records = listOf(
            record("mid", updatedAt = 200),
            record("old", updatedAt = 100),
            record("new", updatedAt = 300),
        )
        assertEquals(listOf("new", "mid", "old"), order(records, sort = TaskSort.RECENT))
        assertEquals(listOf("old", "mid", "new"), order(records, sort = TaskSort.OLDEST))
    }

    @Test
    fun `largest order prefers the known total and falls back to downloaded bytes`() {
        val records = listOf(
            record("big", totalBytes = 9_000, downloadedBytes = 1_000),
            record("unknown", totalBytes = -1, downloadedBytes = 5_000),
            record("small", totalBytes = 100, downloadedBytes = 100),
        )
        assertEquals(listOf("big", "unknown", "small"), order(records, sort = TaskSort.LARGEST))
    }

    @Test
    fun `a task without a known size still sorts without crashing`() {
        val records = listOf(record("a", totalBytes = -1), record("b", totalBytes = 0))
        assertEquals(listOf("a", "b"), order(records, sort = TaskSort.LARGEST).sorted())
    }
}
