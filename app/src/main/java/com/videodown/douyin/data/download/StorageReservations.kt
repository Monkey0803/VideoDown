package com.videodown.douyin.data.download

/** One ledger per download filesystem. Budgets cover peak task bytes plus working reserve.
 * Written bytes already reduce usableSpace, so only the unwritten part is reserved.
 */
internal class StorageReservations(private val availableBytes: () -> Long) {
    private val leases = mutableSetOf<Lease>()

    @Synchronized
    fun acquire(peakBytes: Long, usedBytes: () -> Long): Lease {
        val lease = Lease(peakBytes.coerceAtLeast(0), usedBytes)
        checkBudget(lease, lease.peakBytes)
        leases.add(lease)
        return lease
    }

    // Read footprints before free space: writes racing with this snapshot make admission
    // conservative rather than counting space that has already been consumed as available.
    private fun checkBudget(candidate: Lease, peak: Long) {
        var needed = (peak - candidate.usedBytes().coerceAtLeast(0)).coerceAtLeast(0)
        for (lease in leases) if (lease !== candidate) {
            val remaining = (lease.peakBytes - lease.usedBytes().coerceAtLeast(0)).coerceAtLeast(0)
            if (remaining > Long.MAX_VALUE - needed) throw InsufficientStorageException(spaceFailure(0, 1)!!)
            needed += remaining
        }
        spaceFailure(availableBytes(), needed)?.let { throw InsufficientStorageException(it) }
    }

    inner class Lease internal constructor(internal var peakBytes: Long, internal var usedBytes: () -> Long) : AutoCloseable {
        fun updatePeak(peak: Long) = synchronized(this@StorageReservations) {
            check(this in leases) { "Storage reservation is closed" }
            val next = peak.coerceAtLeast(0)
            checkBudget(this, next)
            peakBytes = next
        }
        // Publishing may temporarily create another file outside the staging directory.
        fun includeUsage(additionalBytes: () -> Long) = synchronized(this@StorageReservations) {
            check(this in leases) { "Storage reservation is closed" }
            val previous = usedBytes
            usedBytes = { saturatingStorageAdd(previous(), additionalBytes()) }
        }
        fun check() = synchronized(this@StorageReservations) { updatePeak(peakBytes) }
        override fun close() { synchronized(this@StorageReservations) { leases.remove(this) } }
    }
}

internal fun saturatingStorageAdd(first: Long, second: Long): Long =
    if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
