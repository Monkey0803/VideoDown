package com.videodown.douyin.presentation.history

import com.videodown.douyin.presentation.components.TabPageHeader
import com.videodown.douyin.presentation.components.AppConfirmDialog
import com.videodown.douyin.presentation.components.AppDialog
import com.videodown.douyin.presentation.components.AppDialogButtonRow
import com.videodown.douyin.presentation.components.AppDialogRadioRow
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.components.AdaptivePage

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Checkbox
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.snapshotFlow
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.ReorderableItem
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.videodown.douyin.core.DouyinIntents
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.core.relativeTime
import com.videodown.douyin.core.remainingTime
import com.videodown.douyin.presentation.relativeTimeText
import com.videodown.douyin.presentation.remainingTimeText
import com.videodown.douyin.core.formatSpeed
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.R
import com.videodown.douyin.domain.model.VaultPolicy
import com.videodown.douyin.domain.model.DownloadStatus
import java.io.File
import com.videodown.douyin.domain.model.RecoveryAction
import com.videodown.douyin.domain.model.failureActions
import com.videodown.douyin.presentation.speedLimitText
import com.videodown.douyin.presentation.storedErrorMessage
import com.videodown.douyin.presentation.taskSortLabelRes
import com.videodown.douyin.presentation.library.authorText
import com.videodown.douyin.presentation.failureDescription
import com.videodown.douyin.presentation.theme.appMotion
import com.videodown.douyin.presentation.failureTitle
import com.videodown.douyin.presentation.recoveryActionLabel
import com.videodown.douyin.presentation.UiDensityPolicy
import com.videodown.douyin.domain.model.legacyFailureKind
import com.videodown.douyin.domain.model.isGenericSiteMediaTitle
import java.net.URI

