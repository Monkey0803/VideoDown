package com.videodown.douyin.presentation.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.R
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.domain.model.StorageUsage
import com.videodown.douyin.presentation.components.AppConfirmDialog
import com.videodown.douyin.presentation.components.AppDialog
import com.videodown.douyin.presentation.components.AppDialogButtonRow
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.theme.appMotion

/** Running-task entries shown individually; the rest collapse into a count. */
private const val VISIBLE_BUDGET_TASKS = 4

/** 临时文件用同色低透明度：与视频同族但明度分开，浅色/深色下都读得出两段。 */
private const val TEMPORARY_ALPHA = 0.38f

/**
 * 存储管理。相比纯文字弹窗：取用空间成为主数值，本应用占用画成比例条，可清理内容独立成分区，
 * 运行任务预算带状态色独立成卡，缺口不再只是一行普通文字。
 *
 * 所有句子仍来自资源，真机测试断言的即用户真实看到的文案。
 */
@Composable
internal fun StorageDialog(model: StorageViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var pendingPlan by remember { mutableStateOf<StorageUsage?>(null) }
    LaunchedEffect(Unit) { model.refresh() }
    pendingPlan?.let { plan ->
        AppConfirmDialog(
            onDismissRequest = { pendingPlan = null },
            icon = AppIcons.Delete,
            title = stringResource(R.string.storage_cleanup_title),
            body = stringResource(R.string.storage_cleanup_body, plan.orphanEntries.size,
                formatBytes(plan.reclaimableBytes), plan.missingRecordIds.size),
            confirmText = stringResource(R.string.storage_cleanup_confirm),
            onConfirm = { pendingPlan = null; model.clean(plan) },
            dismissText = stringResource(R.string.action_cancel),
            destructive = true,
        )
    }
    val usage = state.usage
    AppDialog(
        onDismissRequest = { if (!state.busy) onClose() },
        icon = AppIcons.Storage,
        title = stringResource(R.string.storage_title),
        subtitle = stringResource(R.string.storage_subtitle),
        actions = {
            FilledTonalButton(
                onClick = { pendingPlan = usage },
                enabled = !state.busy && usage?.let { it.orphanEntries.isNotEmpty() || it.missingRecordIds.isNotEmpty() } == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppIcon(AppIcons.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.storage_cleanup_action))
            }
            AppDialogButtonRow {
                TextButton(onClick = model::refresh, enabled = !state.busy) { Text(stringResource(R.string.action_refresh)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose, enabled = !state.busy) { Text(stringResource(R.string.action_done)) }
            }
        },
    ) {
        usage?.let {
            StorageOverviewCard(it)
            StorageCleanupCard(it)
            StorageBudgetSection(it)
            StorageHint()
        }
        if (state.busy) StorageBusyRow()
        state.notice?.let { StorageNoticeCard(it) }
    }
}

/** 可用空间作主数值；应用占用按视频/临时两类画成分段比例条。 */
@Composable
private fun StorageOverviewCard(usage: StorageUsage) {
    val videoBytes = usage.videoBytes.coerceAtLeast(0)
    val temporaryBytes = usage.temporaryBytes.coerceAtLeast(0)
    val footprint = storageFootprintBytes(usage.videoBytes, usage.temporaryBytes)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.storage_available_label), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatBytes(usage.availableBytes), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (footprint > 0L) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().height(10.dp).clip(MaterialTheme.shapes.small)) {
                    val videoShare = storageShareFraction(videoBytes, footprint)
                    val temporaryShare = storageShareFraction(temporaryBytes, footprint)
                    if (videoShare > 0f) Box(Modifier.fillMaxWidth(videoShare).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary))
                    if (temporaryShare > 0f) Box(Modifier.fillMaxWidth(temporaryShare).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = TEMPORARY_ALPHA)))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StorageLegend(MaterialTheme.colorScheme.primary, stringResource(R.string.storage_videos_label),
                    formatBytes(usage.videoBytes), Modifier.weight(1f))
                StorageLegend(MaterialTheme.colorScheme.primary.copy(alpha = TEMPORARY_ALPHA), stringResource(R.string.storage_temporary_label),
                    formatBytes(usage.temporaryBytes), Modifier.weight(1f))
            }
        }
    }
}

/** 比例条图例：色点把标签与对应色块绑在一起。 */
@Composable
private fun StorageLegend(dot: Color, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** 可清理内容：无可清理项时用完成图标，保持正向状态而不是一排零。 */
@Composable
private fun StorageCleanupCard(usage: StorageUsage) {
    val cleanable = usage.orphanEntries.isNotEmpty() || usage.missingRecordIds.isNotEmpty()
    val accent = if (cleanable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(if (cleanable) AppIcons.Warning else AppIcons.Success, null,
                    modifier = Modifier.size(18.dp), tint = accent)
                Text(stringResource(R.string.storage_cleanup_section), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(stringResource(R.string.storage_reclaimable, formatBytes(usage.reclaimableBytes), usage.orphanEntries.size),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.storage_missing, usage.missingRecordIds.size),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 检查/清理进行中，避免长时间扫描看起来像卡死。 */
@Composable
private fun StorageBusyRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.storage_working), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 上一次操作的结果：清理完成用主色，失败用错误色。 */
@Composable
private fun StorageNoticeCard(notice: StorageNotice) {
    val failed = notice is StorageNotice.InspectFailed || notice is StorageNotice.CleanIncomplete
    val container = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = container) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIcon(if (failed) AppIcons.Error else AppIcons.Success, null, modifier = Modifier.size(18.dp), tint = content)
            Text(storageNoticeText(notice), style = MaterialTheme.typography.bodySmall, color = content)
        }
    }
}

/** 说明临时文件的来源与释放空间的位置，刻意保持最轻的字重与颜色。 */
@Composable
private fun StorageHint() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppIcon(AppIcons.Info, null, modifier = Modifier.padding(top = 2.dp).size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.storage_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Draws the running-download space budget: a bar of reservations against free space, the largest
 * per-task shares, and the plain-language reason when the budget no longer fits.
 */
@Composable
private fun StorageBudgetSection(usage: StorageUsage) {
    val budget = usage.budget
    val shortfall = storageBudgetShortfall(usage)
    val accent = if (shortfall > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(AppIcons.Download, null, modifier = Modifier.size(18.dp), tint = accent)
                Text(storageBudgetHeadline(budget), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
            if (!budget.isEmpty) {
                val animatedFraction by animateFloatAsState(
                    targetValue = storageBudgetFraction(usage),
                    animationSpec = appMotion().fast(),
                    label = "storage-budget",
                )
                LinearProgressIndicator(progress = { animatedFraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(8.dp).clip(MaterialTheme.shapes.small),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                Text(storageBudgetPercent(usage), modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                budget.entries.take(VISIBLE_BUDGET_TASKS).forEach { entry ->
                    Text(stringResource(R.string.storage_budget_entry, entry.title.ifBlank { stringResource(R.string.storage_budget_task) }.take(24), formatBytes(entry.remainingBytes)), modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (budget.entries.size > VISIBLE_BUDGET_TASKS) Text(stringResource(R.string.storage_budget_more, budget.entries.size - VISIBLE_BUDGET_TASKS), modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(storageBudgetReason(usage), modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (shortfall > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!budget.isEmpty) Text(stringResource(R.string.storage_budget_note),
                modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
