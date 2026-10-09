package com.videodown.douyin.presentation.library

import com.videodown.douyin.presentation.components.TabPageHeader
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.videodown.douyin.domain.model.VideoPlatform
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.videodown.douyin.core.LibraryLayout
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.formatRelativeTime
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.history.HistoryViewModel
import java.io.File

@Composable
fun LibraryScreen(historyViewModel: HistoryViewModel, onPlay: (String) -> Unit,
    organizationViewModel: LibraryOrganizationViewModel = viewModel(),
    fileExportViewModel: FileExportViewModel = viewModel(),
    audioExtractionViewModel: AudioExtractionViewModel = viewModel()) {
    var extractingId by rememberSaveable { mutableStateOf<String?>(null) }
    val fileExport by fileExportViewModel.state.collectAsStateWithLifecycle()
    var fileExportIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var exportSession by rememberSaveable { mutableStateOf(fileExportViewModel.sessionId) }
    LaunchedEffect(fileExportViewModel.sessionId) {
        if (exportSession != fileExportViewModel.sessionId) {
            if (fileExportIds.isNotEmpty()) fileExportViewModel.message("上次导出已中断，请检查目标文件夹后重新选择文件")
            fileExportIds = emptyList()
            exportSession = fileExportViewModel.sessionId
        }
    }
    if (exportSession == fileExportViewModel.sessionId && fileExportIds.isNotEmpty()) FileExportDialog(fileExportIds, fileExportViewModel) { fileExportIds = emptyList() }
    val organization by organizationViewModel.state.collectAsStateWithLifecycle()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val state by historyViewModel.state.collectAsStateWithLifecycle()
    var layout by remember { mutableStateOf(ServiceLocator.preferences.libraryLayout) }
    var selectedRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val waterfallState = rememberLazyStaggeredGridState()
    val listState = rememberLazyListState()
    val nextLayout = if (layout == LibraryLayout.WATERFALL) LibraryLayout.LIST else LibraryLayout.WATERFALL
    val allSaved = state.records.filter { state.statusFor(it) == DownloadStatus.COMPLETED &&
        it.filePath?.let { path -> File(path).isFile } == true }
    allSaved.firstOrNull { it.id == editingId }?.let { LibraryOrganizationDialog(it, organizationViewModel) { editingId = null } }
    allSaved.firstOrNull { it.id == extractingId }?.let { record ->
        AudioExtractionDialog(record, audioExtractionViewModel, onClose = { extractingId = null }, onQueued = {
            extractingId = null; historyViewModel.showMessage("音频提取已加入任务队列")
        })
    }
    val galleryAction = when {
        allSaved.filter { it.id in selectedIds }.all { it.isAudio } && selectedIds.isNotEmpty() -> "保存到音乐"
        allSaved.any { it.id in selectedIds && it.isAudio } -> "保存到相册/音乐"
        else -> "保存到相册"
    }
    val saved = state.libraryQuery.apply(allSaved)
    val visibleIds = saved.map { it.id }.toSet()
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.intersect(visibleIds) }
    LaunchedEffect(fileExport.finished) {
        if (fileExport.finished && selecting) selectedIds = fileExport.failedIds.intersect(visibleIds)
    }
    LaunchedEffect(state.batchResult) {
        val result = state.batchResult ?: return@LaunchedEffect
        if (selecting && result.operation in setOf("保存到相册", "保存到音乐", "保存到相册/音乐", "删除")) {
            selectedIds = result.failures.map { it.id }.toSet().intersect(visibleIds)
        }
    }
    BackHandler(selecting && !state.batchBusy) { selecting = false; selectedIds = emptySet() }
    fun toggle(id: String) { if (!state.batchBusy) selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun queryChanged(query: LibraryQuery) { selectedIds = emptySet(); historyViewModel.setLibraryQuery(query) }
    fun controlsChanged(query: LibraryQuery) { focusManager.clearFocus(); queryChanged(query) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ids = pendingExport.toSet(); pendingExport = emptyList()
        if (granted) historyViewModel.exportSelected(ids, galleryAction) else historyViewModel.showMessage("需要存储权限才能保存到相册")
    }
    pendingDelete?.let { ids ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除所选视频？") },
            text = { Text("将删除所选 ${ids.size} 项记录和本地文件，无法恢复。已保存到系统相册的副本不受影响。") },
            confirmButton = { TextButton(onClick = { historyViewModel.deleteSelected(ids); pendingDelete = null }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } })
    }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbar.showSnackbar(message)
            historyViewModel.onMessageConsumed()
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            TabPageHeader(
                title = "已存视频",
                subtitle = if (selecting) null else "${allSaved.size} 个视频 · 可离线播放",
                modifier = Modifier.padding(horizontal = 22.dp),
                trailingContent = {
                    IconButton(
                        onClick = { layout = nextLayout; ServiceLocator.preferences.libraryLayout = nextLayout },
                        modifier = Modifier.semantics { stateDescription = layout.label },
                    ) {
                        AppIcon(if (nextLayout == LibraryLayout.LIST) AppIcons.List else AppIcons.Grid,
                            contentDescription = "切换为${nextLayout.label}", tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
            OutlinedTextField(value = state.libraryQuery.text,
                onValueChange = { queryChanged(state.libraryQuery.copy(text = it)) },
                label = { Text("搜索标题或作者") }, singleLine = true, enabled = !state.batchBusy,
                leadingIcon = { AppIcon(AppIcons.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                trailingIcon = { if (state.libraryQuery.text.isNotEmpty()) TextButton(onClick = { queryChanged(state.libraryQuery.copy(text = "")) }) { Text("清除") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(modifier = Modifier.weight(1f).testTag("library-controls"),
                contentPadding = PaddingValues(start = 22.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                item {
                    LibraryPicker("选择平台", state.libraryQuery.platform?.label ?: "全部平台",
                        state.libraryQuery.platform, listOf(null to "全部平台") + VideoPlatform.entries.map { it to it.label },
                        enabled = !state.batchBusy) { controlsChanged(state.libraryQuery.copy(platform = it)) }
                }
                item {
                    LibraryPicker("选择分类", state.libraryQuery.folder?.ifBlank { "未分类" } ?: "全部分类",
                        state.libraryQuery.folder,
                        listOf(null to "全部分类") + (allSaved.map { it.folder } + "").distinct().sorted().map { it to it.ifBlank { "未分类" } },
                        enabled = !state.batchBusy && !organization.busy) { controlsChanged(state.libraryQuery.copy(folder = it)) }
                }
                item {
                    LibraryPicker("选择排序", state.libraryQuery.sort.label, state.libraryQuery.sort,
                        LibrarySort.entries.map { it to it.label }, enabled = !state.batchBusy) {
                        controlsChanged(state.libraryQuery.copy(sort = it))
                    }
                }
                item { FilterChip(selected = state.libraryQuery.favoritesOnly, enabled = !state.batchBusy && !organization.busy,
                    onClick = { controlsChanged(state.libraryQuery.copy(favoritesOnly = !state.libraryQuery.favoritesOnly)) }, label = { Text("仅收藏") }) }
            }
                TextButton(onClick = { focusManager.clearFocus(); selecting = !selecting; selectedIds = emptySet() },
                    enabled = !state.batchBusy && allSaved.isNotEmpty()) { Text(if (selecting) "取消多选" else "多选") }
            }
            if (selecting) SelectionToolbar(selectedIds.size, state.batchBusy, selectedIds == visibleIds && visibleIds.isNotEmpty(),
                onSelectAll = { selectedIds = if (selectedIds == visibleIds) emptySet() else visibleIds },
                onExit = { selecting = false; selectedIds = emptySet() },
                actions = listOf(galleryAction to {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        pendingExport = selectedIds.toList(); permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else historyViewModel.exportSelected(selectedIds, galleryAction)
                }, "导出到文件夹" to { fileExportViewModel.reset(); fileExportIds = selectedIds.toList() }, "删除所选" to { pendingDelete = selectedIds.toSet() }))
            if (editingId == null) organization.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2, modifier = Modifier.padding(horizontal = 22.dp)) }
            if (fileExportIds.isEmpty()) fileExport.message?.let { Text(it, maxLines = 2, modifier = Modifier.padding(horizontal = 22.dp)) }
            BatchFailureDetails(state.batchResult)
            if (state.batchBusy) Text("正在处理 ${state.batchDone}/${state.batchTotal}…", modifier = Modifier.padding(horizontal = 22.dp))
            if (saved.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (allSaved.isEmpty()) "还没有已保存的视频" else "没有符合条件的视频", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } else if (layout == LibraryLayout.WATERFALL) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = waterfallState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalItemSpacing = 20.dp,
                ) {
                    items(saved, key = { it.id }) { record ->
                        Column(Modifier.combinedClickable(
                                role = Role.Button,
                                onClickLabel = if (selecting) "选择视频" else "播放视频",
                                onLongClickLabel = "视频操作",
                                onClick = { if (selecting) toggle(record.id) else if (!state.batchBusy) onPlay(record.id) },
                                onLongClick = { if (selecting) toggle(record.id) else if (!state.batchBusy) selectedRecordId = record.id },
                            )) {
                            LibraryCover(record, Modifier.fillMaxWidth(), adaptToCover = true,
                                selected = if (selecting) record.id in selectedIds else null,
                                selectionEnabled = !state.batchBusy, onSelect = { toggle(record.id) })
                            Text(record.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 9.dp))
                            LibraryMetadata(record, Modifier.padding(top = 4.dp))
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(saved, key = { it.id }) { record ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .combinedClickable(
                                    role = Role.Button,
                                    onClickLabel = if (selecting) "选择视频" else "播放视频",
                                    onLongClickLabel = "视频操作",
                                    onClick = { if (selecting) toggle(record.id) else if (!state.batchBusy) onPlay(record.id) },
                                    onLongClick = { if (selecting) toggle(record.id) else if (!state.batchBusy) selectedRecordId = record.id },
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            LibraryCover(record, Modifier.width(108.dp),
                                selected = if (selecting) record.id in selectedIds else null,
                                selectionEnabled = !state.batchBusy, onSelect = { toggle(record.id) })
                            Column(Modifier.weight(1f)) {
                                Text(record.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(record.displayAuthor, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 5.dp))
                                LibraryMetadata(record, Modifier.padding(top = 5.dp))
                            }
                        }
                    }
                }
            }
        }
        LibraryActions(
            records = state.records,
            selectedRecordId = selectedRecordId,
            onDismiss = { selectedRecordId = null },
            onPlay = onPlay,
            onDelete = historyViewModel::delete,
            snackbar = snackbar,
            onOrganize = { editingId = it.id },
            onFavorite = { organizationViewModel.update(it.id, favorite = !it.favorite) },
            organizing = organization.busy || fileExport.busy,
            onFileExport = { fileExportViewModel.reset(); fileExportIds = listOf(it.id) },
            onExtractAudio = { audioExtractionViewModel.reset(); extractingId = it.id },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun LibraryCover(record: DownloadRecord, modifier: Modifier, adaptToCover: Boolean = false,
    selected: Boolean? = null, selectionEnabled: Boolean = true, onSelect: () -> Unit = {}) {
    var coverAspectRatio by rememberSaveable(record.coverUrl) { mutableFloatStateOf(3f / 4f) }
    Box(
        modifier.aspectRatio(if (adaptToCover) coverAspectRatio else 4f / 3f)
            .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(if (record.isAudio) AppIcons.Audio else AppIcons.Play, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(
            model = record.coverUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onSuccess = { result ->
                val drawable = result.result.drawable
                if (adaptToCover && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    coverAspectRatio = (drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight)
                        .coerceIn(.5f, 2f)
                }
            },
        )
        if (selected != null) {
            Box(Modifier.align(Alignment.TopStart).padding(2.dp).size(48.dp)
                .clip(CircleShape)
                .toggleable(value = selected, enabled = selectionEnabled, role = Role.Checkbox,
                    onValueChange = { onSelect() })
                .semantics { stateDescription = record.title },
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(24.dp).alpha(if (selectionEnabled) 1f else .38f)) {
                    val stroke = 2.dp.toPx()
                    val radius = (size.minDimension - stroke) / 2
                    drawCircle(if (selected) Color(0xFFF2784B) else Color.White.copy(alpha = .18f), radius)
                    drawCircle(Color.White, radius, style = Stroke(stroke))
                    if (selected) {
                        val unit = size.width / 24f
                        drawLine(Color(0xFF33251F), Offset(7 * unit, 12 * unit),
                            Offset(10.5f * unit, 15.5f * unit), stroke, StrokeCap.Round)
                        drawLine(Color(0xFF33251F), Offset(10.5f * unit, 15.5f * unit),
                            Offset(17 * unit, 8.5f * unit), stroke, StrokeCap.Round)
                    }
                }
            }
        }
        if (record.resolutionLabel.isNotBlank()) {
            Text(record.resolutionLabel, color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .clip(MaterialTheme.shapes.extraSmall).background(Color.Black.copy(alpha = .5f))
                    .padding(horizontal = 6.dp, vertical = 3.dp))
        }
    }
}

@Composable
private fun LibraryMetadata(record: DownloadRecord, modifier: Modifier = Modifier) {
    if (record.isAudio) Text("音频 · M4A", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
    Text(
        formatRelativeTime(record.updatedAt),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}

@Composable
private fun <T> LibraryPicker(description: String, label: String, selected: T,
    options: List<Pair<T, String>>, enabled: Boolean, onSelect: (T) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Box {
        AssistChip(onClick = { focusManager.clearFocus(); expanded = true }, enabled = enabled,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp)) },
            trailingIcon = { AppIcon(if (expanded && enabled) AppIcons.ChevronUp else AppIcons.ChevronDown, contentDescription = null) },
            modifier = Modifier.semantics { contentDescription = description; stateDescription = label })
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title) },
                    leadingIcon = { RadioButton(selected = value == selected, onClick = null) },
                    onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}
