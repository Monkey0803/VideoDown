package com.videodown.douyin.presentation.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
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
        title = { Text("检查更新") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("当前版本：${model.currentLabel}", style = MaterialTheme.typography.bodyMedium)
                when (val current = state) {
                    UpdateUiState.Idle, UpdateUiState.Checking ->
                        Text("正在检查更新…", modifier = Modifier.padding(top = 12.dp))
                    is UpdateUiState.UpToDate ->
                        Text("已是最新版本。", modifier = Modifier.padding(top = 12.dp))
                    is UpdateUiState.Available -> {
                        Text("发现新版本：${current.latestLabel}", modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyLarge)
                        current.notes?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                        when {
                            current.downloadUrl == null && current.releasePageUrl == null ->
                                Text("该版本未提供下载地址或发布页，请通过官方渠道获取。",
                                    modifier = Modifier.padding(top = 8.dp))
                            current.downloadUrl == null ->
                                Text("未提供安装包直链，可打开发布页查看下载方式。",
                                    modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    is UpdateUiState.Message ->
                        Text(current.text, modifier = Modifier.padding(top = 12.dp))
                }
                Text(
                    "更新源：${manifestUrl.ifBlank { "未配置" }}",
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
                TextButton(onClick = onClose) { Text("完成") }
            } else {
                TextButton(
                    enabled = target != null,
                    onClick = { if (target != null && openUrl(context, target)) onClose() },
                ) { Text(if (available.downloadUrl != null) "下载新版" else "查看发布页") }
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { editingSource = true }) { Text("更新源") }
                if (state !is UpdateUiState.Checking) TextButton(onClick = model::check) { Text("重新检查") }
            }
        },
    )
}

@Composable
private fun UpdateSourceDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新源地址") },
        text = {
            Column {
                Text("填写返回版本清单 JSON 的 http(s) 地址；留空则使用应用内置地址。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("版本清单地址") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存并检查") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Opens an external link; returns false when no browser handled it so the dialog stays open. */
private fun openUrl(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess
