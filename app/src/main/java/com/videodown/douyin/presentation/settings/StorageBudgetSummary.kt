package com.videodown.douyin.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.domain.model.StorageBudget
import com.videodown.douyin.domain.model.StorageUsage

/**
 * Pure presentation helpers for the running-download space budget.
 *
 * The storage screen already showed physical free space and file sizes; these turn the reservation
 * ledger into the answer users actually need: how much running tasks still have to write, and
 * whether that budget still fits — plus a concrete reason when it does not.
 */

/** Headline for the running budget: how much space running tasks still need. */
@Composable
internal fun storageBudgetHeadline(budget: StorageBudget): String =
    if (budget.isEmpty) stringResource(R.string.storage_budget_none)
    else stringResource(R.string.storage_budget_headline, formatBytes(budget.reservedBytes), budget.taskCount)

/** Bytes missing for the running budget; zero means every reservation still fits. */
internal fun storageBudgetShortfall(usage: StorageUsage): Long =
    usage.budget.shortfallBytes(usage.availableBytes)

/** Explains whether the running budget fits, and the concrete shortfall when it does not. */
@Composable
internal fun storageBudgetReason(usage: StorageUsage): String {
    if (usage.budget.isEmpty) return stringResource(R.string.storage_budget_idle)
    val shortfall = storageBudgetShortfall(usage)
    return if (shortfall == 0L) stringResource(R.string.storage_budget_fits)
    else stringResource(R.string.storage_budget_short, formatBytes(shortfall))
}

/** Fraction of (running reservation + free space) already claimed by running tasks, for the bar. */
internal fun storageBudgetFraction(usage: StorageUsage): Float {
    val reserved = usage.budget.reservedBytes.coerceAtLeast(0)
    val free = usage.availableBytes.coerceAtLeast(0)
    val total = if (reserved > Long.MAX_VALUE - free) Long.MAX_VALUE else reserved + free
    if (total <= 0L || reserved == 0L) return 0f
    return (reserved.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Percentage label for the bar. A bar whose slice is tiny on a mostly empty disk still reads
 * clearly once the exact share is written out.
 */
@Composable
internal fun storageBudgetPercent(usage: StorageUsage): String =
    stringResource(R.string.storage_budget_percent, storageBudgetFraction(usage) * 100)
