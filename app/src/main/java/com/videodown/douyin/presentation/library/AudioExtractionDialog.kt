package com.videodown.douyin.presentation.library

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.domain.model.DownloadRecord

@Composable
internal fun AudioExtractionDialog(record: DownloadRecord, model: AudioExtractionViewModel, onClose: () -> Unit, onQueued: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() }, title = { Text("提取音频") },
        text = { Text(state.message ?: "从“${record.title}”在本机提取音频，保留原视频，保存为 M4A。AAC 音轨优先无损复制，其他编码转换为 AAC。任务加入现有队列，可离线提取。") },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { model.start(record, again = state.duplicate != null, onQueued = onQueued) }) {
            Text(if (state.busy) "正在加入队列…" else if (state.duplicate != null) "仍然提取一份" else "开始提取")
        } }, dismissButton = { TextButton(enabled = !state.busy, onClick = onClose) { Text("取消") } })
}
