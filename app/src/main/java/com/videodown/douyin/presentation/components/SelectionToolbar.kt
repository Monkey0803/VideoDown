package com.videodown.douyin.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.videodown.douyin.R

/**
 * 多选工具条：三态全选 + 批量动作 + 完成选择。
 *
 * 已存与任务页共用；「全选」的语义由 [SelectAllToggle] 承担，这里只排列动作，
 * 避免两页再各写一套“全选/取消全选”文字按钮。
 */
@Composable
internal fun SelectionToolbar(
    count: Int,
    busy: Boolean,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onExit: () -> Unit,
    actions: List<Pair<String, () -> Unit>>,
) {
    LazyRow(
        modifier = Modifier.testTag("selection-toolbar"),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            SelectAllToggle(
                label = stringResource(R.string.selection_select_all_results),
                summary = stringResource(R.string.selection_count, count),
                allSelected = allSelected,
                indeterminate = count > 0 && !allSelected,
                enabled = !busy,
                onToggle = { onSelectAll() },
            )
        }
        actions.forEach { (label, action) ->
            item { TextButton(onClick = action, enabled = !busy && count > 0) { Text(label) } }
        }
        item { TextButton(onClick = onExit, enabled = !busy) { Text(stringResource(R.string.home_done_selecting)) } }
    }
}
