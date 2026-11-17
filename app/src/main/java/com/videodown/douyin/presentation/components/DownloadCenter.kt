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

/** 全局下载条当前最值得用户处理的状态；完成记录本身留在任务页和已存页。 */
internal enum class DownloadCenterKind { ACTIVE, FAILED, PAUSED, COMPLETED }

internal data class DownloadCenterSummary(
    val kind: DownloadCenterKind,
    val count: Int,
    val progress: Float? = null,
)

/**
 * 将实时进度和持久化记录收敛成一个全局摘要。
 *
 * 优先级固定为进行中 → 失败 → 暂停，避免同一条横幅同时要求用户理解多个动作。
 */
internal fun downloadCenterSummary(
    records: List<DownloadRecord>,
    completedCount: Int = 0,
    statusOf: (DownloadRecord) -> DownloadStatus = DownloadRecord::status,
    fractionOf: (DownloadRecord) -> Float = DownloadRecord::progressFraction,
): DownloadCenterSummary? {
    val active = records.filter { statusOf(it) in setOf(DownloadStatus.RUNNING, DownloadStatus.PENDING) }
    if (active.isNotEmpty()) {
        val measurable = active.mapNotNull { record ->
            fractionOf(record).takeIf { record.totalBytes > 0L || it > 0f }
        }
        return DownloadCenterSummary(
            kind = DownloadCenterKind.ACTIVE,
            count = active.size,
            progress = measurable.takeIf(List<Float>::isNotEmpty)?.average()?.toFloat()?.coerceIn(0f, 1f),
        )
    }
    val failed = records.count { statusOf(it) == DownloadStatus.FAILED }
    if (failed > 0) return DownloadCenterSummary(DownloadCenterKind.FAILED, failed)
    val paused = records.count { statusOf(it) == DownloadStatus.PAUSED }
    if (paused > 0) return DownloadCenterSummary(DownloadCenterKind.PAUSED, paused)
    return completedCount.takeIf { it > 0 }?.let { DownloadCenterSummary(DownloadCenterKind.COMPLETED, it) }
}

@Composable
internal fun DownloadCenterBar(state: HistoryUiState, completedCount: Int, onOpenTasks: () -> Unit) {
    val summary = downloadCenterSummary(state.records, completedCount, state::statusFor, state::fractionFor) ?: return
    val title = when (summary.kind) {
        DownloadCenterKind.ACTIVE -> stringResource(R.string.download_center_active, summary.count)
        DownloadCenterKind.FAILED -> stringResource(R.string.download_center_failed, summary.count)
        DownloadCenterKind.PAUSED -> stringResource(R.string.download_center_paused, summary.count)
        DownloadCenterKind.COMPLETED -> stringResource(R.string.download_center_completed, summary.count)
    }
    val subtitle = when (summary.kind) {
        DownloadCenterKind.ACTIVE -> summary.progress?.let {
            stringResource(R.string.download_center_progress, (it * 100).toInt())
        } ?: stringResource(R.string.download_center_preparing)
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenTasks)
            .testTag("download-center"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppIcon(AppIcons.Forward, stringResource(R.string.home_view_tasks), Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
