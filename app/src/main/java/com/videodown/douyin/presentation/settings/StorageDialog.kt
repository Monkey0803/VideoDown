package com.videodown.douyin.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.domain.model.StorageUsage

@Composable
internal fun StorageDialog(model: StorageViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var pendingPlan by remember { mutableStateOf<StorageUsage?>(null) }
    LaunchedEffect(Unit) { model.refresh() }
    pendingPlan?.let { plan ->
        AlertDialog(onDismissRequest = { pendingPlan = null }, title = { Text("清理残留文件和无效记录？") },
            text = { Text("将清理 ${plan.orphanEntries.size} 项残留临时文件（${formatBytes(plan.reclaimableBytes)}）和 ${plan.missingRecordIds.size} 条文件已丢失的完成记录。\n\n保留有效视频、相册副本以及暂停/进行中任务的断点。确认后会再次检查，发生变化的项目将跳过。") },
            confirmButton = { TextButton(onClick = { pendingPlan = null; model.clean(plan) }) { Text("确认清理") } },
            dismissButton = { TextButton(onClick = { pendingPlan = null }) { Text("取消") } })
    }
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() }, title = { Text("存储管理") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            state.usage?.let { usage ->
                Text("可用空间：${formatBytes(usage.availableBytes)}")
                Text("视频文件：${formatBytes(usage.videoBytes)}", modifier = Modifier.padding(top = 8.dp))
                Text("临时文件：${formatBytes(usage.temporaryBytes)}", modifier = Modifier.padding(top = 8.dp))
                Text("可清理残留：${formatBytes(usage.reclaimableBytes)} · ${usage.orphanEntries.size} 项", modifier = Modifier.padding(top = 8.dp))
                Text("文件已丢失的记录：${usage.missingRecordIds.size} 条", modifier = Modifier.padding(top = 8.dp))
                Text("临时文件包含下载断点和合并片段，仍被任务引用的文件会保留。要释放已存视频空间，请在「已存」中选择删除。", modifier = Modifier.padding(top = 12.dp))
            }
            if (state.busy) Text("正在处理存储空间…", modifier = Modifier.padding(top = 12.dp))
            state.message?.let { Text(it, modifier = Modifier.padding(top = 12.dp)) }
        } },
        confirmButton = { TextButton(onClick = onClose, enabled = !state.busy) { Text("完成") } },
        dismissButton = { Column {
            TextButton(onClick = model::refresh, enabled = !state.busy) { Text("刷新") }
            TextButton(onClick = { pendingPlan = state.usage }, enabled = !state.busy && state.usage?.let { it.orphanEntries.isNotEmpty() || it.missingRecordIds.isNotEmpty() } == true) { Text("清理残留与无效记录") }
        } })
}
