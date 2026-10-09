package com.videodown.douyin.domain.model

/** Immutable reviewed plan. Paths are internal identifiers, never displayed to the user. */
data class StorageCleanupEntry(val path: String, val bytes: Long, val fingerprint: String)

/**
 * One running task's share of the space budget: the bytes it still has to write, including its
 * working reserve. [title] is display-only and never used as an identifier.
 */
data class StorageBudgetEntry(val title: String, val remainingBytes: Long)

/**
 * Snapshot of the running-download reservation ledger at inspect time.
 *
 * [reservedBytes] is the total space running tasks still need to write; bytes already on disk are
 * reflected by the physical free space instead, so they are not counted twice. When
 * [reservedBytes] exceeds the free space the ledger itself would refuse another write, which is
 * exactly the shortfall [shortfallBytes] explains to the user.
 */
data class StorageBudget(
    val reservedBytes: Long = 0, val entries: List<StorageBudgetEntry> = emptyList(),
) {
    val taskCount: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty() && reservedBytes == 0L
    /** Bytes missing for the running budget; zero means every reservation still fits. */
    fun shortfallBytes(availableBytes: Long): Long =
        (reservedBytes - availableBytes.coerceAtLeast(0)).coerceAtLeast(0)
}

data class StorageUsage(
    val videoBytes: Long = 0, val temporaryBytes: Long = 0, val availableBytes: Long = 0,
    val orphanEntries: List<StorageCleanupEntry> = emptyList(), val missingRecordIds: Set<String> = emptySet(),
    /** Running-download reservation ledger, empty when nothing is downloading. */
    val budget: StorageBudget = StorageBudget(),
) {
    val reclaimableBytes: Long get() = orphanEntries.sumOf { it.bytes }
    /** Free space a new task could still use after the running budget is honored. */
    val budgetHeadroomBytes: Long get() = (availableBytes.coerceAtLeast(0) - budget.reservedBytes).coerceAtLeast(0)
}
data class StorageCleanupResult(val freedBytes: Long, val deletedEntries: Int, val deletedRecords: Int, val skipped: Int, val failed: Int)
