package com.videodown.douyin.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
internal fun SelectionToolbar(count: Int, busy: Boolean, allSelected: Boolean, onSelectAll: () -> Unit,
    onExit: () -> Unit, actions: List<Pair<String, () -> Unit>>) {
    LazyRow(modifier = Modifier.testTag("selection-toolbar"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        item { Text("已选 $count 项") }
        item { TextButton(onClick = onSelectAll, enabled = !busy) { Text(if (allSelected) "取消全选" else "全选当前结果") } }
        actions.forEach { (label, action) -> item { TextButton(onClick = action, enabled = !busy && count > 0) { Text(label) } } }
        item { TextButton(onClick = onExit, enabled = !busy) { Text("完成选择") } }
    }
}

@Composable
internal fun BatchFailureDetails(result: com.videodown.douyin.presentation.history.BatchResult?) {
    if (result == null || result.failures.isEmpty()) return
    var showDetails by androidx.compose.runtime.remember(result) { androidx.compose.runtime.mutableStateOf(false) }
    TextButton(onClick = { showDetails = true }) { Text("查看失败 ${result.failures.size} 项") }
    if (showDetails) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text("${result.operation}失败明细") },
        text = {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.heightIn(max = 280.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                result.failures.forEach { failure ->
                    Text("${failure.title}：${failure.reason}", modifier = androidx.compose.ui.Modifier.padding(bottom = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("知道了") } },
    )
}
