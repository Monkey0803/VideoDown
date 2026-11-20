package com.videodown.douyin.data.download

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import android.util.Log
import com.videodown.douyin.data.generic.EngineTransfer
import com.videodown.douyin.data.generic.VideoEngineException
import com.videodown.douyin.domain.model.TransferPhase
import com.videodown.douyin.domain.model.ArchiveEntry
import com.videodown.douyin.domain.model.ArchiveRequest
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.SubtitleSelection
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import com.videodown.douyin.domain.repository.Downloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
import com.videodown.douyin.domain.model.DownloadRetryPolicy
import com.videodown.douyin.domain.model.RetrySchedule
import com.videodown.douyin.domain.model.withRetryCleared
import com.videodown.douyin.domain.model.StorageBudgetEntry
import com.videodown.douyin.core.downloadFailureKind
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
    history: DownloadHistoryRepository,
    private val downloader: ResumableDownloader,
    private val paths: DownloadPaths,
    private val scope: CoroutineScope,
    private val referer: String,
    private val userAgent: String,
    maxConcurrent: Int = DEFAULT_CONCURRENCY,
    private val networkPolicy: DownloadNetworkPolicy = DownloadNetworkPolicy.Unrestricted,
    private val onCompleted: (DownloadRecord) -> Unit = {},
    private val onTaskScheduled: suspend () -> Unit = {},
    private val engineDownloader: EngineTransfer? = null,
    private val browserCookie: ((String) -> String?)? = null,
    private val validateFile: ((File, DownloadRecord) -> Unit)? = null,
    private val refreshSource: (suspend (DownloadRecord) -> VideoVariant?)? = null,
    /** 归档任务遇到过期图片地址时整帖重新解析，按同序替换清单。 */
    private val refreshArchive: (suspend (DownloadRecord) -> List<ArchiveEntry>?)? = null,
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

    private val rawHistory = history
    /**
     * 运行中任务的当前单任务限速。它既区分“跟随全局”的 0，也避免进度回写用启动时的旧记录
     * 覆盖用户刚刚修改的值。
     */
    private val taskSpeedLimits = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /**
     * 下载协程会频繁用自己的局部记录回写进度；统一在仓储边界合入当前限速，防止旧快照回滚设置。
     */
    private val history = object : DownloadHistoryRepository by rawHistory {
        override suspend fun upsert(record: DownloadRecord) {
            rawHistory.upsert(record.withCurrentTaskSpeedLimit())
        }

        override suspend fun upsertDurably(record: DownloadRecord) {
            rawHistory.upsertDurably(record.withCurrentTaskSpeedLimit())
        }
    }

    private fun DownloadRecord.withCurrentTaskSpeedLimit(): DownloadRecord {
        val current = taskSpeedLimits[id] ?: return this
        return if (speedLimitKbps == current) this else copy(speedLimitKbps = current)
    }

    private val scheduler = DownloadQueueScheduler(maxConcurrent)
    override val queue = scheduler.state
    /**
     * Shared by every direct transfer, so the global cap limits their total rather than each task.
     * Engine downloads (yt-dlp / FFmpeg) are separate processes and are not paced here.
     */
    private val globalLimiter = BandwidthLimiter()
    /** One limiter per task, created on demand and dropped when its transfer ends. */
    private val taskLimiters = java.util.concurrent.ConcurrentHashMap<String, BandwidthLimiter>()
    /**
     * 正在等待冷却的任务：用户点「立即重试」时用它立刻放行。
     *
     * 只在冷却期间存在；任务结束、暂停或等待到时都会移除。
     */
    private val retrySignals = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    /**
     * 用户已经要求「立即重试」但还没走到等待点的任务。
     *
     * 与 [retrySignals] 互补：信号只覆盖「正在等待」，这个标记覆盖「冷却刚写下、信号还没用上」的
     * 间隙，两种顺序都不会多等一个 `Retry-After`。
     */
    private val manualRetryRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
        // 调度器读完记录、传输尚未走到这里时，setSpeedLimit 可能已经写入新值；旧快照不能覆盖它。
        val current = taskSpeedLimits.putIfAbsent(record.id, record.speedLimitKbps.coerceAtLeast(0))
            ?: record.speedLimitKbps.coerceAtLeast(0)
        if (current <= 0) {
            taskLimiters.remove(record.id)
            return
        }
        taskLimiters.computeIfAbsent(record.id) { BandwidthLimiter() }
            .setRate(speedLimitBytesPerSecond(current))
    }

    override suspend fun setSpeedLimit(id: String, kbps: Int) = maintenanceMutex.withLock {
        val safe = kbps.coerceAtLeast(0)
        if (activeJobs[id]?.isActive == true) taskSpeedLimits[id] = safe
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
        persistQueueOrder(order)
    }

    override suspend fun reorderQueue(orderedPendingIds: List<String>) = maintenanceMutex.withLock {
        // 拖拽给出的是「可见待下载任务」的顺序；未列进来的待下载任务由调度器保持它们的相对位置，
        // 再把调度器算出的完整顺序整体落盘，避免只写一半导致重启后顺序与界面不一致。
        persistQueueOrder(scheduler.setOrder(orderedPendingIds))
    }

    private suspend fun persistQueueOrder(order: List<String>) {
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
            .filter { record ->
                record.status != DownloadStatus.COMPLETED ||
                    (record.filePath?.let { path -> File(path).isFile } == true &&
                        record.livePhotoMotion?.let { motion -> motion.filePath?.let { File(it).isFile } == true } != false)
            }
            .sortedWith(compareByDescending<DownloadRecord> { it.status == DownloadStatus.COMPLETED }
                .thenByDescending { it.updatedAt }).firstOrNull()

    override suspend fun refreshMetadata(id: String, video: DouyinVideo): DownloadRecord? = maintenanceMutex.withLock {
        val existing = history.getById(id) ?: return@withLock null
        if (existing.status == DownloadStatus.RUNNING || existing.status == DownloadStatus.PENDING) return@withLock existing
        val updated = existing.copy(
            title = video.title,
            authorNickname = video.author.nickname,
            authorAvatarUrl = video.author.avatarUrl,
            authorSecUid = video.author.secUid,
            coverUrl = video.coverUrl ?: existing.coverUrl,
            sourcePageUrl = video.videoWebUrl.ifBlank { existing.sourcePageUrl },
        )
        if (updated != existing) history.upsert(updated)
        updated
    }

    override suspend fun startArchive(request: ArchiveRequest): String = maintenanceMutex.withLock {
        val entries = request.entries
        require(entries.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val fileId = "${request.platform.name.lowercase()}_${request.postId}_${id.take(8)}"
        val fileName = paths.buildArchiveFileName(request.title, entries.size, fileId)
        val now = System.currentTimeMillis()
        val known = entries.sumOf {
            saturatingStorageAdd(
                it.sizeBytes?.coerceAtLeast(0L) ?: 0L,
                it.livePhotoMotion?.expectedBytes?.coerceAtLeast(0L) ?: 0L,
            )
        }
        val record = DownloadRecord(
            id = id,
            awemeId = request.postId,
            title = request.title,
            authorNickname = request.authorNickname,
            authorAvatarUrl = request.authorAvatarUrl,
            authorSecUid = request.authorSecUid,
            coverUrl = request.coverUrl,
            sourceUrl = request.postPageUrl,
            expectedBytes = known.takeIf { it > 0L },
            queueOrder = (history.snapshot().maxOfOrNull { it.queueOrder } ?: 0L) + 1,
            fileName = fileName,
            partialPath = "",
            status = DownloadStatus.PENDING,
            createdAt = now,
            updatedAt = now,
            platform = request.platform,
            sourcePageUrl = request.postPageUrl,
            downloadReferer = request.platform.referer,
            mediaKind = MediaKind.ARCHIVE,
            archiveEntries = entries,
        )
        history.upsert(record)
        updateProgress(id) {
            DownloadProgress(id = id, downloadedBytes = 0L, totalBytes = known.takeIf { it > 0L } ?: -1L, status = DownloadStatus.PENDING)
        }
        launch(record)
        return@withLock id
    }

    /**
     * 同一帖、同样张数的归档任务；不看图片 URL，因为签名地址每次解析都会变。
     */
    override suspend fun findArchiveDuplicate(request: ArchiveRequest): DownloadRecord? =
        history.snapshot()
            .filter {
                it.isArchive && it.platform == request.platform && it.awemeId == request.postId &&
                    it.archiveEntries.size == request.entries.size
            }
            .filter { it.status != DownloadStatus.COMPLETED || it.filePath?.let { path -> File(path).isFile } == true }
            .sortedWith(compareByDescending<DownloadRecord> { it.status == DownloadStatus.COMPLETED }
                .thenByDescending { it.updatedAt })
            .firstOrNull()

    override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection): String = maintenanceMutex.withLock {
        val id = UUID.randomUUID().toString()
        val fileId = if (video.platform == VideoPlatform.DOUYIN) video.awemeId
            else "${video.platform.name.lowercase()}_${video.awemeId}"
        // 扩展名以变体为准：图片是 jpg/png，视频是 mp4/webm，不能再写死 .mp4 再替换。
        val fileName = paths.buildFileName(video.title, variant.label, fileId + "_" + id.take(8), variant.extensionOrDefault)
        val partial = File(paths.downloadDir, "$fileName.part")
        val now = System.currentTimeMillis()
        val livePhotoMotion = variant.livePhotoMotion?.copy(
            fileName = paths.buildLivePhotoMotionFileName(fileName, variant.livePhotoMotion.extension),
            filePath = null,
        )

        val record = DownloadRecord(
            id = id,
            awemeId = video.awemeId,
            title = video.title,
            authorNickname = video.author.nickname,
            authorAvatarUrl = video.author.avatarUrl,
            authorSecUid = video.author.secUid,
            coverUrl = video.coverUrl,
            sourceUrl = variant.url,
            expectedBytes = variant.sizeBytes?.let { stillBytes ->
                livePhotoMotion?.expectedBytes?.let { motionBytes ->
                    saturatingStorageAdd(stillBytes.coerceAtLeast(0L), motionBytes.coerceAtLeast(0L))
                } ?: stillBytes
            },
            queueOrder = (history.snapshot().maxOfOrNull { it.queueOrder } ?: 0L) + 1,
            media = variant.media,
            mediaKind = variant.mediaKind,
            livePhotoMotion = livePhotoMotion,
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

    /**
     * 用户主动继续（按钮、批量「继续」、深链恢复）：清零冷却与重试预算后立刻排队。
     *
     * 只有这里会清预算：自动恢复必须沿用落库的冷却，否则一次重启或一次轮询就能绕过
     * 服务端要求的等待时间。
     */
    override suspend fun resume(id: String) = enqueueResume(id, resetRetryBudget = true)

    /**
     * 排队一次恢复。[resetRetryBudget] 为 false 时保留 [DownloadRecord.nextRetryAtMillis] 与
     * [DownloadRecord.retryAttempt]，供自动策略在冷却结束后接着用原来的预算。
     */
    private suspend fun enqueueResume(id: String, resetRetryBudget: Boolean) = maintenanceMutex.withLock {
        val latest = history.getById(id) ?: return@withLock
        if (latest.status == DownloadStatus.COMPLETED || !latest.canRetryDownload) return@withLock
        if (activeJobs[id]?.isActive == true) return@withLock
        // 自动恢复在这里再确认一次冷却：调度期间用户可能暂停或手动改过记录。
        if (!resetRetryBudget && !retryAllowedNow(latest)) return@withLock
        val record = if (resetRetryBudget) latest.withRetryCleared() else latest

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

    /**
     * 「立即重试」：用户明确要求跳过冷却。
     *
     * 正在冷却的传输由等待点放行（不重建任务，已下载的字节和引擎目录都保留）；
     * 不在运行中的任务等同于一次手动继续。
     */
    override suspend fun retryNow(id: String) {
        manualRetryRequests.add(id)
        val signal = retrySignals[id]
        val active = activeJobs[id]?.isActive == true
        history.getById(id)?.takeIf { it.status != DownloadStatus.COMPLETED }?.let { latest ->
            history.upsert(latest.withRetryCleared().copy(updatedAt = System.currentTimeMillis()))
        }
        if (signal != null) { signal.complete(Unit); return }
        if (!active) {
            manualRetryRequests.remove(id)
            enqueueResume(id, resetRetryBudget = true)
        }
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
        paths.livePhotoMotionPartialFileFor(record)?.let { runCatching { it.delete() } }
        paths.livePhotoMotionFileFor(record)?.let { runCatching { it.delete() } }
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
            fileSizeBytes = null,
            fileModifiedAt = null,
            livePhotoMotion = record.livePhotoMotion?.copy(filePath = null),
            taskArchived = false,
            updatedAt = System.currentTimeMillis(),
        ).withRetryCleared()
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
            paths.livePhotoMotionPartialFileFor(record)?.let { runCatching { it.delete() } }
            paths.livePhotoMotionFileFor(record)?.let { runCatching { it.delete() } }
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
            // 落库的冷却（429/503 的 Retry-After）优先于自动策略：到点之前不发请求。
            if (!retryAllowedNow(record)) continue
            if (record.media?.localRecordId == null && !networkPolicy.isAllowed()) continue
            if (!storageAllowsAutoResume(record)) continue
            autoResumeBudget.recordAttempt(record.id)
            enqueueResume(record.id, resetRetryBudget = false)
        }
    }

    /** 冷却是否已经结束；用户手动「立即重试」不走这条判断。 */
    private fun retryAllowedNow(record: DownloadRecord, now: Long = System.currentTimeMillis()): Boolean =
        DownloadRetryPolicy.cooldownRemaining(record.nextRetryAtMillis, now) == 0L

    /**
     * Mirrors the space check performed right before a transfer starts, so an automatic resume
     * never spends an attempt on a disk that is still too full.
     */
    private fun storageAllowsAutoResume(record: DownloadRecord): Boolean {
        val engine = record.media?.usesEngine == true || record.isArchive
        val footprint = if (engine) taskDirectoryBytes(paths.engineDirectory(record.id))
            else File(record.partialPath).length()
        val expected = record.expectedBytes ?: record.totalBytes.takeIf { it > 0 }
        val reserve = reserveFor(record)
        val required = maxOf(requiredStorageBytes(expected, footprint, merging = engine, reserveBytes = reserve),
            saturatingStorageAdd(footprint, reserve))
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
                val engine = current.media?.usesEngine == true || current.isArchive
                val footprint = {
                    if (engine) taskDirectoryBytes(paths.engineDirectory(current.id))
                    else File(current.partialPath).length() + paths.finalFileFor(current).length() +
                        (paths.livePhotoMotionPartialFileFor(current)?.length() ?: 0L) +
                        (paths.livePhotoMotionFileFor(current)?.length() ?: 0L)
                }
                val expected = current.expectedBytes ?: current.totalBytes.takeIf { it > 0 }
                val reserve = reserveFor(current)
                val peak = maxOf(requiredStorageBytes(expected, merging = engine, reserveBytes = reserve),
                    saturatingStorageAdd(footprint(), reserve))
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
                        is DownloadContentException -> e.reasonKey
                        is VideoEngineException -> com.videodown.douyin.core.engineErrorKey(e.kind)
                        is InsufficientStorageException -> e.error.reasonKey ?: e.error.message
                        is DownloadServiceException -> e.message
                        else -> null
                    },
                    failureKind = when (e) { is DownloadContentException -> DownloadFailureKind.FORMAT_CHANGED; is VideoEngineException -> e.failureKind; is InsufficientStorageException -> DownloadFailureKind.STORAGE_FULL; is DownloadServiceException -> DownloadFailureKind.BACKGROUND_SERVICE; else -> DownloadFailureKind.UNKNOWN },
                    pauseCause = null,
                    updatedAt = System.currentTimeMillis()))
                _progress.update { it - record.id }
            } finally {
                storage?.close()
                withContext(NonCancellable) { scheduler.finish(record.id) }
                taskLimiters.remove(record.id)
                taskSpeedLimits.remove(record.id)
                retrySignals.remove(record.id)
                manualRetryRequests.remove(record.id)
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
        if (initial.isArchive) {
            runArchiveTransfer(initial, storage)
            return
        }
        // Subtitles are an extractor feature: a direct HTTP transfer would finish successfully and
        // silently produce a file without them. An engine-parsed direct variant always carries the
        // page it came from, so it can be handed to the engine instead.
        val subtitlesNeedEngine = initial.subtitleLanguages.isNotEmpty() && !initial.media?.pageUrl.isNullOrBlank()
        if (initial.media?.usesEngine == true || subtitlesNeedEngine) {
            runEngineTransfer(initial, storage)
            return
        }
        var attemptsUsed = initial.retryAttempt
        var record = initial
        var refreshed = false

        while (true) {
            if (!networkPolicy.isAllowed()) throw WifiRequiredException()
            val partial = File(record.partialPath)
            val finalFile = paths.finalFileFor(record)
            if (record.isLivePhoto && record.filePath == finalFile.absolutePath && finalFile.isFile) {
                validateFile?.invoke(finalFile, record)
                finishDirectTransfer(record, finalFile, finalFile.length(), storage)
                return
            }
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
                        lastModified = record.lastModified,
                        referer = record.downloadReferer ?: if (record.platform == VideoPlatform.DOUYIN) referer else record.platform.referer,
                        userAgent = record.media?.userAgent ?: userAgent,
                        cookieForUrl = if (record.media?.useBrowserSession == true) browserCookie else null,
                        networkPolicy = networkPolicy,
                        availableBytes = availableBytes,
                        checkStorage = { total, writingThrough ->
                            storage.updatePeak(requiredStorageBytes(maxOf(total, writingThrough), reserveBytes = reserveFor(record)))
                        },
                        throttle = { bytes -> throttle(record, bytes) },
                        onProgress = { downloaded, total, speed, rangeSupported, etag, lastModified ->
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
                                        lastModified = lastModified,
                                        updatedAt = tick,
                                    ),
                                )
                            }
                        },
                    ),
                )

            when (outcome) {
                is ResumableDownloader.Outcome.Completed -> {
                    try { validateFile?.invoke(outcome.file, record) } catch (error: Exception) { outcome.file.delete(); throw error }
                    finishDirectTransfer(record, outcome.file, outcome.totalBytes, storage)
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
                            lastModified = outcome.lastModified,
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
                            lastModified = outcome.lastModified,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    // Judged by status, not by the text of the message: an expired or forbidden
                    // address is worth re-resolving, and a rewritten message must not change that.
                    val httpStatus = (outcome.error as? com.videodown.douyin.core.AppError.Http)?.code
                    if (!refreshed && httpStatus in setOf(401, 403, 404, 410)) {
                        refreshed = true
                        val fresh = refreshSource?.invoke(record)
                        if (fresh != null && fresh.media?.usesEngine != true) {
                            val identical = record.media?.let { old -> fresh.media?.let(old::sameMedia) } == true
                            // If-Range is essential even for an unchanged logical media ID.
                            val reuse = identical && (record.etag != null || record.lastModified != null)
                            if (!reuse) partial.delete()
                            record = record.copy(sourceUrl = fresh.url, media = fresh.media,
                                downloadReferer = fresh.referer, downloadedBytes = if (reuse) outcome.downloadedBytes else 0,
                                etag = if (reuse) record.etag else null,
                                lastModified = if (reuse) record.lastModified else null)
                            history.upsert(record)
                            continue
                        }
                    }
                    val schedule = DownloadRetryPolicy.schedule(
                        attemptsUsed, outcome.error.downloadFailureKind(), httpStatus,
                        outcome.retryAfterMillis, System.currentTimeMillis(),
                    )
                    val cooling = record.copy(
                        downloadedBytes = outcome.downloadedBytes,
                        totalBytes = outcome.totalBytes,
                        rangeSupported = outcome.rangeSupported,
                        etag = outcome.etag,
                        lastModified = outcome.lastModified,
                    )
                    if (schedule != null) {
                        Log.w(TAG, AppText.get(R.string.log_retry_scheduled,
                            schedule.atMillis - System.currentTimeMillis(), schedule.attempt, outcome.error.message))
                        val fresh = scheduleRetryAndWait(cooling, schedule, httpStatus,
                            outcome.downloadedBytes, outcome.totalBytes)
                        if (fresh == null) { _progress.update { it - record.id }; return }
                        // 用户「立即重试」会把落库预算清零，之后从零开始计数。
                        attemptsUsed = fresh.retryAttempt
                        record = fresh
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
                            lastModified = outcome.lastModified,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    _progress.update { it - record.id }
                    return
                }
            }
        }
    }

    /** 静帧和 MOV 共同成功后才提交 Live Photo；暂停/失败时保留两边可续传的文件。 */
    private suspend fun finishDirectTransfer(
        sourceRecord: DownloadRecord,
        primaryFile: File,
        primaryBytes: Long,
        storage: StorageReservations.Lease,
    ) {
        var record = sourceRecord.copy(
            status = DownloadStatus.RUNNING,
            filePath = primaryFile.absolutePath,
            downloadedBytes = primaryBytes,
            totalBytes = primaryBytes,
            errorMessage = null,
            failureKind = null,
            pauseCause = null,
            updatedAt = System.currentTimeMillis(),
        )
        history.upsert(record)
        record = downloadLivePhotoMotion(record, primaryBytes, storage) ?: return
        val completed = record.copy(
            status = DownloadStatus.COMPLETED,
            errorMessage = null,
            failureKind = null,
            pauseCause = null,
            updatedAt = System.currentTimeMillis(),
        ).withRetryCleared().withFileSnapshot(primaryFile)
        history.upsert(completed)
        runCatching { onCompleted(completed) }
            .onFailure { Log.w(TAG, AppText.get(R.string.log_completion_notify_failed), it) }
        _progress.update { it - record.id }
    }

    private suspend fun downloadLivePhotoMotion(
        initial: DownloadRecord,
        primaryBytes: Long,
        storage: StorageReservations.Lease,
    ): DownloadRecord? {
        var record = initial
        var motion = record.livePhotoMotion ?: return record
        val finalFile = paths.livePhotoMotionFileFor(record) ?: return record
        val partial = paths.livePhotoMotionPartialFileFor(record) ?: return record
        if (finalFile.isFile && finalFile.length() > 0L) {
            validateFile?.invoke(finalFile, record.copy(mediaKind = MediaKind.VIDEO, livePhotoMotion = null))
            return record.copy(
                livePhotoMotion = motion.copy(filePath = finalFile.absolutePath),
                downloadedBytes = saturatingStorageAdd(primaryBytes, finalFile.length()),
                totalBytes = saturatingStorageAdd(primaryBytes, finalFile.length()),
            )
        }
        var attemptsUsed = initial.retryAttempt
        var refreshed = false
        while (true) {
            if (!networkPolicy.isAllowed()) throw WifiRequiredException()
            val startBytes = partial.length()
            record = record.copy(
                status = DownloadStatus.RUNNING,
                downloadedBytes = saturatingStorageAdd(primaryBytes, startBytes),
                pauseCause = null,
                updatedAt = System.currentTimeMillis(),
            )
            history.upsert(record)
            var lastPersistAt = 0L
            val outcome = downloader.download(
                ResumableDownloader.DownloadRequest(
                    url = motion.sourceUrl,
                    partialFile = partial,
                    finalFile = finalFile,
                    alreadyDownloaded = startBytes,
                    etag = motion.etag,
                    lastModified = motion.lastModified,
                    referer = motion.referer ?: record.downloadReferer ?: record.platform.referer,
                    userAgent = record.media?.userAgent ?: userAgent,
                    networkPolicy = networkPolicy,
                    availableBytes = availableBytes,
                    checkStorage = { total, writingThrough ->
                        storage.updatePeak(requiredStorageBytes(
                            saturatingStorageAdd(primaryBytes, maxOf(total, writingThrough)),
                            reserveBytes = reserveFor(record),
                        ))
                    },
                    throttle = { bytes -> throttle(record, bytes) },
                    onProgress = { downloaded, total, speed, _, etag, lastModified ->
                        motion = motion.copy(etag = etag, lastModified = lastModified)
                        val aggregateDownloaded = saturatingStorageAdd(primaryBytes, downloaded)
                        val aggregateTotal = saturatingStorageAdd(primaryBytes, total.coerceAtLeast(0L))
                        updateProgress(record.id) {
                            DownloadProgress(record.id, aggregateDownloaded, aggregateTotal, DownloadStatus.RUNNING, speed)
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
                            lastPersistAt = now
                            record = record.copy(
                                downloadedBytes = aggregateDownloaded,
                                totalBytes = aggregateTotal,
                                livePhotoMotion = motion,
                                updatedAt = now,
                            )
                            history.upsert(record)
                        }
                    },
                ),
            )
            when (outcome) {
                is ResumableDownloader.Outcome.Completed -> {
                    try {
                        validateFile?.invoke(outcome.file, record.copy(mediaKind = MediaKind.VIDEO, livePhotoMotion = null))
                    } catch (error: Exception) {
                        outcome.file.delete()
                        throw error
                    }
                    motion = motion.copy(filePath = outcome.file.absolutePath)
                    return record.copy(
                        livePhotoMotion = motion,
                        downloadedBytes = saturatingStorageAdd(primaryBytes, outcome.totalBytes),
                        totalBytes = saturatingStorageAdd(primaryBytes, outcome.totalBytes),
                    )
                }
                is ResumableDownloader.Outcome.Paused -> {
                    motion = motion.copy(etag = outcome.etag, lastModified = outcome.lastModified)
                    record = record.copy(livePhotoMotion = motion)
                    history.upsert(record.copy(
                        status = DownloadStatus.PAUSED,
                        downloadedBytes = saturatingStorageAdd(primaryBytes, outcome.downloadedBytes),
                        totalBytes = saturatingStorageAdd(primaryBytes, outcome.totalBytes.coerceAtLeast(0L)),
                        pauseCause = DownloadPauseCause.NETWORK,
                        updatedAt = System.currentTimeMillis(),
                    ))
                    _progress.update { it - record.id }
                    return null
                }
                is ResumableDownloader.Outcome.Failed -> {
                    motion = motion.copy(etag = outcome.etag, lastModified = outcome.lastModified)
                    record = record.copy(livePhotoMotion = motion)
                    val httpStatus = (outcome.error as? com.videodown.douyin.core.AppError.Http)?.code
                    if (!refreshed && httpStatus in setOf(401, 403, 404, 410)) {
                        refreshed = true
                        val fresh = refreshSource?.invoke(record)?.livePhotoMotion
                        if (fresh != null) {
                            motion = fresh.copy(fileName = motion.fileName, filePath = null)
                            record = record.copy(livePhotoMotion = motion)
                            history.upsert(record)
                            continue
                        }
                    }
                    val aggregateDownloaded = saturatingStorageAdd(primaryBytes, outcome.downloadedBytes)
                    val aggregateTotal = saturatingStorageAdd(primaryBytes, outcome.totalBytes.coerceAtLeast(0L))
                    val schedule = DownloadRetryPolicy.schedule(
                        attemptsUsed, outcome.error.downloadFailureKind(), httpStatus,
                        outcome.retryAfterMillis, System.currentTimeMillis(),
                    )
                    if (schedule != null) {
                        val fresh = scheduleRetryAndWait(record, schedule, httpStatus,
                            aggregateDownloaded, aggregateTotal)
                        if (fresh == null) { _progress.update { it - record.id }; return null }
                        attemptsUsed = fresh.retryAttempt
                        record = fresh
                        continue
                    }
                    history.upsert(record.copy(
                        status = DownloadStatus.FAILED,
                        downloadedBytes = saturatingStorageAdd(primaryBytes, outcome.downloadedBytes),
                        totalBytes = saturatingStorageAdd(primaryBytes, outcome.totalBytes.coerceAtLeast(0L)),
                        errorMessage = outcome.error.message,
                        failureKind = outcome.error.downloadFailureKind(),
                        pauseCause = null,
                        updatedAt = System.currentTimeMillis(),
                    ))
                    _progress.update { it - record.id }
                    return null
                }
            }
        }
    }

    /**
     * 引擎任务（yt-dlp / HLS / DASH / 分轨 / 提取音频）。
     *
     * 失败分类与直链下载共用 [DownloadRetryPolicy]：NETWORK 与 429/503 有界重试并按
     * `Retry-After` 冷却，登录、权限、链接过期先尝试一次重新解析，存储类失败直接终止。
     * 冷却是落库的，所以进程被杀后自动继续也不会提前请求。
     */
    private suspend fun runEngineTransfer(initial: DownloadRecord, storage: StorageReservations.Lease) {
        val executor = requireNotNull(engineDownloader) { AppText.get(R.string.diag_engine_downloader_absent) }
        var attemptsUsed = initial.retryAttempt
        var refreshed = false
        var record = initial.copy(status = DownloadStatus.RUNNING, errorMessage = null, failureKind = null,
            pauseCause = null, phase = TransferPhase.DOWNLOADING, nextRetryAtMillis = null)
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
                executor.download(record, paths,
                    if (record.media?.localRecordId != null) DownloadNetworkPolicy.Unrestricted else networkPolicy,
                    engineRateLimit(record),
                    // 两个 provider 每次进度都重新读，跑着改限速下一个事件就生效。
                    EngineRateControl(
                        sessionId = record.id,
                        globalLimit = { speedLimitBytesPerSecond(globalSpeedLimitKbps()) },
                        taskLimit = { speedLimitBytesPerSecond(taskSpeedLimits[record.id] ?: record.speedLimitKbps) },
                    )) { downloaded, total, speed, phase ->
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

        var staged: File? = null
        while (staged == null) {
            try {
                staged = transfer()
            } catch (error: VideoEngineException) {
                if (error.kind in setOf("AUTH", "ACCESS", "EXPIRED") && !refreshed) {
                    refreshed = true
                    val fresh = refreshSource?.invoke(record)
                    if (fresh != null && fresh.media?.usesEngine == true) {
                        record = record.copy(sourceUrl = fresh.url, media = fresh.media, downloadReferer = fresh.referer)
                        history.upsert(record)
                        continue
                    }
                }
                val schedule = DownloadRetryPolicy.schedule(
                    attemptsUsed, error.failureKind, error.httpStatus, error.retryAfterMillis,
                    System.currentTimeMillis(),
                )
                if (schedule == null) {
                    markEngineFailed(record, error, attemptsUsed)
                    return
                }
                Log.w(TAG, AppText.get(R.string.log_retry_scheduled,
                    schedule.atMillis - System.currentTimeMillis(), schedule.attempt, error.kind))
                val fresh = scheduleRetryAndWait(record, schedule, error.httpStatus,
                    record.downloadedBytes, record.totalBytes)
                if (fresh == null) { _progress.update { it - record.id }; return }
                // 用户「立即重试」会把落库预算清零，之后从零开始计数。
                attemptsUsed = fresh.retryAttempt
                record = fresh.copy(status = DownloadStatus.RUNNING, errorMessage = null,
                    failureKind = null, pauseCause = null)
                history.upsert(record)
            }
        }
        val stagedOutput = staged
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
                updatedAt = System.currentTimeMillis()).withRetryCleared().withFileSnapshot(output)
            history.upsert(completed)
            paths.engineDirectory(record.id).deleteRecursively()
            runCatching { onCompleted(completed) }
            _progress.update { it - record.id }
        }
    }

    /**
     * 把冷却写进记录，然后等到允许重试的时刻。
     *
     * 等待用可提前完成的信号实现：用户点「立即重试」时 [retryNow] 放行，不重建任务。
     * 返回放行后的最新记录（`nextRetryAtMillis` 已清空）；记录被删掉时返回 null。
     */
    private suspend fun scheduleRetryAndWait(
        record: DownloadRecord,
        schedule: RetrySchedule,
        httpStatus: Int?,
        downloaded: Long,
        total: Long,
    ): DownloadRecord? {
        val now = System.currentTimeMillis()
        val cooling = record.copy(
            nextRetryAtMillis = schedule.atMillis,
            retryAttempt = schedule.attempt,
            retryHttpStatus = httpStatus,
            updatedAt = now,
        )
        // 先登记信号再落库：观察到冷却的调用方（界面或「立即重试」）一定能找到放行点。
        val signal = CompletableDeferred<Unit>()
        retrySignals[record.id] = signal
        var manualOverride = false
        try {
            history.upsert(cooling)
            updateProgress(record.id) {
                DownloadProgress(record.id, downloaded, total, DownloadStatus.RUNNING, retryAtMillis = schedule.atMillis)
            }
            manualOverride = manualRetryRequests.remove(record.id)
            val waitMillis = (schedule.atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            if (!manualOverride && waitMillis > 0L) {
                withTimeoutOrNull(waitMillis) { signal.await() }
            }
        } finally {
            retrySignals.remove(record.id, signal)
        }
        updateProgress(record.id) {
            DownloadProgress(record.id, downloaded, total, DownloadStatus.RUNNING)
        }
        val latest = history.getById(record.id) ?: return null
        val cleared = if (manualOverride) latest.withRetryCleared().copy(updatedAt = System.currentTimeMillis())
            else latest.copy(nextRetryAtMillis = null, updatedAt = System.currentTimeMillis())
        history.upsert(cleared)
        return cleared
    }

    /** 引擎任务的终态失败：只存 key 与分类，冷却清掉，重试预算留给「立即重试」清零。 */
    private suspend fun markEngineFailed(record: DownloadRecord, error: VideoEngineException, attemptsUsed: Int) {
        history.upsert(record.copy(
            status = DownloadStatus.FAILED,
            errorMessage = com.videodown.douyin.core.engineErrorKey(error.kind),
            failureKind = error.failureKind,
            pauseCause = null,
            nextRetryAtMillis = null,
            retryAttempt = attemptsUsed,
            retryHttpStatus = error.httpStatus,
            updatedAt = System.currentTimeMillis(),
        ))
        _progress.update { it - record.id }
    }

    /** yt-dlp 只能接受每进程上限；取当前全局/单任务中更严的非零值。 */
    private fun engineRateLimit(record: DownloadRecord): Long? = listOf(
        speedLimitBytesPerSecond(globalSpeedLimitKbps()),
        speedLimitBytesPerSecond(record.speedLimitKbps),
    ).filter { it > 0L }.minOrNull()

    private suspend fun markPaused(id: String, reason: String? = null,
        cause: DownloadPauseCause = DownloadPauseCause.USER) {
        val record = history.getById(id) ?: return
        if (record.status == DownloadStatus.COMPLETED) return
        val partial = File(record.partialPath)
        val onDisk = if (record.isArchive) {
            archiveDownloadedBytes(paths.engineDirectory(record.id))
        } else if (record.isLivePhoto && record.filePath?.let(::File)?.isFile == true) {
            saturatingStorageAdd(File(record.filePath).length(), paths.livePhotoMotionPartialFileFor(record)?.length() ?: 0L)
        } else if (partial.exists()) {
            partial.length()
        } else {
            record.downloadedBytes
        }
        history.upsert(
            record.copy(
                status = DownloadStatus.PAUSED,
                downloadedBytes = onDisk,
                errorMessage = reason,
                failureKind = if (reason == WIFI_PAUSE_REASON) DownloadFailureKind.WIFI_REQUIRED else null,
                pauseCause = cause,
                // 用户/网络原因的中断不需要再等服务器冷却；下次恢复重新计时。
                nextRetryAtMillis = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        _progress.update { it - id }
    }

    private fun updateProgress(id: String, transform: () -> DownloadProgress) {
        _progress.update { current -> current + (id to transform()) }
    }

    /** 归档任务已落盘的字节：已完成的图片加上当前半张图，暂停后的进度条才不会显示 0。 */
    private fun archiveDownloadedBytes(directory: File): Long = directory.walkTopDown()
        .filter { it.isFile && it.name != "archive.zip" && !it.name.endsWith(".publishing") }
        .fold(0L) { total, file -> saturatingStorageAdd(total, file.length().coerceAtLeast(0L)) }

    /**
     * 归档任务：逐张复用直链下载器写进任务目录，全部就绪后再打 ZIP。
     *
     * 已存在的图片文件直接跳过，所以暂停、进程被杀或 403 触发重新解析之后继续时，都不会重下
     * 已经完成的图；ZIP 本身只写一次（先 `.tmp` 再改名），不会留下半个包。
     */
    private suspend fun runArchiveTransfer(initial: DownloadRecord, storage: StorageReservations.Lease) {
        var record = initial.copy(status = DownloadStatus.RUNNING, phase = TransferPhase.DOWNLOADING,
            errorMessage = null, failureKind = null, pauseCause = null)
        history.upsert(record)
        val directory = paths.engineDirectory(record.id)
        directory.mkdirs()
        val knownTotal = record.expectedBytes
            ?: archiveAssets(record.archiveEntries).sumOf { it.sizeBytes?.coerceAtLeast(0L) ?: 0L }.takeIf { it > 0L }
        var refreshed = false
        var attemptsUsed = initial.retryAttempt

        while (true) {
            var completedBytes = 0L
            for (entry in archiveAssets(record.archiveEntries)) {
                val target = File(directory, entry.name)
                if (target.isFile && target.length() > 0L) {
                    if (archiveAssetIsValid(target, entry, record)) {
                        completedBytes += target.length()
                        continue
                    }
                    target.delete()
                }
                if (!networkPolicy.isAllowed()) throw WifiRequiredException()
                val partial = File(target.parentFile, target.name + ".part")
                // 与直链任务一样从断点续传：上一轮中断留下的半张图接着写，而不是从 0 重来。
                val alreadyDownloaded = if (partial.isFile) partial.length() else 0L
                var lastPersistAt = 0L
                val outcome = downloader.download(
                    ResumableDownloader.DownloadRequest(
                        url = entry.url,
                        partialFile = partial,
                        finalFile = target,
                        alreadyDownloaded = alreadyDownloaded,
                        etag = entry.etag,
                        lastModified = entry.lastModified,
                        referer = entry.referer ?: record.downloadReferer,
                        userAgent = userAgent,
                        networkPolicy = networkPolicy,
                        availableBytes = availableBytes,
                        checkStorage = { total, _ ->
                            storage.updatePeak(requiredStorageBytes(
                                maxOf(knownTotal ?: 0L, total).takeIf { it > 0L },
                                merging = true,
                            ))
                        },
                        throttle = { bytes -> throttle(record, bytes) },
                        onProgress = { downloaded, _, speed, _, etag, lastModified ->
                            record = record.withArchiveValidator(entry, etag, lastModified)
                            val written = completedBytes + downloaded
                            updateProgress(record.id) {
                                DownloadProgress(record.id, written, knownTotal ?: -1L, DownloadStatus.RUNNING, speed)
                            }
                            val tick = System.currentTimeMillis()
                            if (tick - lastPersistAt >= PERSIST_INTERVAL_MS) {
                                lastPersistAt = tick
                                record = record.copy(downloadedBytes = written, totalBytes = knownTotal ?: -1L, updatedAt = tick)
                                history.upsert(record)
                            }
                        },
                    ),
                )
                when (outcome) {
                    is ResumableDownloader.Outcome.Completed -> {
                        if (!archiveAssetIsValid(outcome.file, entry, record)) {
                            outcome.file.delete()
                            if (!refreshed) {
                                val fresh = refreshArchive?.invoke(record)
                                if (fresh != null && fresh.size == record.archiveEntries.size) {
                                    refreshed = true
                                    record = record.copy(archiveEntries = fresh)
                                    history.upsert(record)
                                    break
                                }
                            }
                            throw DownloadContentException(if (entry.kind == MediaKind.IMAGE) DOWNLOAD_NOT_AN_IMAGE else "download:invalid_video")
                        }
                        completedBytes += outcome.file.length()
                    }
                    is ResumableDownloader.Outcome.Paused -> {
                        markArchivePaused(record, completedBytes + outcome.downloadedBytes, outcome.totalBytes)
                        return
                    }
                    is ResumableDownloader.Outcome.Failed -> {
                        val httpStatus = (outcome.error as? com.videodown.douyin.core.AppError.Http)?.code
                        if (!refreshed && httpStatus in setOf(401, 403, 404, 410)) {
                            val fresh = refreshArchive?.invoke(record)
                            if (fresh != null && fresh.size == record.archiveEntries.size) {
                                refreshed = true
                                record = record.copy(archiveEntries = fresh)
                                history.upsert(record)
                                break
                            }
                        }
                        val schedule = DownloadRetryPolicy.schedule(
                            attemptsUsed, outcome.error.downloadFailureKind(), httpStatus,
                            outcome.retryAfterMillis, System.currentTimeMillis(),
                        )
                        if (schedule != null) {
                            val fresh = scheduleRetryAndWait(record, schedule, httpStatus,
                                completedBytes + outcome.downloadedBytes, knownTotal ?: outcome.totalBytes)
                            if (fresh == null) { _progress.update { it - record.id }; return }
                            attemptsUsed = fresh.retryAttempt
                            record = fresh
                            break
                        }
                        markArchiveFailed(record, outcome.error, completedBytes + outcome.downloadedBytes, knownTotal)
                        return
                    }
                }
            }
            val allDone = archiveAssets(record.archiveEntries).all { entry ->
                File(directory, entry.name).let { it.isFile && it.length() > 0L && archiveAssetIsValid(it, entry, record) }
            }
            if (!allDone) continue
            publishArchive(record, directory, knownTotal, storage)
            return
        }
    }

    private suspend fun publishArchive(
        record: DownloadRecord,
        directory: File,
        knownTotal: Long?,
        storage: StorageReservations.Lease,
    ) {
        updateProgress(record.id) {
            DownloadProgress(record.id, record.downloadedBytes, knownTotal ?: -1L, DownloadStatus.RUNNING,
                phase = TransferPhase.MERGING)
        }
        withContext(NonCancellable + Dispatchers.IO) {
            val entries = archiveAssets(record.archiveEntries).map { it.name to File(directory, it.name) }
            val zip = File(directory, "archive.zip")
            val publishing = File(directory, "archive.zip.publishing")
            try {
                ImageArchiveWriter.write(entries, zip)
                validateFile?.invoke(zip, record)
                val output = File(paths.downloadDir, record.fileName)
                storage.updatePeak(saturatingStorageAdd(zip.length() * 2, STORAGE_RESERVE_BYTES))
                if (!zip.renameTo(output)) {
                    zip.copyTo(publishing, overwrite = true)
                    if (!publishing.renameTo(output)) throw IOException("archive publish failed")
                    zip.delete()
                }
                val completed = record.copy(
                    status = DownloadStatus.COMPLETED,
                    filePath = output.absolutePath,
                    fileName = output.name,
                    downloadedBytes = output.length(),
                    totalBytes = output.length(),
                    phase = TransferPhase.DOWNLOADING,
                    errorMessage = null, failureKind = null, pauseCause = null,
                    updatedAt = System.currentTimeMillis(),
                ).withRetryCleared().withFileSnapshot(output)
                history.upsert(completed)
                runCatching { onCompleted(completed) }
                _progress.update { it - record.id }
            } finally {
                publishing.delete()
                directory.deleteRecursively()
            }
        }
    }

    private data class ArchiveAsset(
        val name: String,
        val url: String,
        val referer: String?,
        val sizeBytes: Long?,
        val kind: MediaKind,
        val entryIndex: Int,
        val isMotion: Boolean,
        val etag: String?,
        val lastModified: String?,
    )

    private fun archiveAssets(entries: List<ArchiveEntry>): List<ArchiveAsset> = buildList {
        entries.forEachIndexed { index, entry ->
            add(ArchiveAsset(
                ImageArchiveWriter.entryName(index, entry.extension),
                entry.url,
                entry.referer,
                entry.sizeBytes,
                MediaKind.IMAGE,
                index,
                false,
                entry.etag,
                entry.lastModified,
            ))
            entry.livePhotoMotion?.let { motion ->
                add(ArchiveAsset(
                    ImageArchiveWriter.livePhotoMotionEntryName(index, motion.extension),
                    motion.sourceUrl,
                    motion.referer ?: entry.referer,
                    motion.expectedBytes,
                    MediaKind.VIDEO,
                    index,
                    true,
                    motion.etag,
                    motion.lastModified,
                ))
            }
        }
    }

    private fun DownloadRecord.withArchiveValidator(asset: ArchiveAsset, etag: String?, lastModified: String?): DownloadRecord {
        val updated = archiveEntries.mapIndexed { index, entry ->
            if (index != asset.entryIndex) entry
            else if (asset.isMotion) entry.copy(livePhotoMotion = entry.livePhotoMotion?.copy(
                etag = etag, lastModified = lastModified))
            else entry.copy(etag = etag, lastModified = lastModified)
        }
        return copy(archiveEntries = updated)
    }

    private fun archiveAssetIsValid(file: File, asset: ArchiveAsset, record: DownloadRecord): Boolean = runCatching {
        if (asset.kind == MediaKind.IMAGE) validateImage(file)
        else if (validateFile != null) validateFile.invoke(file, record.copy(mediaKind = MediaKind.VIDEO, livePhotoMotion = null))
        else if (!isIsoMedia(file.inputStream().use { input ->
                val bytes = ByteArray(com.videodown.douyin.domain.model.MediaSignature.HEAD_BYTES)
                val count = input.read(bytes)
                if (count <= 0) ByteArray(0) else bytes.copyOf(count)
            })) throw DownloadContentException("download:invalid_video")
    }.isSuccess

    private suspend fun markArchivePaused(record: DownloadRecord, downloaded: Long, total: Long) {
        history.upsert(
            record.copy(
                status = DownloadStatus.PAUSED,
                downloadedBytes = downloaded,
                totalBytes = total.takeIf { it > 0L } ?: record.totalBytes,
                pauseCause = DownloadPauseCause.NETWORK,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        _progress.update { it - record.id }
    }

    private suspend fun markArchiveFailed(record: DownloadRecord, error: com.videodown.douyin.core.AppError, downloaded: Long, total: Long?) {
        history.upsert(
            record.copy(
                status = DownloadStatus.FAILED,
                downloadedBytes = downloaded,
                totalBytes = total ?: record.totalBytes,
                errorMessage = error.message,
                failureKind = error.downloadFailureKind(),
                pauseCause = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        _progress.update { it - record.id }
    }

    /** 完成时把文件大小与修改时间写进记录，供已存页做低成本健康检查。 */
    private fun DownloadRecord.withFileSnapshot(file: File): DownloadRecord =
        copy(fileSizeBytes = file.length(), fileModifiedAt = file.lastModified())

    /** 普通图片的工作余量较小；Live Photo 还包含 MOV，必须按视频任务预留。 */
    private fun reserveFor(record: DownloadRecord): Long =
        if (record.isImage && !record.isLivePhoto) IMAGE_RESERVE_BYTES else STORAGE_RESERVE_BYTES

    private companion object {
        const val TAG = "DownloadEngine"
        const val DEFAULT_CONCURRENCY = 3
        const val PERSIST_INTERVAL_MS = 2_000L
        const val DEFAULT_AUTO_RESUME_INTERVAL_MS = 15_000L
        const val MAX_AUTO_RESUME_ATTEMPTS = 3
    }
}