@Composable
fun HistoryScreen(onPlay: (String) -> Unit, viewModel: HistoryViewModel = viewModel(),
    onOpenParse: () -> Unit = {},
    onRecovery: (DownloadRecord, RecoveryAction) -> Unit = { _, _ -> }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val motion = appMotion()
    val taskListState = rememberLazyListState()
    // 拖拽期间按本地顺序显示待下载任务，松手后落盘一次；顺序回流后本地顺序作废。
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    val statusById = state.visibleRecords.associate { it.id to state.statusFor(it) }
    val taskOrder = pendingOrder?.let { order ->
        val byId = state.visibleRecords.associateBy { it.id }
        val orderedPending = order.mapNotNull { byId[it] }
        var next = 0
        state.visibleRecords.map { record ->
            if (statusById[record.id] == DownloadStatus.PENDING) orderedPending.getOrNull(next++) ?: record else record
        }
    } ?: state.visibleRecords
    val context = LocalContext.current
    // 资源按当前组合读取：context.getString 不会随语言/配置切换重算。
    val resources = LocalResources.current
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    var selecting by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingBatchDelete by remember { mutableStateOf<Set<String>?>(null) }
    fun queryChanged(query: TaskQuery) { selectedIds = emptySet(); viewModel.setTaskQuery(query) }
    val visibleIds = state.visibleRecords.map { it.id }.toSet()
    val showManagementControls = UiDensityPolicy.showManagementControls(
        itemCount = state.records.size,
        hasActiveQuery = state.taskQuery.text.isNotBlank() || state.filter != HistoryFilter.ALL,
    )
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.intersect(visibleIds) }
    LaunchedEffect(state.batchResult) {
        val result = state.batchResult ?: return@LaunchedEffect
        if (selecting && result.operation.keepsFailuresSelected) selectedIds = result.failures.map { it.id }.toSet().intersect(visibleIds)
        snackbar.showSnackbar(batchSummary(context, result))
    }
    BackHandler(selecting && !state.batchBusy) { selecting = false; selectedIds = emptySet() }
    pendingBatchDelete?.let { ids ->
        AppConfirmDialog(
            onDismissRequest = { pendingBatchDelete = null },
            icon = AppIcons.Delete,
            title = stringResource(R.string.history_delete_selected_title),
            body = stringResource(R.string.history_delete_selected_body, ids.size),
            confirmText = stringResource(R.string.action_confirm_delete),
            onConfirm = { viewModel.deleteSelected(ids); pendingBatchDelete = null },
            dismissText = stringResource(R.string.action_cancel),
            destructive = true,
        )
    }
    var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    var pendingSpeedLimit by remember { mutableStateOf<DownloadRecord?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.onMessageConsumed() }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(resources.getString(noticeLabel(it))); viewModel.onNoticeConsumed() }
    }
    pendingSpeedLimit?.let { record ->
        AppDialog(
            onDismissRequest = { pendingSpeedLimit = null },
            icon = AppIcons.Speed,
            title = stringResource(R.string.history_speed_limit_title),
            content = {
                Text(stringResource(R.string.history_speed_limit_body, VaultPolicy.title(record, unlocked = false) ?: stringResource(R.string.vault_locked_title)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    (listOf(0) + com.videodown.douyin.domain.model.speedLimitOptions.filter { it > 0 })
                        .forEach { kbps ->
                            AppDialogRadioRow(
                                selected = record.speedLimitKbps == kbps,
                                onSelect = { viewModel.setSpeedLimit(record.id, kbps); pendingSpeedLimit = null },
                            ) {
                                Text(if (kbps == 0) stringResource(R.string.history_speed_follow_global) else speedLimitText(kbps),
                                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                }
            },
            actions = {
                AppDialogButtonRow {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { pendingSpeedLimit = null }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
    pendingDelete?.let { record ->
        AppConfirmDialog(
            onDismissRequest = { pendingDelete = null },
            icon = AppIcons.Delete,
            title = stringResource(R.string.history_delete_title),
            body = stringResource(R.string.history_delete_body, VaultPolicy.title(record, unlocked = false) ?: stringResource(R.string.vault_locked_title)),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { viewModel.delete(record.id); pendingDelete = null },
            dismissText = stringResource(R.string.action_cancel),
            destructive = true,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        AdaptivePage(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                TabPageHeader(stringResource(R.string.history_header_title))
                if (!selecting && state.records.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.history_stats_compact,
                            state.records.count { state.statusFor(it) in listOf(DownloadStatus.RUNNING, DownloadStatus.PENDING) },
                            state.records.count { state.statusFor(it) == DownloadStatus.COMPLETED },
                            state.records.count { state.statusFor(it) in listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED) },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            if (showManagementControls) {
            OutlinedTextField(value = state.taskQuery.text,
                onValueChange = { queryChanged(state.taskQuery.copy(text = it)) },
                // 圆角与「解析」页的链接输入框（shapes.large）一致，而不是 M3 默认的 extraSmall。
                shape = MaterialTheme.shapes.large,
                label = { Text(stringResource(R.string.history_search_hint)) }, singleLine = true, enabled = !state.batchBusy,
                leadingIcon = { AppIcon(AppIcons.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                trailingIcon = { if (state.taskQuery.text.isNotEmpty()) TextButton(onClick = { queryChanged(state.taskQuery.copy(text = "")) }) { Text(stringResource(R.string.action_clear)) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp).testTag("task-search"))
            if (UiDensityPolicy.showQueueSummary(state.queue.runningIds.size, state.queue.pendingIds.size)) {
                Text(stringResource(R.string.history_queue_line, state.queue.maxConcurrent, state.queue.pendingIds.size),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
                if (state.queue.runningIds.size > state.queue.maxConcurrent) Text(stringResource(R.string.history_queue_note), modifier = Modifier.padding(horizontal = 22.dp), style = MaterialTheme.typography.labelSmall)
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(HistoryFilter.entries) { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { selectedIds = emptySet(); viewModel.setFilter(filter) }, enabled = !state.batchBusy,
                        label = { Text(stringResource(filterLabel(filter))) },
                    )
                }
                item {
                    if (state.activeCount > 0) {
                        TextButton(onClick = viewModel::pauseAll, enabled = !state.batchBusy) { Text(stringResource(R.string.history_pause_all)) }
                    }
                    if (state.records.any { state.statusFor(it) in listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED) }) {
                        TextButton(onClick = viewModel::resumeAll, enabled = !state.batchBusy) { Text(stringResource(R.string.history_resume_all)) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.testTag("task-sort")) {
                    TextButton(onClick = { sortExpanded = true }, enabled = !state.batchBusy) {
                        AppIcon(AppIcons.Sort, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(taskSortLabelRes(state.taskQuery.sort)))
                    }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        TaskSort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(stringResource(taskSortLabelRes(sort))) },
                                onClick = {
                                    queryChanged(state.taskQuery.copy(sort = sort))
                                    sortExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }, enabled = !state.batchBusy && state.records.isNotEmpty()) {
                    Text(stringResource(if (selecting) R.string.action_exit_multi_select else R.string.history_multi_select))
                }
            }
            }
            if (selecting) SelectionToolbar(selectedIds.size, state.batchBusy, selectedIds == visibleIds && visibleIds.isNotEmpty(),
                onSelectAll = { selectedIds = if (selectedIds == visibleIds) emptySet() else visibleIds },
                onExit = { selecting = false; selectedIds = emptySet() },
                actions = listOf(
                    stringResource(R.string.history_pause_selected) to { viewModel.pauseSelected(selectedIds) },
                    stringResource(R.string.history_resume_selected) to { viewModel.resumeSelected(selectedIds) },
                    stringResource(R.string.history_delete_selected) to { pendingBatchDelete = selectedIds.toSet() },
                ))
            BatchFailureDetails(state.batchResult)
            if (state.batchBusy) Text(stringResource(R.string.history_working, state.batchDone, state.batchTotal), modifier = Modifier.padding(horizontal = 22.dp))
            if (state.visibleRecords.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text(
                            stringResource(if (state.records.isEmpty()) R.string.history_empty else R.string.history_empty_filtered),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (state.records.isEmpty()) {
                            Text(stringResource(R.string.history_empty_hint), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                            FilledTonalButton(onClick = onOpenParse, modifier = Modifier.padding(top = 16.dp)) {
                                Text(stringResource(R.string.action_go_parse))
                            }
                        } else {
                            FilledTonalButton(onClick = {
                                viewModel.setFilter(HistoryFilter.ALL)
                                queryChanged(TaskQuery())
                            }, modifier = Modifier.padding(top = 16.dp)) {
                                Text(stringResource(R.string.history_clear_query))
                            }
                        }
                    }
                }
            } else {
                // 拖拽只对待下载任务开放：队列顺序对正在下载和已结束的任务没有意义。
                val canReorder = state.taskQuery.sort == TaskSort.STATUS && state.taskQuery.text.isBlank() && !state.batchBusy
                val reorderableState = rememberReorderableLazyListState(taskListState) { from, to ->
                    val id = from.key as? String ?: return@rememberReorderableLazyListState
                    val ids = taskOrder.map { it.id }.toMutableList()
                    val index = ids.indexOf(id)
                    if (index >= 0 && to.index in 0..ids.size) {
                        ids.add(to.index, ids.removeAt(index))
                        // 只有待下载任务带队列顺序，其余记录只是跟着列表一起动。
                        pendingOrder = ids.filter { statusById[it] == DownloadStatus.PENDING }
                    }
                }
                // 松手后落盘一次；顺序回流后本地顺序作废。
                LaunchedEffect(reorderableState) {
                    snapshotFlow { reorderableState.isAnyItemDragging }.collect { dragging ->
                        if (!dragging) pendingOrder?.let { order ->
                            pendingOrder = null
                            viewModel.reorderQueue(order)
                        }
                    }
                }
                LazyColumn(
                    state = taskListState,
                    modifier = Modifier.testTag("task-list"),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    items(taskOrder, key = { it.id }) { record ->
                        // 任务卡随状态进出列表：位置变化用默认曲线，增删用快曲线淡入淡出。
                        ReorderableItem(reorderableState, key = record.id) { _ ->
                        Column(Modifier.animateItem(
                            fadeInSpec = motion.fast(),
                            placementSpec = motion.normal(),
                            fadeOutSpec = motion.fast(),
                        )) {
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
                                onSpeedLimit = { pendingSpeedLimit = record },
                                onMove = { offset -> viewModel.moveInQueue(record.id, offset) },
                                onRecover = { action -> if (action == RecoveryAction.RETRY) viewModel.resume(record.id) else onRecovery(record, action) },
                                onShare = {
                                    DouyinIntents.shareRecord(context, record)
                                },
                                // 只有待下载的任务给出拖拽把手。
                                onDragHandle = if (canReorder && state.statusFor(record) == DownloadStatus.PENDING) {
                                    {
                                        AppIcon(AppIcons.Sort, stringResource(R.string.history_drag_handle),
                                            modifier = Modifier.size(20.dp).draggableHandle().testTag("task-drag-${record.id}"),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else null,
                            )
                        }
                        }
                    }
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
    onSpeedLimit: () -> Unit,
    onShare: () -> Unit,
    onMove: (Int) -> Unit,
    onRecover: (RecoveryAction) -> Unit,
    /** 待下载任务的拖拽把手；其余状态为 null（拖拽只重排待下载区间）。 */
    onDragHandle: (@Composable () -> Unit)? = null,
) {
    val status = state.statusFor(record)
    val progress = state.progressFor(record.id)
    val issue = record.failureKind ?: legacyFailureKind(record.errorMessage)
    val guidance = issue?.let(::failureActions)
    val position = state.queue.position(record.id)
    val hasSourcePage = record.sourcePageUrl.isNotBlank() || !record.media?.pageUrl.isNullOrBlank()
    val guidedRecovery = status in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED) && guidance != null
    var moreExpanded by remember(record.id) { mutableStateOf(false) }
    var retrySeconds by remember(progress?.retryAtMillis) { mutableStateOf(0L) }
    LaunchedEffect(progress?.retryAtMillis) {
        val deadline = progress?.retryAtMillis ?: run { retrySeconds = 0L; return@LaunchedEffect }
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
            retrySeconds = (remaining + 999L) / 1000L
            if (remaining <= 0L) break
            delay(minOf(remaining, 1000L))
        }
    }
    fun canRecover(action: RecoveryAction): Boolean = action != RecoveryAction.NONE &&
        (action !in setOf(RecoveryAction.BROWSER, RecoveryAction.REPARSE) || hasSourcePage)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("task-${record.id}"), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // Resolved once, outside the semantics lambda (which is not a composable scope).
        val lockedTitle = recordTitle(record, unlocked = false)
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selecting) Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = !state.batchBusy,
                    modifier = Modifier.semantics { stateDescription = lockedTitle })
                Box(
                    modifier = Modifier.size(width = 84.dp, height = 52.dp).clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .testTag("task-cover-${record.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(AppIcons.Play, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    AsyncImage(
                        model = record.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.weight(1f)) {
                    // The task list has no vault gate, so a private task never names itself here.
                    Text(lockedTitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sizeText = formatBytes(state.totalFor(record).takeIf { it > 0 } ?: state.downloadedFor(record))
                    Text(
                        when {
                            record.isArchive -> stringResource(R.string.library_archive_entries, record.archiveEntries.size)
                            record.resolutionLabel.isBlank() -> sizeText
                            else -> "${record.resolutionLabel} · $sizeText"
                        },
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
                onDragHandle?.invoke()
                if (!selecting && !guidedRecovery) {
                IconButton(onClick = when (status) {
                    DownloadStatus.COMPLETED -> onPlay
                    DownloadStatus.RUNNING, DownloadStatus.PENDING -> onPause
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> if (guidance != null && guidance.primary != RecoveryAction.RETRY) ({ onRecover(guidance.primary) }) else onResume
                }, enabled = !state.batchBusy && (guidance == null || canRecover(guidance.primary)), modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    AppIcon(when {
                        status == DownloadStatus.COMPLETED && record.isImage -> AppIcons.Grid
                        status == DownloadStatus.COMPLETED && record.isArchive -> AppIcons.ExportFolder
                        status == DownloadStatus.COMPLETED -> AppIcons.Play
                        status == DownloadStatus.RUNNING || status == DownloadStatus.PENDING -> AppIcons.Pause
                        guidance?.primary == RecoveryAction.BROWSER -> AppIcons.Browser
                        guidance?.primary == RecoveryAction.REPARSE -> AppIcons.Parse
                        guidance?.primary == RecoveryAction.STORAGE -> AppIcons.Storage
                        guidance?.primary == RecoveryAction.SETTINGS -> AppIcons.Settings
                        guidance?.primary == RecoveryAction.NONE -> AppIcons.Warning
                        else -> AppIcons.ResumeDownload
                    },
                        contentDescription = when (status) {
                            DownloadStatus.COMPLETED -> stringResource(
                                when {
                                    record.isImage -> R.string.library_click_view_image
                                    record.isArchive -> R.string.library_click_open_archive
                                    else -> R.string.action_play
                                },
                            )
                            DownloadStatus.RUNNING, DownloadStatus.PENDING -> stringResource(R.string.action_pause)
                            else -> guidance?.primary?.let { recoveryActionLabel(it) } ?: stringResource(R.string.action_resume)
                        }, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(17.dp))
                }
                }
                if (!selecting) {
                Box {
                    IconButton(onClick = { moreExpanded = true }, enabled = !state.batchBusy,
                        modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest).testTag("task-more-${record.id}")) {
                        AppIcon(AppIcons.More, stringResource(R.string.history_more_actions),
                            tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                        if (status != DownloadStatus.COMPLETED) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_set_limit)) },
                                leadingIcon = { AppIcon(AppIcons.Speed, null, modifier = Modifier.size(18.dp)) },
                                onClick = { moreExpanded = false; onSpeedLimit() },
                            )
                        }
                        if (status == DownloadStatus.COMPLETED) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_share)) },
                                leadingIcon = { AppIcon(AppIcons.Share, null, modifier = Modifier.size(18.dp)) },
                                onClick = { moreExpanded = false; onShare() },
                            )
                        }
                        if (status != DownloadStatus.RUNNING && status != DownloadStatus.PENDING && guidance?.primary != RecoveryAction.NONE) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_retry_download)) },
                                leadingIcon = { AppIcon(AppIcons.Retry, null, modifier = Modifier.size(18.dp)) },
                                onClick = { moreExpanded = false; onRestart() },
                            )
                        }
                        guidance?.secondary
                            ?.takeIf { it != RecoveryAction.NONE && it != guidance.primary }
                            ?.let { secondary ->
                                DropdownMenuItem(
                                    text = { Text(recoveryActionLabel(secondary)) },
                                    enabled = canRecover(secondary),
                                    onClick = { moreExpanded = false; onRecover(secondary) },
                                )
                            }
                        position?.let { queuedPosition ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_move_up)) },
                                enabled = queuedPosition > 1,
                                onClick = { moreExpanded = false; onMove(-1) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_move_down)) },
                                enabled = queuedPosition < state.queue.pendingIds.size,
                                onClick = { moreExpanded = false; onMove(1) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_move_top)) },
                                enabled = queuedPosition > 1,
                                onClick = { moreExpanded = false; onMove(-Int.MAX_VALUE) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { AppIcon(AppIcons.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                            onClick = { moreExpanded = false; onDelete() },
                        )
                    }
                }
            }
            }
            if (status != DownloadStatus.COMPLETED) {
                // 进度条补间到新比例；百分比文字仍用原始值，断言与读数都不受影响。
                val animatedFraction by animateFloatAsState(
                    targetValue = state.fractionFor(record),
                    animationSpec = appMotion().fast(),
                    label = "task-progress",
                )
                LinearProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(5.dp).clip(MaterialTheme.shapes.small),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${(state.fractionFor(record) * 100).toInt()}% · " +
                        if (status == DownloadStatus.RUNNING && progress?.retryAtMillis != null) {
                            stringResource(R.string.history_waiting_retry, retrySeconds)
                        } else if (status == DownloadStatus.RUNNING) when (progress?.phase ?: record.phase) {
                            com.videodown.douyin.domain.model.TransferPhase.EXTRACTING -> stringResource(R.string.history_phase_extracting)
                            com.videodown.douyin.domain.model.TransferPhase.MERGING -> stringResource(R.string.history_phase_merging)
                            com.videodown.douyin.domain.model.TransferPhase.VERIFYING -> stringResource(R.string.history_phase_verifying)
                            else -> formatSpeed(progress?.bytesPerSecond ?: 0L)
                        } else statusLabel(status),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    // While bytes are actually moving the estimate is more useful than the static
                    // resume hint; the hint returns whenever no estimate is possible.
                    val eta = if (status == DownloadStatus.RUNNING &&
                        (progress?.phase ?: record.phase) == com.videodown.douyin.domain.model.TransferPhase.DOWNLOADING
                    ) remainingTime(state.totalFor(record) - state.downloadedFor(record), progress?.bytesPerSecond ?: 0L) else null
                    when {
                        eta != null -> Text(remainingTimeText(eta), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        record.rangeSupported -> Text(stringResource(R.string.history_range_supported), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (position != null) {
                Text(stringResource(R.string.history_queued_position, position), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            }
            if (guidance != null) {
                Text(failureTitle(issue), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                if (issue == com.videodown.douyin.domain.model.DownloadFailureKind.BACKGROUND_SERVICE) {
                    storedErrorMessage(record.errorMessage)?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
                Text(failureDescription(issue), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                if (!hasSourcePage && (guidance.primary in setOf(RecoveryAction.BROWSER, RecoveryAction.REPARSE) || guidance.secondary in setOf(RecoveryAction.BROWSER, RecoveryAction.REPARSE)))
                    Text(stringResource(R.string.history_no_source), style = MaterialTheme.typography.labelSmall)
                if (!selecting && guidance.primary != RecoveryAction.NONE) {
                    FilledTonalButton(
                        onClick = { onRecover(guidance.primary) },
                        enabled = !state.batchBusy && canRecover(guidance.primary),
                        modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)
                            .testTag("task-primary-recovery-${record.id}"),
                    ) {
                        Text(recoveryActionLabel(guidance.primary))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val limitLabel = record.speedLimitKbps.takeIf { it > 0 }
                    ?.let { stringResource(R.string.history_limit_label, speedLimitText(it)) }
                val meta = (listOf(downloadSourceText(record), relativeTimeText(relativeTime(record.updatedAt))) + listOfNotNull(limitLabel))
                    .joinToString(" · ")
                Text(
                    meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun statusLabel(status: DownloadStatus) = when (status) {
    DownloadStatus.PENDING -> stringResource(R.string.history_status_pending)
    DownloadStatus.RUNNING -> stringResource(R.string.history_status_running)
    DownloadStatus.PAUSED -> stringResource(R.string.history_status_paused)
    DownloadStatus.COMPLETED -> stringResource(R.string.history_status_completed)
    DownloadStatus.FAILED -> stringResource(R.string.history_status_failed)
}

@Composable
private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
    DownloadStatus.RUNNING -> MaterialTheme.colorScheme.primary
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The batch summary line; the ViewModel only hands over the numbers. */
/**
 * The batch summary line; the ViewModel only hands over the numbers.
 *
 * Takes a `Context` instead of being a composable because it is built inside a `LaunchedEffect`.
 */
private fun batchSummary(context: android.content.Context, result: BatchResult): String = buildString {
    append(context.getString(R.string.history_batch_summary, context.getString(contextOperationLabel(result.operation)), result.succeeded))
    if (result.skipped > 0) append(context.getString(R.string.history_batch_skipped, result.skipped))
    if (result.failures.isNotEmpty()) {
        append(context.getString(R.string.history_batch_failed, result.failures.size,
            result.failures.map {
                // The key may name no failure (a generic batch error), which reads as the generic text.
                val failure = com.videodown.douyin.data.gallery.galleryFailureOf(it.galleryFailureKey) ?: com.videodown.douyin.data.gallery.GalleryExportFailure.WRITE_FAILED
                context.getString(com.videodown.douyin.core.galleryFailureRes(failure))
            }.distinct().take(2).joinToString(context.getString(R.string.list_separator))))
    }
}

/** The same mapping as the library's `operationLabel`, usable outside a composable. */
internal fun contextOperationLabel(operation: BatchOperation): Int = when (operation) {
    BatchOperation.DELETE -> R.string.batch_operation_delete
    BatchOperation.PAUSE -> R.string.history_pause_selected
    BatchOperation.RESUME -> R.string.history_resume_selected
    BatchOperation.SAVE_TO_MUSIC -> R.string.library_save_to_music
    BatchOperation.SAVE_TO_GALLERY_AND_MUSIC -> R.string.library_save_to_gallery_music
    BatchOperation.SAVE_TO_GALLERY -> R.string.library_save_to_gallery
}

@Composable
private fun filterLabel(filter: HistoryFilter): Int = when (filter) {
    HistoryFilter.ALL -> R.string.history_filter_all
    HistoryFilter.RUNNING -> R.string.history_filter_running
    HistoryFilter.COMPLETED -> R.string.history_filter_completed
}

private fun noticeLabel(notice: HistoryNotice): Int = when (notice) {
    HistoryNotice.PAUSED -> R.string.history_notice_paused
    HistoryNotice.DELETED -> R.string.history_notice_deleted
    HistoryNotice.CLEARED -> R.string.history_notice_cleared
}

/** The record's title as shown while the vault may be locked. */
@Composable
private fun recordTitle(record: com.videodown.douyin.domain.model.DownloadRecord, unlocked: Boolean): String =
    com.videodown.douyin.domain.model.VaultPolicy.title(record, unlocked)?.let { title ->
        title.takeUnless(::isGenericSiteMediaTitle) ?: stringResource(R.string.sites_unknown_video_title)
    } ?: stringResource(R.string.vault_locked_title)

/** 匿名站点下载用站点名替代“未知作者”，其他平台仍沿用原作者文案。 */
@Composable
private fun downloadSourceText(record: DownloadRecord): String {
    if (record.displayAuthor.isNotBlank()) return record.displayAuthor
    val pageUrl = record.sourcePageUrl.ifBlank { record.media?.pageUrl.orEmpty() }
    val host = runCatching { URI(pageUrl).host?.lowercase() }.getOrNull()
    return if (host == "kubb.tv" || host?.endsWith(".kubb.tv") == true) "KUBB.TV" else authorText("")
}
