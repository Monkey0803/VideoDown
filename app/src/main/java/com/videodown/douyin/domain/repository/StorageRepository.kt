package com.videodown.douyin.domain.repository

import com.videodown.douyin.domain.model.StorageUsage
import com.videodown.douyin.domain.model.StorageCleanupResult

interface StorageRepository {
    suspend fun inspect(): StorageUsage
    suspend fun clean(plan: StorageUsage): StorageCleanupResult
}
