package com.videodown.douyin.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus {
    /** Queued but not started yet. */
    PENDING,

    /** Actively transferring bytes. */
    RUNNING,

    /** Interrupted on purpose (or by a network drop) and resumable. */
    PAUSED,

    /** Fully downloaded and verified on disk. */
    COMPLETED,

    /** Permanently failed after exhausting retries. */
    FAILED,
}

/**
 * Why a record is currently [DownloadStatus.PAUSED].
 *
 * Only [USER] is a deliberate choice that must never be undone automatically; the others are
 * interruptions a user-enabled auto-resume policy may continue. Records written before this field
 * existed load as `null` and are treated as user pauses, so an upgrade cannot resurrect a task the
 * user had stopped on purpose.
 */
@Serializable
enum class DownloadPauseCause {
    /** The user tapped pause. */
    USER,

    /** A network policy (for example Wi-Fi only) interrupted an active transfer. */
    NETWORK,

    /** A previous process died while the transfer was running. */
    PROCESS,
}

/**
 * Persisted record of a single download. Written to disk as JSON so download history
 * (and the byte offset needed for resumable transfer) survives process death.
 */
@Serializable
data class DownloadRecord(
    val id: String,
    val awemeId: String,
    val title: String,
    val authorNickname: String = "",
    val authorAvatarUrl: String? = null,
    val authorSecUid: String? = null,
    val coverUrl: String? = null,
    /** Remote URL this record downloads from. */
    val sourceUrl: String = "",
    val resolutionLabel: String = "",
    val fileName: String = "",
    /** Absolute path of the finished file, set when [status] becomes COMPLETED. */
    val filePath: String? = null,
    /** Absolute path of the in-progress `.part` file used for resuming. */
    val partialPath: String = "",
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * True when the origin server confirmed `Accept-Ranges: bytes`, which is what makes
     * resuming this particular record possible.
     */
    val rangeSupported: Boolean = false,
    /** ETag captured from the origin server, used as `If-Range` when resuming. */
    val etag: String? = null,
    // Defaults preserve old download history and its existing Douyin resume behavior.
    val platform: VideoPlatform = VideoPlatform.DOUYIN,
    val sourcePageUrl: String = "",
    val downloadReferer: String? = null,
    val media: MediaDownload? = null,
    val phase: TransferPhase = TransferPhase.DOWNLOADING,
    val failureKind: DownloadFailureKind? = null,
    /** Why this record is paused; `null` is legacy history and is never auto-resumed. */
    val pauseCause: DownloadPauseCause? = null,
    val expectedBytes: Long? = null,
    val queueOrder: Long = 0,
    /**
     * 已存页「自定义排序」里的位置，`0` 表示还没被手动排过（按更新时间排到已排过的之后）。
     *
     * 与 [queueOrder] 分开：队列顺序只对下载队列有意义，这个只影响已存列表的展示顺序。
     */
    val sortIndex: Long = 0,
    val favorite: Boolean = false,
    val folder: String = "",
    /** Per-task rate cap in KB/s; `0` follows the global setting. Defaults keep old history. */
    val speedLimitKbps: Int = 0,
    /** Languages to fetch and embed while downloading; empty means the file keeps no subtitles. */
    val subtitleLanguages: List<String> = emptyList(),
    /** Whether those languages are machine captions rather than real subtitle tracks. */
    val subtitleAutomatic: Boolean = false,
    /** Kept out of the normal library, the task titles and every automatic export. */
    val isPrivate: Boolean = false,
) {
    val isAudio: Boolean get() = media?.audioOnly == true

    val progressFraction: Float
        get() = when {
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            status == DownloadStatus.COMPLETED -> 1f
            else -> 0f
        }

    val isResumable: Boolean
        get() = status == DownloadStatus.PAUSED || status == DownloadStatus.FAILED

    /**
     * The stored author name, blank when the source gave none.
     *
     * The screen substitutes its own wording for a blank name, so this file stays copy-free.
     */
    val displayAuthor: String get() = authorNickname
}

/**
 * Live, transient progress for one download. Not persisted.
 */
data class DownloadProgress(
    val id: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val bytesPerSecond: Long = 0L,
    val phase: TransferPhase = TransferPhase.DOWNLOADING,
    val failureKind: DownloadFailureKind? = null,
    val errorMessage: String? = null,
) {
    val fraction: Float
        get() = when {
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            status == DownloadStatus.COMPLETED -> 1f
            else -> 0f
        }

    val isActive: Boolean
        get() = status == DownloadStatus.RUNNING || status == DownloadStatus.PENDING
}
