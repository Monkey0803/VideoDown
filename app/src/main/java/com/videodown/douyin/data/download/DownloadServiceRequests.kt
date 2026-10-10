package com.videodown.douyin.data.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class DownloadServiceException(
    val failure: com.videodown.douyin.core.ServiceFailure,
    cause: Throwable? = null,
) : Exception(failure.storageKey, cause)
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
                throw DownloadServiceException(com.videodown.douyin.core.ServiceFailure.FOREGROUND_START, error)
            }
            if (withTimeoutOrNull(timeoutMs) { ready.await(); true } != true) {
                throw DownloadServiceException(com.videodown.douyin.core.ServiceFailure.FOREGROUND_START)
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
