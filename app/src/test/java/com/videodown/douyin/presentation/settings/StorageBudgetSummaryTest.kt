package com.videodown.douyin.presentation.settings

import com.videodown.douyin.domain.model.StorageBudget
import com.videodown.douyin.domain.model.StorageBudgetEntry
import com.videodown.douyin.domain.model.StorageUsage
import org.junit.Assert.*
import org.junit.Test

class StorageBudgetSummaryTest {
    @Test fun `empty budget reads as no running reservation`() {
        val usage = StorageUsage(availableBytes = 5000)
        assertTrue(storageBudgetHeadline(usage.budget).contains("无"))
        assertEquals(0L, storageBudgetShortfall(usage))
        assertEquals(0f, storageBudgetFraction(usage), 0f)
        assertTrue(storageBudgetReason(usage).contains("没有运行中的下载任务"))
    }
    @Test fun `fitting budget reports amount tasks and positive headroom`() {
        val usage = StorageUsage(availableBytes = 1000, budget = StorageBudget(400, listOf(StorageBudgetEntry("a", 400))))
        assertEquals("运行任务尚需 400 B · 1 个任务", storageBudgetHeadline(usage.budget))
        assertEquals(0L, storageBudgetShortfall(usage))
        assertEquals(600L, usage.budgetHeadroomBytes)
        assertTrue(storageBudgetReason(usage).contains("预算在可用空间内"))
        assertEquals(400f / 1400f, storageBudgetFraction(usage), 0.0001f)
        assertEquals("预算占用 28.6%", storageBudgetPercent(usage))
    }
    @Test fun `shortfall names the missing bytes as the reason`() {
        val usage = StorageUsage(availableBytes = 1000, budget = StorageBudget(1500, listOf(StorageBudgetEntry("a", 1500))))
        assertEquals(500L, storageBudgetShortfall(usage))
        assertEquals(0L, usage.budgetHeadroomBytes)
        val reason = storageBudgetReason(usage)
        assertTrue(reason.contains("空间不足"))
        assertTrue(reason.contains("仍缺 500 B"))
    }
    @Test fun `saturated totals and zero free space cannot break the bar`() {
        val usage = StorageUsage(availableBytes = Long.MAX_VALUE,
            budget = StorageBudget(Long.MAX_VALUE, listOf(StorageBudgetEntry("a", Long.MAX_VALUE))))
        assertEquals(0L, storageBudgetShortfall(usage))
        assertTrue(storageBudgetFraction(usage) in 0f..1f)
        assertEquals(1f, storageBudgetFraction(usage), 0.0001f)
        assertEquals(0f, storageBudgetFraction(StorageUsage(availableBytes = 0, budget = StorageBudget(0))), 0f)
        val negative = StorageUsage(availableBytes = -5, budget = StorageBudget(10))
        assertEquals(10L, negative.budget.shortfallBytes(negative.availableBytes))
        assertEquals(0L, negative.budgetHeadroomBytes)
    }
}
