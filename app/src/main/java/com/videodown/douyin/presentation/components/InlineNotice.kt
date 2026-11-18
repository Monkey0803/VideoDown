package com.videodown.douyin.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 内联提示的语气；容器与文字颜色由组件统一选，不再各页面自己配。 */
internal enum class NoticeTone { NEUTRAL, INFO, ERROR }

/**
 * 页面内联提示条：图标 + 可选标题 + 正文 + 可选动作。
 *
 * 批量导入的提交结果、存储清理结果与首页剪贴板提示共用；它是“说明 + 动作”，
 * 与 [AppConfirmDialog] 的弹窗职责不同，不把两者混成一个东西。
 */
@Composable
internal fun InlineNotice(
    message: String,
    modifier: Modifier = Modifier,
    icon: AppIcons? = null,
    title: String? = null,
    tone: NoticeTone = NoticeTone.NEUTRAL,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val container = when (tone) {
        NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh
        NoticeTone.INFO -> MaterialTheme.colorScheme.primaryContainer
        NoticeTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeTone.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
        NoticeTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = container) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                icon?.let { AppIcon(it, null, modifier = Modifier.size(18.dp), tint = content) }
                Column(Modifier.weight(1f)) {
                    title?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = content)
                    }
                    Text(message, style = MaterialTheme.typography.bodySmall, color = content)
                }
            }
            if (actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}
