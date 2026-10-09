package com.videodown.douyin.data.download

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.canAutoResume

/**
 * In-memory retry budget for automatic resume.
 *
 * A transfer that keeps failing after an automatic retry must eventually be left to the user
 * instead of being scheduled forever. The budget is deliberately not persisted: a process restart
 * is a new opportunity, and so is a changed blocking condition (network back, switch turned on).
 *
 * A task that is still blocked (no Wi-Fi, no space) never spends an attempt, so waiting for a
 * condition to clear cannot exhaust the budget.
 */
internal class AutoResumeBudget(private val limit: Int) {
    init { require(limit > 0) { "自动继续次数上限必须为正数" } }

    private val attempts = mutableMapOf<String, Int>()

    /** Drops the budget of tasks that stopped being candidates (done, cancelled, user-paused). */
    @Synchronized
    fun retainOnly(ids: Set<String>) { attempts.keys.retainAll(ids) }

    @Synchronized
    fun isExhausted(id: String): Boolean = attempts.getOrDefault(id, 0) >= limit

    @Synchronized
    fun recordAttempt(id: String) { attempts[id] = attempts.getOrDefault(id, 0) + 1 }

    /** A changed blocking condition grants a fresh budget. */
    @Synchronized
    fun clear() { attempts.clear() }
}

/**
 * Ids whose retry budget must survive the next sweep.
 *
 * A task that is mid-attempt (PENDING/RUNNING) is not a current candidate, but dropping its budget
 * there would let a repeatedly failing task retry forever.
 */
internal fun autoResumeBudgetKeepSet(records: List<DownloadRecord>): Set<String> =
    records.filter {
        it.canAutoResume || it.status == DownloadStatus.PENDING || it.status == DownloadStatus.RUNNING
    }.mapTo(mutableSetOf()) { it.id }
