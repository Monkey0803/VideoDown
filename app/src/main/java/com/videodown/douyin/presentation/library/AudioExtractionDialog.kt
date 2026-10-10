package com.videodown.douyin.presentation.library

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.domain.model.DownloadRecord

@Composable
internal fun AudioExtractionDialog(record: DownloadRecord, model: AudioExtractionViewModel, onClose: () -> Unit, onQueued: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() }, title = { Text(stringResource(R.string.audio_extract_title)) },
        text = { Text(state.notice?.let { audioExtractNoticeText(it) } ?: stringResource(R.string.audio_extract_body, record.title)) },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { model.start(record, again = state.duplicate != null, onQueued = onQueued) }) {
            Text(stringResource(when {
                state.busy -> R.string.home_submitting
                state.duplicate != null -> R.string.audio_extract_again
                else -> R.string.audio_extract_start
            }))
        } }, dismissButton = { TextButton(enabled = !state.busy, onClick = onClose) { Text(stringResource(R.string.action_cancel)) } })
}

/** The wording for an extract notice. */
@Composable
private fun audioExtractNoticeText(notice: AudioExtractNotice): String = when (notice) {
    AudioExtractNotice.Duplicate -> stringResource(R.string.audio_extract_duplicate)
    AudioExtractNotice.QueueFailed -> stringResource(R.string.audio_extract_queue_failed)
}
