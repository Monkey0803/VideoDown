package com.videodown.douyin.domain.repository

interface LibraryMetadataRepository {
    suspend fun update(id: String, title: String? = null, favorite: Boolean? = null, folder: String? = null): Result<Unit>
    suspend fun recoverRenames()
}
