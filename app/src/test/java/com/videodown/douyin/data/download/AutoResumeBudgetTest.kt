package com.videodown.douyin.data.download

import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.DownloadPauseCause
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import org.junit.Assert.*
import org.junit.Test

/** Bounded retry budget that keeps automatic resume from hammering a permanently broken task. */
class AutoResumeBudgetTest {

    private fun record(id: String, status: DownloadStatus, pauseCause: DownloadPauseCause? = null,
        failureKind: DownloadFailureKind? = null) =
        DownloadRecord(id = id, awemeId = id, title = id, status = status,
            pauseCause = pauseCause, failureKind = failureKind)

    @Test fun `exhausts after the configured number of attempts`() {
        val budget = AutoResumeBudget(3)
        assertFalse(budget.isExhausted("task"))
        budget.recordAttempt("task")
        assertFalse(budget.isExhausted("task"))
        budget.recordAttempt("task")
        assertFalse(budget.isExhausted("task"))
        budget.recordAttempt("task")
        assertTrue(budget.isExhausted("task"))
        // Other tasks are unaffected.
        assertFalse(budget.isExhausted("other"))
    }

    @Test fun `dropping a task that is no longer a candidate resets its budget`() {
        val budget = AutoResumeBudget(1)
        budget.recordAttempt("done")
        assertTrue(budget.isExhausted("done"))
        budget.retainOnly(setOf("waiting"))
        assertFalse(budget.isExhausted("done"))
    }

    @Test fun `a changed blocking condition grants a fresh budget`() {
        val budget = AutoResumeBudget(2)
        budget.recordAttempt("task")
        budget.recordAttempt("task")
        assertTrue(budget.isExhausted("task"))
        budget.clear()
        assertFalse(budget.isExhausted("task"))
    }

    @Test fun `a failing task keeps its budget while the attempt is running`() {
        val budget = AutoResumeBudget(1)
        budget.recordAttempt("task")
        assertTrue(budget.isExhausted("task"))

        // The resumed task is now PENDING/RUNNING and no longer a candidate; its budget must not
        // be forgotten, otherwise the cap would never be reached.
        budget.retainOnly(autoResumeBudgetKeepSet(listOf(record("task", DownloadStatus.RUNNING))))
        assertTrue(budget.isExhausted("task"))

        // Once the task is decided, the budget is released.
        budget.retainOnly(autoResumeBudgetKeepSet(listOf(record("task", DownloadStatus.COMPLETED))))
        assertFalse(budget.isExhausted("task"))
    }

    @Test fun `only pending running and still-eligible tasks keep a budget`() {
        val records = listOf(
            record("running", DownloadStatus.RUNNING),
            record("pending", DownloadStatus.PENDING),
            record("network", DownloadStatus.PAUSED, pauseCause = DownloadPauseCause.NETWORK),
            record("failed-network", DownloadStatus.FAILED, failureKind = DownloadFailureKind.NETWORK),
            record("complete", DownloadStatus.COMPLETED),
            record("user-paused", DownloadStatus.PAUSED, pauseCause = DownloadPauseCause.USER),
            record("legacy-paused", DownloadStatus.PAUSED),
            record("auth", DownloadStatus.FAILED, failureKind = DownloadFailureKind.LOGIN_REQUIRED),
        )
        assertEquals(setOf("running", "pending", "network", "failed-network"),
            autoResumeBudgetKeepSet(records))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive limit`() {
        AutoResumeBudget(0)
    }
}
