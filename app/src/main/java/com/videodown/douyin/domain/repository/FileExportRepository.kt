package com.videodown.douyin.domain.repository

/** Copies a completed local file into a user-selected document tree; preserves the source. */
interface FileExportRepository {
    suspend fun recoverInterruptedExports() {}
    suspend fun pendingCleanupCount(): Int = 0
    suspend fun export(id: String, treeUri: String): Result<String>
}
