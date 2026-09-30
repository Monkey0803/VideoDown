package com.videodown.douyin.domain.model

/** Immutable reviewed plan. Paths are internal identifiers, never displayed to the user. */
data class StorageCleanupEntry(val path: String, val bytes: Long, val fingerprint: String)
data class StorageUsage(
    val videoBytes: Long = 0, val temporaryBytes: Long = 0, val availableBytes: Long = 0,
    val orphanEntries: List<StorageCleanupEntry> = emptyList(), val missingRecordIds: Set<String> = emptySet(),
) {
    val reclaimableBytes: Long get() = orphanEntries.sumOf { it.bytes }
}
data class StorageCleanupResult(val freedBytes: Long, val deletedEntries: Int, val deletedRecords: Int, val skipped: Int, val failed: Int)
