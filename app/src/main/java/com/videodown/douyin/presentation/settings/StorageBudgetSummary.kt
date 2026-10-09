package com.videodown.douyin.presentation.settings

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
internal fun storageBudgetHeadline(budget: StorageBudget): String =
    if (budget.isEmpty) "运行任务空间预算：无"
    else "运行任务尚需 ${formatBytes(budget.reservedBytes)} · ${budget.taskCount} 个任务"

/** Bytes missing for the running budget; zero means every reservation still fits. */
internal fun storageBudgetShortfall(usage: StorageUsage): Long =
    usage.budget.shortfallBytes(usage.availableBytes)

/** Explains whether the running budget fits, and the concrete shortfall when it does not. */
internal fun storageBudgetReason(usage: StorageUsage): String {
    if (usage.budget.isEmpty) return "当前没有运行中的下载任务占用空间预算。"
    val shortfall = storageBudgetShortfall(usage)
    return if (shortfall == 0L) "预算在可用空间内；写入前仍会逐次检查实际剩余空间。"
    else "空间不足：仍缺 ${formatBytes(shortfall)}，请清理残留或删除已存视频后继续。"
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
internal fun storageBudgetPercent(usage: StorageUsage): String =
    String.format(java.util.Locale.US, "预算占用 %.1f%%", storageBudgetFraction(usage) * 100)
