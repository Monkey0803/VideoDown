package com.videodown.douyin.presentation.home

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.R
import com.videodown.douyin.core.formatDuration
import com.videodown.douyin.core.readClipboardText
import com.videodown.douyin.data.remote.VideoLinkExtractor
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.presentation.components.AppConfirmDialog
import com.videodown.douyin.presentation.components.AppDialogHeader
import com.videodown.douyin.presentation.components.AppDialogMetrics
import com.videodown.douyin.presentation.components.AppDialogWindow
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.components.MediaCover
import com.videodown.douyin.presentation.components.AppPrimaryButton
import com.videodown.douyin.presentation.components.InlineNotice
import com.videodown.douyin.presentation.components.NoticeTone
import com.videodown.douyin.presentation.components.SelectAllToggle
import com.videodown.douyin.presentation.components.TriStateSelectionCheckbox
import com.videodown.douyin.presentation.failureDescription
import com.videodown.douyin.presentation.theme.AppSpacing
import com.videodown.douyin.presentation.videoPlatformLabelRes
import kotlin.math.roundToInt

/**
 * 批量链接导入：全屏流程，而不是「输入框 + 一长串卡片」的表单。
 *
 * 结构固定为「标题栏 / 列表 / 通知 / 页脚」四段，列表第一项是输入区：
 * 输入阶段它是大输入框，解析后折叠成一行摘要。这样键盘弹起、50 条滚动时，
 * 进度、全选与「下载所选」都还在原地，而输入框仍能按需展开。
 */
