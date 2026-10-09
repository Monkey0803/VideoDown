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
            } catch (_: Exception) { model.message("无法保存文件夹权限，请重新选择可写的文件夹") }
        }
    }
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() },
        title = { Text("导出到文件夹") },
        text = { Column {
            Text("复制所选 ${ids.size} 项，保留本地原文件。同名文件会添加序号，不覆盖已有文件。")
            if (state.busy) {
                LinearProgressIndicator()
                Text("正在导出 ${state.done}/${state.total}…")
            }
            state.message?.let { Text(it) }
            if (!state.busy && remaining.isNotEmpty()) TextButton(onClick = { picker.launch(rememberedTree?.let(Uri::parse)) }) {
                Text(if (rememberedTree == null) "选择文件夹" else "选择其他文件夹")
            }
        } },
        confirmButton = {
            if (!state.busy && rememberedTree != null && rememberedTree != state.invalidTreeUri && remaining.isNotEmpty()) TextButton(onClick = { model.export(remaining, checkNotNull(rememberedTree)) }) { Text(if (state.finished) "重试失败项到上次文件夹" else "导出到上次文件夹") }
        },
        dismissButton = { TextButton(onClick = { if (state.busy) model.cancel() else onClose() }) { Text(if (state.busy) "停止导出" else "关闭") } })
}
