package com.videodown.douyin.presentation.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.domain.model.DownloadRecord

@Composable
internal fun LibraryOrganizationDialog(record: DownloadRecord, model: LibraryOrganizationViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var title by rememberSaveable(record.id) { mutableStateOf(record.title) }
    var folder by rememberSaveable(record.id) { mutableStateOf(record.folder) }
    LaunchedEffect(record.id) { model.clearError() }
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() }, title = { Text("重命名与分类") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = title, onValueChange = { title = it }, enabled = !state.busy, label = { Text("文件名称") }, singleLine = true)
            Text("保留原扩展名；更名后的文件会用于播放、分享和导出。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = folder, onValueChange = { folder = it }, enabled = !state.busy, label = { Text("分类名称（留空表示未分类）") }, singleLine = true)
            Text("分类用于影像库筛选，不移动到外部目录。", style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !state.busy && (title == record.title || (title.isNotBlank() && title.length <= 80)) && folder.length <= 40,
            onClick = { model.update(record.id, title = title.takeIf { it != record.title }, folder = folder, onSaved = onClose) }) { Text(if (state.busy) "正在保存…" else "保存修改") } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = onClose) { Text("取消") } })
}
