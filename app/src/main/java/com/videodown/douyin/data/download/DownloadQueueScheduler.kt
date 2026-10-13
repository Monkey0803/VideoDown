package com.videodown.douyin.data.download

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import com.videodown.douyin.domain.model.DownloadQueueState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** FIFO registration happens before jobs are launched, independent of dispatcher order. */
internal class DownloadQueueScheduler(limit: Int) {
    private val mutex = Mutex()
    private var capacity = limit.coerceIn(1, 5)
    private val permits = linkedMapOf<String, CompletableDeferred<Unit>>()
    private val pending = mutableListOf<String>()
    private val running = linkedSetOf<String>()
    private val mutableState = MutableStateFlow(DownloadQueueState(capacity))
    val state = mutableState.asStateFlow()

    suspend fun enqueue(id: String) = mutex.withLock {
        if (id !in permits) { permits[id] = CompletableDeferred(); pending += id; dispatch() }
    }
    suspend fun awaitSlot(id: String) {
        val permit = mutex.withLock { permits[id] } ?: throw CancellationException(AppText.get(R.string.queue_task_removed))
        permit.await()
    }
    suspend fun finish(id: String) = mutex.withLock {
        permits.remove(id)?.cancel(); pending.remove(id); running.remove(id); dispatch()
    }
    suspend fun setLimit(count: Int) = mutex.withLock { capacity = count.coerceIn(1, 5); dispatch() }
    suspend fun move(id: String, offset: Int): List<String> = mutex.withLock {
        val index = pending.indexOf(id)
        if (index >= 0) {
            val target = (index + offset.toLong()).coerceIn(0L, (pending.size - 1).toLong()).toInt()
            pending.removeAt(index); pending.add(target, id); publish()
        }
        pending.toList()
    }
    /**
     * 把 [ids] 按给定顺序放回它们原本占据的槽位。
     *
     * 只动这些 id：没出现在 [ids] 里的待下载任务（例如被搜索或过滤挡掉的）保持彼此相对位置，
     * 也不会被挤到队尾——这是拖拽重排最容易出错的地方。
     */
    suspend fun setOrder(ids: List<String>): List<String> = mutex.withLock {
        val slots = pending.withIndex().filter { it.value in ids }.map { it.index }
        val ordered = ids.filter { id -> pending.contains(id) }
        if (slots.size != ordered.size) return@withLock pending.toList()
        slots.forEachIndexed { i, slot -> pending[slot] = ordered[i] }
        publish()
        pending.toList()
    }
    private fun dispatch() {
        while (running.size < capacity && pending.isNotEmpty()) {
            val id = pending.removeAt(0); running += id; permits.getValue(id).complete(Unit)
        }
        publish()
    }
    private fun publish() { mutableState.value = DownloadQueueState(capacity, running.toList(), pending.toList()) }
}
