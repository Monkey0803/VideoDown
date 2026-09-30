package com.videodown.douyin.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaSourceType { DIRECT, HLS, DASH, SEPARATE }

/** Persistent selection, never credentials. Browser cookies are resolved at request time. */
@Serializable
data class MediaDownload(
    val type: MediaSourceType = MediaSourceType.DIRECT,
    val pageUrl: String = "",
    val mediaId: String = "",
    val formatId: String = "",
    val audioFormatId: String? = null,
    val audioUrl: String? = null,
    val extension: String = "mp4",
    val userAgent: String? = null,
    val useBrowserSession: Boolean = false,
    val extractor: String = "",
    val audioOnly: Boolean = false,
    /** Local history ID, resolved under the maintenance gate; never an arbitrary path. */
    val localRecordId: String? = null,
) {
    val usesEngine: Boolean get() = audioOnly || type != MediaSourceType.DIRECT
    val formatSelector: String get() = listOfNotNull(formatId.takeIf { it.isNotBlank() }, audioFormatId).joinToString("+")
    fun sameMedia(other: MediaDownload): Boolean = mediaId.isNotBlank() && mediaId == other.mediaId &&
        extractor == other.extractor && formatId == other.formatId && audioFormatId == other.audioFormatId && type == other.type &&
        audioOnly == other.audioOnly && localRecordId == other.localRecordId
}

@Serializable
enum class TransferPhase { DOWNLOADING, MERGING, EXTRACTING, VERIFYING }

fun videoMimeType(extension: String): String = when (extension.lowercase()) {
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "mp3" -> "audio/mpeg"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "ts" -> "video/mp2t"
    "mp4", "m4v" -> "video/mp4"
    else -> "application/octet-stream"
}
