package com.videodown.douyin.presentation.history

import com.videodown.douyin.presentation.components.TabPageHeader
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.videodown.douyin.presentation.library.SelectionToolbar
import com.videodown.douyin.presentation.library.BatchFailureDetails
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.videodown.douyin.core.DouyinIntents
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.core.formatEta
import com.videodown.douyin.core.formatRelativeTime
import com.videodown.douyin.core.formatSpeed
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import java.io.File
import com.videodown.douyin.domain.model.RecoveryAction
import com.videodown.douyin.domain.model.failureGuidance
import com.videodown.douyin.domain.model.legacyFailureKind

@Composable
fun HistoryScreen(onPlay: (String) -> Unit, viewModel: HistoryViewModel = viewModel(),
    onRecovery: (DownloadRecord, RecoveryAction) -> Unit = { _, _ -> }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingBatchDelete by remember { mutableStateOf<Set<String>?>(null) }
    fun queryChanged(query: TaskQuery) { selectedIds = emptySet(); viewModel.setTaskQuery(query) }
    val visibleIds = state.visibleRecords.map { it.id }.toSet()
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.intersect(visibleIds) }
    LaunchedEffect(state.batchResult) {
        val result = state.batchResult ?: return@LaunchedEffect
        if (selecting && result.operation == "删除") selectedIds = result.failures.map { it.id }.toSet().intersect(visibleIds)
    }
    BackHandler(selecting && !state.batchBusy) { selecting = false; selectedIds = emptySet() }
    pendingBatchDelete?.let { ids ->
        AlertDialog(onDismissRequest = { pendingBatchDelete = null }, title = { Text("删除所选任务？") },
            text = { Text("将删除所选 ${ids.size} 项记录和本地文件，无法恢复。相册副本不受影响。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteSelected(ids); pendingBatchDelete = null }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { pendingBatchDelete = null }) { Text("取消") } })
    }
    var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.onMessageConsumed() }
    }
    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除下载任务？") },
            text = { Text("将删除“${record.title}”的记录和已下载文件。") },
            confirmButton = { TextButton(onClick = { viewModel.delete(record.id); pendingDelete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                TabPageHeader("下载任务")
                if (!selecting) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(state.records.count { state.statusFor(it) in listOf(DownloadStatus.RUNNING, DownloadStatus.PENDING) }, "进行中", Modifier.weight(1f))
                    StatCard(state.records.count { state.statusFor(it) == DownloadStatus.COMPLETED }, "已完成", Modifier.weight(1f))
                    StatCard(state.records.count { state.statusFor(it) in listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED) }, "可续传", Modifier.weight(1f))
                }
            }
            OutlinedTextField(value = state.taskQuery.text,
                onValueChange = { queryChanged(state.taskQuery.copy(text = it)) },
                label = { Text("搜索标题或作者") }, singleLine = true, enabled = !state.batchBusy,
                leadingIcon = { AppIcon(AppIcons.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                trailingIcon = { if (state.taskQuery.text.isNotEmpty()) TextButton(onClick = { queryChanged(state.taskQuery.copy(text = "")) }) { Text("清除") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp).testTag("task-search"))
            Text("同时下载 ${state.queue.maxConcurrent} 个 · ${state.queue.pendingIds.size} 个排队",
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            if (state.queue.runningIds.size > state.queue.maxConcurrent) Text("正在运行的任务继续完成，新任务等待名额", modifier = Modifier.padding(horizontal = 22.dp), style = MaterialTheme.typography.labelSmall)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(HistoryFilter.entries) { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { selectedIds = emptySet(); viewModel.setFilter(filter) }, enabled = !state.batchBusy,
                        label = { Text(filter.label) },
                    )
                }
                item {
                    if (state.activeCount > 0) {
                        TextButton(onClick = viewModel::pauseAll, enabled = !state.batchBusy) { Text("全部暂停") }
                    }
                    if (state.records.any { state.statusFor(it) in listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED) }) {
                        TextButton(onClick = viewModel::resumeAll, enabled = !state.batchBusy) { Text("全部继续") }
                    }
                }
            }
            LazyRow(
                modifier = Modifier.testTag("task-sort"),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    Text("排序", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(TaskSort.entries) { sort ->
                    FilterChip(selected = state.taskQuery.sort == sort, enabled = !state.batchBusy,
                        onClick = { queryChanged(state.taskQuery.copy(sort = sort)) }, label = { Text(sort.label) })
                }
            }
            TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }, enabled = !state.batchBusy && state.records.isNotEmpty(),
                modifier = Modifier.padding(horizontal = 16.dp)) { Text(if (selecting) "取消多选" else "多选任务") }
            if (selecting) SelectionToolbar(selectedIds.size, state.batchBusy, selectedIds == visibleIds && visibleIds.isNotEmpty(),
                onSelectAll = { selectedIds = if (selectedIds == visibleIds) emptySet() else visibleIds },
                onExit = { selecting = false; selectedIds = emptySet() },
                actions = listOf("暂停所选" to { viewModel.pauseSelected(selectedIds) },
                    "继续所选" to { viewModel.resumeSelected(selectedIds) }, "删除所选" to { pendingBatchDelete = selectedIds.toSet() }))
            BatchFailureDetails(state.batchResult)
            if (state.batchBusy) Text("正在处理 ${state.batchDone}/${state.batchTotal}…", modifier = Modifier.padding(horizontal = 22.dp))
            if (state.visibleRecords.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (state.records.isEmpty()) "这里还没有任务" else "没有符合条件的任务",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.testTag("task-list"),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    items(state.visibleRecords, key = { it.id }) { record ->
                        TaskCard(
                            record = record,
                            selecting = selecting,
                            selected = record.id in selectedIds,
                            onToggle = { selectedIds = if (record.id in selectedIds) selectedIds - record.id else selectedIds + record.id },
                            state = state,
                            onPlay = { onPlay(record.id) },
                            onPause = { viewModel.pause(record.id) },
                            onResume = { viewModel.resume(record.id) },
                            onRestart = { viewModel.restart(record.id) },
                            onDelete = { pendingDelete = record },
                            onMove = { offset -> viewModel.moveInQueue(record.id, offset) },
                            onRecover = { action -> if (action == RecoveryAction.RETRY) viewModel.resume(record.id) else onRecovery(record, action) },
                            onShare = {
                                record.filePath?.let(::File)?.let { DouyinIntents.shareFile(context, it) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(count: Int, label: String, modifier: Modifier) {
    Card(modifier, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TaskCard(
    record: DownloadRecord,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    state: HistoryUiState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onMove: (Int) -> Unit,
    onRecover: (RecoveryAction) -> Unit,
) {
    val status = state.statusFor(record)
    val progress = state.progressFor(record.id)
    val issue = record.failureKind ?: legacyFailureKind(record.errorMessage)
    val guidance = issue?.let(::failureGuidance)
    val position = state.queue.position(record.id)
    val hasSourcePage = record.sourcePageUrl.isNotBlank() || !record.media?.pageUrl.isNullOrBlank()
    fun canRecover(action: RecoveryAction): Boolean = action != RecoveryAction.NONE &&
        (action !in setOf(RecoveryAction.BROWSER, RecoveryAction.REPARSE) || hasSourcePage)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("task-${record.id}"), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selecting) Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = !state.batchBusy,
                    modifier = Modifier.semantics { stateDescription = record.title })
                AsyncImage(
                    model = record.coverUrl, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 65.dp, height = 75.dp).clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Column(Modifier.weight(1f)) {
                    Text(record.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${record.resolutionLabel} · ${formatBytes(state.totalFor(record).takeIf { it > 0 } ?: state.downloadedFor(record))}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
                    Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppIcon(when (status) {
                            DownloadStatus.PENDING -> AppIcons.Queue
                            DownloadStatus.RUNNING -> AppIcons.Download
                            DownloadStatus.PAUSED -> AppIcons.Pause
                            DownloadStatus.COMPLETED -> AppIcons.Success
                            DownloadStatus.FAILED -> AppIcons.Error
                        }, null, modifier = Modifier.size(14.dp), tint = statusColor(status))
                        Text(statusLabel(status), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            color = statusColor(status))
                    }
                }
                if (!selecting) {
                IconButton(onClick = when (status) {
                    DownloadStatus.COMPLETED -> onPlay
                    DownloadStatus.RUNNING, DownloadStatus.PENDING -> onPause
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> if (guidance != null && guidance.action != RecoveryAction.RETRY) ({ onRecover(guidance.action) }) else onResume
                }, enabled = !state.batchBusy && (guidance == null || canRecover(guidance.action)), modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    AppIcon(when {
                        status == DownloadStatus.COMPLETED -> AppIcons.Play
                        status == DownloadStatus.RUNNING || status == DownloadStatus.PENDING -> AppIcons.Pause
                        guidance?.action == RecoveryAction.BROWSER -> AppIcons.Browser
                        guidance?.action == RecoveryAction.REPARSE -> AppIcons.Parse
                        guidance?.action == RecoveryAction.STORAGE -> AppIcons.Storage
                        guidance?.action == RecoveryAction.SETTINGS -> AppIcons.Settings
                        guidance?.action == RecoveryAction.NONE -> AppIcons.Warning
                        else -> AppIcons.ResumeDownload
                    },
                        contentDescription = when (status) {
                            DownloadStatus.COMPLETED -> "播放"
                            DownloadStatus.RUNNING, DownloadStatus.PENDING -> "暂停"
                            else -> guidance?.action?.label ?: "继续"
                        }, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onDelete, enabled = !state.batchBusy, modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    AppIcon(AppIcons.Delete, "删除", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(17.dp))
                }
                }
            }
            if (status != DownloadStatus.COMPLETED) {
                LinearProgressIndicator(
                    progress = { state.fractionFor(record) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(5.dp).clip(MaterialTheme.shapes.small),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${(state.fractionFor(record) * 100).toInt()}% · " +
                        if (status == DownloadStatus.RUNNING) when (progress?.phase ?: record.phase) {
                            com.videodown.douyin.domain.model.TransferPhase.EXTRACTING -> "正在提取音频"
                            com.videodown.douyin.domain.model.TransferPhase.MERGING -> "正在合并音视频"
                            com.videodown.douyin.domain.model.TransferPhase.VERIFYING -> "正在校验文件"
                            else -> formatSpeed(progress?.bytesPerSecond ?: 0L)
                        } else statusLabel(status),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    // While bytes are actually moving the estimate is more useful than the static
                    // resume hint; the hint returns whenever no estimate is possible.
                    val eta = if (status == DownloadStatus.RUNNING &&
                        (progress?.phase ?: record.phase) == com.videodown.douyin.domain.model.TransferPhase.DOWNLOADING
                    ) formatEta(state.totalFor(record) - state.downloadedFor(record), progress?.bytesPerSecond ?: 0L) else null
                    when {
                        eta != null -> Text(eta, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        record.rangeSupported -> Text("支持断点续传", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (position != null) {
                Text("排队第 $position 个 · 等待空闲下载名额", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                if (!selecting) LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { TextButton(onClick = { onMove(-1) }, enabled = position > 1 && !state.batchBusy) { Text("前移") } }
                    item { TextButton(onClick = { onMove(1) }, enabled = position < state.queue.pendingIds.size && !state.batchBusy) { Text("后移") } }
                    item { TextButton(onClick = { onMove(-Int.MAX_VALUE) }, enabled = position > 1 && !state.batchBusy) { Text("优先下载") } }
                }
            }
            if (guidance != null) {
                Text(guidance.title, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                if (issue == com.videodown.douyin.domain.model.DownloadFailureKind.BACKGROUND_SERVICE) {
                    record.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
                Text(guidance.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                if (!hasSourcePage && (guidance.action in setOf(RecoveryAction.BROWSER, RecoveryAction.REPARSE) || guidance.secondaryAction in setOf(RecoveryAction.BROWSER, RecoveryAction.REPARSE)))
                    Text("原始网址未保留，请在解析页重新粘贴视频链接。", style = MaterialTheme.typography.labelSmall)
                if (!selecting) LazyRow {
                    listOfNotNull(guidance.action.takeUnless { it == RecoveryAction.NONE }, guidance.secondaryAction).forEach { action ->
                        item { TextButton(onClick = { onRecover(action) }, enabled = !state.batchBusy && canRecover(action)) { Text(action.label) } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${record.displayAuthor} · ${formatRelativeTime(record.updatedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!selecting && status == DownloadStatus.COMPLETED) {
                    IconButton(onClick = onShare, enabled = !state.batchBusy, modifier = Modifier.size(48.dp)) {
                        AppIcon(AppIcons.Share, "分享", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                    }
                }
                if (!selecting && status != DownloadStatus.RUNNING && status != DownloadStatus.PENDING && guidance?.action != RecoveryAction.NONE) {
                    IconButton(onClick = onRestart, enabled = !state.batchBusy, modifier = Modifier.size(48.dp)) {
                        AppIcon(AppIcons.Retry, "重新下载", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: DownloadStatus) = when (status) {
    DownloadStatus.PENDING -> "等待中"
    DownloadStatus.RUNNING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.COMPLETED -> "已完成"
    DownloadStatus.FAILED -> "下载失败"
}

@Composable
private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
    DownloadStatus.RUNNING -> MaterialTheme.colorScheme.primary
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