@Composable
internal fun BatchImportDialog(model: BatchImportViewModel, onClose: () -> Unit, onTasks: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val focus = LocalFocusManager.current
    // 有关键结果时默认收起输入区；首次打开（无结果）默认展开。
    var editing by rememberSaveable { mutableStateOf(state.rows.isEmpty()) }
    var pasteEmpty by remember { mutableStateOf(false) }
    var confirmReparse by remember { mutableStateOf(false) }
    fun close() { if (!state.submitting) { model.stop(); onClose() } }
    fun parse() {
        focus.clearFocus()
        if (state.rows.isNotEmpty() && state.selectedCount > 0) confirmReparse = true
        else { editing = false; model.prepareAndParse() }
    }
    fun paste() {
        val text = runCatching { readClipboardText(context) }.getOrNull()
        if (text == null) pasteEmpty = true else {
            pasteEmpty = false
            // 追加而不是替换：再粘一批时不该吞掉上一批已识别的内容。
            model.input(if (state.input.isBlank()) text else state.input.trimEnd() + "\n" + text)
        }
    }
    LaunchedEffect(state.rows.isEmpty()) { if (state.rows.isEmpty()) editing = true }
    val wide = configuration.screenWidthDp >= 600
    AppDialogWindow(
        onDismissRequest = ::close,
        shape = if (wide) MaterialTheme.shapes.extraLarge else RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        cardModifier = {
            if (wide) {
                Modifier.fillMaxWidth().widthIn(max = AppDialogMetrics.FULL_SCREEN_MAX_WIDTH)
                    .fillMaxHeight(AppDialogMetrics.FULL_SCREEN_MAX_HEIGHT_FRACTION)
            } else {
                Modifier.fillMaxSize()
            }
        },
    ) { availableHeightPx ->
        val layout = BatchImportLayoutPolicy.layoutFor(
            with(density) { availableHeightPx.toDp().value.roundToInt() },
        )
        Column(Modifier.fillMaxSize()) {
            BatchImportHeader(layout = layout, submitting = state.submitting, onClose = ::close)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (state.rows.isNotEmpty() && BatchImportLayoutPolicy.showsStatusStrip(layout)) {
                ImportStatusStrip(
                    state = state,
                    onSelectAll = model::selectAll,
                    onStop = model::stop,
                    onResume = model::parseRemaining,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f).testTag("batch-import-list"),
                contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                if (state.rows.isEmpty() || editing) {
                    item(key = "batch-import-input") {
                        ImportInputSection(
                            layout = layout,
                            state = state,
                            showCollapse = state.rows.isNotEmpty(),
                            pasteEmpty = pasteEmpty,
                            onInput = { model.input(it); pasteEmpty = false },
                            onPaste = ::paste,
                            onParse = ::parse,
                            onCollapse = { focus.clearFocus(); editing = false },
                        )
                    }
                } else if (layout != ImportLayout.MINIMAL) {
                    item(key = "batch-import-input") {
                        ImportLinksSummary(
                            count = state.rows.size,
                            enabled = !state.locked,
                            onEdit = { editing = true },
                        )
                    }
                }
                items(state.rows, key = { it.url }) { row ->
                    ImportRowCard(
                        row = row,
                        state = state,
                        onSelect = model::select,
                        onChoose = model::choose,
                        onSelectRow = model::selectRow,
                        onRetry = model::retry,
                    )
                }
            }
            state.notice?.let { ImportNoticeBanner(it) }
            if (state.rows.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ImportFooter(
                    state = state,
                    onSubmit = { focus.clearFocus(); model.submit() },
                    onTasks = { close(); onTasks() },
                )
            }
        }
    }
    if (confirmReparse) AppConfirmDialog(
        onDismissRequest = { confirmReparse = false },
        icon = AppIcons.BatchImport,
        title = stringResource(R.string.import_reparse_title),
        body = stringResource(R.string.import_reparse_body),
        confirmText = stringResource(R.string.import_reparse),
        onConfirm = { confirmReparse = false; editing = false; model.prepareAndParse() },
        dismissText = stringResource(R.string.action_cancel),
    )
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

/** 标题栏：FULL 用统一弹窗头部，紧凑档位只保留标题与关闭，把高度让给列表。 */
@Composable
private fun BatchImportHeader(layout: ImportLayout, submitting: Boolean, onClose: () -> Unit) {
    val close = { if (!submitting) onClose() }
    if (BatchImportLayoutPolicy.showsHeaderIcon(layout)) {
        AppDialogHeader(
            icon = AppIcons.BatchImport,
            title = stringResource(R.string.import_title),
            subtitle = if (BatchImportLayoutPolicy.showsHeaderSubtitle(layout)) stringResource(R.string.import_hint, 50) else null,
            onClose = close,
            closeEnabled = !submitting,
            closeModifier = Modifier.testTag("batch-import-close"),
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = AppSpacing.lg, end = AppSpacing.xs, top = AppSpacing.sm, bottom = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.import_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = close, enabled = !submitting, modifier = Modifier.testTag("batch-import-close")) {
                AppIcon(AppIcons.Close, stringResource(R.string.action_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 输入区：实时识别计数、粘贴（追加）、解析；有关键结果时可「收起」。 */
@Composable
private fun ImportInputSection(
    layout: ImportLayout,
    state: BatchImportState,
    showCollapse: Boolean,
    pasteEmpty: Boolean,
    onInput: (String) -> Unit,
    onPaste: () -> Unit,
    onParse: () -> Unit,
    onCollapse: () -> Unit,
) {
    val links = remember(state.input) { VideoLinkExtractor.extractAll(state.input) }
    val overLimit = links.size > 50
    val canParse = !state.locked && links.size in 1..50
    val hint = when {
        state.input.isBlank() -> stringResource(R.string.home_supported_sources)
        links.isEmpty() -> stringResource(R.string.import_no_links)
        overLimit -> stringResource(R.string.import_too_many)
        else -> stringResource(R.string.import_links_detected, links.size)
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = AppSpacing.xxs),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = onInput,
            enabled = !state.locked,
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = if (layout == ImportLayout.MINIMAL) 96.dp else 140.dp)
                .testTag("batch-import-input"),
            // 极短窗口里浮标签会被列表裁掉，改成框内提示（与旧的横屏处理一致）。
            label = if (layout == ImportLayout.MINIMAL) null else { { Text(stringResource(R.string.import_input_label)) } },
            placeholder = if (layout == ImportLayout.MINIMAL) {
                { Text(stringResource(R.string.import_input_label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } else null,
            trailingIcon = if (state.input.isNotEmpty() && !state.locked) {
                {
                    IconButton(onClick = { onInput("") }) {
                        AppIcon(AppIcons.Close, stringResource(R.string.import_clear_input), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else null,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canParse) onParse() }),
        )
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = if (overLimit || (links.isEmpty() && state.input.isNotBlank())) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pasteEmpty) {
            Text(
                stringResource(R.string.home_clipboard_empty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // 次操作一行，推进型操作通栏——与首页「粘贴 → 解析」保持同一种主次关系。
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            OutlinedButton(onClick = onPaste, enabled = !state.locked) {
                AppIcon(AppIcons.Paste, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.home_paste_link))
            }
            Spacer(Modifier.weight(1f))
            if (showCollapse) {
                TextButton(onClick = onCollapse) { Text(stringResource(R.string.import_collapse_input)) }
            }
        }
        AppPrimaryButton(
            text = stringResource(if (state.rows.isEmpty()) R.string.import_parse else R.string.import_reparse),
            onClick = onParse,
            enabled = canParse,
            modifier = Modifier.padding(top = AppSpacing.xs),
            trailingIcon = if (state.rows.isEmpty()) AppIcons.Forward else AppIcons.Retry,
        )
    }
}

/** 解析后折叠的输入区：只留一行「N 条链接」与「编辑链接」。 */
@Composable
private fun ImportLinksSummary(count: Int, enabled: Boolean, onEdit: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = AppSpacing.sm, end = AppSpacing.xxs, top = AppSpacing.xxs, bottom = AppSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(AppIcons.Link, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(AppSpacing.xs))
            Text(
                stringResource(R.string.import_summary_links, count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEdit, enabled = enabled) { Text(stringResource(R.string.import_edit_links)) }
        }
    }
}

/** 固定状态条：三态全选 / 已解析与已选数量 / 停止或继续解析；解析中再加一条进度。 */
@Composable
private fun ImportStatusStrip(
    state: BatchImportState,
    onSelectAll: (Boolean) -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
) {
    val ready = state.rows.count { it.status == ImportStatus.READY }
    val total = state.rows.size
    val notReady = total - ready
    val summary = stringResource(
        if (state.parsing) R.string.import_summary_parsing else R.string.import_summary,
        ready, total, state.selectedCount, state.selectableCount,
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = AppSpacing.md, end = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectAllToggle(
                label = stringResource(R.string.action_select_all),
                summary = summary,
                allSelected = state.allSelected,
                indeterminate = state.selectedCount > 0 && !state.allSelected,
                enabled = !state.selectionLocked,
                onToggle = onSelectAll,
                modifier = Modifier.weight(1f),
            )
            if (state.parsing) {
                TextButton(onClick = onStop) { Text(stringResource(R.string.import_stop)) }
            } else if (notReady > 0) {
                TextButton(onClick = onResume, enabled = !state.locked) { Text(stringResource(R.string.import_retry, notReady)) }
            }
        }
        if (state.parsing) {
            LinearProgressIndicator(
                progress = { if (total > 0) ready.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

/**
 * 一条链接的结果卡：先给「哪条链接、现在什么状态」，再给每条媒体的封面、标题、作者与画质。
 *
 * URL 只在失败时出现——成功时用户靠封面和标题确认内容，原始地址是排查信息而不是主信息。
 */
@Composable
private fun ImportRowCard(
    row: ImportRow,
    state: BatchImportState,
    onSelect: (String, Boolean) -> Unit,
    onChoose: (String, VideoVariant) -> Unit,
    onSelectRow: (String, Boolean) -> Unit,
    onRetry: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("import-${row.url}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                val platform = VideoLinkExtractor.extract(row.url)?.platform ?: VideoPlatform.GENERIC
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        stringResource(videoPlatformLabelRes(platform)),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(
                    row.url.toHostLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val selectable = row.media.filter { it.choice != null }
                if (selectable.size > 1) {
                    val rowAllSelected = selectable.all { it.selected }
                    val rowCheckLabel = stringResource(R.string.home_check_label, row.media.first().video.title)
                    TriStateSelectionCheckbox(
                        allSelected = rowAllSelected,
                        indeterminate = selectable.any { it.selected },
                        enabled = !state.selectionLocked,
                        onToggle = { onSelectRow(row.url, it) },
                        modifier = Modifier.semantics { contentDescription = rowCheckLabel },
                    )
                }
                ImportRowStatus(row)
            }
            if (row.status == ImportStatus.READY) {
                row.media.forEach { media -> ImportMediaBlock(media, state, onSelect, onChoose) }
            } else if (row.status == ImportStatus.FAILED) {
                row.error?.let {
                    Text(importRowErrorText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                // UNKNOWN 的说明来自「下载失败」模板，对解析失败不成立（这里还没有断点），不展示。
                row.failureKind?.takeIf { it != DownloadFailureKind.UNKNOWN }?.let {
                    Text(failureDescription(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    row.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { onRetry(row.url) }, enabled = !state.locked) {
                    AppIcon(AppIcons.Retry, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.import_row_retry))
                }
            }
        }
    }
}

/** 单条链接的状态字：图标 + 文案，不只靠颜色。 */
@Composable
private fun ImportRowStatus(row: ImportRow) {
    when (row.status) {
        ImportStatus.PENDING -> Text(
            stringResource(R.string.import_row_pending),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ImportStatus.PARSING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.import_row_parsing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ImportStatus.READY -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppIcon(AppIcons.Success, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                importRowReadyText(row.media),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ImportStatus.FAILED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppIcon(AppIcons.Error, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            Text(
                stringResource(R.string.import_row_failed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun importRowReadyText(media: List<ImportMedia>): String =
    if (media.isNotEmpty() && media.all { it.video.isImagePost }) stringResource(R.string.import_row_ready_images, media.size)
    else stringResource(R.string.import_row_ready, media.size)

/** 一条媒体：封面 + 标题 + 作者/时长 + 勾选，下面一排画质（只有一个选项时改成静态文字）。 */
@Composable
private fun ImportMediaBlock(
    media: ImportMedia,
    state: BatchImportState,
    onSelect: (String, Boolean) -> Unit,
    onChoose: (String, VideoVariant) -> Unit,
) {
    val video = media.video
    val checkLabel = stringResource(R.string.home_check_label, video.title)
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MediaCover(
                model = video.coverUrl,
                modifier = Modifier.size(width = 72.dp, height = 40.dp),
                shape = MaterialTheme.shapes.small,
                placeholderIcon = when {
                    video.isImagePost -> AppIcons.Grid
                    video.variants.isNotEmpty() && video.variants.all { it.mediaKind == MediaKind.AUDIO } -> AppIcons.Audio
                    else -> AppIcons.Play
                },
            )
            Spacer(Modifier.width(AppSpacing.xs))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    video.author.nickname.takeIf { it.isNotBlank() }?.let { "@$it" },
                    formatDuration(video.durationMs).takeIf { video.durationMs > 0 },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Checkbox(
                checked = media.selected && media.choice != null,
                enabled = !state.selectionLocked && media.choice != null,
                onCheckedChange = { onSelect(media.key, it) },
                modifier = Modifier.semantics { contentDescription = checkLabel },
            )
        }
        val options = video.variants + video.audioVariants
        when {
            options.size == 1 -> Text(
                options.first().label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 80.dp),
            )
            options.size > 1 -> LazyRow(
                modifier = Modifier.fillMaxWidth().padding(start = 80.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                // 不设 key：音轨与视频变体可能指向同一个地址，用下标最稳。
                items(options) { variant ->
                    FilterChip(
                        selected = media.choice == variant,
                        enabled = !state.selectionLocked,
                        onClick = { onChoose(media.key, variant) },
                        label = { Text(variant.label) },
                    )
                }
            }
        }
    }
}

/** 提交结果通知：失败用错误色容器，其余用中性容器；固定列表与页脚之间，不会被滚动带走。 */
@Composable
private fun ImportNoticeBanner(notice: ImportNotice) {
    val errorTone = (notice is ImportNotice.Queued && notice.failedTitles.isNotEmpty()) ||
        notice == ImportNotice.RecordsUnavailable
    InlineNotice(
        message = importNoticeText(notice),
        modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.xxs),
        icon = if (errorTone) AppIcons.Error else AppIcons.Info,
        tone = if (errorTone) NoticeTone.ERROR else NoticeTone.NEUTRAL,
    )
}

/** 页脚：任务入口是次操作，推进型操作唯一且带数量。 */
@Composable
private fun ImportFooter(state: BatchImportState, onSubmit: () -> Unit, onTasks: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
    ) {
        TextButton(onClick = onTasks, enabled = !state.submitting) { Text(stringResource(R.string.home_view_tasks)) }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onSubmit,
            enabled = !state.locked && state.selectedCount > 0,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(R.string.import_download_selected), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text("(${state.selectedCount})", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun String.toHostLabel(): String = Uri.parse(this).host?.removePrefix("www.") ?: this

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
