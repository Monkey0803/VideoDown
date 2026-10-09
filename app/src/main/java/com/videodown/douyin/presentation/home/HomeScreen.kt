package com.videodown.douyin.presentation.home

import com.videodown.douyin.presentation.components.TabPageHeader
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.RecoveryAction
import com.videodown.douyin.domain.model.failureGuidance
import com.videodown.douyin.domain.model.selectionKey
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.videodown.douyin.core.DouyinIntents
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.core.formatDuration
import com.videodown.douyin.data.remote.VideoLinkExtractor
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    sharedText: String?,
    onSharedTextConsumed: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLibrary: () -> Unit,
    onPlayDownloaded: (String) -> Unit,
    recentRecords: List<DownloadRecord>,
    viewModel: HomeViewModel = viewModel(),
    onOpenStorage: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    openBatchImport: Boolean = false,
    onBatchImportOpened: () -> Unit = {},
    batchImportModel: BatchImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputArea = remember { BringIntoViewRequester() }
    var inputFocused by remember { mutableStateOf(false) }
    var showBrowser by remember(state.parseRequestId) { mutableStateOf(false) }
    var showBatchImport by rememberSaveable { mutableStateOf(false) }
    if (showBatchImport) BatchImportDialog(batchImportModel, { showBatchImport = false }, onOpenHistory)
    LaunchedEffect(openBatchImport) {
        if (openBatchImport) { showBatchImport = true; onBatchImportOpened() }
    }
    LaunchedEffect(state.browserRequestUrl) {
        if (state.browserRequestUrl != null) { showBrowser = true; viewModel.onBrowserRequestConsumed() }
    }
    val browserUrl = com.videodown.douyin.data.remote.VideoLinkExtractor.extract(state.input)?.url
    BackHandler(enabled = state.video != null && !showBrowser) {
        viewModel.clearResult()
    }
    if (showBrowser && browserUrl != null) {
        com.videodown.douyin.presentation.browser.BrowserAssistScreen(browserUrl,
            onClose = { showBrowser = false },
            onResult = { result -> showBrowser = false; viewModel.acceptBrowserResult(result) })
    }
    LaunchedEffect(state.queuedCount) {
        if (state.queuedCount != null) { viewModel.onQueuedConsumed(); onOpenHistory() }
    }
    state.duplicates?.let { pending ->
        val single = pending.requests.size == 1
        val existing = pending.records.values.first()
        AlertDialog(onDismissRequest = viewModel::dismissDuplicates,
            title = { Text(if (single) "已有相同视频和画质" else "发现 ${pending.records.size} 个重复任务") },
            text = { Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                Text(if (single) "此视频已有下载记录，可以直接查看，或重新保存一份。" else "所选视频中有相同画质的下载记录，跳过重复项将继续下载其余视频。")
                pending.records.values.forEach { record -> Text("${record.title} · ${record.resolutionLabel} · " + when (record.status) {
                    DownloadStatus.COMPLETED -> "已保存"
                    DownloadStatus.PENDING, DownloadStatus.RUNNING -> "下载中"
                    else -> "可继续"
                }, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { TextButton(onClick = {
                if (single) { viewModel.dismissDuplicates(); if (existing.status == DownloadStatus.COMPLETED && existing.filePath?.let { File(it).isFile } == true) onPlayDownloaded(existing.id) else onOpenHistory() }
                else viewModel.confirmDuplicates(downloadAgain = false)
            }) { Text(if (single) if (existing.status == DownloadStatus.COMPLETED) "查看已保存视频" else "查看下载任务" else "跳过重复项") } },
            dismissButton = { Row {
                TextButton(onClick = { viewModel.confirmDuplicates(downloadAgain = true) }) { Text("仍然下载") }
                TextButton(onClick = viewModel::dismissDuplicates) { Text("取消") }
            } })
    }
    val imeVisible = WindowInsets.isImeVisible
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var wasImeVisible by remember { mutableStateOf(false) }

    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    // Include the submit button in the visible area, not just the text cursor.
    LaunchedEffect(inputFocused, imeBottom) {
        if (inputFocused && imeBottom > 0) inputArea.bringIntoView()
    }
    LaunchedEffect(imeVisible) {
        if (wasImeVisible && !imeVisible) focusManager.clearFocus(force = true)
        wasImeVisible = imeVisible
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { dismissKeyboard() }

    // Reading the clipboard is gated on window focus: `hasPrimaryClip()` and `getPrimaryClip()`
    // both report nothing without input focus, and Android 12+ shows a system notice the first time
    // another app's clip is read. Checking the description first does not trigger that notice.
    val clipboardDetect by ServiceLocator.preferences.clipboardDetectChanges.collectAsStateWithLifecycle()
    val rootView = LocalView.current
    fun offerCopiedLink() {
        if (!clipboardDetect) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        if (!clipboard.hasPrimaryClip()) return
        if (clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true) return
        val copied = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        viewModel.offerClipboardText(copied)
    }
    DisposableEffect(rootView, clipboardDetect) {
        if (!clipboardDetect) return@DisposableEffect onDispose { }
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) offerCopiedLink() }
        val observer = rootView.viewTreeObserver
        observer.addOnWindowFocusChangeListener(listener)
        // A window that is already focused never fires the listener, so cover that entry path too.
        if (rootView.hasWindowFocus()) offerCopiedLink()
        onDispose { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
    }

    LaunchedEffect(sharedText) {
        sharedText?.takeIf(String::isNotBlank)?.let {
            dismissKeyboard()
            viewModel.parseSharedText(it)
            onSharedTextConsumed()
        }
    }
    LaunchedEffect(state.errorMessage, state.infoMessage, state.errorKind) {
        (state.errorMessage ?: state.infoMessage)?.let {
            // Persistent inline recovery already explains this parsing failure.
            // A duplicate snackbar can cover its action buttons at the bottom.
            if (state.errorMessage == null || state.errorKind == null || state.video != null) snackbar.showSnackbar(it)
            viewModel.onErrorConsumed()
        }
    }

    Scaffold(
        modifier = Modifier.pointerInput(focusManager, keyboard) {
            detectTapGestures(onTap = { dismissKeyboard() })
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.video == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp),
            ) {
                TabPageHeader("拾影", "粘贴分享链接，保存喜欢的视频。")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(17.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("解析视频", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Text("粘贴链接", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                        state.clipboardOffer?.let { copied ->
                            ClipboardOfferBanner(
                                platformLabel = VideoLinkExtractor.extract(copied)?.platform?.label,
                                onParse = { dismissKeyboard(); viewModel.acceptClipboardOffer() },
                                onDismiss = { dismissKeyboard(); viewModel.dismissClipboardOffer() },
                            )
                        }
                        Column(Modifier.bringIntoViewRequester(inputArea).padding(bottom = 12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(top = 13.dp).clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest).heightIn(min = 72.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                TextField(
                                    value = state.input,
                                    onValueChange = viewModel::onInputChange,
                                    placeholder = { Text("在这里粘贴视频分享链接或分享文案…",
                                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    maxLines = 4,
                                    modifier = Modifier.weight(1f).onFocusChanged { inputFocused = it.isFocused },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                    ),
                                )
                                TextButton(modifier = Modifier.padding(end = 4.dp), onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                        ?.takeIf(String::isNotBlank)?.let {
                                            viewModel.onInputChange(it)
                                            dismissKeyboard()
                                        }
                                }) { Text("粘贴", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium) }
                            }
                            PrimaryButton(
                                text = if (state.isParsing) "解析中…" else "立即解析",
                                onClick = { dismissKeyboard(); viewModel.parse() },
                                enabled = state.canParse,
                                modifier = Modifier.padding(top = 12.dp),
                                loading = state.isParsing,
                            )
                        }
                        TextButton(contentPadding = PaddingValues(vertical = 8.dp), enabled = browserUrl != null && !state.isParsing, onClick = { dismissKeyboard(); showBrowser = true }) {
                            Text("打开网页查找视频")
                        }
                        state.errorKind?.let { kind ->
                            val advice = failureGuidance(kind)
                            Text(advice.title, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                            Text(advice.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            listOfNotNull(advice.action.takeUnless { it == RecoveryAction.NONE }, advice.secondaryAction).forEach { action ->
                                TextButton(contentPadding = PaddingValues(vertical = 8.dp), enabled = !state.isParsing && (action != RecoveryAction.BROWSER || browserUrl != null), onClick = {
                                    dismissKeyboard()
                                    when (action) {
                                        RecoveryAction.BROWSER -> showBrowser = true
                                        RecoveryAction.STORAGE -> onOpenStorage()
                                        RecoveryAction.SETTINGS -> onOpenSettings()
                                        else -> viewModel.parse()
                                    }
                                }) { Text(if (action == RecoveryAction.RETRY) "重新尝试解析" else action.label) }
                            }
                        }
                        TextButton(contentPadding = PaddingValues(vertical = 8.dp), onClick = { dismissKeyboard(); showBatchImport = true }) {
                            AppIcon(AppIcons.BatchImport, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("批量导入多个链接")
                        }
                        Text("支持主流平台与普通网页视频", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 27.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("最近保存", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { dismissKeyboard(); onOpenLibrary() }) {
                        Text("查看全部 →", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                val completed = recentRecords.filter {
                    it.status == com.videodown.douyin.domain.model.DownloadStatus.COMPLETED &&
                        it.filePath?.let { path -> File(path).isFile } == true
                }.take(2)
                if (completed.isEmpty()) {
                    Text("保存的视频会显示在这里", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 25.dp))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 25.dp)) {
                        completed.forEach { record ->
                            Box(
                                modifier = Modifier.weight(1f).height(150.dp).clip(MaterialTheme.shapes.large)
                                    .clickable { dismissKeyboard(); onPlayDownloaded(record.id) },
                            ) {
                                AsyncImage(record.coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .68f)))))
                                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                                    Text(record.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(record.resolutionLabel, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            ResultScreen(
                state = state,
                onBack = viewModel::clearResult,
                onOpenHistory = onOpenHistory,
                onSelectVariant = viewModel::selectVariant,
                onSelectVideo = viewModel::selectVideo,
                onDownload = viewModel::download,
                onToggleMedia = viewModel::toggleMedia,
                onSelectAllMedia = viewModel::selectAllMedia,
                onDownloadSelected = viewModel::downloadSelected,
                onPlayDownloaded = onPlayDownloaded,
                onOpenAuthor = { video ->
                    if (!DouyinIntents.openAuthorProfile(context, video.author)) {
                        scope.launch { snackbar.showSnackbar("未能打开${video.platform.label}账号") }
                    }
                },
                onPreview = { video ->
                    if (!DouyinIntents.openVideo(context, video)) {
                        scope.launch { snackbar.showSnackbar("未能打开${video.platform.label}视频") }
                    }
                },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultScreen(
    state: HomeUiState,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onSelectVariant: (VideoVariant) -> Unit,
    onSelectVideo: (Int) -> Unit,
    onDownload: () -> Unit,
    onToggleMedia: (String) -> Unit,
    onSelectAllMedia: () -> Unit,
    onDownloadSelected: () -> Unit,
    onPlayDownloaded: (String) -> Unit,
    onOpenAuthor: (DouyinVideo) -> Unit,
    onPreview: (DouyinVideo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val video = state.video ?: return
    var showingVideos by rememberSaveable(state.videos.firstOrNull()?.selectionKey()) { mutableStateOf(false) }
    var expandedTitle by rememberSaveable(video.selectionKey(), video.title) { mutableStateOf(false) }
    var titleOverflows by remember(video.selectionKey(), video.title) { mutableStateOf(false) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val screenHeight = with(LocalDensity.current) { windowHeight.toDp().value }
    val batchSelected = state.videos.size > 1 && state.selectedMedia.isNotEmpty()
    val selectableKeys = state.videos.filter { state.variantChoices[it.selectionKey()] != null }.map { it.selectionKey() }.toSet()
    val allSelected = selectableKeys.isNotEmpty() && state.selectedMedia == selectableKeys
    val resultScroll = rememberScrollState()
    LaunchedEffect(video.selectionKey()) { resultScroll.scrollTo(0) }
    if (showingVideos) ModalBottomSheet(
        onDismissRequest = { showingVideos = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = (screenHeight * .8f).dp).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选择视频（${state.videos.size}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onSelectAllMedia, enabled = selectableKeys.isNotEmpty() && !state.isSubmitting && state.duplicates == null) {
                    Text(if (allSelected) "取消全选" else "全选")
                }
            }
            Text("已选 ${state.selectedMedia.size} 个 · 点击封面预览，勾选后批量保存", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), modifier = Modifier.weight(1f, fill = false).testTag("video-selection-grid"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(state.videos, key = { _, item -> item.selectionKey() }) { index, item ->
                    val current = item.selectionKey() == video.selectionKey()
                    val label = item.mediaLabel ?: "视频 ${index + 1}"
                    Card(onClick = { onSelectVideo(index); showingVideos = false }, enabled = !state.isSubmitting && state.duplicates == null,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "预览$label"; selected = current },
                        border = BorderStroke(if (current) 2.dp else 1.dp, if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                            AsyncImage(item.coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Checkbox(checked = item.selectionKey() in state.selectedMedia,
                                enabled = !state.isSubmitting && state.duplicates == null && state.variantChoices[item.selectionKey()] != null,
                                onCheckedChange = { onToggleMedia(item.selectionKey()) },
                                modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), CircleShape)
                                    .semantics { contentDescription = "勾选$label" })
                        }
                        Column(Modifier.padding(8.dp)) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            Text(state.variantChoices[item.selectionKey()]?.label ?: "无可用画质", maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            TextButton(onClick = { showingVideos = false }, modifier = Modifier.align(Alignment.End)) { Text("完成选择") }
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                AppIcon(AppIcons.Back, "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(12.dp))
            Text("解析结果", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenHistory, modifier = Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                AppIcon(AppIcons.Download, "下载任务", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (state.videos.size > 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("当前 ${state.videos.indexOfFirst { it.selectionKey() == video.selectionKey() } + 1}/${state.videos.size} · 已选 ${state.selectedMedia.size} 个",
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { showingVideos = true }, enabled = !state.isSubmitting && state.duplicates == null) {
                    Text("查看全部")
                    Spacer(Modifier.width(4.dp))
                    AppIcon(AppIcons.ChevronDown, null, modifier = Modifier.size(18.dp))
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(resultScroll)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).height((screenHeight * .28f).coerceIn(140f, 260f).dp).clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                AsyncImage(video.coverUrl, "视频封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.TopStart).padding(16.dp).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .95f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppIcon(AppIcons.Success, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Text("已解析 · ${video.platform.label}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { onPreview(video) }, modifier = Modifier.align(Alignment.Center)
                    .size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = .28f))) {
                    AppIcon(AppIcons.Play, "在${video.platform.label}预览", tint = Color.White, modifier = Modifier.size(35.dp))
                }
                Text(formatDuration(video.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(MaterialTheme.shapes.small)
                        .background(Color.Black.copy(alpha = .5f)).padding(horizontal = 7.dp, vertical = 5.dp))
            }
            Column(Modifier.padding(horizontal = 22.dp, vertical = 21.dp)) {
                Text(video.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, maxLines = if (expandedTitle) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expandedTitle) titleOverflows = it.hasVisualOverflow })
                if (titleOverflows || expandedTitle) TextButton(onClick = { expandedTitle = !expandedTitle }) {
                    Text(if (expandedTitle) "收起标题" else "展开标题")
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(video.author.avatarUrl, "作者头像", Modifier.size(26.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(8.dp))
                    Text("@ ${video.author.nickname.ifBlank { "未知作者" }}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onOpenAuthor(video) }, enabled = video.author.profileUrl != null) {
                        Text("查看作者", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(4.dp))
                        AppIcon(AppIcons.ExternalLink, null, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("选择画质", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text("文件大小以视频源为准", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val variants = video.sortedVariants
                variants.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { variant ->
                            val selected = state.selectedVariant?.let {
                                it.label == variant.label && it.url == variant.url
                            } == true
                            Card(
                                onClick = { onSelectVariant(variant) }, enabled = !state.isSubmitting,
                                modifier = Modifier.weight(1f).semantics { contentDescription = "画质 ${variant.label}" },
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                                border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 11.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(variant.label.substringBefore(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    Text(variant.sizeBytes?.takeIf { it > 0L }?.let(::formatBytes)
                                        ?: if (state.isLoadingSizes) "获取大小中…" else "大小未知",
                                        maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (video.audioVariants.isNotEmpty()) {
                    Text("单独保存音轨", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp))
                    video.audioVariants.forEach { variant ->
                        FilterChip(selected = state.selectedVariant == variant, enabled = !state.isSubmitting && state.duplicates == null,
                            onClick = { onSelectVariant(variant) }, label = { Text(variant.label) })
                    }
                    Text("只下载所选音轨，保存为 M4A；所示码率来自源音轨，转换后文件大小可能变化。", style = MaterialTheme.typography.labelMedium)
                } else {
                    Text("此源未提供独立音轨，保存视频后可在影像库中提取音频。", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                }
                Text("下载后可在「已存」中播放、分享。实际可选画质取决于视频源。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 18.dp))
            }
        }
        PrimaryButton(
            text = when {
                state.isSubmitting -> "正在加入队列…"
                batchSelected -> "保存所选 ${state.selectedMedia.size} 个视频"
                state.isDownloadFinished -> "查看已保存视频"
                state.activeDownloadId != null -> "查看下载任务"
                else -> if (state.selectedVariant?.media?.audioOnly == true) "保存音频到本地" else if (state.videos.size > 1) "保存当前视频" else "保存到本地"
            },
            onClick = {
                val id = state.activeRecord?.id
                when {
                    batchSelected -> onDownloadSelected()
                    state.isDownloadFinished && id != null -> onPlayDownloaded(id)
                    state.activeDownloadId != null -> onOpenHistory()
                    else -> onDownload()
                }
            },
            enabled = if (batchSelected) !state.isSubmitting && state.duplicates == null else state.canDownload,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else {
            Text(text, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            AppIcon(AppIcons.Forward, null, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Offers a link found on the clipboard.
 *
 * It never parses on its own: the user confirms, or dismisses and is not asked about that link
 * again. The hint says where the switch lives so the behaviour is discoverable rather than magic.
 */
@Composable
private fun ClipboardOfferBanner(platformLabel: String?, onParse: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 13.dp).testTag("clipboard-offer"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon(AppIcons.Link, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(
                    text = if (platformLabel.isNullOrBlank()) "检测到视频链接" else "检测到 $platformLabel 链接",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("复制后回到拾影即可解析，可在「我的」关闭此提示。",
                    color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("clipboard-offer-dismiss")) { Text("忽略") }
                TextButton(onClick = onParse, modifier = Modifier.testTag("clipboard-offer-parse")) { Text("解析") }
            }
        }
    }
}
