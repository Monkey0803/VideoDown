package com.videodown.douyin.domain.repository

import com.videodown.douyin.domain.model.SiteWatchRecord
import kotlinx.coroutines.flow.StateFlow

interface SiteWatchHistoryRepository {
    val records: StateFlow<List<SiteWatchRecord>>
    suspend fun load(): List<SiteWatchRecord>
    suspend fun upsert(record: SiteWatchRecord)
    suspend fun delete(id: String)
    suspend fun clear()
    suspend fun find(id: String): SiteWatchRecord?
}
