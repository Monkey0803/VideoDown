package com.videodown.douyin.data.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DownloadQueueSchedulerTest {
    @Test fun `FIFO registration is stable and priority changes only waiting tasks`() = runTest {
        val queue = DownloadQueueScheduler(1)
        listOf("one", "two", "three", "four").forEach { queue.enqueue(it) }
        assertEquals(listOf("one"), queue.state.value.runningIds)
        assertEquals(listOf("two", "three", "four"), queue.state.value.pendingIds)
        queue.move("four", -Int.MAX_VALUE)
        assertEquals(listOf("four", "two", "three"), queue.state.value.pendingIds)
        queue.move("one", 2)
        assertEquals(listOf("one"), queue.state.value.runningIds)
        queue.finish("one"); queue.awaitSlot("four")
        assertEquals(listOf("four"), queue.state.value.runningIds)
    }
    @Test fun `lower limit drains running jobs and never grants a new slot too early`() = runTest {
        val queue = DownloadQueueScheduler(3)
        (1..5).forEach { queue.enqueue("$it") }
        queue.setLimit(1)
        assertEquals(3, queue.state.value.runningIds.size)
        queue.finish("1"); queue.finish("2")
        assertEquals(listOf("3"), queue.state.value.runningIds)
        assertEquals(listOf("4", "5"), queue.state.value.pendingIds)
        queue.finish("3"); queue.awaitSlot("4")
        assertEquals(listOf("4"), queue.state.value.runningIds)
    }
    @Test fun `increasing limit admits waiting jobs and removing a queued job preserves remaining order`() = runTest {
        val queue = DownloadQueueScheduler(1)
        listOf("a", "b", "c", "d").forEach { queue.enqueue(it) }
        queue.finish("b"); queue.setLimit(2)
        assertEquals(listOf("a", "c"), queue.state.value.runningIds)
        assertEquals(listOf("d"), queue.state.value.pendingIds)
        queue.finish("a"); queue.finish("a")
        assertEquals(listOf("c", "d"), queue.state.value.runningIds)
    }
    @Test fun `boundaries clamp move offsets and concurrency`() = runTest {
        val queue = DownloadQueueScheduler(0)
        queue.enqueue("a"); queue.enqueue("b"); queue.enqueue("c")
        queue.move("b", Int.MAX_VALUE)
        assertEquals(listOf("c", "b"), queue.state.value.pendingIds)
        queue.setLimit(99); assertEquals(5, queue.state.value.maxConcurrent)
        assertTrue(queue.state.value.pendingIds.isEmpty())
    }
    @Test fun `storage estimate accounts for existing bytes merging reserve and overflow`() {
        assertEquals(STORAGE_RESERVE_BYTES + 600L, requiredStorageBytes(1000, 400))
        assertEquals(STORAGE_RESERVE_BYTES + 2000L, requiredStorageBytes(1000, merging = true))
        assertEquals(STORAGE_RESERVE_BYTES, requiredStorageBytes(null))
        assertEquals(Long.MAX_VALUE, requiredStorageBytes(Long.MAX_VALUE, merging = true))
        assertNotNull(spaceFailure(100, 101)); assertNull(spaceFailure(101, 101))
    }

    @Test fun `setOrder reorders only the given ids and keeps the rest where they were`() = runTest {
        val queue = DownloadQueueScheduler(1)
        listOf("running", "a", "hidden", "b", "c").forEach { queue.enqueue(it) }
        // 待下载顺序：a, hidden, b, c（hidden 代表被搜索/过滤挡掉、界面看不见的任务）
        assertEquals(listOf("a", "hidden", "b", "c"), queue.state.value.pendingIds)
        // 界面只把可见的 a/b/c 按 c,a,b 拖过：hidden 必须留在它原来的槽位（第 2 个）。
        queue.setOrder(listOf("c", "a", "b"))
        assertEquals(listOf("c", "hidden", "a", "b"), queue.state.value.pendingIds)
        // 正在下载的不参与重排。
        assertEquals(listOf("running"), queue.state.value.runningIds)
    }

    @Test fun `setOrder ignores ids that are not waiting (running or unknown)`() = runTest {
        val queue = DownloadQueueScheduler(1)
        listOf("running", "a", "b").forEach { queue.enqueue(it) }
        queue.setOrder(listOf("b", "a"))
        assertEquals(listOf("b", "a"), queue.state.value.pendingIds)
        // 混进正在下载或根本不存在的 id：待下载队列不受影响。
        queue.setOrder(listOf("running", "b", "a", "nope"))
        assertEquals(listOf("b", "a"), queue.state.value.pendingIds)
    }
}
