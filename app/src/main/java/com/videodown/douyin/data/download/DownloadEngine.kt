package com.videodown.douyin.data.download

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import android.util.Log
import com.videodown.douyin.data.generic.EngineMediaDownloader
import com.videodown.douyin.data.generic.VideoEngineException
import com.videodown.douyin.domain.model.TransferPhase
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.SubtitleSelection
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.videodown.douyin.domain.model.canAutoResume
import com.videodown.douyin.domain.model.canRetryDownload
import com.videodown.douyin.domain.model.matchesSelection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.DownloadPauseCause
import com.videodown.douyin.domain.model.StorageBudget
import com.videodown.douyin.domain.model.speedLimitBytesPerSecond
import com.videodown.douyin.domain.model.StorageBudgetEntry
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
    /** Flips when the user enables or disables automatic resume. */
    private val autoResumeChanges: Flow<Boolean>? = null,
    /** Current value of the automatic-resume switch, read before every sweep. */
    private val autoResumeEnabled: () -> Boolean = { false },
    /** How often blocked tasks are re-examined while automatic resume is on. */
    private val autoResumeIntervalMs: Long = DEFAULT_AUTO_RESUME_INTERVAL_MS,
    /** Failed automatic resumes allowed before a task is left for the user. */
    private val autoResumeAttemptsLimit: Int = MAX_AUTO_RESUME_ATTEMPTS,
    /** Global rate cap changes, in KB/s; `0` means unlimited. */
    private val globalSpeedLimitChanges: Flow<Int>? = null,
    /** Current global rate cap in KB/s, read once before the change flow starts. */
    private val globalSpeedLimitKbps: () -> Int = { 0 },
) : Downloader {

    private val scheduler = DownloadQueueScheduler(maxConcurrent)
    override val queue = scheduler.state
    /**
     * Shared by every direct transfer, so the global cap limits their total rather than each task.
     * Engine downloads (yt-dlp / FFmpeg) are separate processes and are not paced here.
     */
    private val globalLimiter = BandwidthLimiter()
    /** One limiter per task, created on demand and dropped when its transfer ends. */
    private val taskLimiters = java.util.concurrent.ConcurrentHashMap<String, BandwidthLimiter>()
    private val storageReservations = StorageReservations(availableBytes)
    private val autoResumeMutex = Mutex()
    private val autoResumeBudget = AutoResumeBudget(autoResumeAttemptsLimit)

    fun activeTaskIds(): Set<String> = activeJobs.keys.toSet()

    /**
     * Display-only snapshot of the running space budget for the storage screen. Sums each open
     * lease's unwritten bytes with saturating arithmetic, so an absurd peak can never wrap into a
     * misleading small number.
     */
    fun storageBudget(): StorageBudget {
        val entries = storageReservations.snapshot().map { entry ->
            // A blank label is data; the storage dialog substitutes its own wording.
            StorageBudgetEntry(entry.label, entry.remainingBytes)
        }
        var total = 0L
        entries.forEach { total = saturatingStorageAdd(total, it.remainingBytes) }
        return StorageBudget(total, entries.sortedByDescending { it.remainingBytes })
    }

    init {
        concurrencyChanges?.let { changes -> scope.launch { changes.collect { scheduler.setLimit(it) } } }
        globalLimiter.setRate(speedLimitBytesPerSecond(globalSpeedLimitKbps()))
        globalSpeedLimitChanges?.let { changes ->
            scope.launch { changes.collect { globalLimiter.setRate(speedLimitBytesPerSecond(it)) } }
        }
        startAutoResumeWatchers()
    }

    /**
     * Paces one chunk of [record] against both caps.
     *
     * The global bucket is consumed first so a global limit stays a genuine total, then the task's
     * own bucket; an unset cap is unlimited and never suspends.
     */
    private suspend fun throttle(record: DownloadRecord, bytes: Int) {
        globalLimiter.acquire(bytes)
        // Looked up by id rather than read off the captured record, so changing a cap mid-transfer
        // takes effect on the very next chunk; no entry means the task follows the global cap.
        taskLimiters[record.id]?.acquire(bytes)
    }

    /** Seeds the per-task limiter from the persisted cap; called once per transfer. */
    private fun seedTaskLimiter(record: DownloadRecord) {
        if (record.speedLimitKbps <= 0) {
            taskLimiters.remove(record.id)
            return
        }
        taskLimiters.computeIfAbsent(record.id) { BandwidthLimiter() }
            .setRate(speedLimitBytesPerSecond(record.speedLimitKbps))
    }

    override suspend fun setSpeedLimit(id: String, kbps: Int) = maintenanceMutex.withLock {
        val safe = kbps.coerceAtLeast(0)
        history.getById(id)?.let { history.upsert(it.copy(speedLimitKbps = safe, updatedAt = System.currentTimeMillis())) }
        if (safe > 0) {
            taskLimiters.computeIfAbsent(id) { BandwidthLimiter() }.setRate(speedLimitBytesPerSecond(safe))
        } else {
            taskLimiters.remove(id)
        }
        Unit
    }
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

    override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection): String = maintenanceMutex.withLock {
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
            // Never for an audio-only target: there is nothing to embed an audio file into.
            subtitleLanguages = if (variant.media?.audioOnly == true) emptyList() else subtitles.languages,
            subtitleAutomatic = subtitles.automatic,
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

            job.cancel(CancellationException(AppText.get(R.string.download_cancel_user_paused)))
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
            pauseCause = null,
            queueOrder = (history.snapshot().maxOfOrNull { it.queueOrder } ?: 0L) + 1)
        history.upsert(queued)
        updateProgress(id) {
            DownloadProgress(id = id, downloadedBytes = existing, totalBytes = record.totalBytes, status = DownloadStatus.PENDING)
        }
        launch(queued)
    }

    override suspend fun restart(id: String) = maintenanceMutex.withLock {
        val job = activeJobs.remove(id)

        job?.cancel(CancellationException(AppText.get(R.string.download_cancel_restart)))
        job?.join()
        scheduler.finish(id)

        val record = history.getById(id) ?: return@withLock
        if (record.media?.localRecordId == null && !networkPolicy.isAllowed()) {
            markPaused(id, WIFI_PAUSE_REASON, DownloadPauseCause.NETWORK)
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
            pauseCause = null,
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

        job?.cancel(CancellationException(AppText.get(R.string.download_cancel_user_cancelled)))
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

    /**
     * Continues every task the automatic policy may pick up: a transfer stopped by the network or
     * by a full disk, or one orphaned by a process death. A deliberate pause is never included.
     *
     * A task whose blocking condition is still present is skipped without spending an attempt, so a
     * still-full disk or a still-missing Wi-Fi waits indefinitely for the condition to clear while
     * a genuinely broken transfer gives up after [autoResumeAttemptsLimit] tries.
     */
    override suspend fun autoResume() = autoResumeMutex.withLock {
        if (!autoResumeEnabled()) return@withLock
        val records = history.snapshot()
        // Keep the budget while a task is still mid-attempt (PENDING/RUNNING) or still eligible;
        // dropping it then would let a failing task retry forever. Anything decided - completed,
        // cancelled, or failed for a reason automatic resume does not handle - loses its budget.
        autoResumeBudget.retainOnly(autoResumeBudgetKeepSet(records))
        val candidates = records
            .filter { it.canAutoResume && activeJobs[it.id]?.isActive != true }
            .sortedWith(compareBy<DownloadRecord> { it.queueOrder }.thenBy { it.createdAt })
        for (record in candidates) {
            if (autoResumeBudget.isExhausted(record.id)) continue
            if (record.media?.localRecordId == null && !networkPolicy.isAllowed()) continue
            if (!storageAllowsAutoResume(record)) continue
            autoResumeBudget.recordAttempt(record.id)
            resume(record.id)
        }
    }

    /**
     * Mirrors the space check performed right before a transfer starts, so an automatic resume
     * never spends an attempt on a disk that is still too full.
     */
    private fun storageAllowsAutoResume(record: DownloadRecord): Boolean {
        val engine = record.media?.usesEngine == true
        val footprint = if (engine) taskDirectoryBytes(paths.engineDirectory(record.id))
            else File(record.partialPath).length()
        val expected = record.expectedBytes ?: record.totalBytes.takeIf { it > 0 }
        val required = maxOf(requiredStorageBytes(expected, footprint, merging = engine),
            saturatingStorageAdd(footprint, STORAGE_RESERVE_BYTES))
        return availableBytes() >= required
    }

    /**
     * Wakes the automatic policy when the network returns or the switch is turned on, then keeps
     * sweeping slowly so freed disk space is noticed even while the network never changes.
     */
    private fun startAutoResumeWatchers() {
        val enabled = autoResumeChanges ?: flowOf(autoResumeEnabled())
        scope.launch {
            combine(enabled, networkPolicy.allowed) { on, allowed -> on && allowed }
                .distinctUntilChanged()
                .collect { ready ->
                    // A condition change grants a fresh retry budget.
                    if (ready) safeAutoResume() else autoResumeBudget.clear()
                }
        }
        scope.launch {
            while (true) {
                delay(autoResumeIntervalMs)
                if (autoResumeEnabled()) safeAutoResume()
            }
        }
    }

    private suspend fun safeAutoResume() {
        try {
            autoResume()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {

            Log.w(TAG, AppText.get(R.string.log_auto_resume_failed), e)
        }
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
                val lease = storageReservations.acquire(peak, footprint, label = current.title.ifBlank { current.fileName })
                storage = lease
                runTransfer(current, lease)

            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val backgroundFailure = generateSequence<Throwable>(e) { it.cause }
                        .filterIsInstance<BackgroundDownloadCancellation>().firstOrNull()
                    if (backgroundFailure != null) markBackgroundFailure(record.id, backgroundFailure.message.orEmpty())
                    else markPaused(record.id, if (e is WifiRequiredException) WIFI_PAUSE_REASON else null,
                        if (e is WifiRequiredException) DownloadPauseCause.NETWORK else DownloadPauseCause.USER)
                }
                throw e
            } catch (e: Exception) {
                if (e is DownloadServiceException) {
                    Log.w(TAG, AppText.get(R.string.log_service_start_failed), e)
                }
                val latest = history.getById(record.id) ?: record
                history.upsert(latest.copy(status = DownloadStatus.FAILED,
                    errorMessage = when (e) {
                        // Stored as a key: the record outlives the language it was written in.
                        is VideoEngineException -> com.videodown.douyin.core.engineErrorKey(e.kind)
                        is InsufficientStorageException -> e.error.reasonKey ?: e.error.message
                        is DownloadServiceException -> e.message
                        else -> null
                    },
                    failureKind = when (e) { is VideoEngineException -> e.failureKind; is InsufficientStorageException -> DownloadFailureKind.STORAGE_FULL; is DownloadServiceException -> DownloadFailureKind.BACKGROUND_SERVICE; else -> DownloadFailureKind.UNKNOWN },
                    pauseCause = null,
                    updatedAt = System.currentTimeMillis()))
                _progress.update { it - record.id }
            } finally {
                storage?.close()
                withContext(NonCancellable) { scheduler.finish(record.id) }
                taskLimiters.remove(record.id)
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
                failureKind = DownloadFailureKind.BACKGROUND_SERVICE, pauseCause = null,
                updatedAt = System.currentTimeMillis()))
        }
        _progress.update { it - id }
    }

    private suspend fun runTransfer(initial: DownloadRecord, storage: StorageReservations.Lease) {
        seedTaskLimiter(initial)
        // Subtitles are an extractor feature: a direct HTTP transfer would finish successfully and
        // silently produce a file without them. An engine-parsed direct variant always carries the
        // page it came from, so it can be handed to the engine instead.
        val subtitlesNeedEngine = initial.subtitleLanguages.isNotEmpty() && !initial.media?.pageUrl.isNullOrBlank()
        if (initial.media?.usesEngine == true || subtitlesNeedEngine) {
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
                pauseCause = null,
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
                        throttle = { bytes -> throttle(record, bytes) },
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
                        pauseCause = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                    history.upsert(completed)
                    // Only a newly completed transfer emits; restoring history never re-notifies.
                    runCatching { onCompleted(completed) }
                        .onFailure { Log.w(TAG, AppText.get(R.string.log_completion_notify_failed), it) }
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
                            // The connection closed before the last byte; the policy may retry it.
                            pauseCause = DownloadPauseCause.NETWORK,
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
                    // Judged by status, not by the text of the message: an expired or forbidden
                    // address is worth re-resolving, and a rewritten message must not change that.
                    val httpStatus = (outcome.error as? com.videodown.douyin.core.AppError.Http)?.code
                    if (!refreshed && httpStatus in setOf(401, 403, 410)) {
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
                    val retryableStatus = httpStatus == null || httpStatus !in 400..499
                    if (attempt <= MAX_RETRIES && outcome.error !is com.videodown.douyin.core.AppError.Storage && retryableStatus) {
                        val backoff = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))

                        Log.w(TAG, AppText.get(R.string.log_retry_scheduled, backoff, attempt, outcome.error.message))
                        delay(backoff)
                        record = history.getById(record.id) ?: record
                        continue
                    }
                    history.upsert(
                        record.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = outcome.error.message,
                            failureKind = outcome.error.downloadFailureKind(),
                            pauseCause = null,
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
        val executor = requireNotNull(engineDownloader) { AppText.get(R.string.diag_engine_downloader_absent) }
        var record = initial.copy(status = DownloadStatus.RUNNING, errorMessage = null, failureKind = null,
            pauseCause = null, phase = TransferPhase.DOWNLOADING)
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
                pauseCause = null,
                updatedAt = System.currentTimeMillis())
            history.upsert(completed)
            paths.engineDirectory(record.id).deleteRecursively()
            runCatching { onCompleted(completed) }
            _progress.update { it - record.id }
        }
    }

    private suspend fun markPaused(id: String, reason: String? = null,
        cause: DownloadPauseCause = DownloadPauseCause.USER) {
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
                pauseCause = cause,
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
        const val DEFAULT_AUTO_RESUME_INTERVAL_MS = 15_000L
        const val MAX_AUTO_RESUME_ATTEMPTS = 3
    }
}
