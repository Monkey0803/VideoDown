package com.videodown.douyin.presentation.history

import com.videodown.douyin.presentation.components.TabPageHeader
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
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
import com.videodown.douyin.presentation.failureTitle
import com.videodown.douyin.presentation.recoveryActionLabel
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
        if (selecting && result.operation.keepsFailuresSelected) selectedIds = result.failures.map { it.id }.toSet().intersect(visibleIds)
        snackbar.showSnackbar(batchSummary(context, result))
    }
    BackHandler(selecting && !state.batchBusy) { selecting = false; selectedIds = emptySet() }
    pendingBatchDelete?.let { ids ->
        AlertDialog(onDismissRequest = { pendingBatchDelete = null }, title = { Text(stringResource(R.string.history_delete_selected_title)) },
            text = { Text(stringResource(R.string.history_delete_selected_body, ids.size)) },
            confirmButton = { TextButton(onClick = { viewModel.deleteSelected(ids); pendingBatchDelete = null }) { Text(stringResource(R.string.action_confirm_delete)) } },
            dismissButton = { TextButton(onClick = { pendingBatchDelete = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    var pendingSpeedLimit by remember { mutableStateOf<DownloadRecord?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.onMessageConsumed() }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(context.getString(noticeLabel(it))); viewModel.onNoticeConsumed() }
    }
    pendingSpeedLimit?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingSpeedLimit = null },
            title = { Text(stringResource(R.string.history_speed_limit_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(stringResource(R.string.history_speed_limit_body, VaultPolicy.title(record, unlocked = false) ?: stringResource(R.string.vault_locked_title)),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                (listOf(0) + com.videodown.douyin.domain.model.speedLimitOptions.filter { it > 0 })
                    .forEach { kbps ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(selected = record.speedLimitKbps == kbps, role = Role.RadioButton,
                                onClick = { viewModel.setSpeedLimit(record.id, kbps); pendingSpeedLimit = null })
                            .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = record.speedLimitKbps == kbps, onClick = null)
                            Text(if (kbps == 0) stringResource(R.string.history_speed_follow_global) else speedLimitText(kbps),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
            } },
            confirmButton = { TextButton(onClick = { pendingSpeedLimit = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_body, VaultPolicy.title(record, unlocked = false) ?: stringResource(R.string.vault_locked_title))) },
            confirmButton = { TextButton(onClick = { viewModel.delete(record.id); pendingDelete = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                TabPageHeader(stringResource(R.string.history_header_title))
                if (!selecting) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(state.records.count { state.statusFor(it) in listOf(DownloadStatus.RUNNING, DownloadStatus.PENDING) }, stringResource(R.string.history_stat_running), Modifier.weight(1f))
                    StatCard(state.records.count { state.statusFor(it) == DownloadStatus.COMPLETED }, stringResource(R.string.history_stat_done), Modifier.weight(1f))
                    StatCard(state.records.count { state.statusFor(it) in listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED) }, stringResource(R.string.history_stat_resumable), Modifier.weight(1f))
                }
            }
            OutlinedTextField(value = state.taskQuery.text,
                onValueChange = { queryChanged(state.taskQuery.copy(text = it)) },
                label = { Text(stringResource(R.string.history_search_hint)) }, singleLine = true, enabled = !state.batchBusy,
                leadingIcon = { AppIcon(AppIcons.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                trailingIcon = { if (state.taskQuery.text.isNotEmpty()) TextButton(onClick = { queryChanged(state.taskQuery.copy(text = "")) }) { Text(stringResource(R.string.action_clear)) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp).testTag("task-search"))
            Text(stringResource(R.string.history_queue_line, state.queue.maxConcurrent, state.queue.pendingIds.size),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            if (state.queue.runningIds.size > state.queue.maxConcurrent) Text(stringResource(R.string.history_queue_note), modifier = Modifier.padding(horizontal = 22.dp), style = MaterialTheme.typography.labelSmall)
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
            LazyRow(
                modifier = Modifier.testTag("task-sort"),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    Text(stringResource(R.string.history_sort), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(TaskSort.entries) { sort ->
                    FilterChip(selected = state.taskQuery.sort == sort, enabled = !state.batchBusy,
                        onClick = { queryChanged(state.taskQuery.copy(sort = sort)) }, label = { Text(stringResource(taskSortLabelRes(sort))) })
                }
            }
            TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }, enabled = !state.batchBusy && state.records.isNotEmpty(),
                modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(if (selecting) R.string.action_exit_multi_select else R.string.history_multi_select)) }
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
                    Text(stringResource(if (state.records.isEmpty()) R.string.history_empty else R.string.history_empty_filtered),
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
                            onSpeedLimit = { pendingSpeedLimit = record },
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
    onSpeedLimit: () -> Unit,
    onShare: () -> Unit,
    onMove: (Int) -> Unit,
    onRecover: (RecoveryAction) -> Unit,
) {
    val status = state.statusFor(record)
    val progress = state.progressFor(record.id)
    val issue = record.failureKind ?: legacyFailureKind(record.errorMessage)
    val guidance = issue?.let(::failureActions)
    val position = state.queue.position(record.id)
    val hasSourcePage = record.sourcePageUrl.isNotBlank() || !record.media?.pageUrl.isNullOrBlank()
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
                AsyncImage(
                    model = record.coverUrl, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 65.dp, height = 75.dp).clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Column(Modifier.weight(1f)) {
                    // The task list has no vault gate, so a private task never names itself here.
                    Text(lockedTitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
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
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> if (guidance != null && guidance.primary != RecoveryAction.RETRY) ({ onRecover(guidance.primary) }) else onResume
                }, enabled = !state.batchBusy && (guidance == null || canRecover(guidance.primary)), modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    AppIcon(when {
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
                            DownloadStatus.COMPLETED -> stringResource(R.string.action_play)
                            DownloadStatus.RUNNING, DownloadStatus.PENDING -> stringResource(R.string.action_pause)
                            else -> guidance?.primary?.let { recoveryActionLabel(it) } ?: stringResource(R.string.action_resume)
                        }, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onDelete, enabled = !state.batchBusy, modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    AppIcon(AppIcons.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(17.dp))
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
                if (!selecting) LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { TextButton(onClick = { onMove(-1) }, enabled = position > 1 && !state.batchBusy) { Text(stringResource(R.string.history_move_up)) } }
                    item { TextButton(onClick = { onMove(1) }, enabled = position < state.queue.pendingIds.size && !state.batchBusy) { Text(stringResource(R.string.history_move_down)) } }
                    item { TextButton(onClick = { onMove(-Int.MAX_VALUE) }, enabled = position > 1 && !state.batchBusy) { Text(stringResource(R.string.history_move_top)) } }
                }
            }
            if (guidance != null) {
                Text(failureTitle(issue), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                if (issue == com.videodown.douyin.domain.model.DownloadFailureKind.BACKGROUND_SERVICE) {
                    storedErrorMessage(record.errorMessage)?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
                Text(failureDescription(issue), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                if (!hasSourcePage && (guidance.primary in setOf(RecoveryAction.BROWSER, RecoveryAction.REPARSE) || guidance.secondary in setOf(RecoveryAction.BROWSER, RecoveryAction.REPARSE)))
                    Text(stringResource(R.string.history_no_source), style = MaterialTheme.typography.labelSmall)
                if (!selecting) LazyRow {
                    listOfNotNull(guidance.primary.takeUnless { it == RecoveryAction.NONE }, guidance.secondary).forEach { action ->
                        item { TextButton(onClick = { onRecover(action) }, enabled = !state.batchBusy && canRecover(action)) { Text(recoveryActionLabel(action)) } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val limitLabel = record.speedLimitKbps.takeIf { it > 0 }
                    ?.let { stringResource(R.string.history_limit_label, speedLimitText(it)) }
                val meta = (listOf(authorText(record.displayAuthor), relativeTimeText(relativeTime(record.updatedAt))) + listOfNotNull(limitLabel))
                    .joinToString(" · ")
                Text(
                    meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!selecting && status != DownloadStatus.COMPLETED) {
                    IconButton(onClick = onSpeedLimit, enabled = !state.batchBusy, modifier = Modifier.size(48.dp)) {
                        AppIcon(AppIcons.Speed,
                            record.speedLimitKbps.takeIf { it > 0 }
                                ?.let { stringResource(R.string.history_limit_label, speedLimitText(it)) } ?: stringResource(R.string.history_set_limit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                    }
                }
                if (!selecting && status == DownloadStatus.COMPLETED) {
                    IconButton(onClick = onShare, enabled = !state.batchBusy, modifier = Modifier.size(48.dp)) {
                        AppIcon(AppIcons.Share, stringResource(R.string.action_share), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                    }
                }
                if (!selecting && status != DownloadStatus.RUNNING && status != DownloadStatus.PENDING && guidance?.primary != RecoveryAction.NONE) {
                    IconButton(onClick = onRestart, enabled = !state.batchBusy, modifier = Modifier.size(48.dp)) {
                        AppIcon(AppIcons.Retry, stringResource(R.string.action_retry_download), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                    }
                }
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
    com.videodown.douyin.domain.model.VaultPolicy.title(record, unlocked) ?: stringResource(R.string.vault_locked_title)
