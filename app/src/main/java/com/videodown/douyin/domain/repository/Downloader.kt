package com.videodown.douyin.domain.repository

import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.SubtitleSelection
import com.videodown.douyin.domain.model.ArchiveRequest
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import com.videodown.douyin.domain.model.DownloadQueueState

/**
 * Owns the lifecycle of download tasks: starting, pausing (so they can be resumed later),
 * resuming from a stored byte offset, and cancelling.
 */
private val emptyDownloadQueue = MutableStateFlow(DownloadQueueState())

interface Downloader {
    val queue: StateFlow<DownloadQueueState> get() = emptyDownloadQueue
    suspend fun setConcurrency(count: Int) {}
    suspend fun moveInQueue(id: String, offset: Int) {}

    /**
     * 把待下载任务按给定顺序重排（拖拽用）。
     *
     * [orderedPendingIds] 只是当前可见的待下载任务：没列进来的待下载任务保持它们之间的相对位置，
     * 已在下下载或已结束的任务不受影响。
     */
    suspend fun reorderQueue(orderedPendingIds: List<String>) {}

    /** Snapshot of every task the engine currently knows about, keyed by record id. */
    val progress: StateFlow<Map<String, DownloadProgress>>

    /**
     * Creates a history record for [video]/[variant] and starts downloading it.
     * Returns the id of the created record.
     */
    suspend fun start(
        video: DouyinVideo,
        variant: VideoVariant,
        /** Subtitle track to fetch and embed into the downloaded file; [SubtitleSelection.NONE] keeps none. */
        subtitles: SubtitleSelection = SubtitleSelection.NONE,
    ): String

    /** Existing matching task, or a completed task whose local file still exists. */
    suspend fun findDuplicate(video: DouyinVideo, variant: VideoVariant): DownloadRecord? = null

    /** 用重新解析到的页面展示信息修复旧任务；运行中的任务由传输循环独占，避免旧快照覆盖。 */
    suspend fun refreshMetadata(id: String, video: DouyinVideo): DownloadRecord? = null

    /**
     * 把一组图片打成一个 ZIP 任务；返回记录 id。
     *
     * 默认实现抛异常，是为了让设备/单元测试里的轻量 Downloader 伪造实现不必新增方法；
     * 真实引擎总会覆盖它，界面把异常转成「加入队列失败」。
     */
    suspend fun startArchive(request: ArchiveRequest): String =
        throw UnsupportedOperationException("archive downloads are not supported by this downloader")

    /** 已存在、且本地文件仍在的同一帖压缩包；用于"仍然打包"之前的重复提醒。 */
    suspend fun findArchiveDuplicate(request: ArchiveRequest): DownloadRecord? = null

    /** Stops an active transfer but keeps the partial file and byte offset. */
    suspend fun pause(id: String)

    /** Continues a paused/failed transfer from its stored byte offset. */
    suspend fun resume(id: String)

    /**
     * 跳过服务端冷却，立刻重试一次（用户明确点击「立即重试」）。
     *
     * 与 [resume] 的区别只在意图：冷却中的任务仍然算「自动」恢复，必须等 `Retry-After`；
     * 用户主动要求才允许提前请求，并清零已经用掉的重试预算。
     */
    suspend fun retryNow(id: String) { resume(id) }

    /** Stops a transfer and deletes the partial file plus its history record. */
    suspend fun cancel(id: String, removeRecord: Boolean = true)

    /** Resumes every record that is in a resumable state. */
    suspend fun resumeAll()

    /**
     * Continues only the records an automatic policy may pick up: transfers interrupted by the
     * network or by a full disk, never a pause the user chose.
     */
    suspend fun autoResume() {}

    /** Pauses every active transfer. */
    suspend fun pauseAll()

    /** Restarts a failed/paused record from byte zero. */
    suspend fun restart(id: String)

    /**
     * Applies a per-task rate cap in KB/s (`0` follows the global setting) to a task, running or
     * not. The cap is persisted on the record so it survives a restart.
     */
    suspend fun setSpeedLimit(id: String, kbps: Int) {}

    /** Observes one record's persisted state (used to reflect completion in the UI). */
    fun observeRecord(id: String): Flow<DownloadRecord?>
}
