package com.videodown.douyin.data.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class DownloadServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)
internal class BackgroundDownloadCancellation(message: String) : kotlinx.coroutines.CancellationException(message)

/** Each start needs its own acknowledgement; stale services cannot acknowledge a later request. */
internal class DownloadServiceRequests {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    suspend fun awaitStart(timeoutMs: Long = 4_000, start: (String) -> Unit) {
        val token = UUID.randomUUID().toString()
        val ready = CompletableDeferred<Unit>()
        pending[token] = ready
        try {
            try { start(token) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                throw DownloadServiceException("后台下载服务无法启动，请返回应用后重试", error)
            }
            if (withTimeoutOrNull(timeoutMs) { ready.await(); true } != true) {
                throw DownloadServiceException("后台下载服务启动超时，请返回应用后重试")
            }
        } finally { pending.remove(token) }
    }

    fun acknowledge(token: String?, error: DownloadServiceException? = null) {
        val ready = token?.let(pending::get) ?: return
        if (error == null) ready.complete(Unit) else ready.completeExceptionally(error)
    }

    fun failPending(error: DownloadServiceException) {
        pending.values.forEach { it.completeExceptionally(error) }
    }
}
