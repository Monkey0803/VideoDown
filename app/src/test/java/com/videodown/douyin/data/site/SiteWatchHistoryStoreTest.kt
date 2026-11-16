package com.videodown.douyin.data.site

import com.videodown.douyin.domain.model.SiteWatchRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class SiteWatchHistoryStoreTest {
    @Test fun `upsert replaces the same episode and enforces newest-first limit`() = runTest {
        val directory = Files.createTempDirectory("site-watch").toFile()
        val store = SiteWatchHistoryStore(directory.resolve("history.json"), limit = 2)
        store.upsert(record("one", 1))
        store.upsert(record("two", 2))
        store.upsert(record("three", 3))
        store.upsert(record("two", 4, position = 9_000))

        assertEquals(listOf("two", "three"), store.load().map { it.id })
        assertEquals(9_000L, store.find("two")?.positionMs)
        store.delete("two")
        assertNull(store.find("two"))
    }

    @Test fun `completed item resumes from zero`() {
        val item = record("done", 1, position = 98_000).copy(durationMs = 100_000)
        assertEquals(0L, item.resumePositionMs)
    }

    private fun record(id: String, updated: Long, position: Long = 0L) = SiteWatchRecord(
        id = id,
        siteId = "kubb",
        pageUrl = "https://m.kubb.tv/play/$id",
        title = id,
        positionMs = position,
        updatedAt = updated,
    )
}
