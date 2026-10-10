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
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
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
        AlertDialog(onDismissRequest = { pendingPlan = null }, title = { Text(stringResource(R.string.storage_cleanup_title)) },
            text = { Text(stringResource(R.string.storage_cleanup_body, plan.orphanEntries.size,
                formatBytes(plan.reclaimableBytes), plan.missingRecordIds.size)) },
            confirmButton = { TextButton(onClick = { pendingPlan = null; model.clean(plan) }) { Text(stringResource(R.string.storage_cleanup_confirm)) } },
            dismissButton = { TextButton(onClick = { pendingPlan = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() }, title = { Text(stringResource(R.string.storage_title)) },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            state.usage?.let { usage ->
                Text(stringResource(R.string.storage_available, formatBytes(usage.availableBytes)))
                Text(stringResource(R.string.storage_videos, formatBytes(usage.videoBytes)), modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.storage_temporary, formatBytes(usage.temporaryBytes)), modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.storage_reclaimable, formatBytes(usage.reclaimableBytes), usage.orphanEntries.size), modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.storage_missing, usage.missingRecordIds.size), modifier = Modifier.padding(top = 8.dp))
                StorageBudgetSection(usage)
                Text(stringResource(R.string.storage_hint), modifier = Modifier.padding(top = 12.dp))
            }
            if (state.busy) Text(stringResource(R.string.storage_working), modifier = Modifier.padding(top = 12.dp))
            state.notice?.let { Text(storageNoticeText(it), modifier = Modifier.padding(top = 12.dp)) }
        } },
        confirmButton = { TextButton(onClick = onClose, enabled = !state.busy) { Text(stringResource(R.string.action_done)) } },
        dismissButton = { Column {
            TextButton(onClick = model::refresh, enabled = !state.busy) { Text(stringResource(R.string.action_refresh)) }
            TextButton(onClick = { pendingPlan = state.usage }, enabled = !state.busy && state.usage?.let { it.orphanEntries.isNotEmpty() || it.missingRecordIds.isNotEmpty() } == true) { Text(stringResource(R.string.storage_cleanup_action)) }
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
            Text(stringResource(R.string.storage_budget_entry, entry.title.ifBlank { stringResource(R.string.storage_budget_task) }.take(24), formatBytes(entry.remainingBytes)), modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        if (budget.entries.size > VISIBLE_BUDGET_TASKS) Text(stringResource(R.string.storage_budget_more, budget.entries.size - VISIBLE_BUDGET_TASKS), modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
    }
    Text(storageBudgetReason(usage), modifier = Modifier.padding(top = 8.dp),
        color = if (shortfall > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (!budget.isEmpty) Text(stringResource(R.string.storage_budget_note),
        modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
}

/** The wording for a storage action. */
@Composable
private fun storageNoticeText(notice: StorageNotice): String = when (notice) {
    StorageNotice.InspectFailed -> stringResource(R.string.storage_inspect_failed)
    StorageNotice.CleanIncomplete -> stringResource(R.string.storage_clean_incomplete)
    is StorageNotice.Cleaned -> {
        val base = stringResource(
            R.string.storage_cleaned,
            notice.deletedEntries, notice.deletedRecords, formatBytes(notice.freedBytes),
        )
        if (notice.skipped + notice.failed > 0) {
            base + stringResource(R.string.storage_cleaned_detail, notice.skipped, notice.failed)
        } else {
            base
        }
    }
}
