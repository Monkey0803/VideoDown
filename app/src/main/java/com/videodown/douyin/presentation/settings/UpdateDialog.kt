package com.videodown.douyin.presentation.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import com.videodown.douyin.R
import com.videodown.douyin.core.updateReasonRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Manual update entry: shows the installed version, checks the configured manifest and opens the
 * download or release link in the browser when a newer build is published.
 */
@Composable
internal fun UpdateDialog(model: UpdateViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val manifestUrl by model.manifestUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingSource by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { model.check() }

    if (editingSource) {
        UpdateSourceDialog(
            initial = manifestUrl,
            onDismiss = { editingSource = false },
            onSave = { url ->
                model.saveManifestUrl(url)
                editingSource = false
                model.check()
            },
        )
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.update_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.update_current_version, appVersionText(model.currentVersion.versionName, model.currentVersion.versionCode)), style = MaterialTheme.typography.bodyMedium)
                when (val current = state) {
                    UpdateUiState.Idle, UpdateUiState.Checking ->
                        Text(stringResource(R.string.update_checking), modifier = Modifier.padding(top = 12.dp))
                    is UpdateUiState.UpToDate ->
                        Text(stringResource(R.string.update_up_to_date), modifier = Modifier.padding(top = 12.dp))
                    is UpdateUiState.Available -> {
                        Text(stringResource(R.string.update_available, versionText(current.latest.versionName, current.latest.versionCode)), modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyLarge)
                        current.notes?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                        when {
                            current.downloadUrl == null && current.releasePageUrl == null ->
                                Text(stringResource(R.string.update_no_link),
                                    modifier = Modifier.padding(top = 8.dp))
                            current.downloadUrl == null ->
                                Text(stringResource(R.string.update_no_direct_link),
                                    modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    is UpdateUiState.Message ->
                        Text(updateMessageText(current), modifier = Modifier.padding(top = 12.dp))
                }
                Text(
                    stringResource(R.string.update_source,
                        manifestUrl.ifBlank { stringResource(R.string.update_source_unset) }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            val available = state as? UpdateUiState.Available
            val target = available?.downloadUrl ?: available?.releasePageUrl
            if (available == null) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_done)) }
            } else {
                TextButton(
                    enabled = target != null,
                    onClick = { if (target != null && openUrl(context, target)) onClose() },
                ) { Text(stringResource(if (available.downloadUrl != null) R.string.update_download else R.string.update_view_release)) }
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { editingSource = true }) { Text(stringResource(R.string.update_source_action)) }
                if (state !is UpdateUiState.Checking) TextButton(onClick = model::check) { Text(stringResource(R.string.update_recheck)) }
            }
        },
    )
}

@Composable
private fun UpdateSourceDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_source_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.update_source_hint),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.update_source_label)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.update_source_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Opens an external link; returns false when no browser handled it so the dialog stays open. */
private fun openUrl(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

/**
 * The published version as the manifest states it: its name when it has one, otherwise its code.
 *
 * Deliberately not the same as [appVersionText]: the manifest names a release, while the installed
 * build is identified by name *and* code.
 */
@Composable
private fun versionText(name: String?, code: Int?): String {
    val trimmed = name?.trim().orEmpty()
    return when {
        trimmed.isNotEmpty() -> trimmed
        code != null -> stringResource(R.string.version_label_code, code)
        else -> stringResource(R.string.version_label_unknown)
    }
}

/** The installed build, e.g. `1.0.1（内部版本 2）`, or just the build when the name is missing. */
@Composable
private fun appVersionText(name: String, code: Int): String =
    if (name.isBlank()) stringResource(R.string.version_label_code, code)
    else stringResource(R.string.version_label_both, name, code)

/** The wording for a failed check; the state only carries a key. */
@Composable
private fun updateMessageText(message: UpdateUiState.Message): String {
    val res = updateReasonRes(message.reasonKey)
    return if (message.httpStatus != null && res == R.string.update_reason_http) {
        stringResource(res, message.httpStatus)
    } else {
        stringResource(res)
    }
}
