package com.videodown.douyin.presentation.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.domain.repository.FileExportFailureKind

/**
 * The wording for a finished export run.
 *
 * The view model reports counts and reason kinds; this is the one place that turns them into the
 * sentence the dialog shows.
 */
@Composable
internal fun exportResultText(result: FileExportResult): String {
    if (result.cancelled) return stringResource(R.string.export_cancelled, result.succeeded)
    val clause = stringResource(R.string.export_clause_separator)
    val parts = buildList {
        add(stringResource(R.string.export_done, result.succeeded))
        if (result.failed > 0) {
            add(stringResource(R.string.export_failed_clause, result.failed))
            add(result.reasons.map { stringResource(exportReasonRes(it)) }.joinToString(clause))
            add(stringResource(R.string.export_originals_untouched))
        } else {
            add(stringResource(R.string.export_originals_kept))
        }
    }
    return buildString {
        append(parts.first())
        parts.drop(1).forEach { append(stringResource(R.string.export_comma_separator)); append(it) }
        when (val cleanup = result.cleanup) {
            ExportCleanup.None -> Unit
            is ExportCleanup.Pending -> {
                append(clause); append(stringResource(R.string.export_cleanup_pending, cleanup.count))
            }
            ExportCleanup.Unknown -> { append(clause); append(stringResource(R.string.export_cleanup_unknown)) }
        }
    }
}

internal fun exportReasonRes(kind: FileExportFailureKind): Int = when (kind) {
    FileExportFailureKind.ACCESS_LOST -> R.string.export_reason_access_lost
    FileExportFailureKind.SPACE_FULL -> R.string.export_reason_space_full
    FileExportFailureKind.OTHER -> R.string.export_reason_other
}
