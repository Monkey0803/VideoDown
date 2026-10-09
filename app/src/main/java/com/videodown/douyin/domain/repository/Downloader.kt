package com.videodown.douyin.domain.repository

import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
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

    /** Snapshot of every task the engine currently knows about, keyed by record id. */
    val progress: StateFlow<Map<String, DownloadProgress>>

    /**
     * Creates a history record for [video]/[variant] and starts downloading it.
     * Returns the id of the created record.
     */
    suspend fun start(video: DouyinVideo, variant: VideoVariant): String

    /** Existing matching task, or a completed task whose local file still exists. */
    suspend fun findDuplicate(video: DouyinVideo, variant: VideoVariant): DownloadRecord? = null

    /** Stops an active transfer but keeps the partial file and byte offset. */
    suspend fun pause(id: String)

    /** Continues a paused/failed transfer from its stored byte offset. */
    suspend fun resume(id: String)

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

    /** Observes one record's persisted state (used to reflect completion in the UI). */
    fun observeRecord(id: String): Flow<DownloadRecord?>
}