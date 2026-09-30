package com.videodown.douyin.domain.repository

import com.videodown.douyin.domain.model.DownloadRecord
import kotlinx.coroutines.flow.Flow

interface DownloadHistoryRepository {
    /** All records, newest first. Emits on every mutation. */
    fun observeAll(): Flow<List<DownloadRecord>>

    suspend fun getById(id: String): DownloadRecord?

    /** One-shot snapshot of every record, newest first. */
    suspend fun snapshot(): List<DownloadRecord>

    /** Observes a single record, emitting `null` once it is deleted. */
    fun observeRecord(id: String): Flow<DownloadRecord?>

    suspend fun upsert(record: DownloadRecord)

    /** Publishes only after durable persistence; throws on write failure. */
    suspend fun upsertDurably(record: DownloadRecord) = upsert(record)

    suspend fun delete(id: String)

    /** Deletes every record, optionally leaving the downloaded files on disk. */
    suspend fun clear(deleteFiles: Boolean = false)

    /** Marks any record left in RUNNING/PENDING state by a previous process as PAUSED. */
    suspend fun recoverInterruptedDownloads()
}
