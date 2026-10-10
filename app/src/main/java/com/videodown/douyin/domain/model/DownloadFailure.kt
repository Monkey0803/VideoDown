package com.videodown.douyin.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadFailureKind { NETWORK, LOGIN_REQUIRED, ACCESS_DENIED, LINK_EXPIRED, STORAGE_FULL, STORAGE_ACCESS, FORMAT_CHANGED, UNSUPPORTED, RATE_LIMITED, WIFI_REQUIRED, BACKGROUND_SERVICE, RUNTIME, NO_AUDIO, UNKNOWN }

/** What the user can do about a failure. The wording lives in the presentation layer. */
enum class RecoveryAction { RETRY, REPARSE, BROWSER, STORAGE, SETTINGS, NONE }

/** Which recovery actions a failure offers, most useful first. No user-visible copy lives here. */
data class FailureActions(val primary: RecoveryAction, val secondary: RecoveryAction? = null)

fun failureActions(kind: DownloadFailureKind): FailureActions = when (kind) {
    DownloadFailureKind.NETWORK -> FailureActions(RecoveryAction.RETRY)
    DownloadFailureKind.LOGIN_REQUIRED -> FailureActions(RecoveryAction.BROWSER)
    DownloadFailureKind.ACCESS_DENIED -> FailureActions(RecoveryAction.REPARSE, RecoveryAction.BROWSER)
    DownloadFailureKind.LINK_EXPIRED -> FailureActions(RecoveryAction.REPARSE)
    DownloadFailureKind.STORAGE_FULL -> FailureActions(RecoveryAction.STORAGE)
    DownloadFailureKind.STORAGE_ACCESS -> FailureActions(RecoveryAction.STORAGE)
    DownloadFailureKind.FORMAT_CHANGED -> FailureActions(RecoveryAction.REPARSE)
    DownloadFailureKind.NO_AUDIO -> FailureActions(RecoveryAction.NONE)
    DownloadFailureKind.UNSUPPORTED -> FailureActions(RecoveryAction.NONE)
    DownloadFailureKind.RATE_LIMITED -> FailureActions(RecoveryAction.SETTINGS, RecoveryAction.RETRY)
    DownloadFailureKind.WIFI_REQUIRED -> FailureActions(RecoveryAction.SETTINGS, RecoveryAction.RETRY)
    DownloadFailureKind.BACKGROUND_SERVICE -> FailureActions(RecoveryAction.RETRY)
    DownloadFailureKind.RUNTIME -> FailureActions(RecoveryAction.STORAGE)
    DownloadFailureKind.UNKNOWN -> FailureActions(RecoveryAction.RETRY, RecoveryAction.REPARSE)
}

/**
 * Fallback only for older history that predates typed failure categories.
 *
 * The literals below match messages that are *already stored* on user devices, so they are data,
 * not copy: they must keep matching exactly as written. Marked `l10n-ignore` for that reason.
 */
// l10n-ignore: matches already-stored history messages, never displayed as new copy
fun legacyFailureKind(message: String?): DownloadFailureKind? {
    if (message.isNullOrBlank()) return null
    return when {
        message.contains("空间不足") -> DownloadFailureKind.STORAGE_FULL
        message.contains("Wi-Fi") -> DownloadFailureKind.WIFI_REQUIRED
        message.contains("HTTP 401") || message.contains("要求登录") -> DownloadFailureKind.LOGIN_REQUIRED
        message.contains("HTTP 403") -> DownloadFailureKind.ACCESS_DENIED
        message.contains("HTTP 404") || message.contains("HTTP 410") -> DownloadFailureKind.LINK_EXPIRED
        message.contains("HTTP 429") -> DownloadFailureKind.RATE_LIMITED
        message.contains("DRM") || message.contains("直播") -> DownloadFailureKind.UNSUPPORTED
        message.contains("已变化") || message.contains("续传位置不一致") -> DownloadFailureKind.FORMAT_CHANGED
        message.contains("网络") || message.contains("超时") -> DownloadFailureKind.NETWORK
        else -> DownloadFailureKind.UNKNOWN
    }
}

/** Applies the same retry boundary to older history and typed engine failures. */
val DownloadRecord.canRetryDownload: Boolean
    get() = (failureKind ?: legacyFailureKind(errorMessage)) !in setOf(DownloadFailureKind.UNSUPPORTED, DownloadFailureKind.NO_AUDIO)

/**
 * Typed failures an automatic resume may retry on its own.
 *
 * Kept deliberately narrow: only a network interruption or a full disk is a condition that clears
 * by itself. Authentication, expired links, engine and rate-limit failures need the user.
 */
val AUTO_RESUME_FAILURE_KINDS = setOf(DownloadFailureKind.NETWORK, DownloadFailureKind.STORAGE_FULL)

/** True when the record is resumable and the interruption was not the user's own pause. */
val DownloadRecord.canAutoResume: Boolean
    get() = canRetryDownload && when (status) {
        DownloadStatus.PAUSED -> pauseCause != null && pauseCause != DownloadPauseCause.USER
        DownloadStatus.FAILED -> (failureKind ?: legacyFailureKind(errorMessage)) in AUTO_RESUME_FAILURE_KINDS
        else -> false
    }
