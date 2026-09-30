package com.videodown.douyin.data.repository

import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DownloadHistoryRepositoryImpl(
    private val store: DownloadHistoryStore,
) : DownloadHistoryRepository {

    override fun observeAll(): Flow<List<DownloadRecord>> = store.records

    override suspend fun getById(id: String): DownloadRecord? {
        store.load()
        return store.records.value.firstOrNull { it.id == id }
    }

    override suspend fun snapshot(): List<DownloadRecord> = store.load()

    override fun observeRecord(id: String): Flow<DownloadRecord?> =
        store.records.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun upsert(record: DownloadRecord) = store.upsert(record)

    override suspend fun upsertDurably(record: DownloadRecord) = store.upsert(record, durable = true)

    override suspend fun delete(id: String) = store.delete(id)

    override suspend fun clear(deleteFiles: Boolean) = store.clear(deleteFiles)

    override suspend fun recoverInterruptedDownloads() = store.markInterruptedAsPaused()
}
