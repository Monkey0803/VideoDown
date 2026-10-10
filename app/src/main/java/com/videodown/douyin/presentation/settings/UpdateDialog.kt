package com.videodown.douyin.presentation.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.R
import com.videodown.douyin.core.updateReasonRes
import com.videodown.douyin.presentation.components.AppDialog
import com.videodown.douyin.presentation.components.AppDialogButtonRow
import com.videodown.douyin.presentation.components.AppIcons

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

    AppDialog(
        onDismissRequest = onClose,
        icon = AppIcons.Download,
        title = stringResource(R.string.update_title),
        content = {
            Text(stringResource(R.string.update_current_version, appVersionText(model.currentVersion.versionName, model.currentVersion.versionCode)), style = MaterialTheme.typography.bodyMedium)
            when (val current = state) {
                UpdateUiState.Idle, UpdateUiState.Checking ->
                    Text(stringResource(R.string.update_checking))
                is UpdateUiState.UpToDate ->
                    Text(stringResource(R.string.update_up_to_date))
                is UpdateUiState.Available -> {
                    Text(stringResource(R.string.update_available, versionText(current.latest.versionName, current.latest.versionCode)),
                        style = MaterialTheme.typography.bodyLarge)
                    current.notes?.let { Text(it) }
                    when {
                        current.downloadUrl == null && current.releasePageUrl == null ->
                            Text(stringResource(R.string.update_no_link))
                        current.downloadUrl == null ->
                            Text(stringResource(R.string.update_no_direct_link))
                    }
                }
                is UpdateUiState.Message ->
                    Text(updateMessageText(current))
            }
            Text(
                stringResource(R.string.update_source,
                    manifestUrl.ifBlank { stringResource(R.string.update_source_unset) }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            val available = state as? UpdateUiState.Available
            val target = available?.downloadUrl ?: available?.releasePageUrl
            // 推进型操作通栏在上：M3 的动作行放不下三个次要操作时会把确认按钮挤到下一行。
            if (available != null) {
                FilledTonalButton(
                    enabled = target != null,
                    onClick = { if (target != null && openUrl(context, target)) onClose() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (available.downloadUrl != null) R.string.update_download else R.string.update_view_release)) }
            }
            AppDialogButtonRow {
                TextButton(onClick = { editingSource = true }) { Text(stringResource(R.string.update_source_action)) }
                if (state !is UpdateUiState.Checking) TextButton(onClick = model::check) { Text(stringResource(R.string.update_recheck)) }
                Spacer(Modifier.weight(1f))
                if (available == null) {
                    Button(onClick = onClose) { Text(stringResource(R.string.action_done)) }
                } else {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_done)) }
                }
            }
        },
    )
}

@Composable
private fun UpdateSourceDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AppDialog(
        onDismissRequest = onDismiss,
        icon = AppIcons.Link,
        title = stringResource(R.string.update_source_dialog_title),
        content = {
            Text(stringResource(R.string.update_source_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.update_source_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        actions = {
            AppDialogButtonRow {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSave(value) }) { Text(stringResource(R.string.update_source_save)) }
            }
        },
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
