package com.videodown.douyin.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.domain.model.StorageUsage

/** Running-task entries shown individually; the rest collapse into a count. */
private const val VISIBLE_BUDGET_TASKS = 4

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
                StorageBudgetSection(usage)
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

/**
 * Draws the running-download space budget: a bar of reservations against free space, the largest
 * per-task shares, and the plain-language reason when the budget no longer fits.
 */
@Composable
private fun StorageBudgetSection(usage: StorageUsage) {
    val budget = usage.budget
    val shortfall = storageBudgetShortfall(usage)
    Text(storageBudgetHeadline(budget), modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
    if (!budget.isEmpty) {
        LinearProgressIndicator(progress = { storageBudgetFraction(usage) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(MaterialTheme.shapes.small),
            color = if (shortfall > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        Text(storageBudgetPercent(usage), modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        budget.entries.take(VISIBLE_BUDGET_TASKS).forEach { entry ->
            Text("· ${entry.title.take(24)} 尚需 ${formatBytes(entry.remainingBytes)}", modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        if (budget.entries.size > VISIBLE_BUDGET_TASKS) Text("· 另有 ${budget.entries.size - VISIBLE_BUDGET_TASKS} 个运行任务", modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
    }
    Text(storageBudgetReason(usage), modifier = Modifier.padding(top = 8.dp),
        color = if (shortfall > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (!budget.isEmpty) Text("预算含每个任务 32 MiB 工作余量；已写入部分由可用空间反映，不重复计算。",
        modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
}
