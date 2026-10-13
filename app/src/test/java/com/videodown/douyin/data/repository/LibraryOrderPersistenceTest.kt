package com.videodown.douyin.data.repository

import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 已存「自定义排序」的落盘行为。
 *
 * 关键是**跨进程可读**：写完之后用一个全新的 store/repository 重新读文件，
 * 顺序必须还在——这正是用户重启应用后期望看到的东西。
 */
class LibraryOrderPersistenceTest {

    private val root = TemporaryFolder()

    @get:Rule
    val tempFolder: TemporaryFolder = root

    private fun file() = File(root.root, "download_history.json")

    private fun record(id: String, updatedAt: Long) = DownloadRecord(
        id = id, awemeId = id, title = id, status = DownloadStatus.COMPLETED,
        downloadedBytes = 1, updatedAt = updatedAt,
    )

    @Test fun `reordered positions survive a fresh store and repository`() = runBlocking {
        val repository = DownloadHistoryRepositoryImpl(DownloadHistoryStore(file()))
        listOf(record("a", 10), record("b", 20), record("c", 30)).forEach { repository.upsert(it) }

        repository.reorderLibrary(listOf("c", "a", "b"))

        // 全新实例读同一个文件：顺序、以及「排过」的标记都必须还在。
        val reopened = DownloadHistoryRepositoryImpl(DownloadHistoryStore(file())).snapshot()
        assertEquals(listOf("c", "a", "b"), reopened.sortedBy { it.sortIndex }.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), reopened.sortedBy { it.sortIndex }.map { it.sortIndex })
    }

    @Test fun `unknown ids are ignored and untouched records keep their position`() = runBlocking {
        val repository = DownloadHistoryRepositoryImpl(DownloadHistoryStore(file()))
        listOf(record("a", 10), record("b", 20), record("c", 30)).forEach { repository.upsert(it) }
        repository.reorderLibrary(listOf("b", "a"))

        repository.reorderLibrary(listOf("deleted-id", "a"))

        val records = repository.snapshot().associateBy { it.id }
        assertEquals(2L, records.getValue("a").sortIndex)
        assertEquals(1L, records.getValue("b").sortIndex)
        // 从没被排过的记录保持 0：它按更新时间接在排过的之后，而不是被塞进中间。
        assertEquals(0L, records.getValue("c").sortIndex)
    }

    @Test fun `an empty order writes nothing`() = runBlocking {
        val repository = DownloadHistoryRepositoryImpl(DownloadHistoryStore(file()))
        repository.upsert(record("a", 10))
        repository.reorderLibrary(emptyList())
        assertEquals(0L, repository.snapshot().single().sortIndex)
    }
}
