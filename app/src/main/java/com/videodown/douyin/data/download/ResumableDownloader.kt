package com.videodown.douyin.data.download

import android.util.Log
import com.videodown.douyin.core.AppError
import com.videodown.douyin.domain.model.DownloadFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performs a single resumable HTTP transfer.
 *
 * Resumption works the way `curl -C -` does:
 *  1. If a partial file already holds [DownloadRequest.alreadyDownloaded] bytes, ask for
 *     `Range: bytes=<n>-` and, when we have one, guard it with `If-Range: <etag>` so a
 *     changed remote file restarts cleanly instead of producing a corrupt result.
 *  2. `206 Partial Content` -> append the remaining bytes to the partial file.
 *  3. `200 OK` -> the server ignored the range, so truncate and write from scratch.
 *  4. `416 Range Not Satisfiable` -> the partial file is already as large as the remote
 *     object, which means the download actually finished before the interruption.
 */
class ResumableDownloader(
    private val client: OkHttpClient,
) {

    sealed interface Outcome {
        data class Completed(val file: File, val totalBytes: Long) : Outcome

        /** Interrupted, but [downloadedBytes] are safely on disk and can be resumed. */
        data class Paused(
            val downloadedBytes: Long,
            val totalBytes: Long,
            val rangeSupported: Boolean,
            val etag: String?,
        ) : Outcome

        data class Failed(
            val error: AppError,
            val downloadedBytes: Long,
            val totalBytes: Long,
            val rangeSupported: Boolean,
            val etag: String?,
        ) : Outcome
    }

    /**
     * One transfer description. Named `DownloadRequest` rather than `Request` so it does not
     * shadow `okhttp3.Request` inside this file.
     */
    data class DownloadRequest(
        val url: String,
        val partialFile: File,
        val finalFile: File,
        val alreadyDownloaded: Long = 0L,
        val etag: String? = null,
        val referer: String? = null,
        val userAgent: String? = null,
        val cookieForUrl: ((String) -> String?)? = null,
        val networkPolicy: DownloadNetworkPolicy = DownloadNetworkPolicy.Unrestricted,
        val availableBytes: () -> Long = { partialFile.parentFile?.usableSpace ?: 0L },
        val checkStorage: ((total: Long, writingThrough: Long) -> Unit)? = null,
        val onProgress: suspend (downloaded: Long, total: Long, bytesPerSecond: Long, rangeSupported: Boolean, etag: String?) -> Unit = { _, _, _, _, _ -> },
    )

    suspend fun download(request: DownloadRequest): Outcome = withContext(Dispatchers.IO) {
        if (!request.networkPolicy.isAllowed()) throw WifiRequiredException()
        request.partialFile.parentFile?.mkdirs()

        val existing = if (request.partialFile.exists()) request.partialFile.length() else 0L
        val startOffset = when {
            existing <= 0L -> 0L
            request.alreadyDownloaded in 1..existing -> request.alreadyDownloaded
            else -> existing
        }

        val builder = Request.Builder().url(request.url).get()
        request.referer?.let { builder.header("Referer", it) }
        request.userAgent?.let { builder.header("User-Agent", it) }
        builder.header("Accept", "*/*")
        builder.header("Accept-Encoding", "identity")

        if (startOffset > 0L) {
            builder.header("Range", "bytes=$startOffset-")
            request.etag?.takeIf { it.isNotBlank() }?.let { builder.header("If-Range", it) }
        }

        val networkBlocked = AtomicBoolean(false)
        fun checkNetwork() {
            if (networkBlocked.get() || !request.networkPolicy.isAllowed()) throw WifiRequiredException()
        }
        // Do not reuse a connection opened by parsing/size probing on a previous mobile network.
        // DownloadEngine owns retries; every redirect/exchange must recheck the current policy.
        val transferPool = ConnectionPool()
        val transferClient = client.newBuilder()
            .connectionPool(transferPool)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor { chain ->
                checkNetwork()
                val next = chain.request().newBuilder().removeHeader("Cookie")
                request.cookieForUrl?.invoke(chain.request().url.toString())?.let { next.header("Cookie", it) }
                chain.proceed(next.build())
            }
            .build()
        val call = transferClient.newCall(builder.build())

        // Make coroutine cancellation actually abort the socket read.
        val job = currentCoroutineContext().job
        val cancellationWatcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                request.networkPolicy.allowed.collect { allowed ->
                    if (!allowed) {
                        networkBlocked.set(true)
                        call.cancel()
                    }
                }
                awaitCancellation()
            } finally {
                // Runs on cancellation, even when execute()/read() is still blocked in IO.
                call.cancel()
            }
        }

        var downloaded = startOffset
        var totalBytes = -1L
        var rangeSupported = startOffset > 0L
        var etag: String? = request.etag
        var lastEmitAt = 0L
        var speed = 0L
        var writingStorage = false

        fun checkStorage(writingThrough: Long) {
            if (request.checkStorage != null) request.checkStorage.invoke(totalBytes, writingThrough)
            else spaceFailure(request.availableBytes(), requiredStorageBytes(maxOf(totalBytes, writingThrough), downloaded))
                ?.let { throw InsufficientStorageException(it) }
        }

        try {
            checkNetwork()
            call.execute().use { response ->
                checkNetwork()
                if (!response.isSuccessful) {
                    val code = response.code
                    return@use if (code == 416 && downloaded > 0 && response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() == downloaded) {
                        // Everything is already present locally.
                        writingStorage = true
                        val completeSize = request.partialFile.length()
                        totalBytes = completeSize
                        checkStorage(completeSize)
                        if (request.finalFile.exists()) request.finalFile.delete()
                        if (!request.partialFile.renameTo(request.finalFile)) {
                            checkStorage(saturatingStorageAdd(completeSize, completeSize))
                            request.partialFile.copyTo(request.finalFile, overwrite = true)
                            request.partialFile.delete()
                        }
                        writingStorage = false
                        Outcome.Completed(request.finalFile, completeSize)
                    } else {
                        Outcome.Failed(
                            AppError.Network("服务器返回 HTTP $code", failureKind = when (code) {
                                401 -> DownloadFailureKind.LOGIN_REQUIRED
                                403 -> DownloadFailureKind.ACCESS_DENIED
                                404, 410 -> DownloadFailureKind.LINK_EXPIRED
                                429 -> DownloadFailureKind.RATE_LIMITED
                                else -> DownloadFailureKind.NETWORK
                            }),
                            downloaded,
                            totalBytes,
                            rangeSupported,
                            etag,
                        )
                    }
                }

                rangeSupported = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true ||
                    response.code == 206
                response.header("ETag")?.let { etag = it }

                val body = response.body
                val isPartial = response.code == 206
                if (isPartial && response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull() != startOffset) {
                    return@use Outcome.Failed(AppError.Network("服务器返回的续传位置不一致，请重新下载", failureKind = DownloadFailureKind.FORMAT_CHANGED), downloaded, totalBytes, false, etag)
                }

                if (startOffset > 0L && !isPartial) {
                    // Server refused the range: restart from zero so the file stays coherent.
                    Log.w(TAG, "Server ignored Range request; restarting from 0")
                    downloaded = 0L
                    totalBytes = body?.contentLength() ?: -1L
                } else {
                    totalBytes = when {
                        isPartial -> {
                            val contentRangeTotal = response.header("Content-Range")
                                ?.substringAfterLast('/')
                                ?.trim()
                                ?.toLongOrNull()
                            val len = body?.contentLength() ?: -1L
                            when {
                                contentRangeTotal != null && contentRangeTotal > 0 -> contentRangeTotal
                                len >= 0L -> startOffset + len
                                else -> -1L
                            }
                        }
                        else -> body?.contentLength() ?: -1L
                    }
                }

                checkStorage(downloaded)
                val input = body?.byteStream() ?: run {
                    return@use Outcome.Failed(
                        AppError.Network("响应体为空"),
                        downloaded,
                        totalBytes,
                        rangeSupported,
                        etag,
                    )
                }

                val mode = if (downloaded > 0L && isPartial) "rwd" else "rw"
                writingStorage = true
                RandomAccessFile(request.partialFile, mode).use { output ->
                    if (mode == "rw") output.setLength(0L)
                    if (mode == "rwd") output.seek(downloaded)
                    writingStorage = false

                    val buffer = ByteArray(BUFFER_SIZE)
                    val startedAt = System.currentTimeMillis()
                    var firstEmit = true

                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkNetwork()

                        val read = input.read(buffer)
                        if (read == -1) break

                        checkStorage(saturatingStorageAdd(downloaded, read.toLong()))
                        writingStorage = true
                        output.write(buffer, 0, read)
                        writingStorage = false
                        downloaded += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - startedAt
                        if (elapsed > 0) {
                            speed = ((downloaded - startOffset) * 1000L) / elapsed
                        }

                        if (firstEmit || now - lastEmitAt >= PROGRESS_INTERVAL_MS) {
                            firstEmit = false
                            lastEmitAt = now
                            request.onProgress(downloaded, totalBytes, speed, rangeSupported, etag)
                        }
                    }
                }

                request.onProgress(downloaded, totalBytes, speed, rangeSupported, etag)
                checkNetwork()

                if (totalBytes > 0L && downloaded < totalBytes) {
                    // Connection closed early: keep the partial file so the next attempt resumes.
                    return@use Outcome.Paused(downloaded, totalBytes, rangeSupported, etag)
                }

                writingStorage = true
                if (request.finalFile.exists()) request.finalFile.delete()
                if (!request.partialFile.renameTo(request.finalFile)) {
                    checkStorage(saturatingStorageAdd(downloaded, downloaded))
                    request.partialFile.copyTo(request.finalFile, overwrite = true)
                    request.partialFile.delete()
                }

                writingStorage = false
                Outcome.Completed(request.finalFile, if (totalBytes > 0L) totalBytes else downloaded)
            }
        } catch (error: InsufficientStorageException) {
            Outcome.Failed(error.error, downloaded, totalBytes, rangeSupported, etag)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                request.onProgress(downloaded, totalBytes, 0L, rangeSupported, etag)
            }
            throw e
        } catch (e: IOException) {
            if (networkBlocked.get() || !request.networkPolicy.isAllowed()) {
                withContext(NonCancellable) {
                    request.onProgress(downloaded, totalBytes, 0L, rangeSupported, etag)
                }
                throw WifiRequiredException()
            }
            if (job.isCancelled) {
                throw CancellationException("下载已取消").also { it.initCause(e) }
            }
            val outOfSpace = generateSequence<Throwable>(e) { it.cause }.take(8).any {
                it.message.orEmpty().let { message -> message.contains("ENOSPC", true) || message.contains("no space left", true) }
            }
            val error = if (outOfSpace) AppError.Storage("存储空间不足，请清理空间后继续", failureKind = DownloadFailureKind.STORAGE_FULL)
                else if (writingStorage || e is java.io.FileNotFoundException) AppError.Storage("无法写入视频文件，请检查存储空间", failureKind = DownloadFailureKind.STORAGE_ACCESS)
                else AppError.Network("网络连接失败，请检查连接后重试", failureKind = DownloadFailureKind.NETWORK)
            Outcome.Failed(error, downloaded, totalBytes, rangeSupported, etag)
        } catch (e: Exception) {
            Outcome.Failed(if (writingStorage || e is SecurityException) AppError.Storage("无法写入视频文件，请检查存储空间", failureKind = DownloadFailureKind.STORAGE_ACCESS)
                else AppError.Network("网络连接失败，请检查连接后重试", failureKind = DownloadFailureKind.NETWORK), downloaded, totalBytes, rangeSupported, etag)
        } finally {
            cancellationWatcher.cancel()
            transferPool.evictAll()
        }
    }

    private companion object {
        const val TAG = "ResumableDownloader"
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
