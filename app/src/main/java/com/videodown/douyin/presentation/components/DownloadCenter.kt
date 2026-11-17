package com.videodown.douyin.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.videodown.douyin.R
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.history.HistoryUiState
import com.videodown.douyin.presentation.theme.AppSpacing

/** 全局下载条当前最值得用户处理的状态；完成记录本身留在任务页和已存页。 */
internal enum class DownloadCenterKind { ACTIVE, FAILED, PAUSED, COMPLETED }

internal data class DownloadCenterSummary(
    val kind: DownloadCenterKind,
    val count: Int,
    val progress: Float? = null,
    val failedCount: Int = 0,
    val pausedCount: Int = 0,
)

/**
 * 将实时进度和持久化记录收敛成一个全局摘要。
 *
 * 优先级固定为进行中 → 失败 → 暂停，避免同一条横幅同时要求用户理解多个动作。
 */
internal fun downloadCenterSummary(
    records: List<DownloadRecord>,
    failedCount: Int = 0,
    pausedCount: Int = 0,
    completedCount: Int = 0,
    statusOf: (DownloadRecord) -> DownloadStatus = DownloadRecord::status,
    downloadedOf: (DownloadRecord) -> Long = DownloadRecord::downloadedBytes,
    totalOf: (DownloadRecord) -> Long = DownloadRecord::totalBytes,
): DownloadCenterSummary? {
    val active = records.filter { statusOf(it) in setOf(DownloadStatus.RUNNING, DownloadStatus.PENDING) }
    if (active.isNotEmpty()) {
        val measurable = active.all { totalOf(it) > 0L }
        val total = if (measurable) active.sumOf(totalOf) else 0L
        val downloaded = if (measurable) active.sumOf { downloadedOf(it).coerceIn(0L, totalOf(it)) } else 0L
        return DownloadCenterSummary(
            kind = DownloadCenterKind.ACTIVE,
            count = active.size,
            progress = total.takeIf { it > 0L }?.let { (downloaded.toDouble() / it).toFloat().coerceIn(0f, 1f) },
            failedCount = failedCount,
            pausedCount = pausedCount,
        )
    }
    if (failedCount > 0) return DownloadCenterSummary(DownloadCenterKind.FAILED, failedCount, pausedCount = pausedCount)
    if (pausedCount > 0) return DownloadCenterSummary(DownloadCenterKind.PAUSED, pausedCount)
    return completedCount.takeIf { it > 0 }?.let { DownloadCenterSummary(DownloadCenterKind.COMPLETED, it) }
}

@Composable
internal fun DownloadCenterBar(
    state: HistoryUiState,
    failedCount: Int,
    pausedCount: Int,
    completedCount: Int,
    onOpenTasks: () -> Unit,
    onOpenLibrary: () -> Unit,
    onDismissResults: () -> Unit,
) {
    val summary = downloadCenterSummary(
        records = state.records,
        failedCount = failedCount,
        pausedCount = pausedCount,
        completedCount = completedCount,
        statusOf = state::statusFor,
        downloadedOf = state::downloadedFor,
        totalOf = state::totalFor,
    ) ?: return
    val title = when (summary.kind) {
        DownloadCenterKind.ACTIVE -> stringResource(R.string.download_center_active, summary.count)
        DownloadCenterKind.FAILED -> stringResource(R.string.download_center_failed, summary.count)
        DownloadCenterKind.PAUSED -> stringResource(R.string.download_center_paused, summary.count)
        DownloadCenterKind.COMPLETED -> stringResource(R.string.download_center_completed, summary.count)
    }
    val activeStatus = summary.progress?.let {
            stringResource(R.string.download_center_progress, (it * 100).toInt())
        } ?: stringResource(R.string.download_center_preparing)
    val attention = listOfNotNull(
        summary.failedCount.takeIf { it > 0 }?.let { stringResource(R.string.download_center_failed_short, it) },
        summary.pausedCount.takeIf { it > 0 }?.let { stringResource(R.string.download_center_paused_short, it) },
    ).joinToString(stringResource(R.string.list_separator))
    val subtitle = when (summary.kind) {
        DownloadCenterKind.ACTIVE -> if (attention.isEmpty()) activeStatus
            else stringResource(R.string.download_center_active_with_attention, activeStatus, attention)
        DownloadCenterKind.FAILED -> stringResource(R.string.download_center_failed_hint)
        DownloadCenterKind.PAUSED -> stringResource(R.string.download_center_paused_hint)
        DownloadCenterKind.COMPLETED -> stringResource(R.string.download_center_completed_hint)
    }
    val icon = when (summary.kind) {
        DownloadCenterKind.ACTIVE -> AppIcons.Download
        DownloadCenterKind.FAILED -> AppIcons.Error
        DownloadCenterKind.PAUSED -> AppIcons.Pause
        DownloadCenterKind.COMPLETED -> AppIcons.Success
    }
    val open = if (summary.kind == DownloadCenterKind.COMPLETED) onOpenLibrary else onOpenTasks
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().testTag("download-center"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    start = AppSpacing.md,
                    top = AppSpacing.xs,
                    end = AppSpacing.xs,
                    bottom = AppSpacing.xs,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                AppIcon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = open)
                    .padding(vertical = AppSpacing.xxs)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (summary.kind == DownloadCenterKind.ACTIVE) {
                    IconButton(onClick = open, modifier = Modifier.size(48.dp)) {
                        AppIcon(AppIcons.Forward, stringResource(R.string.home_view_tasks), Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    IconButton(onClick = onDismissResults, modifier = Modifier.size(48.dp).testTag("download-center-dismiss")) {
                        AppIcon(AppIcons.Close, stringResource(R.string.action_close), Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (summary.kind == DownloadCenterKind.ACTIVE && summary.progress != null) {
                LinearProgressIndicator(
                    progress = { summary.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).testTag("download-center-progress"),
                )
            } else {
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}
