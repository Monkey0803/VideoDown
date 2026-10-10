package com.videodown.douyin.presentation.library

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.presentation.components.AppDialog
import com.videodown.douyin.presentation.components.AppDialogButtonRow
import com.videodown.douyin.presentation.components.AppIcons

@Composable
internal fun AudioExtractionDialog(record: DownloadRecord, model: AudioExtractionViewModel, onClose: () -> Unit, onQueued: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    AppDialog(
        onDismissRequest = { if (!state.busy) onClose() },
        icon = AppIcons.ExtractAudio,
        title = stringResource(R.string.audio_extract_title),
        actions = {
            AppDialogButtonRow {
                TextButton(enabled = !state.busy, onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.weight(1f))
                Button(enabled = !state.busy, onClick = { model.start(record, again = state.duplicate != null, onQueued = onQueued) }) {
                    Text(stringResource(when {
                        state.busy -> R.string.home_submitting
                        state.duplicate != null -> R.string.audio_extract_again
                        else -> R.string.audio_extract_start
                    }))
                }
            }
        },
    ) {
        Text(
            state.notice?.let { audioExtractNoticeText(it) } ?: stringResource(R.string.audio_extract_body, record.title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The wording for an extract notice. */
@Composable
private fun audioExtractNoticeText(notice: AudioExtractNotice): String = when (notice) {
    AudioExtractNotice.Duplicate -> stringResource(R.string.audio_extract_duplicate)
    AudioExtractNotice.QueueFailed -> stringResource(R.string.audio_extract_queue_failed)
}
