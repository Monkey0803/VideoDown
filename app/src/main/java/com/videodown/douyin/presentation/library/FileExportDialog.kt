package com.videodown.douyin.presentation.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.presentation.components.AppDialog
import com.videodown.douyin.presentation.components.AppDialogButtonRow
import com.videodown.douyin.presentation.components.AppIcons

@Composable
internal fun FileExportDialog(ids: List<String>, model: FileExportViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 资源按当前组合读取：context.getString 不会随语言/配置切换重算。
    val resources = LocalResources.current
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
            } catch (_: Exception) { model.message(resources.getString(R.string.export_permission_failed)) }
        }
    }
    AppDialog(
        onDismissRequest = { if (!state.busy) onClose() },
        icon = AppIcons.ExportFolder,
        title = stringResource(R.string.export_title),
        content = {
            Text(stringResource(R.string.export_body, ids.size),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) {
                LinearProgressIndicator()
                Text(stringResource(R.string.export_progress, state.done, state.total),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.result?.let { Text(exportResultText(it), style = MaterialTheme.typography.bodyMedium) }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!state.busy && remaining.isNotEmpty()) TextButton(onClick = { picker.launch(rememberedTree?.let(Uri::parse)) }) {
                Text(stringResource(if (rememberedTree == null) R.string.export_pick_folder else R.string.export_pick_other_folder))
            }
        },
        actions = {
            val canExport = !state.busy && rememberedTree != null && rememberedTree != state.invalidTreeUri && remaining.isNotEmpty()
            if (canExport) {
                FilledTonalButton(
                    onClick = { model.export(remaining, checkNotNull(rememberedTree)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (state.finished) R.string.export_retry_last else R.string.export_to_last)) }
            }
            AppDialogButtonRow {
                Spacer(Modifier.weight(1f))
                Button(onClick = { if (state.busy) model.cancel() else onClose() }) {
                    Text(stringResource(if (state.busy) R.string.export_stop else R.string.action_close))
                }
            }
        },
    )
}
