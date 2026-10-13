package com.videodown.douyin.presentation.library

import com.videodown.douyin.R
import com.videodown.douyin.presentation.videoPlatformLabelRes
import com.videodown.douyin.presentation.libraryLayoutLabelRes
import com.videodown.douyin.presentation.librarySortLabelRes
import com.videodown.douyin.presentation.history.BatchOperation
import com.videodown.douyin.presentation.history.contextOperationLabel
import com.videodown.douyin.presentation.components.TabPageHeader
import com.videodown.douyin.presentation.components.AppConfirmDialog
import com.videodown.douyin.core.LibrarySort
import com.videodown.douyin.presentation.components.EmptyStateIllustration
import com.videodown.douyin.presentation.components.sharedCover
import com.videodown.douyin.presentation.components.shimmer
import com.videodown.douyin.presentation.theme.appMotion
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import sh.calvin.reorderable.rememberReorderableLazyStaggeredGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.ReorderableItem
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import coil3.compose.AsyncImage
import com.videodown.douyin.core.LibraryLayout
import com.videodown.douyin.core.WatchBadge
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.relativeTime
import com.videodown.douyin.presentation.relativeTimeText
import com.videodown.douyin.presentation.watchBadgeText
import com.videodown.douyin.core.watchBadge
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.history.HistoryViewModel
import java.io.File

