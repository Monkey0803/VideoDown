package com.videodown.douyin.presentation.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.R
import com.videodown.douyin.presentation.failureDescription
import com.videodown.douyin.presentation.components.AppConfirmDialog
import com.videodown.douyin.presentation.components.AppDialogContainer
import com.videodown.douyin.presentation.components.APP_DIALOG_CARD_TAG
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.components.consumeTaps
import kotlinx.coroutines.launch

@Composable
internal fun BatchImportDialog(model: BatchImportViewModel, onClose: () -> Unit, onTasks: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()
    fun close() { if (!state.submitting) { model.stop(); onClose() } }
    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 与 AppDialog 同一套键盘处理：浮动窗口只会被平移，动作行会落到键盘背后。
            decorFitsSystemWindows = false,
            // 容器铺满整屏，系统的「点在内容之外」判断恒为「在内部」，改由容器实现。
            dismissOnClickOutside = false,
        ),
    ) {
        AppDialogContainer(onDismissRequest = ::close) {
            Surface(
                Modifier.fillMaxWidth().fillMaxHeight(.94f).consumeTaps().testTag(APP_DIALOG_CARD_TAG),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(16.dp)) {
                    LazyColumn(Modifier.weight(1f).testTag("batch-import-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                    Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.import_hint, 50), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = state.input, onValueChange = model::input, enabled = !state.locked,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 130.dp), label = { Text(stringResource(R.string.import_input_label)) }, maxLines = 4)
                    LazyRow {
                        item { TextButton(enabled = !state.locked, onClick = { scope.launch { clipboard.getClipEntry()?.clipData?.getItemAt(0)?.coerceToText(context)?.let { model.input(it.toString()) } } }) { Text(stringResource(R.string.home_paste_link)) } }
                        item { TextButton(enabled = !state.locked && state.input.isNotBlank(), onClick = { focus.clearFocus(); model.prepareAndParse() }) { Text(stringResource(R.string.import_parse)) } }
                        item { if (state.parsing) TextButton(onClick = model::stop) { Text(stringResource(R.string.import_stop)) }
                            else if (state.rows.any { it.status != ImportStatus.READY }) TextButton(enabled = !state.locked, onClick = model::parseRemaining) { Text(stringResource(R.string.import_retry)) } }
                    }
                    val ready = state.rows.count { it.status == ImportStatus.READY }
                    Text(stringResource(R.string.import_progress, ready, state.rows.size, state.selectedCount))
                    Row {
                        TextButton(enabled = !state.locked, onClick = { model.selectAll(true) }) { Text(stringResource(R.string.import_select_all)) }
                        TextButton(enabled = !state.locked, onClick = { model.selectAll(false) }) { Text(stringResource(R.string.action_deselect_all)) }
                    }
                        }
                        items(state.rows, key = { it.url }) { row ->
                            Card(Modifier.fillMaxWidth().testTag("import-${row.url}")) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(row.url, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                                    Text(when (row.status) {
                                        ImportStatus.PENDING -> stringResource(R.string.import_row_pending)
                                        ImportStatus.PARSING -> stringResource(R.string.import_row_parsing)
                                        ImportStatus.READY -> stringResource(R.string.import_row_ready, row.media.size)
                                        ImportStatus.FAILED -> row.error?.let { importRowErrorText(it) } ?: stringResource(R.string.import_row_failed)
                                    })
                                    row.failureKind?.let { Text(failureDescription(it), style = MaterialTheme.typography.bodySmall) }
                                    row.media.forEach { media ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = media.selected && media.choice != null, enabled = !state.locked && media.choice != null,
                                                onCheckedChange = { model.select(media.key, it) })
                                            Text(media.video.title, modifier = Modifier.weight(1f))
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
                    state.notice?.let { Text(importNoticeText(it), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(enabled = !state.locked && state.selectedCount > 0, onClick = { model.submit() }) { Text(stringResource(R.string.import_download_selected)) }
                        TextButton(enabled = !state.submitting, onClick = { close(); onTasks() }) { Text(stringResource(R.string.home_view_tasks)) }
                        TextButton(enabled = !state.submitting, onClick = ::close) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }
    }
    if (state.duplicateCount > 0) AppConfirmDialog(
        onDismissRequest = model::dismissDuplicates,
        icon = AppIcons.BatchImport,
        title = stringResource(R.string.import_duplicate_title, state.duplicateCount),
        body = stringResource(R.string.import_duplicate_body),
        confirmText = stringResource(R.string.home_skip_duplicates),
        onConfirm = { model.submit(false) },
        dismissText = stringResource(R.string.action_cancel),
        extraActions = { TextButton(onClick = { model.submit(true) }) { Text(stringResource(R.string.import_download_all)) } },
    )
}

/** The wording for an import notice. */
@Composable
private fun importNoticeText(notice: ImportNotice): String = when (notice) {
    ImportNotice.NoLinks -> stringResource(R.string.import_no_links)
    ImportNotice.TooManyLinks -> stringResource(R.string.import_too_many)
    ImportNotice.RecordsUnavailable -> stringResource(R.string.import_records_unavailable)
    is ImportNotice.Queued -> {
        val base = stringResource(R.string.import_queued, notice.queued, notice.skipped, notice.failedTitles.size)
        if (notice.failedTitles.isEmpty()) base
        else {
            val names = notice.failedTitles.take(3).joinToString(stringResource(R.string.list_separator)) +
                if (notice.failedTitles.size > 3) stringResource(R.string.import_and_more) else ""
            base + stringResource(R.string.import_keep_selected, names)
        }
    }
}

@Composable
private fun importRowErrorText(error: ImportRowError): String = when (error) {
    ImportRowError.NO_QUALITY -> stringResource(R.string.import_no_quality)
    ImportRowError.PARSE_FAILED -> stringResource(R.string.import_parse_failed)
}
