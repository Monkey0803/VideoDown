package com.videodown.douyin.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.RecoveryAction

/**
 * The wording for download failures.
 *
 * `domain` owns the failure taxonomy and which recovery actions apply; it deliberately holds no
 * user-visible copy, because it is framework-free and cannot reach resources. This file is the one
 * place that turns a failure into text, so a new kind cannot ship without a translation.
 */
internal fun failureTitleRes(kind: DownloadFailureKind): Int = when (kind) {
    DownloadFailureKind.NETWORK -> R.string.failure_network_title
    DownloadFailureKind.LOGIN_REQUIRED -> R.string.failure_login_title
    DownloadFailureKind.ACCESS_DENIED -> R.string.failure_access_title
    DownloadFailureKind.LINK_EXPIRED -> R.string.failure_expired_title
    DownloadFailureKind.STORAGE_FULL -> R.string.failure_storage_full_title
    DownloadFailureKind.STORAGE_ACCESS -> R.string.failure_storage_access_title
    DownloadFailureKind.FORMAT_CHANGED -> R.string.failure_format_title
    DownloadFailureKind.NO_AUDIO -> R.string.failure_no_audio_title
    DownloadFailureKind.UNSUPPORTED -> R.string.failure_unsupported_title
    DownloadFailureKind.RATE_LIMITED -> R.string.failure_rate_title
    DownloadFailureKind.WIFI_REQUIRED -> R.string.failure_wifi_title
    DownloadFailureKind.BACKGROUND_SERVICE -> R.string.failure_background_title
    DownloadFailureKind.RUNTIME -> R.string.failure_runtime_title
    DownloadFailureKind.UNKNOWN -> R.string.failure_unknown_title
}

internal fun failureDescriptionRes(kind: DownloadFailureKind): Int = when (kind) {
    DownloadFailureKind.NETWORK -> R.string.failure_network_body
    DownloadFailureKind.LOGIN_REQUIRED -> R.string.failure_login_body
    DownloadFailureKind.ACCESS_DENIED -> R.string.failure_access_body
    DownloadFailureKind.LINK_EXPIRED -> R.string.failure_expired_body
    DownloadFailureKind.STORAGE_FULL -> R.string.failure_storage_full_body
    DownloadFailureKind.STORAGE_ACCESS -> R.string.failure_storage_access_body
    DownloadFailureKind.FORMAT_CHANGED -> R.string.failure_format_body
    DownloadFailureKind.NO_AUDIO -> R.string.failure_no_audio_body
    DownloadFailureKind.UNSUPPORTED -> R.string.failure_unsupported_body
    DownloadFailureKind.RATE_LIMITED -> R.string.failure_rate_body
    DownloadFailureKind.WIFI_REQUIRED -> R.string.failure_wifi_body
    DownloadFailureKind.BACKGROUND_SERVICE -> R.string.failure_background_body
    DownloadFailureKind.RUNTIME -> R.string.failure_runtime_body
    DownloadFailureKind.UNKNOWN -> R.string.failure_unknown_body
}

internal fun recoveryActionRes(action: RecoveryAction): Int = when (action) {
    RecoveryAction.RETRY -> R.string.recovery_retry
    RecoveryAction.REPARSE -> R.string.recovery_reparse
    RecoveryAction.BROWSER -> R.string.recovery_browser
    RecoveryAction.STORAGE -> R.string.recovery_storage
    RecoveryAction.SETTINGS -> R.string.recovery_settings
    RecoveryAction.NONE -> R.string.recovery_none
}

@Composable
fun failureTitle(kind: DownloadFailureKind): String = stringResource(failureTitleRes(kind))

@Composable
fun failureDescription(kind: DownloadFailureKind): String = stringResource(failureDescriptionRes(kind))

@Composable
fun recoveryActionLabel(action: RecoveryAction): String = stringResource(recoveryActionRes(action))
