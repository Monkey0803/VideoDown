package com.videodown.douyin.presentation.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.core.ServiceLocator

@Composable
internal fun FileExportDialog(ids: List<String>, model: FileExportViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val remaining = if (state.finished) ids.filter { it in state.failedIds } else ids
    var rememberedTree by remember { mutableStateOf(ServiceLocator.preferences.exportTreeUri) }
    LaunchedEffect(state.invalidTreeUri) {
        state.invalidTreeUri?.let { invalid ->
            if (rememberedTree == invalid) rememberedTree = null
            if (ServiceLocator.preferences.exportTreeUri == invalid) ServiceLocator.preferences.exportTreeUri = null
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            try {
                context.contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                ServiceLocator.preferences.exportTreeUri = tree.toString()
                rememberedTree = tree.toString()
                model.export(remaining, tree.toString())
            } catch (_: Exception) { model.message(context.getString(R.string.export_permission_failed)) }
        }
    }
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() },
        title = { Text(stringResource(R.string.export_title)) },
        text = { Column {
            Text(stringResource(R.string.export_body, ids.size))
            if (state.busy) {
                LinearProgressIndicator()
                Text(stringResource(R.string.export_progress, state.done, state.total))
            }
            state.result?.let { Text(exportResultText(it)) }
            state.message?.let { Text(it) }
            if (!state.busy && remaining.isNotEmpty()) TextButton(onClick = { picker.launch(rememberedTree?.let(Uri::parse)) }) {
                Text(stringResource(if (rememberedTree == null) R.string.export_pick_folder else R.string.export_pick_other_folder))
            }
        } },
        confirmButton = {
            if (!state.busy && rememberedTree != null && rememberedTree != state.invalidTreeUri && remaining.isNotEmpty()) TextButton(onClick = { model.export(remaining, checkNotNull(rememberedTree)) }) { Text(stringResource(if (state.finished) R.string.export_retry_last else R.string.export_to_last)) }
        },
        dismissButton = { TextButton(onClick = { if (state.busy) model.cancel() else onClose() }) { Text(stringResource(if (state.busy) R.string.export_stop else R.string.action_close)) } })
}