@Composable
fun LibraryScreen(historyViewModel: HistoryViewModel, onPlay: (String) -> Unit,
    watchProgress: Map<String, com.videodown.douyin.domain.model.WatchProgress> = emptyMap(),
    organizationViewModel: LibraryOrganizationViewModel = viewModel(),
    fileExportViewModel: FileExportViewModel = viewModel(),
    audioExtractionViewModel: AudioExtractionViewModel = viewModel(),
    // 共享元素作用域由 MainActivity 的 SharedTransitionLayout 传入；直接渲染本界面的设备测试不传，
    // 此时封面不带共享修饰，行为与改造前一致。
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null) {
    val motion = appMotion()
    val context = LocalContext.current
    // 资源按当前组合读取：context.getString 不会随语言/配置切换重算。
    val resources = LocalResources.current
    var extractingId by rememberSaveable { mutableStateOf<String?>(null) }
    val fileExport by fileExportViewModel.state.collectAsStateWithLifecycle()
    var fileExportIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var exportSession by rememberSaveable { mutableStateOf(fileExportViewModel.sessionId) }
    LaunchedEffect(fileExportViewModel.sessionId) {
        if (exportSession != fileExportViewModel.sessionId) {
            if (fileExportIds.isNotEmpty()) fileExportViewModel.message(resources.getString(R.string.library_export_interrupted))
            fileExportIds = emptyList()
            exportSession = fileExportViewModel.sessionId
        }
    }
    if (exportSession == fileExportViewModel.sessionId && fileExportIds.isNotEmpty()) FileExportDialog(fileExportIds, fileExportViewModel) { fileExportIds = emptyList() }
    val organization by organizationViewModel.state.collectAsStateWithLifecycle()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val state by historyViewModel.state.collectAsStateWithLifecycle()
    var layout by remember { mutableStateOf(ServiceLocator.preferences.libraryLayout) }
    val layoutDescription = stringResource(libraryLayoutLabelRes(layout))
    var selectedRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val waterfallState = rememberLazyStaggeredGridState()
    val listState = rememberLazyListState()
    // 首次进入时套用持久化的排序方式（手动顺序重启后要看得见）。
    LaunchedEffect(Unit) {
        val persisted = ServiceLocator.preferences.librarySort
        if (persisted != state.libraryQuery.sort) historyViewModel.setLibraryQuery(state.libraryQuery.copy(sort = persisted))
    }
    val nextLayout = if (layout == LibraryLayout.WATERFALL) LibraryLayout.LIST else LibraryLayout.WATERFALL
    // Private records only join the visible stock once the vault is unlocked, so the count, the
    // category list and every batch action are all computed over what the user may see.
    val allSaved = state.records.filter { state.statusFor(it) == DownloadStatus.COMPLETED &&
        it.filePath?.let { path -> File(path).isFile } == true &&
        (!it.isPrivate || state.libraryQuery.vaultUnlocked) }
    allSaved.firstOrNull { it.id == editingId }?.let { LibraryOrganizationDialog(it, organizationViewModel) { editingId = null } }
    allSaved.firstOrNull { it.id == extractingId }?.let { record ->
        AudioExtractionDialog(record, audioExtractionViewModel, onClose = { extractingId = null }, onQueued = {
            extractingId = null; historyViewModel.showMessage(resources.getString(R.string.library_extract_queued))
        })
    }
    val galleryOperation = when {
        allSaved.filter { it.id in selectedIds }.all { it.isAudio } && selectedIds.isNotEmpty() -> BatchOperation.SAVE_TO_MUSIC
        allSaved.any { it.id in selectedIds && it.isAudio } -> BatchOperation.SAVE_TO_GALLERY_AND_MUSIC
        else -> BatchOperation.SAVE_TO_GALLERY
    }
    val galleryAction = stringResource(contextOperationLabel(galleryOperation))
    val saved = state.libraryQuery.apply(allSaved)
    val visibleIds = saved.map { it.id }.toSet()
    // 拖拽只在「自定义排序 + 没有搜索/过滤 + 非多选」时开放：其他档位的顺序由规则决定，拖了没有意义。
    val reorderAllowed = state.libraryQuery.sort == LibrarySort.CUSTOM &&
        state.libraryQuery.text.isBlank() && state.libraryQuery.platform == null &&
        state.libraryQuery.folder == null && !state.libraryQuery.favoritesOnly && !state.libraryQuery.vaultOnly
    val canReorder = reorderAllowed && !selecting && !state.batchBusy
    // 拖拽期间先按本地顺序显示，松手后落盘；落盘回流的新数据会取代本地顺序。
    var draggedOrder by remember { mutableStateOf<List<String>?>(null) }
    val displayed = draggedOrder?.let { order -> order.mapNotNull { id -> saved.firstOrNull { it.id == id } } } ?: saved
    // 拖拽中只改本地顺序，松手（isAnyItemDragging 落回 false）才落盘一次。
    fun moveDragged(fromKey: Any?, toIndex: Int) {
        val id = fromKey as? String ?: return
        val ids = displayed.map { it.id }.toMutableList()
        val index = ids.indexOf(id)
        if (index >= 0 && toIndex in 0..ids.size) {
            ids.add(toIndex, ids.removeAt(index))
            draggedOrder = ids
        }
    }
    /** 把手上的无障碍动作：TalkBack 用户不能做拖拽手势，得给「前移/后移」。 */
    val moveUpLabel = stringResource(R.string.history_move_up)
    val moveDownLabel = stringResource(R.string.history_move_down)
    fun moveWithinDisplayed(id: String, delta: Int) {
        val ids = displayed.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = (from + delta).coerceIn(0, ids.size - 1)
        if (from < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        historyViewModel.reorderLibrary(ids)
    }
    fun accessibilityActions(id: String): List<CustomAccessibilityAction> {
        val index = displayed.indexOfFirst { it.id == id }
        if (index < 0) return emptyList()
        return buildList {
            if (index > 0) add(CustomAccessibilityAction(moveUpLabel) { moveWithinDisplayed(id, -1); true })
            if (index < displayed.size - 1) add(CustomAccessibilityAction(moveDownLabel) { moveWithinDisplayed(id, 1); true })
        }
    }
    val reorderableListState = rememberReorderableLazyListState(listState) { from, to -> moveDragged(from.key, to.index) }
    val reorderableGridState = rememberReorderableLazyStaggeredGridState(waterfallState) { from, to -> moveDragged(from.key, to.index) }
    LaunchedEffect(reorderableListState, reorderableGridState) {
        snapshotFlow { reorderableListState.isAnyItemDragging || reorderableGridState.isAnyItemDragging }
            .collect { dragging ->
                if (!dragging) draggedOrder?.let { order ->
                    draggedOrder = null
                    historyViewModel.reorderLibrary(order)
                }
            }
    }
    // 第一次切到自定义排序且从来没排过：按切换前的顺序 seed 一次，切过去的瞬间列表不跳变。
    var sortBeforeCustom by remember { mutableStateOf(state.libraryQuery.sort) }
    LaunchedEffect(state.libraryQuery.sort, allSaved.size) {
        val sort = state.libraryQuery.sort
        if (sort == LibrarySort.CUSTOM && reorderAllowed && allSaved.isNotEmpty() && allSaved.none { it.sortIndex > 0L }) {
            val seed = state.libraryQuery.copy(sort = sortBeforeCustom.takeIf { it != LibrarySort.CUSTOM } ?: LibrarySort.NEWEST)
            historyViewModel.reorderLibrary(seed.apply(allSaved).map { it.id })
        }
        sortBeforeCustom = sort
    }
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.intersect(visibleIds) }
    LaunchedEffect(fileExport.finished) {
        if (fileExport.finished && selecting) selectedIds = fileExport.failedIds.intersect(visibleIds)
    }
    LaunchedEffect(state.batchResult) {
        val result = state.batchResult ?: return@LaunchedEffect
        if (selecting && result.operation.keepsFailuresSelected) {
            selectedIds = result.failures.map { it.id }.toSet().intersect(visibleIds)
        }
    }
    BackHandler(selecting && !state.batchBusy) { selecting = false; selectedIds = emptySet() }
    fun toggle(id: String) { if (!state.batchBusy) selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun queryChanged(query: LibraryQuery) { selectedIds = emptySet(); historyViewModel.setLibraryQuery(query) }

    fun controlsChanged(query: LibraryQuery) { focusManager.clearFocus(); queryChanged(query) }
    val vaultScope = rememberCoroutineScope()
    fun unlockVault() {
        val query = state.libraryQuery
        if (query.vaultUnlocked) { controlsChanged(query.copy(vaultUnlocked = false, vaultOnly = false)); return }
        val activity = context.findActivity()
        if (activity == null) { vaultScope.launch { snackbar.showSnackbar(resources.getString(R.string.vault_unavailable)) }; return }
        val authenticator = ServiceLocator.vaultAuthenticator
        if (!authenticator.isAvailable(context)) {
            vaultScope.launch { snackbar.showSnackbar(resources.getString(R.string.vault_no_keyguard)) }
            return
        }
        val started = authenticator.authenticate(activity) { granted ->
            if (granted) controlsChanged(query.copy(vaultUnlocked = true, vaultOnly = true))
            else vaultScope.launch { snackbar.showSnackbar(resources.getString(R.string.vault_denied)) }
        }
        if (!started) vaultScope.launch { snackbar.showSnackbar(resources.getString(R.string.vault_unavailable)) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ids = pendingExport.toSet(); pendingExport = emptyList()
        if (granted) historyViewModel.exportSelected(ids, galleryOperation) else historyViewModel.showMessage(resources.getString(R.string.library_need_storage_permission))
    }
    pendingDelete?.let { ids ->
        AppConfirmDialog(
            onDismissRequest = { pendingDelete = null },
            icon = AppIcons.Delete,
            title = stringResource(R.string.library_delete_selected_title),
            body = stringResource(R.string.library_delete_selected_body, ids.size),
            confirmText = stringResource(R.string.action_confirm_delete),
            onConfirm = { historyViewModel.deleteSelected(ids); pendingDelete = null },
            dismissText = stringResource(R.string.action_cancel),
            destructive = true,
        )
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
                title = stringResource(R.string.library_title),
                subtitle = if (selecting) null else stringResource(R.string.library_subtitle, allSaved.size),
                modifier = Modifier.padding(horizontal = 22.dp),
                trailingContent = {
                    IconButton(
                        onClick = { layout = nextLayout; ServiceLocator.preferences.libraryLayout = nextLayout },
                        modifier = Modifier.semantics { stateDescription = layoutDescription },
                    ) {
                        AppIcon(if (nextLayout == LibraryLayout.LIST) AppIcons.List else AppIcons.Grid,
                            contentDescription = stringResource(R.string.library_switch_layout, stringResource(libraryLayoutLabelRes(nextLayout))), tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
            OutlinedTextField(value = state.libraryQuery.text,
                onValueChange = { queryChanged(state.libraryQuery.copy(text = it)) },
                // 「已存」的搜索框与「任务」页保持一致，圆角同「解析」页的链接输入框（shapes.large）。
                shape = MaterialTheme.shapes.large,
                label = { Text(stringResource(R.string.history_search_hint)) }, singleLine = true, enabled = !state.batchBusy,
                leadingIcon = { AppIcon(AppIcons.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                trailingIcon = { if (state.libraryQuery.text.isNotEmpty()) TextButton(onClick = { queryChanged(state.libraryQuery.copy(text = "")) }) { Text(stringResource(R.string.action_clear)) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp).testTag("library-search"))
            Row(Modifier.fillMaxWidth().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(modifier = Modifier.weight(1f).testTag("library-controls"),
                contentPadding = PaddingValues(start = 22.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                item {
                    FilterChip(selected = state.libraryQuery.vaultOnly, enabled = !state.batchBusy,
                        onClick = { unlockVault() },
                        label = { Text(stringResource(if (state.libraryQuery.vaultUnlocked) R.string.vault_open else R.string.vault_locked)) },
                        modifier = Modifier.testTag("vault-chip"))
                }
                item {
                    LibraryPicker(stringResource(R.string.library_pick_platform), state.libraryQuery.platform?.let { stringResource(videoPlatformLabelRes(it)) } ?: stringResource(R.string.library_all_platforms),
                        state.libraryQuery.platform, listOf(null to stringResource(R.string.library_all_platforms)) + VideoPlatform.entries.map { it to stringResource(videoPlatformLabelRes(it)) },
                        enabled = !state.batchBusy) { controlsChanged(state.libraryQuery.copy(platform = it)) }
                }
                item {
                    LibraryPicker(stringResource(R.string.library_pick_folder), state.libraryQuery.folder?.ifBlank { stringResource(R.string.library_uncategorized) } ?: stringResource(R.string.library_all_folders),
                        state.libraryQuery.folder,
                        listOf(null to stringResource(R.string.library_all_folders)) + (allSaved.map { it.folder } + "").distinct().sorted().map { it to it.ifBlank { stringResource(R.string.library_uncategorized) } },
                        enabled = !state.batchBusy && !organization.busy) { controlsChanged(state.libraryQuery.copy(folder = it)) }
                }
                item {
                    LibraryPicker(stringResource(R.string.library_pick_sort), stringResource(librarySortLabelRes(state.libraryQuery.sort)), state.libraryQuery.sort,
                        LibrarySort.entries.map { it to stringResource(librarySortLabelRes(it)) }, enabled = !state.batchBusy) { sort ->
                        // 手动顺序重启后还要看得见，所以排序方式本身也要落盘（同 libraryLayout 的做法）。
                        ServiceLocator.preferences.librarySort = sort
                        controlsChanged(state.libraryQuery.copy(sort = sort))
                    }
                }
                item { FilterChip(selected = state.libraryQuery.favoritesOnly, enabled = !state.batchBusy && !organization.busy,
                    onClick = { controlsChanged(state.libraryQuery.copy(favoritesOnly = !state.libraryQuery.favoritesOnly)) }, label = { Text(stringResource(R.string.library_favorites_only)) }) }
            }
                if (state.libraryQuery.vaultUnlocked) {
                    TextButton(onClick = { unlockVault() }, modifier = Modifier.testTag("vault-lock")) { Text(stringResource(R.string.vault_lock_action)) }
                }
                TextButton(onClick = { focusManager.clearFocus(); selecting = !selecting; selectedIds = emptySet() },
                    enabled = !state.batchBusy && allSaved.isNotEmpty()) { Text(stringResource(if (selecting) R.string.action_exit_multi_select else R.string.action_multi_select)) }
            }
            if (selecting) SelectionToolbar(selectedIds.size, state.batchBusy, selectedIds == visibleIds && visibleIds.isNotEmpty(),
                onSelectAll = { selectedIds = if (selectedIds == visibleIds) emptySet() else visibleIds },
                onExit = { selecting = false; selectedIds = emptySet() },
                actions = listOf(galleryAction to {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        pendingExport = selectedIds.toList(); permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else historyViewModel.exportSelected(selectedIds, galleryOperation)
                }, stringResource(R.string.library_export_folder) to { fileExportViewModel.reset(); fileExportIds = selectedIds.toList() },
                    stringResource(R.string.history_delete_selected) to { pendingDelete = selectedIds.toSet() }))
            if (editingId == null) organization.error?.let { Text(organizeErrorText(it), color = MaterialTheme.colorScheme.error, maxLines = 2, modifier = Modifier.padding(horizontal = 22.dp)) }
            if (fileExportIds.isEmpty()) fileExport.message?.let { Text(it, maxLines = 2, modifier = Modifier.padding(horizontal = 22.dp)) }
            BatchFailureDetails(state.batchResult)
            if (state.batchBusy) Text(stringResource(R.string.history_working, state.batchDone, state.batchTotal), modifier = Modifier.padding(horizontal = 22.dp))
            if (saved.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 只在「整个已存是空的」时候插画：被搜索/过滤筛空是另一回事，配图会让人困惑。
                        if (allSaved.isEmpty()) {
                            EmptyStateIllustration(Modifier.padding(bottom = 20.dp))
                        }
                        Text(stringResource(if (allSaved.isEmpty()) R.string.library_empty else R.string.library_empty_filtered), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
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
                    items(displayed, key = { it.id }) { record ->
                        val watch = watchBadge(record, watchProgress)
                        ReorderableItem(reorderableGridState, key = record.id) { dragging ->
                        Column(Modifier.animateItem(
                            fadeInSpec = motion.fast(),
                            placementSpec = motion.normal(),
                            fadeOutSpec = motion.fast(),
                        ).combinedClickable(
                                role = Role.Button,
                                onClickLabel = stringResource(if (selecting) R.string.library_click_select else R.string.library_click_play),
                                onLongClickLabel = stringResource(R.string.library_long_click),
                                onClick = { if (selecting) toggle(record.id) else if (!state.batchBusy) onPlay(record.id) },
                                onLongClick = { if (selecting) toggle(record.id) else if (!state.batchBusy) selectedRecordId = record.id },
                            )) {
                            LibraryCover(record, Modifier.fillMaxWidth()
                                .sharedCover(record.id, sharedTransitionScope, animatedVisibilityScope, inOverlay = true), adaptToCover = true,
                                watch = watch,
                                selected = if (selecting) record.id in selectedIds else null,
                                selectionEnabled = !state.batchBusy, onSelect = { toggle(record.id) })
                            Text(record.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 9.dp))
                            LibraryMetadata(record, Modifier.padding(top = 4.dp), watch)
                            if (canReorder) {
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                                    Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .85f))) {
                                        val actions = accessibilityActions(record.id)
                                        AppIcon(AppIcons.Sort, stringResource(R.string.library_drag_handle),
                                            modifier = Modifier.padding(6.dp).size(20.dp).draggableHandle()
                                                .semantics { customActions = actions }
                                                .testTag("library-drag-${record.id}"),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
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
                    items(displayed, key = { it.id }) { record ->
                        val watch = watchBadge(record, watchProgress)
                        ReorderableItem(reorderableListState, key = record.id) { dragging ->
                        Row(
                            modifier = Modifier.animateItem(
                                fadeInSpec = motion.fast(),
                                placementSpec = motion.normal(),
                                fadeOutSpec = motion.fast(),
                            ).fillMaxWidth().clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .combinedClickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(if (selecting) R.string.library_click_select else R.string.library_click_play),
                                    onLongClickLabel = stringResource(R.string.library_long_click),
                                    onClick = { if (selecting) toggle(record.id) else if (!state.batchBusy) onPlay(record.id) },
                                    onLongClick = { if (selecting) toggle(record.id) else if (!state.batchBusy) selectedRecordId = record.id },
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            LibraryCover(record, Modifier.width(108.dp)
                                .sharedCover(record.id, sharedTransitionScope, animatedVisibilityScope, inOverlay = true),
                                watch = watch,
                                selected = if (selecting) record.id in selectedIds else null,
                                selectionEnabled = !state.batchBusy, onSelect = { toggle(record.id) })
                            Column(Modifier.weight(1f)) {
                                Text(record.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(authorText(record.displayAuthor), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 5.dp))
                                LibraryMetadata(record, Modifier.padding(top = 5.dp), watch)
                            }
                            if (canReorder) {
                                val actions = accessibilityActions(record.id)
                                AppIcon(AppIcons.Sort, stringResource(R.string.library_drag_handle),
                                    modifier = Modifier.size(20.dp).draggableHandle()
                                        .semantics { customActions = actions }
                                        .testTag("library-drag-${record.id}"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
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
            onToggleVault = { record ->
                organizationViewModel.update(record.id, isPrivate = !record.isPrivate) {
                    if (record.isPrivate) historyViewModel.showMessage(resources.getString(R.string.vault_removed))
                    else historyViewModel.showMessage(resources.getString(R.string.vault_added))
                }
            },
            vaultUnlocked = state.libraryQuery.vaultUnlocked,
            organizing = organization.busy || fileExport.busy,
            onFileExport = { fileExportViewModel.reset(); fileExportIds = listOf(it.id) },
            onExtractAudio = { audioExtractionViewModel.reset(); extractingId = it.id },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun LibraryCover(record: DownloadRecord, modifier: Modifier, adaptToCover: Boolean = false,
    watch: WatchBadge? = null,
    selected: Boolean? = null, selectionEnabled: Boolean = true, onSelect: () -> Unit = {}) {
    var coverAspectRatio by rememberSaveable(record.coverUrl) { mutableFloatStateOf(3f / 4f) }
    // 封面解码出来之前显示骨架屏扫光；加载完成后停止，避免常驻的无限动画。
    var coverLoaded by remember(record.coverUrl) { mutableStateOf(false) }
    val showShimmer = !record.coverUrl.isNullOrBlank() && !coverLoaded
    Box(
        modifier.aspectRatio(if (adaptToCover) coverAspectRatio else 4f / 3f)
            .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (showShimmer) Modifier.shimmer() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(if (record.isAudio) AppIcons.Audio else AppIcons.Play, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(
            model = record.coverUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onSuccess = { result ->
                coverLoaded = true
                // Coil 3：State.Success.result 是 SuccessResult，图片本体在它的 image 上。
                val image = result.result.image
                if (adaptToCover && image.width > 0 && image.height > 0) {
                    coverAspectRatio = (image.width.toFloat() / image.height).coerceIn(.5f, 2f)
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
        // A partly watched file gets a thin bar right on the cover, so the list reads at a glance.
        if (watch != null) {
            LinearProgressIndicator(
                progress = { watch.fraction },
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Black.copy(alpha = .35f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                    .testTag("watch-progress-${record.id}"),
            )
        }
    }
}

@Composable
private fun LibraryMetadata(record: DownloadRecord, modifier: Modifier = Modifier, watch: WatchBadge? = null) {
    if (record.isAudio) Text(stringResource(R.string.library_audio_m4a), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
    Text(
        listOfNotNull(relativeTimeText(relativeTime(record.updatedAt)), watch?.let { watchBadgeText(it.fraction) }).joinToString(" · "),
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

/** The vault prompt needs an Activity; Compose only hands out a Context. */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The wording for an organise failure, shown inline on the library screen as well. */
@Composable
internal fun organizeErrorText(error: LibraryOrganizeError): String = when (error) {
    LibraryOrganizeError.Failed -> stringResource(R.string.organize_failed)
    LibraryOrganizeError.Incomplete -> stringResource(R.string.organize_incomplete)
}

/** The author line, with the wording for a source that gave no name. */
@Composable
internal fun authorText(author: String): String =
    author.ifBlank { stringResource(R.string.unknown_author) }
