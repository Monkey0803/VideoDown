package com.videodown.douyin.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.videodown.douyin.presentation.theme.BrandColors

/**
 * 封面上的选择标记：48dp 命中区 + 24dp 手绘圆环 / 对勾。
 *
 * 结果选择面板与已存页的网格、列表卡共用同一份画法：未选是白色细环叠一圈深色外描边
 * （纯白环在浅色封面上会消失），选中是品牌橙填充加深色对勾。
 *
 * 只画可见标记：命中区固定 48dp，可见直径由 [markSize] 决定，位置由调用方的
 * `align + padding` 决定——避免出现「48dp 底色圆盘里躺着一枚 20dp 勾选框」这种大色块，
 * 那是 Material `Checkbox` 自带最小触控区被当成可见底形时的形态。
 */
@Composable
internal fun CoverSelectionMark(
    selected: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    markSize: Dp = 24.dp,
) {
    Box(
        modifier = modifier.size(48.dp).clip(CircleShape)
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            // 标题读作内容描述；选中状态由 Checkbox 角色自己朗读，不再塞进 stateDescription。
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(markSize).alpha(if (enabled) 1f else .38f)) {
            val stroke = 2.dp.toPx()
            val radius = (size.minDimension - stroke) / 2
            drawCircle(if (selected) BrandColors.selectionIndicator else Color.White.copy(alpha = .18f), radius)
            // 浅色封面上纯白描边会消失：先画一圈深色外描边，再压白色细线。
            drawCircle(Color.Black.copy(alpha = .45f), radius, style = Stroke(stroke * 2.5f))
            drawCircle(Color.White, radius, style = Stroke(stroke))
            if (selected) {
                val unit = size.width / 24f
                drawLine(BrandColors.onSelectionIndicator, Offset(7 * unit, 12 * unit),
                    Offset(10.5f * unit, 15.5f * unit), stroke, StrokeCap.Round)
                drawLine(BrandColors.onSelectionIndicator, Offset(10.5f * unit, 15.5f * unit),
                    Offset(17 * unit, 8.5f * unit), stroke, StrokeCap.Round)
            }
        }
    }
}
