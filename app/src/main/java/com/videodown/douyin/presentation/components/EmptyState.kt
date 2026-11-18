package com.videodown.douyin.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 空状态：可选插图 + 标题 + 可说明 + 一枚主操作。
 *
 * 「已存」与「任务」两页原本是同一段居中布局各写一遍；插图只在“整个内容为空”时出现，
 * 被筛选筛空时只留文字与清除筛选按钮，这个区别由 [showIllustration] 表达。
 */
@Composable
internal fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    showIllustration: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            if (showIllustration) EmptyStateIllustration(Modifier.padding(bottom = 20.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            body?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (actionLabel != null && onAction != null) {
                FilledTonalButton(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionLabel) }
            }
        }
    }
}
