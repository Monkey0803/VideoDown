package com.videodown.douyin.domain.repository

interface LibraryMetadataRepository {
    suspend fun update(
        id: String,
        title: String? = null,
        favorite: Boolean? = null,
        folder: String? = null,
        /** Moves the record into or out of the private vault. */
        isPrivate: Boolean? = null,
    ): Result<Unit>
    suspend fun recoverRenames()
}
