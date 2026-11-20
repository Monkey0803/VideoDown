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
    /** Last-Modified fallback for origins that do not provide an ETag. */
    val lastModified: String? = null,
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
    /**
     * 这条记录最终落地的是什么媒体。
     *
     * 旧版本写下的历史没有这个字段，反序列化后按 [MediaKind.VIDEO] 处理；图片和压缩包靠它分流，
     * 所以不要用扩展名去猜。
     */
    val mediaKind: MediaKind = MediaKind.VIDEO,
    /** 打包下载的图片清单；只有 [MediaKind.ARCHIVE] 记录使用。 */
    val archiveEntries: List<ArchiveEntry> = emptyList(),
    /** Live Photo 静帧对应的 MOV；与本记录共同下载、移动和删除。 */
    val livePhotoMotion: LivePhotoMotion? = null,
    /**
     * 下一次允许重试的绝对时间（毫秒）；`null` 表示当前没有冷却。
     *
     * 落库而不是只留在内存里：进程被杀或应用重启后，自动继续必须等到这个时间之后才发请求，
     * 否则服务端的 429/503 冷却会被一次重启绕过。用户手动点「立即重试」时清空。
     */
    val nextRetryAtMillis: Long? = null,
    /** 当前这串失败已经用掉的有界重试次数；成功、手动重试和重新下载都会清零。 */
    val retryAttempt: Int = 0,
    /** 最近一次触发重试的 HTTP 状态码（引擎或直链给出的），冷却恢复后仍能判断该不该重试。 */
    val retryHttpStatus: Int? = null,
    /**
     * 完成时记录的文件大小与修改时间，用于「已存文件健康检查」的低成本比对。
     *
     * 旧记录没有这两个字段，读到 `null` 时只做「文件还在不在」的判断，不误报「已变化」。
     */
    val fileSizeBytes: Long? = null,
    val fileModifiedAt: Long? = null,
    /**
     * 从「任务」列表移除（归档）后为 `true`；只影响任务页，不影响「已存」。
     *
     * 任务页和已存页共用同一份记录，所以「清除已完成」不能删记录——那会把已存内容一起删掉。
     * 旧记录没有这个字段，反序列化后按未归档处理。
     */
    val taskArchived: Boolean = false,
) {
    val isAudio: Boolean get() = media?.audioOnly == true

    val isImage: Boolean get() = mediaKind == MediaKind.IMAGE

    val isArchive: Boolean get() = mediaKind == MediaKind.ARCHIVE

    val isLivePhoto: Boolean get() = isImage && livePhotoMotion != null

    /**
     * 视频：能播放、能提取音频。
     *
     * 旧版本写下的音频记录没有 mediaKind（反序列化后是 VIDEO），所以还要排除 `audioOnly`，
     * 否则「提取音频」会把这些音频记录当成视频再提取一次。
     */
    val isVideo: Boolean get() = mediaKind == MediaKind.VIDEO && !isAudio

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
 * 清空冷却与重试预算。
 *
 * 成功、用户手动继续、「立即重试」和重新下载都走这一处，避免某个路径漏清字段后
 * 恢复出一次提前请求。
 */
fun DownloadRecord.withRetryCleared(): DownloadRecord =
    copy(nextRetryAtMillis = null, retryAttempt = 0, retryHttpStatus = null)

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
    /** Absolute wall-clock deadline for an in-process retry; never persisted. */
    val retryAtMillis: Long? = null,
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
