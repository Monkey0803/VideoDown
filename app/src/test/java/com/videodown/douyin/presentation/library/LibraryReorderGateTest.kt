package com.videodown.douyin.presentation.library

import com.videodown.douyin.core.LibrarySort
import com.videodown.douyin.domain.model.VideoPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「什么时候允许把已存列表拖着重排」的纯逻辑。
 *
 * 为什么这条要在 JVM 上把六个条件逐个钉住：真机/模拟器上一条设备用例只能演一个分支（搜索那条已有），
 * 而六个条件是同一处判定共用出来的——多选、批量忙、平台、分类、仅收藏、保险箱里任何一条漏判，
 * 用户就会在"顺序其实由规则决定"的列表上拖动并写坏手动顺序。判定本身与框架无关，所以在这里穷举。
 */
class LibraryReorderGateTest {

    private val open = LibraryQuery(sort = LibrarySort.CUSTOM)

    @Test
    fun `custom sort with a clean query opens the gate`() {
        assertTrue(open.ordersManually)
        assertTrue(open.canReorder(selecting = false, batchBusy = false))
    }

    @Test
    fun `every rule-based sort closes the gate`() {
        LibrarySort.entries.filter { it != LibrarySort.CUSTOM }.forEach { sort ->
            assertFalse(sort.name, LibraryQuery(sort = sort).ordersManually)
        }
    }

    @Test
    fun `searching closes the gate but whitespace alone does not`() {
        assertFalse(open.copy(text = "春天").ordersManually)
        // 搜索框只敲了空格等于没搜：apply 也会把它当成没有关键词。
        assertTrue(open.copy(text = "   ").ordersManually)
    }

    @Test
    fun `each filter closes the gate`() {
        assertFalse("平台过滤", open.copy(platform = VideoPlatform.DOUYIN).ordersManually)
        assertFalse("分类过滤", open.copy(folder = "旅行").ordersManually)
        assertFalse("仅收藏", open.copy(favoritesOnly = true).ordersManually)
        assertFalse("保险箱", open.copy(vaultOnly = true).ordersManually)
    }

    @Test
    fun `multi-select and batch work close the gate`() {
        assertFalse("多选中", open.canReorder(selecting = true, batchBusy = false))
        assertFalse("批量忙", open.canReorder(selecting = false, batchBusy = true))
        assertFalse(open.canReorder(selecting = true, batchBusy = true))
        // 查询本身不允许时，多选/批量忙是 false 也没用。
        assertFalse(open.copy(text = "春天").canReorder(selecting = false, batchBusy = false))
    }

    @Test
    fun `the drag gate is strictly narrower than the seed gate`() {
        val queries = listOf(
            open,
            open.copy(text = "x"),
            open.copy(text = "   "),
            open.copy(platform = VideoPlatform.DOUYIN),
            open.copy(folder = ""),
            open.copy(favoritesOnly = true),
            open.copy(vaultOnly = true),
            LibraryQuery(sort = LibrarySort.NEWEST),
            LibraryQuery(),
        )
        queries.forEach { query ->
            // 能拖就一定处于"顺序由用户决定"：否则会出现"能拖但从未 seed 过"的列表。
            for (selecting in listOf(false, true)) for (busy in listOf(false, true)) {
                if (query.canReorder(selecting, busy)) {
                    assertTrue("$query", query.ordersManually)
                }
            }
            // 多选/批量忙都为 false 时，两个判定必须一致（seed 只看查询这一半）。
            assertEquals("$query", query.ordersManually, query.canReorder(selecting = false, batchBusy = false))
        }
    }
}
