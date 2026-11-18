package com.videodown.douyin.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/** 三态全选的视觉状态；`allSelected` 优先于 `indeterminate`。 */
internal enum class SelectAllVisual { OFF, INDETERMINATE, ON }

internal fun selectAllVisual(allSelected: Boolean, indeterminate: Boolean): SelectAllVisual = when {
    allSelected -> SelectAllVisual.ON
    indeterminate -> SelectAllVisual.INDETERMINATE
    else -> SelectAllVisual.OFF
}

private fun SelectAllVisual.toToggleableState(): ToggleableState = when (this) {
    SelectAllVisual.ON -> ToggleableState.On
    SelectAllVisual.INDETERMINATE -> ToggleableState.Indeterminate
    SelectAllVisual.OFF -> ToggleableState.Off
}

/**
 * 「全选 / 已选」的唯一实现：三态勾选 + 标签 + 可选摘要。
 *
 * 批量导入、已存/任务选择条与结果选择面板共用；点击整行即切换，勾选框只负责显示状态。
 * 调用方保留自己的摘要文案（解析进度 / 已选数量），这里不复制任何页面文案。
 */
/** 只有勾选框、没有标签的三态选择（用于行级全选，标签由调用方放在别处）。 */
@Composable
internal fun TriStateSelectionCheckbox(
    allSelected: Boolean,
    indeterminate: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    TriStateCheckbox(
        state = selectAllVisual(allSelected, indeterminate).toToggleableState(),
        onClick = { onToggle(!allSelected) },
        enabled = enabled,
        modifier = modifier,
    )
}

@Composable
internal fun SelectAllToggle(
    label: String,
    summary: String?,
    allSelected: Boolean,
    indeterminate: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clip(MaterialTheme.shapes.small)
            .toggleable(
                value = allSelected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle(!allSelected) },
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = selectAllVisual(allSelected, indeterminate).toToggleableState(),
            onClick = null,
            enabled = enabled,
        )
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            summary?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
