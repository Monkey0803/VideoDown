package com.videodown.douyin.presentation.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.domain.model.failureGuidance
import kotlinx.coroutines.launch

@Composable
internal fun BatchImportDialog(model: BatchImportViewModel, onClose: () -> Unit, onTasks: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()
    fun close() { if (!state.submitting) { model.stop(); onClose() } }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                LazyColumn(Modifier.weight(1f).testTag("batch-import-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                Text("批量链接导入", style = MaterialTheme.typography.titleLarge)
                Text("一次最多 50 个网址；相同网址自动合并，每个视频可独立选择画质。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = state.input, onValueChange = model::input, enabled = !state.locked,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 130.dp), label = { Text("粘贴多条分享文案或网址") }, maxLines = 4)
                LazyRow {
                    item { TextButton(enabled = !state.locked, onClick = { scope.launch { clipboard.getClipEntry()?.clipData?.getItemAt(0)?.coerceToText(context)?.let { model.input(it.toString()) } } }) { Text("粘贴链接") } }
                    item { TextButton(enabled = !state.locked && state.input.isNotBlank(), onClick = { focus.clearFocus(); model.prepareAndParse() }) { Text("开始批量解析") } }
                    item { if (state.parsing) TextButton(onClick = model::stop) { Text("停止解析") }
                        else if (state.rows.any { it.status != ImportStatus.READY }) TextButton(enabled = !state.locked, onClick = model::parseRemaining) { Text("重试未完成链接") } }
                }
                val ready = state.rows.count { it.status == ImportStatus.READY }
                Text("解析完成 $ready/${state.rows.size} · 已选择 ${state.selectedCount} 项")
                Row {
                    TextButton(enabled = !state.locked, onClick = { model.selectAll(true) }) { Text("全选视频") }
                    TextButton(enabled = !state.locked, onClick = { model.selectAll(false) }) { Text("取消全选") }
                }
                    }
                    items(state.rows, key = { it.url }) { row ->
                        Card(Modifier.fillMaxWidth().testTag("import-${row.url}")) {
                            Column(Modifier.padding(12.dp)) {
                                Text(row.url, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                                Text(when (row.status) { ImportStatus.PENDING -> "等待解析"; ImportStatus.PARSING -> "正在解析…";
                                    ImportStatus.READY -> "找到 ${row.media.size} 个视频"; ImportStatus.FAILED -> row.error ?: "解析失败" })
                                row.failureKind?.let { Text(failureGuidance(it).description, style = MaterialTheme.typography.bodySmall) }
                                row.media.forEach { media ->
                                    Row {
                                        Checkbox(checked = media.selected && media.choice != null, enabled = !state.locked && media.choice != null,
                                            onCheckedChange = { model.select(media.key, it) })
                                        Text(media.video.title, modifier = Modifier.weight(1f).padding(top = 12.dp))
                                    }
                                    LazyRow { items(media.video.variants + media.video.audioVariants) { variant ->
                                        FilterChip(selected = media.choice == variant, enabled = !state.locked,
                                            onClick = { model.choose(media.key, variant) }, label = { Text(variant.label) })
                                    } }
                                }
                            }
                        }
                    }
                }
                state.message?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton(enabled = !state.locked && state.selectedCount > 0, onClick = { model.submit() }) { Text("下载所选") }
                    TextButton(enabled = !state.submitting, onClick = { close(); onTasks() }) { Text("查看任务") }
                    TextButton(enabled = !state.submitting, onClick = ::close) { Text("关闭") }
                }
            }
        }
    }
    if (state.duplicateCount > 0) AlertDialog(onDismissRequest = model::dismissDuplicates,
        title = { Text("发现 ${state.duplicateCount} 个重复任务") }, text = { Text("可跳过已有任务，或明确再次下载。") },
        confirmButton = { TextButton(onClick = { model.submit(false) }) { Text("跳过重复项") } },
        dismissButton = { Row { TextButton(onClick = { model.submit(true) }) { Text("仍然全部下载") }; TextButton(onClick = model::dismissDuplicates) { Text("取消") } } })
}
