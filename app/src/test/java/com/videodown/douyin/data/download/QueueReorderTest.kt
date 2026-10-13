package com.videodown.douyin.data.download

import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 拖拽重排队列的引擎级验证：新顺序既要立刻生效，也要**落盘到历史**（重启后按 queueOrder 恢复）。
 *
 * 只开一个并发槽且下载端点是死地址，所以三个任务都会停在待下载队列里，正是拖拽作用的区间。
 * 断言用全新 store 读同一个文件，避免「只改了内存」蒙混过关。
 */
class QueueReorderTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { DownloadHistoryStore(File(temp.root, "history.json")) }
    private val history by lazy { DownloadHistoryRepositoryImpl(store) }
    private val paths by lazy { DownloadPaths(temp.root) }
    private val engine by lazy {
        DownloadEngine(
            history,
            ResumableDownloader(OkHttpClient.Builder().retryOnConnectionFailure(false).build()),
            paths, scope, "", "test", maxConcurrent = 1,
        )
    }

    @After fun close() { scope.cancel() }

    /** 用一个永远连不上的地址入队：任务停在 PENDING，不产生真实流量。 */
    private suspend fun enqueue(name: String): String = engine.start(
        DouyinVideo(name, name),
        VideoVariant("http://127.0.0.1:1/$name.mp4", "720P", sizeBytes = 1024),
    )

    private fun pendingOrder(): List<String> = engine.queue.value.pendingIds

    @Test fun `dragging rewrites the pending order and it survives a fresh store`() = runBlocking {
        val running = enqueue("a"); val b = enqueue("b"); val c = enqueue("c")
        // 一个并发槽：a 已在下载，拖拽作用的是待下载的 b、c。
        assertEquals(listOf(b, c), pendingOrder())

        engine.reorderQueue(listOf(c, b))

        assertEquals(listOf(c, b), pendingOrder())
        // 落盘：全新实例读同一个文件，两条待下载任务的先后必须反过来。
        val reopened = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json"))).snapshot()
        val order = reopened.sortedBy { it.queueOrder }.map { it.id }
        assertTrue(order.indexOf(c) < order.indexOf(b))
        // 正在进行的那条不在待下载区间内，它的队列位置不应该被动过。
        assertEquals(listOf(running), engine.queue.value.runningIds)
    }

    @Test fun `ids the queue does not hold never drop or clear the stored order`() = runBlocking {
        val running = enqueue("a"); val b = enqueue("b"); val c = enqueue("c")
        engine.reorderQueue(listOf(running, c, "ghost"))

        assertEquals(listOf(b, c), pendingOrder())
        // 三条记录都还在，而且待下载的相对顺序没有被这次无效拖拽打乱。
        val reopened = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json"))).snapshot()
        assertEquals(3, reopened.size)
        assertTrue(reopened.all { it.queueOrder > 0L })
    }
}
