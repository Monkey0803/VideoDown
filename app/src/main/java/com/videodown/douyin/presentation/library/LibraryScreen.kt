package com.videodown.douyin.presentation.library

import com.videodown.douyin.R
import com.videodown.douyin.presentation.videoPlatformLabelRes
import com.videodown.douyin.presentation.libraryLayoutLabelRes
import com.videodown.douyin.presentation.librarySortLabelRes
import com.videodown.douyin.presentation.history.BatchOperation
import com.videodown.douyin.presentation.history.contextOperationLabel
import com.videodown.douyin.presentation.components.TabPageHeader
import com.videodown.douyin.presentation.components.SelectionToolbar
import com.videodown.douyin.presentation.components.AppConfirmDialog
import com.videodown.douyin.core.LibrarySort
import com.videodown.douyin.presentation.components.EmptyState
import com.videodown.douyin.presentation.components.sharedCover
import com.videodown.douyin.presentation.history.BatchFailureDetails
import com.videodown.douyin.presentation.theme.AppSpacing
import com.videodown.douyin.presentation.theme.appMotion
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.components.MediaCover
import com.videodown.douyin.presentation.components.CoverChip
import com.videodown.douyin.presentation.components.CoverSelectionMark
import com.videodown.douyin.presentation.components.AdaptiveWorkspacePage
import com.videodown.douyin.presentation.adaptive.LocalWindowWidth
import com.videodown.douyin.presentation.adaptive.WindowLayoutPolicy
import com.videodown.douyin.presentation.adaptive.pagePadding

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import com.videodown.douyin.domain.model.VideoPlatform
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.core.LibraryLayout
import com.videodown.douyin.core.VaultAccess
import com.videodown.douyin.core.WatchBadge
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.relativeTime
import com.videodown.douyin.presentation.relativeTimeText
import com.videodown.douyin.presentation.watchBadgeText
import com.videodown.douyin.presentation.imagePostKey
import com.videodown.douyin.presentation.imagePostSizes
import com.videodown.douyin.core.watchBadge
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.presentation.history.HistoryViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(historyViewModel: HistoryViewModel, onPlay: (String) -> Unit,
    watchProgress: Map<String, com.videodown.douyin.domain.model.WatchProgress> = emptyMap(),
    organizationViewModel: LibraryOrganizationViewModel = viewModel(),
    fileExportViewModel: FileExportViewModel = viewModel(),
    audioExtractionViewModel: AudioExtractionViewModel = viewModel(),
    // 共享元素作用域由 MainActivity 的 SharedTransitionLayout 传入；直接渲染本界面的设备测试不传，
    // 此时封面不带共享修饰，行为与改造前一致。
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onOpenParse: () -> Unit = {},
    /** 文件缺失/损坏时：按原地址重新下载，或回到解析页重新解析。 */
    onRedownload: (com.videodown.douyin.domain.model.DownloadRecord) -> Unit = {},
    onReparse: (com.videodown.douyin.domain.model.DownloadRecord) -> Unit = {}) {
    val motion = appMotion()
    val context = LocalContext.current
    // 一列 Feed 的列数与页面边距都由窗口尺寸类决定，页内不再各写 22dp / 固定 2 列。
    val windowWidth = LocalWindowWidth.current
    val pagePadding = pagePadding()
    val feedColumns = WindowLayoutPolicy.feedColumns(windowWidth)
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
    val state by historyViewModel.state.collectAsStateWithLifecycle()
    var selecting by remember { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var filterDraft by remember { mutableStateOf(state.libraryQuery) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 多选时的区间锚点：长按另一张即可选中两点之间的全部条目。
    var selectionAnchor by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
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
    // 只在这里 stat 一次文件；页面重组（选择、弹窗、滚动状态）不再对整库做 File.isFile。
    val allSaved = remember(state.records, state.libraryQuery.vaultUnlocked) {
        state.records.filter { state.statusFor(it) == DownloadStatus.COMPLETED &&
            it.filePath?.let { path -> File(path).isFile } == true &&
            (!it.isPrivate || state.libraryQuery.vaultUnlocked) }
    }
    val savedInventoryCount = remember(state.records) {
        state.records.count { state.statusFor(it) == DownloadStatus.COMPLETED &&
            it.filePath?.let { path -> File(path).isFile } == true }
    }
    val showManagementControls = com.videodown.douyin.presentation.UiDensityPolicy.showManagementControls(
        itemCount = savedInventoryCount,
        // 单纯记住了排序方式不会把空库筛空；只有搜索/筛选需要保留控件让用户清除。
        hasActiveQuery = state.libraryQuery.text.isNotBlank() || state.libraryQuery.activeFilterCount > 0,
    )
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
    // 拖拽只在「自定义排序 + 没有搜索/过滤 + 非多选非批量忙」时开放，判定集中在 LibraryQuery 里。
    val canReorder = state.libraryQuery.canReorder(selecting, state.batchBusy)
    // 拖拽期间先按本地顺序显示，松手后落盘；落盘回流的新数据会取代本地顺序。
    var draggedOrder by remember { mutableStateOf<List<String>?>(null) }
    val displayed = draggedOrder?.let { order -> order.mapNotNull { id -> saved.firstOrNull { it.id == id } } } ?: saved
    // 同一作品的多张图在网格里只需要一个"N 张"角标；只统计当前可见库，避免把保险箱里的张数透出来。
    val imageGroupCounts = remember(allSaved) { imagePostSizes(allSaved) }
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
        if (state.libraryQuery.ordersManually && allSaved.isNotEmpty() && allSaved.none { it.sortIndex > 0L }) {
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
    BackHandler(selecting && !state.batchBusy) { selecting = false; selectedIds = emptySet(); selectionAnchor = null }
    fun toggle(id: String) {
        if (state.batchBusy) return
        selectionAnchor = id
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    /** 长按区间选择：取锚点到当前项的连续区间；没有锚点时等价于选中当前项。 */
    fun selectRangeTo(id: String) {
        if (state.batchBusy) return
        val range = selectionRange(displayed.map { it.id }, selectionAnchor, id)
        if (range.isEmpty()) return
        selectedIds = selectedIds + range
        selectionAnchor = id
    }
    fun queryChanged(query: LibraryQuery) { selectedIds = emptySet(); historyViewModel.setLibraryQuery(query) }

    fun controlsChanged(query: LibraryQuery) { focusManager.clearFocus(); queryChanged(query) }
    fun openFilters() {
        filterDraft = state.libraryQuery
        showFilters = true
    }
    val vaultScope = rememberCoroutineScope()
    fun unlockVault() {
        val query = state.libraryQuery
        if (query.vaultUnlocked) {
            VaultAccess.lock()
            controlsChanged(query.copy(vaultUnlocked = false, vaultOnly = false))
            return
        }
        val activity = context.findActivity()
        if (activity == null) { vaultScope.launch { snackbar.showSnackbar(resources.getString(R.string.vault_unavailable)) }; return }
        val authenticator = ServiceLocator.vaultAuthenticator
        if (!authenticator.isAvailable(context)) {
            vaultScope.launch { snackbar.showSnackbar(resources.getString(R.string.vault_no_keyguard)) }
            return
        }
        val started = authenticator.authenticate(activity) { granted ->
            if (granted) {
                VaultAccess.unlock()
                controlsChanged(query.copy(vaultUnlocked = true, vaultOnly = true))
            } else vaultScope.launch { snackbar.showSnackbar(resources.getString(R.string.vault_denied)) }
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
    if (showFilters) {
        ModalBottomSheet(
            onDismissRequest = { showFilters = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = pagePadding, vertical = AppSpacing.xs)
                    .testTag("library-controls"),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                Text(stringResource(R.string.library_filter_title), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                LibraryPicker(stringResource(R.string.library_pick_platform), filterDraft.platform?.let { stringResource(videoPlatformLabelRes(it)) } ?: stringResource(R.string.library_all_platforms),
                    filterDraft.platform, listOf(null to stringResource(R.string.library_all_platforms)) + VideoPlatform.entries.map { it to stringResource(videoPlatformLabelRes(it)) },
                    enabled = !state.batchBusy) { filterDraft = filterDraft.copy(platform = it) }
                LibraryPicker(stringResource(R.string.library_pick_kind), stringResource(libraryKindLabelRes(filterDraft.mediaKind)),
                    filterDraft.mediaKind, listOf(null to stringResource(R.string.library_kind_all)) + MediaKind.entries.map { it to stringResource(libraryKindLabelRes(it)) },
                    enabled = !state.batchBusy) { filterDraft = filterDraft.copy(mediaKind = it) }
                LibraryPicker(stringResource(R.string.library_pick_folder), filterDraft.folder?.ifBlank { stringResource(R.string.library_uncategorized) } ?: stringResource(R.string.library_all_folders),
                    filterDraft.folder, listOf(null to stringResource(R.string.library_all_folders)) + (allSaved.map { it.folder } + "").distinct().sorted().map { it to it.ifBlank { stringResource(R.string.library_uncategorized) } },
                    enabled = !state.batchBusy && !organization.busy) { filterDraft = filterDraft.copy(folder = it) }
                LibraryPicker(stringResource(R.string.library_pick_sort), stringResource(librarySortLabelRes(filterDraft.sort)), filterDraft.sort,
                    LibrarySort.entries.map { it to stringResource(librarySortLabelRes(it)) }, enabled = !state.batchBusy) { filterDraft = filterDraft.copy(sort = it) }
                FilterChip(
                    selected = filterDraft.favoritesOnly,
                    enabled = !state.batchBusy && !organization.busy,
                    onClick = { filterDraft = filterDraft.copy(favoritesOnly = !filterDraft.favoritesOnly) },
                    label = { Text(stringResource(R.string.library_favorites_only)) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { filterDraft = filterDraft.clearFilters() }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.library_clear_filters))
                    }
                    FilledTonalButton(onClick = {
                        ServiceLocator.preferences.librarySort = filterDraft.sort
                        controlsChanged(filterDraft)
                        showFilters = false
                    }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.library_apply_filters))
                    }
                }
            }
        }
    }
    AdaptiveWorkspacePage(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(Modifier.fillMaxWidth().fillMaxHeight()) {
        Column(Modifier.fillMaxSize()) {
            TabPageHeader(
                title = stringResource(R.string.library_title),
                subtitle = if (selecting) null else stringResource(R.string.library_subtitle, allSaved.size),
                modifier = Modifier.padding(horizontal = pagePadding),
                trailingContent = {
                    if (allSaved.isNotEmpty()) {
                    IconButton(
                        onClick = { layout = nextLayout; ServiceLocator.preferences.libraryLayout = nextLayout },
                        modifier = Modifier.semantics { stateDescription = layoutDescription },
                    ) {
                        AppIcon(if (nextLayout == LibraryLayout.LIST) AppIcons.List else AppIcons.Grid,
                            contentDescription = stringResource(R.string.library_switch_layout, stringResource(libraryLayoutLabelRes(nextLayout))), tint = MaterialTheme.colorScheme.primary)
                    }
                    }
                },
            )
            if (showManagementControls) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = pagePadding, vertical = AppSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            OutlinedTextField(value = state.libraryQuery.text,
                onValueChange = { queryChanged(state.libraryQuery.copy(text = it)) },
                // 「已存」的搜索框与「任务」页保持一致，圆角同「解析」页的链接输入框（shapes.large）。
                shape = MaterialTheme.shapes.large,
                label = { Text(stringResource(R.string.history_search_hint)) }, singleLine = true, enabled = !state.batchBusy,
                leadingIcon = { AppIcon(AppIcons.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                trailingIcon = { if (state.libraryQuery.text.isNotEmpty()) TextButton(onClick = { queryChanged(state.libraryQuery.copy(text = "")) }) { Text(stringResource(R.string.action_clear)) } },
                modifier = Modifier.weight(1f).testTag("library-search"))
                FilledTonalButton(onClick = ::openFilters, enabled = !state.batchBusy, modifier = Modifier.heightIn(min = 56.dp).testTag("library-filter")) {
                    AppIcon(AppIcons.Sort, null, modifier = Modifier.size(18.dp))
                    Text(
                        if (state.libraryQuery.activeFilterCount > 0) stringResource(R.string.library_filter_count, state.libraryQuery.activeFilterCount)
                        else stringResource(R.string.library_filter),
                        modifier = Modifier.padding(start = AppSpacing.xxs),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = pagePadding - AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = state.libraryQuery.vaultOnly, enabled = !state.batchBusy,
                    onClick = { unlockVault() },
                    label = { Text(stringResource(if (state.libraryQuery.vaultUnlocked) R.string.vault_open else R.string.vault_locked)) },
                    modifier = Modifier.testTag("vault-chip"))
                if (state.libraryQuery.vaultUnlocked) {
                    TextButton(onClick = { unlockVault() }, modifier = Modifier.testTag("vault-lock")) { Text(stringResource(R.string.vault_lock_action)) }
                }
                Spacer(Modifier.weight(1f))
                if (!selecting) {
                    Box {
                        TextButton(
                            onClick = { sortExpanded = true },
                            enabled = !state.batchBusy,
                            modifier = Modifier.testTag("library-sort"),
                        ) {
                            AppIcon(AppIcons.Sort, null, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(librarySortLabelRes(state.libraryQuery.sort)),
                                modifier = Modifier.padding(start = AppSpacing.xxs),
                            )
                        }
                        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                            LibrarySort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(librarySortLabelRes(sort))) },
                                    onClick = {
                                        sortExpanded = false
                                        ServiceLocator.preferences.librarySort = sort
                                        queryChanged(state.libraryQuery.copy(sort = sort))
                                    },
                                )
                            }
                        }
                    }
                    TextButton(onClick = { focusManager.clearFocus(); selecting = true; selectedIds = emptySet(); selectionAnchor = null },
                        enabled = !state.batchBusy && allSaved.isNotEmpty()) { Text(stringResource(R.string.action_multi_select)) }
                }
            }
            }
            if (selecting) SelectionToolbar(selectedIds.size, state.batchBusy, selectedIds == visibleIds && visibleIds.isNotEmpty(),
                onSelectAll = { selectedIds = if (selectedIds == visibleIds) emptySet() else visibleIds },
                onExit = { selecting = false; selectedIds = emptySet(); selectionAnchor = null },
                actions = listOf(galleryAction to {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        pendingExport = selectedIds.toList(); permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else historyViewModel.exportSelected(selectedIds, galleryOperation)
                }, stringResource(R.string.library_export_folder) to { fileExportViewModel.reset(); fileExportIds = selectedIds.toList() },
                    stringResource(R.string.history_delete_selected) to { pendingDelete = selectedIds.toSet() }))
            if (editingId == null) organization.error?.let { Text(organizeErrorText(it), color = MaterialTheme.colorScheme.error, maxLines = 2, modifier = Modifier.padding(horizontal = pagePadding)) }
            if (fileExportIds.isEmpty()) fileExport.message?.let { Text(it, maxLines = 2, modifier = Modifier.padding(horizontal = pagePadding)) }
            BatchFailureDetails(state.batchResult)
            if (state.batchBusy) Text(stringResource(R.string.history_working, state.batchDone, state.batchTotal), modifier = Modifier.padding(horizontal = pagePadding))
            if (!state.hydrated) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (saved.isEmpty()) {
                // 只在「整个已存是空的」时候插画：被搜索/过滤筛空是另一回事，配图会让人困惑。
                EmptyState(
                    title = stringResource(if (allSaved.isEmpty()) R.string.library_empty else R.string.library_empty_filtered),
                    body = if (allSaved.isEmpty()) stringResource(R.string.library_empty_hint) else null,
                    showIllustration = allSaved.isEmpty(),
                    actionLabel = stringResource(if (allSaved.isEmpty()) R.string.library_start_download else R.string.library_clear_filters),
                    onAction = if (allSaved.isEmpty()) onOpenParse else ({ controlsChanged(state.libraryQuery.clearFilters()) }),
                )
            } else if (layout == LibraryLayout.WATERFALL) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(feedColumns),
                    state = waterfallState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = pagePadding, end = pagePadding, top = AppSpacing.lg, bottom = AppSpacing.lg),
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
                        ).testTag("library-item-${record.id}").combinedClickable(
                                role = Role.Button,
                                onClickLabel = stringResource(if (selecting) R.string.library_click_select else libraryClickRes(record)),
                                onLongClickLabel = stringResource(R.string.library_long_click),
                                onClick = { if (selecting) toggle(record.id) else if (!state.batchBusy) onPlay(record.id) },
                                onLongClick = { if (selecting) selectRangeTo(record.id) else if (!state.batchBusy) selectedRecordId = record.id },
                            )) {
                            LibraryCover(record, Modifier.fillMaxWidth()
                                .sharedCover(record.id, sharedTransitionScope, animatedVisibilityScope, inOverlay = true), adaptToCover = true,
                                watch = watch,
                                groupCount = imageGroupCounts[imagePostKey(record)]?.takeIf { it > 1 },
                                selected = if (selecting) record.id in selectedIds else null,
                                selectionEnabled = !state.batchBusy, onSelect = { toggle(record.id) },
                                onMore = if (selecting || state.batchBusy) null else ({ selectedRecordId = record.id }))
                            Text(record.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 9.dp))
                            LibraryMetadata(record, Modifier.padding(top = 4.dp), watch)
                            if (canReorder) {
                                Row(Modifier.fillMaxWidth().padding(top = AppSpacing.xxs), horizontalArrangement = Arrangement.End) {
                                    // 与封面右上角的「更多」共用同一个圆底，避免两个角上的控件一深一浅。
                                    CoverChip {
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
                    contentPadding = PaddingValues(start = pagePadding, end = pagePadding, top = AppSpacing.lg, bottom = AppSpacing.lg),
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
                            ).testTag("library-item-${record.id}").fillMaxWidth().clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .combinedClickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(if (selecting) R.string.library_click_select else libraryClickRes(record)),
                                    onLongClickLabel = stringResource(R.string.library_long_click),
                                    onClick = { if (selecting) toggle(record.id) else if (!state.batchBusy) onPlay(record.id) },
                                    onLongClick = { if (selecting) selectRangeTo(record.id) else if (!state.batchBusy) selectedRecordId = record.id },
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            LibraryCover(record, Modifier.width(108.dp)
                                .sharedCover(record.id, sharedTransitionScope, animatedVisibilityScope, inOverlay = true),
                                watch = watch,
                                groupCount = imageGroupCounts[imagePostKey(record)]?.takeIf { it > 1 },
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
                            if (!selecting) {
                                IconButton(
                                    onClick = { selectedRecordId = record.id },
                                    modifier = Modifier.size(48.dp).testTag("library-more-${record.id}"),
                                ) {
                                    AppIcon(AppIcons.More, stringResource(R.string.library_more_actions),
                                        modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
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
            onRedownload = onRedownload,
            onReparse = onReparse,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }
}

@Composable
private fun LibraryCover(record: DownloadRecord, modifier: Modifier, adaptToCover: Boolean = false,
    watch: WatchBadge? = null,
    groupCount: Int? = null,
    selected: Boolean? = null, selectionEnabled: Boolean = true, onSelect: () -> Unit = {},
    onMore: (() -> Unit)? = null) {
    var coverAspectRatio by rememberSaveable(record.coverUrl) { mutableFloatStateOf(3f / 4f) }
    MediaCover(
        // 图片记录直接显示本地文件：CDN 链接会过期，已下载的内容不该因此变成灰底。
        model = if (record.isImage) record.filePath?.let(::File)?.takeIf(File::isFile) ?: record.coverUrl else record.coverUrl,
        modifier = modifier.aspectRatio(if (adaptToCover) coverAspectRatio else 4f / 3f),
        shape = MaterialTheme.shapes.medium,
        placeholderIcon = when {
            record.isAudio -> AppIcons.Audio
            record.isImage -> AppIcons.Grid
            record.isArchive -> AppIcons.ExportFolder
            else -> AppIcons.Play
        },
        // 封面解码出来之前骨架屏扫光；加载完成后由 MediaCover 内部停止。
        shimmer = true,
        onImageSize = { width, height ->
            if (adaptToCover && width > 0 && height > 0) {
                coverAspectRatio = (width.toFloat() / height).coerceIn(.5f, 2f)
            }
        },
    ) {
        if (selected != null) {
            CoverSelectionMark(
                selected = selected,
                enabled = selectionEnabled,
                contentDescription = record.title,
                onToggle = onSelect,
                modifier = Modifier.align(Alignment.TopStart).padding(AppSpacing.xxs),
            )
        }
        if (onMore != null) {
            // 48dp 命中区贴角，可见的只有居中的 32dp 圆：视觉距封面边缘 8dp，不再压住圆角。
            IconButton(
                onClick = onMore,
                modifier = Modifier.align(Alignment.TopEnd).size(48.dp).testTag("library-more-${record.id}"),
            ) {
                CoverChip {
                    AppIcon(AppIcons.More, stringResource(R.string.library_more_actions),
                        modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (record.isLivePhoto || groupCount != null) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (record.isLivePhoto) {
                    CoverChip(size = 22.dp) {
                        AppIcon(
                            AppIcons.Play,
                            stringResource(R.string.viewer_live_photo_badge),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                groupCount?.let {
                    Text(
                        stringResource(R.string.library_group_count, it),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clip(MaterialTheme.shapes.extraSmall)
                            .background(Color.Black.copy(alpha = .5f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
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

/** 类型筛选的显示名；`null` 表示"全部类型"。 */
private fun libraryKindLabelRes(kind: MediaKind?): Int = when (kind) {
    null -> R.string.library_kind_all
    MediaKind.VIDEO -> R.string.library_kind_video
    MediaKind.AUDIO -> R.string.library_kind_audio
    MediaKind.IMAGE -> R.string.library_kind_image
    MediaKind.ARCHIVE -> R.string.library_kind_archive
}

/** 点击已存的落点随记录类型变化：图片查看、压缩包打开、其余播放。 */
private fun libraryClickRes(record: DownloadRecord): Int = when {
    record.isImage -> R.string.library_click_view_image
    record.isArchive -> R.string.library_click_open_archive
    else -> R.string.library_click_play
}

@Composable
private fun LibraryMetadata(record: DownloadRecord, modifier: Modifier = Modifier, watch: WatchBadge? = null) {
    when {
        record.isAudio -> Text(stringResource(R.string.library_audio_m4a), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        record.isImage -> Text(
            stringResource(
                R.string.library_image_label,
                record.filePath?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.isNotBlank() } ?: "JPG",
            ),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
        )
        record.isArchive -> Text(
            stringResource(R.string.library_archive_entries, record.archiveEntries.size),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
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
