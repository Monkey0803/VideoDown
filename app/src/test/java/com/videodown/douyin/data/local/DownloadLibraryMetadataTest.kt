package com.videodown.douyin.data.local

import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DownloadLibraryMetadataTest {
    @get:Rule val temp = TemporaryFolder()
    private val paths by lazy { DownloadPaths(temp.root) }
    private val historyFile by lazy { File(temp.root, "history.json") }
    private val history by lazy { DownloadHistoryRepositoryImpl(DownloadHistoryStore(historyFile)) }
    private val journal by lazy { File(temp.root, "private") }
    private val gate = Mutex()
    private var active = emptySet<String>()
    private fun repository(storage: DownloadHistoryRepository = history) = DownloadLibraryMetadataRepository(paths, storage, journal, gate) { active }
    private suspend fun saved(): DownloadRecord {
        val file = File(paths.downloadDir, "original.mp4").apply { writeBytes(ByteArray(40) { it.toByte() }) }
        return DownloadRecord("abcd1234-1234-1234-1234-123456789abc", "media", "original", fileName = file.name, filePath = file.path,
            partialPath = file.path + ".part", status = DownloadStatus.COMPLETED, updatedAt = 100L).also { history.upsert(it) }
    }
    @Test fun `rename changes actual file and persists favorite folder without modifying identity or saved order`() = runBlocking {
        val before = saved()
        assertTrue(repository().update(before.id, "新名字", true, "旅行").isSuccess)
        val after = DownloadHistoryRepositoryImpl(DownloadHistoryStore(historyFile)).getById(before.id)!!
        assertEquals("新名字", after.title); assertEquals("旅行", after.folder); assertTrue(after.favorite)
        assertEquals(before.awemeId, after.awemeId); assertEquals(before.updatedAt, after.updatedAt)
        assertFalse(File(before.filePath!!).exists()); assertTrue(File(after.filePath!!).isFile)
        assertTrue(after.fileName.startsWith("新名字")); assertEquals("mp4", File(after.filePath!!).extension)
        assertArrayEquals(ByteArray(40) { it.toByte() }, File(after.filePath!!).readBytes())
        assertFalse(File(journal, "rename.json").exists())
    }
    @Test fun `renaming a live photo moves its still and motion together`() = runBlocking {
        val still = File(paths.downloadDir, "live.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val motion = File(paths.downloadDir, "live_motion.mov").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }
        val before = DownloadRecord(
            id = "live-photo", awemeId = "post-live", title = "实况照片",
            fileName = still.name, filePath = still.path, partialPath = still.path + ".part",
            status = DownloadStatus.COMPLETED, mediaKind = MediaKind.IMAGE,
            livePhotoMotion = LivePhotoMotion(
                sourceUrl = "https://example.test/live.mov",
                fileName = motion.name,
                filePath = motion.path,
            ),
        ).also { history.upsert(it) }

        assertTrue(repository().update(before.id, "海边实况", null, null).isSuccess)
        val after = history.getById(before.id)!!
        val renamedStill = File(after.filePath!!)
        val renamedMotion = File(after.livePhotoMotion!!.filePath!!)
        assertTrue(renamedStill.isFile)
        assertTrue(renamedMotion.isFile)
        assertEquals("海边实况", after.title)
        assertEquals(renamedStill.nameWithoutExtension + "_motion.mov", renamedMotion.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), renamedStill.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), renamedMotion.readBytes())
        assertFalse(still.exists())
        assertFalse(motion.exists())
        assertFalse(File(journal, "rename.json").exists())
    }
    @Test fun `empty or overlength names and active missing or outside files are rejected without loss`() = runBlocking {
        val before = saved(); val repo = repository()
        assertTrue(repo.update(before.id, "   ", null, null).isFailure)
        assertTrue(repo.update(before.id, "长".repeat(81), null, null).isFailure)
        assertTrue(repo.update(before.id, null, null, "bad/folder").isFailure)
        active = setOf(before.id); assertTrue(repo.update(before.id, null, true, null).isFailure); active = emptySet()
        val outside = File(temp.root, "outside.mp4").apply { writeText("keep") }
        history.upsert(before.copy(filePath = outside.path))
        assertTrue(repo.update(before.id, "outside renamed", null, null).isFailure)
        assertTrue(outside.isFile); assertTrue(File(before.filePath!!).isFile)
    }
    @Test fun `history write failure rolls file back and preserves existing metadata`() = runBlocking {
        val before = saved()
        val faulty = object : DownloadHistoryRepository by history { override suspend fun upsertDurably(record: DownloadRecord) { throw IOException("disk write failed") } }
        assertTrue(repository(faulty).update(before.id, "changed", null, null).isFailure)
        assertEquals(before, history.getById(before.id)); assertTrue(File(before.filePath!!).isFile)
        assertFalse(File(journal, "rename.json").exists())
    }
    @Test fun `real store persistence failure preserves disk memory and original file`() = runBlocking {
        val before = saved()
        val diskBefore = historyFile.readBytes()
        val blockedTemporary = File(temp.root, "history.json.tmp").apply { mkdir() }
        assertTrue(repository().update(before.id, "changed", true, "folder").isFailure)
        assertEquals(before, history.getById(before.id))
        assertEquals(before, DownloadHistoryRepositoryImpl(DownloadHistoryStore(historyFile)).getById(before.id))
        assertArrayEquals(diskBefore, historyFile.readBytes())
        assertTrue(File(before.filePath!!).isFile)
        assertFalse(File(journal, "rename.json").exists())
        assertTrue(repository().update(before.id, null, true, "folder").isFailure)
        assertEquals(before, history.getById(before.id))
        assertTrue(blockedTemporary.delete())
        assertTrue(repository().update(before.id, null, true, "folder").isSuccess)
    }
    @Test fun `long existing title may be categorized without renaming`() = runBlocking {
        val before = saved().copy(title = "长".repeat(100)).also { history.upsert(it) }
        assertTrue(repository().update(before.id, null, null, "旅行").isSuccess)
        val after = history.getById(before.id)!!
        assertEquals(before.title, after.title)
        assertEquals(before.filePath, after.filePath)
        assertEquals("旅行", after.folder)
    }
    @Test fun `platform caption line breaks do not block a rename and never reach the file name`() = runBlocking {
        val before = saved()
        // 平台标题本身就是多行文本（X 的推文换行会原样进库），重命名不能因此失败。
        val caption = "还是得前排32\n\n这钱\u0001花的真值\n风光无限好啊"
        assertTrue(repository().update(before.id, caption, null, "美女").isSuccess)
        val after = history.getById(before.id)!!
        assertEquals(caption, after.title)
        assertEquals("美女", after.folder)
        assertTrue(File(after.filePath!!).isFile)
        assertFalse(after.fileName.any { it.isISOControl() })
        assertTrue(after.fileName.startsWith("还是得前排32 这钱 花的真值 风光无限好啊_"))
        assertTrue(File(after.filePath!!).parentFile == paths.downloadDir)
    }
    @Test fun `caption punctuation cannot turn a title into a path outside the download directory`() = runBlocking {
        val before = saved()
        assertTrue(repository().update(before.id, "../../逃逸/名字?", null, null).isSuccess)
        val after = history.getById(before.id)!!
        assertEquals("../../逃逸/名字?", after.title)
        assertTrue(File(after.filePath!!).isFile)
        assertEquals(paths.downloadDir.canonicalFile, File(after.filePath!!).canonicalFile.parentFile)
    }
    @Test fun `journal recovers a process interruption between file move and history commit`() = runBlocking {
        val before = saved()
        val killed = object : DownloadHistoryRepository by history { override suspend fun upsertDurably(record: DownloadRecord) { throw AssertionError("simulated process interruption") } }
        try { repository(killed).update(before.id, "恢复名字", true, "分类"); fail("expected interruption") } catch (_: AssertionError) { }
        assertFalse(File(before.filePath!!).exists()); assertTrue(File(journal, "rename.json").isFile)
        repository().recoverRenames()
        val after = history.getById(before.id)!!
        assertEquals("恢复名字", after.title); assertTrue(after.favorite); assertEquals("分类", after.folder)
        assertTrue(File(after.filePath!!).isFile); assertFalse(File(journal, "rename.json").exists())
    }
    @Test fun `symlink source is never renamed`() = runBlocking {
        val before = saved()
        val link = File(paths.downloadDir, "link.mp4")
        Files.createSymbolicLink(link.toPath(), File(before.filePath!!).toPath())
        history.upsert(before.copy(filePath = link.path))
        assertTrue(repository().update(before.id, "new", null, null).isFailure)
        assertTrue(Files.isSymbolicLink(link.toPath())); assertTrue(File(before.filePath!!).isFile)
    }
}
