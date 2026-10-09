package com.videodown.douyin.data.download

import android.util.Log
import com.videodown.douyin.data.generic.EngineMediaDownloader
import com.videodown.douyin.data.generic.VideoEngineException
import com.videodown.douyin.domain.model.TransferPhase
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import com.videodown.douyin.domain.repository.Downloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.videodown.douyin.domain.model.canRetryDownload
import com.videodown.douyin.domain.model.matchesSelection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.core.downloadFailureKind
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Coordinates download tasks on top of [ResumableDownloader].
 *
 * Responsibilities:
 *  - turning a parsed video + chosen rendition into a persisted [DownloadRecord];
 *  - running transfers with bounded concurrency;
 *  - publishing live byte counters through [progress];
 *  - persisting the byte offset often enough that a killed process can still resume;
 *  - retrying transient network failures with backoff before giving up.
 */
class DownloadEngine(
    private val history: DownloadHistoryRepository,
    private val downloader: ResumableDownloader,
    private val paths: DownloadPaths,
    private val scope: CoroutineScope,
    private val referer: String,
    private val userAgent: String,
    maxConcurrent: Int = DEFAULT_CONCURRENCY,
    private val networkPolicy: DownloadNetworkPolicy = DownloadNetworkPolicy.Unrestricted,
    private val onCompleted: (DownloadRecord) -> Unit = {},
    private val onTaskScheduled: suspend () -> Unit = {},
    private val engineDownloader: EngineMediaDownloader? = null,
    private val browserCookie: ((String) -> String?)? = null,
    private val validateFile: ((File) -> Unit)? = null,
    private val refreshSource: (suspend (DownloadRecord) -> VideoVariant?)? = null,
    private val maintenanceMutex: Mutex = Mutex(),
    private val concurrencyChanges: Flow<Int>? = null,
    private val availableBytes: () -> Long = { paths.downloadDir.usableSpace },
) : Downloader {

    private val scheduler = DownloadQueueScheduler(maxConcurrent)
    override val queue = scheduler.state
    private val storageReservations = StorageReservations(availableBytes)

    fun activeTaskIds(): Set<String> = activeJobs.keys.toSet()

    init { concurrencyChanges?.let { changes -> scope.launch { changes.collect { scheduler.setLimit(it) } } } }
    override suspend fun setConcurrency(count: Int) { scheduler.setLimit(count) }
    override suspend fun moveInQueue(id: String, offset: Int) = maintenanceMutex.withLock {
        val order = scheduler.move(id, offset)
        val base = history.snapshot().maxOfOrNull { it.queueOrder } ?: 0L
        order.forEachIndexed { index, recordId ->
            history.getById(recordId)?.let { history.upsert(it.copy(queueOrder = base + index + 1)) }
        }
    }

    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    override val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    override suspend fun findDuplicate(video: DouyinVideo, variant: VideoVariant): DownloadRecord? =
        history.snapshot().filter { it.matchesSelection(video, variant) }
            .filterNot { it.status == DownloadStatus.FAILED && it.failureKind in setOf(DownloadFailureKind.LOGIN_REQUIRED, DownloadFailureKind.ACCESS_DENIED) &&
                variant.media?.useBrowserSession == true && it.media?.useBrowserSession != true }
            .filter { it.status != DownloadStatus.COMPLETED || it.filePath?.let { path -> File(path).isFile } == true }
            .sortedWith(compareByDescending<DownloadRecord> { it.status == DownloadStatus.COMPLETED }
                .thenByDescending { it.updatedAt }).firstOrNull()

    override suspend fun start(video: DouyinVideo, variant: VideoVariant): String = maintenanceMutex.withLock {
        val id = UUID.randomUUID().toString()
        val fileId = if (video.platform == VideoPlatform.DOUYIN) video.awemeId
            else "${video.platform.name.lowercase()}_${video.awemeId}"
        val fileName = paths.buildFileName(video.title, variant.label, fileId + "_" + id.take(8))
            .substringBeforeLast('.') + "." + (variant.media?.extension ?: "mp4")
        val partial = File(paths.downloadDir, "$fileName.part")
        val now = System.currentTimeMillis()

        val record = DownloadRecord(
            id = id,
            awemeId = video.awemeId,
            title = video.title,
            authorNickname = video.author.nickname,
            authorAvatarUrl = video.author.avatarUrl,
            authorSecUid = video.author.secUid,
            coverUrl = video.coverUrl,
            sourceUrl = variant.url,
            expectedBytes = variant.sizeBytes,
            queueOrder = (history.snapshot().maxOfOrNull { it.queueOrder } ?: 0L) + 1,
            media = variant.media,
            resolutionLabel = variant.label,
            fileName = fileName,
            partialPath = partial.absolutePath,
            status = DownloadStatus.PENDING,
            createdAt = now,
            updatedAt = now,
            platform = video.platform,
            sourcePageUrl = video.videoWebUrl,
            downloadReferer = variant.referer ?: video.platform.referer,
        )

        history.upsert(record)
        updateProgress(id) {
            DownloadProgress(id = id, downloadedBytes = 0L, totalBytes = -1L, status = DownloadStatus.PENDING)
        }
        launch(record)
        return@withLock id
    }

    override suspend fun pause(id: String) = maintenanceMutex.withLock {
        val job = activeJobs[id]
        if (job != null) {
            job.cancel(CancellationException("用户暂停"))
            job.join()
        }
        scheduler.finish(id)
        markPaused(id)
    }

    override suspend fun resume(id: String) = maintenanceMutex.withLock {
        val record = history.getById(id) ?: return@withLock
        if (record.status == DownloadStatus.COMPLETED || !record.canRetryDownload) return@withLock
        if (activeJobs[id]?.isActive == true) return@withLock

        val partial = File(record.partialPath)
        val existing = if (partial.exists()) partial.length() else 0L
        val queued = record.copy(status = DownloadStatus.PENDING, errorMessage = null, failureKind = null,
            queueOrder = (history.snapshot().maxOfOrNull { it.queueOrder } ?: 0L) + 1)
        history.upsert(queued)
        updateProgress(id) {
            DownloadProgress(id = id, downloadedBytes = existing, totalBytes = record.totalBytes, status = DownloadStatus.PENDING)
        }
        launch(queued)
    }

    override suspend fun restart(id: String) = maintenanceMutex.withLock {
        val job = activeJobs.remove(id)
        job?.cancel(CancellationException("重新开始"))
        job?.join()
        scheduler.finish(id)

        val record = history.getById(id) ?: return@withLock
        if (record.media?.localRecordId == null && !networkPolicy.isAllowed()) {
            markPaused(id, WIFI_PAUSE_REASON)
            return@withLock
        }
        runCatching { File(record.partialPath).delete() }
        paths.engineDirectory(record.id).deleteRecursively()
        runCatching { paths.finalFileFor(record).delete() }

        val reset = record.copy(
            downloadedBytes = 0L,
            totalBytes = -1L,
            status = DownloadStatus.PENDING,
            queueOrder = (history.snapshot().maxOfOrNull { it.queueOrder } ?: 0L) + 1,
            errorMessage = null, failureKind = null,
            etag = null,
            filePath = null,
            updatedAt = System.currentTimeMillis(),
        )
        history.upsert(reset)
        updateProgress(id) {
            DownloadProgress(id = id, downloadedBytes = 0L, totalBytes = -1L, status = DownloadStatus.PENDING)
        }
        launch(reset)
    }

    override suspend fun cancel(id: String, removeRecord: Boolean) = maintenanceMutex.withLock {
        val job = activeJobs.remove(id)
        job?.cancel(CancellationException("用户取消"))
        job?.join()
        scheduler.finish(id)

        val record = history.getById(id)
        if (record != null) {
            runCatching { File(record.partialPath).delete() }
            paths.engineDirectory(record.id).deleteRecursively()
            runCatching { paths.finalFileFor(record).delete() }
        }
        _progress.update { it - id }
        if (removeRecord) history.delete(id)
    }

    override suspend fun resumeAll() {
        history.snapshot()
            .filter { (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) && it.canRetryDownload }
            .sortedWith(compareBy<DownloadRecord> { it.queueOrder }.thenBy { it.createdAt })
            .forEach { resume(it.id) }
    }

    override suspend fun pauseAll() {
        activeJobs.keys.toList().forEach { pause(it) }
    }

    override fun observeRecord(id: String) = history.observeRecord(id)

    private suspend fun launch(record: DownloadRecord) {
        val previous = activeJobs[record.id]
        if (previous?.isActive == true) return
        if (previous != null) { activeJobs.remove(record.id, previous); scheduler.finish(record.id) }
        scheduler.enqueue(record.id)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var storage: StorageReservations.Lease? = null
            try {
                onTaskScheduled()
                scheduler.awaitSlot(record.id)
                if (record.media?.localRecordId == null && !networkPolicy.isAllowed()) throw WifiRequiredException()
                // Reordering persists while this job is waiting. Read it after the
                // maintenance gate so initial transfer updates retain that durable order.
                val current = maintenanceMutex.withLock { history.getById(record.id) } ?: return@launch
                val engine = current.media?.usesEngine == true
                val footprint = {
                    if (engine) taskDirectoryBytes(paths.engineDirectory(current.id))
                    else File(current.partialPath).length() + paths.finalFileFor(current).length()
                }
                val expected = current.expectedBytes ?: current.totalBytes.takeIf { it > 0 }
                val peak = maxOf(requiredStorageBytes(expected, merging = engine),
                    saturatingStorageAdd(footprint(), STORAGE_RESERVE_BYTES))
                val lease = storageReservations.acquire(peak, footprint)
                storage = lease
                runTransfer(current, lease)

            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val backgroundFailure = generateSequence<Throwable>(e) { it.cause }
                        .filterIsInstance<BackgroundDownloadCancellation>().firstOrNull()
                    if (backgroundFailure != null) markBackgroundFailure(record.id, backgroundFailure.message.orEmpty())
                    else markPaused(record.id, if (e is WifiRequiredException) WIFI_PAUSE_REASON else null)
                }
                throw e
            } catch (e: Exception) {
                if (e is DownloadServiceException) Log.w(TAG, "后台下载服务启动失败", e)
                val latest = history.getById(record.id) ?: record
                history.upsert(latest.copy(status = DownloadStatus.FAILED,
                    errorMessage = when (e) { is VideoEngineException -> e.message; is InsufficientStorageException -> e.error.message; is DownloadServiceException -> e.message; else -> "下载或合并失败，请检查存储空间后重试" },
                    failureKind = when (e) { is VideoEngineException -> e.failureKind; is InsufficientStorageException -> DownloadFailureKind.STORAGE_FULL; is DownloadServiceException -> DownloadFailureKind.BACKGROUND_SERVICE; else -> DownloadFailureKind.UNKNOWN },
                    updatedAt = System.currentTimeMillis()))
                _progress.update { it - record.id }
            } finally {
                storage?.close()
                withContext(NonCancellable) { scheduler.finish(record.id) }
            }
        }
        if (activeJobs.putIfAbsent(record.id, job) != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { activeJobs.remove(record.id, job) }
        // Progress is registered before the job asks the service for its foreground acknowledgement.
        job.start()
    }

    suspend fun interruptForBackgroundFailure(message: String) {
        // Cancel a snapshot without holding the maintenance gate: jobs may be waiting for it.
        val jobs = activeJobs.values.toList()
        jobs.forEach { it.cancel(BackgroundDownloadCancellation(message)) }
        jobs.forEach { it.join() }
    }

    private suspend fun markBackgroundFailure(id: String, message: String) {
        history.getById(id)?.takeIf { it.status != DownloadStatus.COMPLETED }?.let { latest ->
            val progress = _progress.value[id]
            val partial = File(latest.partialPath)
            val downloaded = if (latest.media?.usesEngine != true && partial.isFile) partial.length()
                else progress?.downloadedBytes ?: latest.downloadedBytes
            history.upsert(latest.copy(status = DownloadStatus.FAILED, errorMessage = message,
                downloadedBytes = downloaded, totalBytes = progress?.totalBytes?.takeIf { it > 0 } ?: latest.totalBytes,
                phase = progress?.phase ?: latest.phase,
                failureKind = DownloadFailureKind.BACKGROUND_SERVICE, updatedAt = System.currentTimeMillis()))
        }
        _progress.update { it - id }
    }

    private suspend fun runTransfer(initial: DownloadRecord, storage: StorageReservations.Lease) {
        if (initial.media?.usesEngine == true) {
            runEngineTransfer(initial, storage)
            return
        }
        var attempt = 0
        var record = initial
        var refreshed = false

        while (true) {
            if (!networkPolicy.isAllowed()) throw WifiRequiredException()
            val partial = File(record.partialPath)
            val finalFile = paths.finalFileFor(record)
            val startBytes = if (partial.exists()) partial.length() else 0L
            val now = System.currentTimeMillis()

            record = record.copy(
                status = DownloadStatus.RUNNING,
                downloadedBytes = startBytes,
                errorMessage = null, failureKind = null,
                updatedAt = now,
            )
            history.upsert(record)
            updateProgress(record.id) {
                DownloadProgress(
                    id = record.id,
                    downloadedBytes = startBytes,
                    totalBytes = record.totalBytes,
                    status = DownloadStatus.RUNNING,
                )
            }

            var lastPersistAt = 0L
            val outcome =
                downloader.download(
                    ResumableDownloader.DownloadRequest(
                        url = record.sourceUrl,
                        partialFile = partial,
                        finalFile = finalFile,
                        alreadyDownloaded = startBytes,
                        etag = record.etag,
                        referer = record.downloadReferer ?: if (record.platform == VideoPlatform.DOUYIN) referer else record.platform.referer,
                        userAgent = record.media?.userAgent ?: userAgent,
                        cookieForUrl = if (record.media?.useBrowserSession == true) browserCookie else null,
                        networkPolicy = networkPolicy,
                        availableBytes = availableBytes,
                        checkStorage = { total, writingThrough ->
                            storage.updatePeak(requiredStorageBytes(maxOf(total, writingThrough)))
                        },
                        onProgress = { downloaded, total, speed, rangeSupported, etag ->
                            updateProgress(record.id) {
                                DownloadProgress(
                                    id = record.id,
                                    downloadedBytes = downloaded,
                                    totalBytes = total,
                                    status = DownloadStatus.RUNNING,
                                    bytesPerSecond = speed,
                                )
                            }
                            val tick = System.currentTimeMillis()
                            if (tick - lastPersistAt >= PERSIST_INTERVAL_MS) {
                                lastPersistAt = tick
                                history.upsert(
                                    record.copy(
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        rangeSupported = rangeSupported,
                                        etag = etag,
                                        updatedAt = tick,
                                    ),
                                )
                            }
                        },
                    ),
                )

            when (outcome) {
                is ResumableDownloader.Outcome.Completed -> {
                    try { validateFile?.invoke(outcome.file) } catch (error: Exception) { outcome.file.delete(); throw error }
                    val completed = record.copy(
                        status = DownloadStatus.COMPLETED,
                        filePath = outcome.file.absolutePath,
                        downloadedBytes = outcome.totalBytes,
                        totalBytes = outcome.totalBytes,
                        errorMessage = null, failureKind = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                    history.upsert(completed)
                    // Only a newly completed transfer emits; restoring history never re-notifies.
                    runCatching { onCompleted(completed) }
                        .onFailure { Log.w(TAG, "无法发送下载完成通知", it) }
                    _progress.update { it - record.id }
                    return
                }

                is ResumableDownloader.Outcome.Paused -> {
                    history.upsert(
                        record.copy(
                            status = DownloadStatus.PAUSED,
                            downloadedBytes = outcome.downloadedBytes,
                            totalBytes = outcome.totalBytes,
                            rangeSupported = outcome.rangeSupported,
                            etag = outcome.etag,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    _progress.update { it - record.id }
                    return
                }

                is ResumableDownloader.Outcome.Failed -> {
                    history.upsert(
                        record.copy(
                            downloadedBytes = outcome.downloadedBytes,
                            totalBytes = outcome.totalBytes,
                            rangeSupported = outcome.rangeSupported,
                            etag = outcome.etag,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    if (!refreshed && (outcome.error.message.contains("HTTP 403") || outcome.error.message.contains("HTTP 401") || outcome.error.message.contains("HTTP 410"))) {
                        refreshed = true
                        val fresh = refreshSource?.invoke(record)
                        if (fresh != null && fresh.media?.usesEngine != true) {
                            val identical = record.media?.let { old -> fresh.media?.let(old::sameMedia) } == true
                            // If-Range is essential even for an unchanged logical media ID.
                            val reuse = identical && record.etag != null
                            if (!reuse) partial.delete()
                            record = record.copy(sourceUrl = fresh.url, media = fresh.media,
                                downloadReferer = fresh.referer, downloadedBytes = if (reuse) outcome.downloadedBytes else 0,
                                etag = if (reuse) record.etag else null)
                            history.upsert(record)
                            continue
                        }
                    }
                    attempt++
                    if (attempt <= MAX_RETRIES && outcome.error !is com.videodown.douyin.core.AppError.Storage && !outcome.error.message.contains("HTTP 4")) {
                        val backoff = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                        Log.w(TAG, "下载失败，${backoff}ms 后第 $attempt 次重试: ${outcome.error.message}")
                        delay(backoff)
                        record = history.getById(record.id) ?: record
                        continue
                    }
                    history.upsert(
                        record.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = outcome.error.message,
                            failureKind = outcome.error.downloadFailureKind(),
                            downloadedBytes = outcome.downloadedBytes,
                            totalBytes = outcome.totalBytes,
                            rangeSupported = outcome.rangeSupported,
                            etag = outcome.etag,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    _progress.update { it - record.id }
                    return
                }
            }
        }
    }

    private suspend fun runEngineTransfer(initial: DownloadRecord, storage: StorageReservations.Lease) {
        val executor = requireNotNull(engineDownloader) { "未安装通用下载引擎" }
        var record = initial.copy(status = DownloadStatus.RUNNING, errorMessage = null, failureKind = null, phase = TransferPhase.DOWNLOADING)
        history.upsert(record)
        var persistedAt = 0L
        val expected = java.util.concurrent.atomic.AtomicLong(record.expectedBytes ?: record.totalBytes.takeIf { it > 0 } ?: 0)
        val directory = paths.engineDirectory(record.id)
        fun checkEngineSpace() {
            val used = taskDirectoryBytes(directory)
            storage.updatePeak(maxOf(requiredStorageBytes(expected.get().takeIf { it > 0 }, merging = true),
                saturatingStorageAdd(used, STORAGE_RESERVE_BYTES)))
        }
        suspend fun transfer(): File = kotlinx.coroutines.coroutineScope {
            // FFmpeg may write without reporting download progress. Continue monitoring until
            // extraction/merging/verification has returned and the subprocess is stopped.
            val monitor = launch(kotlinx.coroutines.Dispatchers.IO) {
                while (true) { checkEngineSpace(); delay(250) }
            }
            try {
                executor.download(record, paths, if (record.media?.localRecordId != null) DownloadNetworkPolicy.Unrestricted else networkPolicy) { downloaded, total, speed, phase ->
                    if (total > 0) expected.updateAndGet { maxOf(it, total) }
                    checkEngineSpace()
                    updateProgress(record.id) { DownloadProgress(record.id, downloaded, total, DownloadStatus.RUNNING, speed, phase) }
                    val now = System.currentTimeMillis()
                    val phaseChanged = phase != record.phase
                    record = record.copy(downloadedBytes = downloaded, totalBytes = total, phase = phase, updatedAt = now)
                    if (phaseChanged || now - persistedAt > PERSIST_INTERVAL_MS) { history.upsert(record); persistedAt = now }
                }
            } finally { monitor.cancel(); monitor.join() }
        }

        val stagedOutput = try { transfer() } catch (error: VideoEngineException) {
            if (error.kind !in setOf("AUTH", "ACCESS", "EXPIRED")) throw error
            val fresh = refreshSource?.invoke(record) ?: throw error
            if (fresh.media?.usesEngine != true) throw error
            record = record.copy(sourceUrl = fresh.url, media = fresh.media, downloadReferer = fresh.referer)
            history.upsert(record)
            transfer()
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            val output = File(paths.downloadDir, record.fileName.substringBeforeLast('.') + "." + stagedOutput.extension)
            val publishing = File(output.parentFile, output.name + ".publishing")
            storage.includeUsage { saturatingStorageAdd(output.length(), publishing.length()) }
            if (!stagedOutput.renameTo(output)) {
                storage.updatePeak(saturatingStorageAdd(taskDirectoryBytes(paths.engineDirectory(record.id)),
                    saturatingStorageAdd(stagedOutput.length(), STORAGE_RESERVE_BYTES)))
                try { stagedOutput.copyTo(publishing, overwrite = true); check(publishing.renameTo(output)); stagedOutput.delete() }
                finally { publishing.delete() }
            }
            val completed = record.copy(status = DownloadStatus.COMPLETED, filePath = output.absolutePath,
                fileName = output.name, downloadedBytes = output.length(), totalBytes = output.length(), errorMessage = null, failureKind = null,
                updatedAt = System.currentTimeMillis())
            history.upsert(completed)
            paths.engineDirectory(record.id).deleteRecursively()
            runCatching { onCompleted(completed) }
            _progress.update { it - record.id }
        }
    }

    private suspend fun markPaused(id: String, reason: String? = null) {
        val record = history.getById(id) ?: return
        if (record.status == DownloadStatus.COMPLETED) return
        val partial = File(record.partialPath)
        val onDisk = if (partial.exists()) partial.length() else record.downloadedBytes
        history.upsert(
            record.copy(
                status = DownloadStatus.PAUSED,
                downloadedBytes = onDisk,
                errorMessage = reason,
                failureKind = if (reason == WIFI_PAUSE_REASON) DownloadFailureKind.WIFI_REQUIRED else null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        _progress.update { it - id }
    }

    private fun updateProgress(id: String, transform: () -> DownloadProgress) {
        _progress.update { current -> current + (id to transform()) }
    }

    private companion object {
        const val TAG = "DownloadEngine"
        const val DEFAULT_CONCURRENCY = 3
        const val MAX_RETRIES = 3
        const val RETRY_BASE_DELAY_MS = 1_500L
        const val PERSIST_INTERVAL_MS = 2_000L
    }
}
