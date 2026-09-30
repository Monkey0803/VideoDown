package com.videodown.douyin.data.download

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
        val permit = mutex.withLock { permits[id] } ?: throw CancellationException("任务已移出队列")
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
    private fun dispatch() {
        while (running.size < capacity && pending.isNotEmpty()) {
            val id = pending.removeAt(0); running += id; permits.getValue(id).complete(Unit)
        }
        publish()
    }
    private fun publish() { mutableState.value = DownloadQueueState(capacity, running.toList(), pending.toList()) }
}
