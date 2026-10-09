package com.videodown.douyin.data.local

import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class DownloadStorageRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val root by lazy { File(temp.root, "downloads").apply { mkdirs() } }
    private val history by lazy { DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json"))) }
    private var active = emptySet<String>()
    private val repo by lazy { DownloadStorageRepository({ root }, history, Mutex(), { active }, { 10000000 }) }
    private fun file(name: String, size: Int = 10) = File(root, name).apply { writeBytes(ByteArray(size)) }
    private fun engine(id: String = UUID.randomUUID().toString()) = File(root, "engine-$id").apply { mkdirs(); resolve("segment.part").writeBytes(ByteArray(40)) }

    @Test fun `scan separates video temporary and reclaimable bytes while keeping resumable files`() = runBlocking {
        val video = file("video.mp4", 100)
        val partial = file("paused.mp4.part", 30)
        val referenced = engine()
        file("orphan.part", 20); engine()
        history.upsert(DownloadRecord("saved", "saved", "saved", filePath = video.path, status = DownloadStatus.COMPLETED))
        history.upsert(DownloadRecord("paused", "paused", "paused", partialPath = partial.path, status = DownloadStatus.PAUSED))
        val id = referenced.name.removePrefix("engine-")
        history.upsert(DownloadRecord(id, id, "engine paused", status = DownloadStatus.PAUSED))
        history.upsert(DownloadRecord("missing", "missing", "missing", filePath = root.resolve("gone.mp4").path, status = DownloadStatus.COMPLETED))
        val usage = repo.inspect()
        assertEquals(100L, usage.videoBytes); assertEquals(130L, usage.temporaryBytes)
        assertEquals(60L, usage.reclaimableBytes); assertEquals(setOf("missing"), usage.missingRecordIds)
        val result = repo.clean(usage)
        assertEquals(60L, result.freedBytes); assertEquals(1, result.deletedRecords)
        assertTrue(video.exists()); assertTrue(partial.exists()); assertTrue(referenced.exists())
        assertNotNull(history.getById("paused")); assertNull(history.getById("missing"))
    }
    @Test fun `new references changed files and restored completed files are skipped after confirmation`() = runBlocking {
        val newlyReferenced = file("new.part")
        val changed = file("changed.part")
        val restored = root.resolve("restored.mp4")
        history.upsert(DownloadRecord("restored", "restored", "restored", filePath = restored.path, status = DownloadStatus.COMPLETED))
        val plan = repo.inspect()
        history.upsert(DownloadRecord("new", "new", "new", partialPath = newlyReferenced.path, status = DownloadStatus.RUNNING))
        changed.appendText("changed"); restored.writeBytes(byteArrayOf(1))
        val result = repo.clean(plan)
        assertEquals(3, result.skipped); assertEquals(0, result.deletedEntries); assertEquals(0, result.deletedRecords)
        assertTrue(newlyReferenced.exists()); assertTrue(changed.exists()); assertNotNull(history.getById("restored"))
    }
    @Test fun `symlinks unrecognized directories and unreferenced videos are preserved`() = runBlocking {
        val outside = File(temp.root, "outside.mp4").apply { writeBytes(ByteArray(20)) }
        val link = File(root, "link.part")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val linkedEngine = engine()
        Files.createSymbolicLink(linkedEngine.resolve("outside.part").toPath(), outside.toPath())
        val other = File(root, "engine-user-files").apply { mkdirs(); resolve("keep.part").writeBytes(ByteArray(20)) }
        val video = file("unreferenced.mp4")
        assertTrue(repo.inspect().orphanEntries.isEmpty())
        repo.clean(repo.inspect())
        assertTrue(outside.exists()); assertTrue(Files.isSymbolicLink(link.toPath())); assertTrue(linkedEngine.exists())
        assertTrue(other.exists()); assertTrue(video.exists())
    }
    @Test fun `active publishing output and engine directory are never reclaimed`() = runBlocking {
        val id = UUID.randomUUID().toString()
        val dir = engine(id); val publishing = file("publishing.mp4.publishing")
        active = setOf(id)
        assertTrue(repo.inspect().orphanEntries.isEmpty())
        val plan = repo.inspect(); active = emptySet(); repo.clean(plan)
        assertTrue(dir.exists()); assertTrue(publishing.exists())
    }
    @Test fun `cleanup refuses injected out of root entries`() = runBlocking {
        val outside = File(temp.root, "outside.part").apply { writeBytes(ByteArray(20)) }
        val forged = StorageUsage(orphanEntries = listOf(StorageCleanupEntry(outside.path, outside.length(), "fake")))
        assertEquals(1, repo.clean(forged).skipped); assertTrue(outside.exists())
    }
    @Test fun `inspect surfaces the live running budget and headroom`() = runBlocking {
        assertTrue(repo.inspect().budget.isEmpty)
        var reserved = StorageBudgetEntry("task", 400)
        val withBudget = DownloadStorageRepository({ root }, history, Mutex(), { active }, { 1000 },
            budget = { StorageBudget(reserved.remainingBytes, listOf(reserved)) })
        val usage = withBudget.inspect()
        assertEquals(400L, usage.budget.reservedBytes); assertEquals("task", usage.budget.entries.single().title)
        assertEquals(600L, usage.budgetHeadroomBytes)
        reserved = StorageBudgetEntry("task", 1500)
        val short = withBudget.inspect()
        assertEquals(500L, short.budget.shortfallBytes(short.availableBytes))
        assertEquals(0L, short.budgetHeadroomBytes)
    }
}
