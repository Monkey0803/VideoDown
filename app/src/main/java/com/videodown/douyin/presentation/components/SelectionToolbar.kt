package com.videodown.douyin.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
 * 多选工具条：三态全选 + 可横向滚动的批量动作 + 固定的「完成」。
 *
 * 「完成」必须固定：动作多时它以前会被挤出屏幕，用户只能回到顶部找另一个退出入口。
 * 动作仍可横向滚动，避免在窄屏/大字体下把主操作裁掉。
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
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag("selection-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectAllToggle(
            label = stringResource(R.string.selection_select_all_results),
            summary = stringResource(R.string.selection_count, count),
            allSelected = allSelected,
            indeterminate = count > 0 && !allSelected,
            enabled = !busy,
            onToggle = { onSelectAll() },
            modifier = Modifier.padding(start = 8.dp),
        )
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(actions) { (label, action) ->
                TextButton(onClick = action, enabled = !busy && count > 0) { Text(label) }
            }
        }
        TextButton(
            onClick = onExit,
            enabled = !busy,
            modifier = Modifier.testTag("selection-done"),
        ) { Text(stringResource(R.string.home_done_selecting)) }
    }
}
