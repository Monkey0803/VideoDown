package com.videodown.douyin.presentation.viewer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import coil3.compose.AsyncImage
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.CoilZoomState
import com.github.panpf.zoomimage.rememberCoilZoomState
import com.videodown.douyin.R
import com.videodown.douyin.core.DouyinIntents
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.gallery.GalleryExportOutcome
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.videoMimeType
import com.videodown.douyin.presentation.components.AppConfirmDialog
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppPlayerView
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.components.sharedCover
import com.videodown.douyin.presentation.galleryErrorMessage
import com.videodown.douyin.presentation.imagePostPosition
import com.videodown.douyin.presentation.library.FileExportDialog
import com.videodown.douyin.presentation.library.FileExportViewModel
import com.videodown.douyin.presentation.library.LibraryOrganizationDialog
import com.videodown.douyin.presentation.library.LibraryOrganizationViewModel
import com.videodown.douyin.presentation.library.LibraryQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val DEFAULT_AUTO_HIDE_MILLIS = 3_000L

private enum class VaultViewState { GRANTED, CHECKING, DENIED, UNAVAILABLE }

/**
 * 已存图片中心。
 *
 * ZoomImage 继续独占缩放、平移、双击与子采样；本页只在基础比例开放横向翻页，避免放大后
 * 的拖动被 Pager 抢走。普通图片与保险箱图片由 [imageViewerRecords] 在进入时严格分库，
 * 并沿用进入时「已存」的搜索、筛选与排序——从收藏或搜索结果点进来，左右滑动不会突然
 * 跳进整库。
 */
@Composable
fun ImageViewerScreen(
    records: List<DownloadRecord>,
    initialRecordId: String,
    onBack: () -> Unit,
    onDelete: (String, (Result<Unit>) -> Unit) -> Unit,
    onDeleteMany: (Set<String>) -> Unit = {},
    query: LibraryQuery? = null,
    autoHideMillis: Long = DEFAULT_AUTO_HIDE_MILLIS,
    slideshowSecondMillis: Long = 1_000L,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    organizationViewModel: LibraryOrganizationViewModel = viewModel(),
    fileExportViewModel: FileExportViewModel = viewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val initialRecord = records.firstOrNull { it.id == initialRecordId }
    // 入口被删除后 records 中已找不到它，因此在首次进入时固定普通库/保险箱边界。
    val collectionPrivate = remember(initialRecordId) { initialRecord?.isPrivate }
    var vaultAttempt by rememberSaveable(initialRecordId) { mutableIntStateOf(0) }
    var closing by remember(initialRecordId) { mutableStateOf(false) }
    var vaultState by remember(initialRecordId) {
        mutableStateOf(if (collectionPrivate == true) VaultViewState.CHECKING else VaultViewState.GRANTED)
    }

    LaunchedEffect(initialRecordId, collectionPrivate, vaultAttempt) {
        if (collectionPrivate != true) {
            vaultState = VaultViewState.GRANTED
            return@LaunchedEffect
        }
        val target = activity
        val authenticator = ServiceLocator.vaultAuthenticator
        if (target == null || !authenticator.isAvailable(context)) {
            vaultState = VaultViewState.UNAVAILABLE
            return@LaunchedEffect
        }
        vaultState = VaultViewState.CHECKING
        val started = authenticator.authenticate(target) { granted ->
            vaultState = if (granted) VaultViewState.GRANTED else VaultViewState.DENIED
        }
        if (!started) vaultState = VaultViewState.UNAVAILABLE
    }

    fun closeViewer() {
        if (closing) return
        closing = true
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
            scope.launch {
                // hide() 异步提交；等 root insets 确认隐藏后再 pop，避免浅色底页先从系统栏穿出。
                repeat(9) {
                    withFrameNanos { }
                    val hidden = ViewCompat.getRootWindowInsets(window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.systemBars()) == false
                    if (hidden) {
                        onBack()
                        return@launch
                    }
                }
                // 厂商实现若始终不回报状态，也不能把用户困在看图器里。
                onBack()
            }
            return
        }
        onBack()
    }

    BackHandler(onBack = ::closeViewer)
    if (vaultState != VaultViewState.GRANTED) {
        VaultLockedViewer(vaultState, onBack = ::closeViewer, onRetry = { vaultAttempt++ })
        return
    }

    // 文件是否还在只在进入/记录变化时检查一次；重组（点按、控制栏、幻灯片计时）不再全库扫描。
    // 入口是否有效、逛哪个分区都在进入时固定：删掉当前这张后仍能继续浏览同一份列表。
    val entryValid = remember(initialRecordId) {
        initialRecord?.let { record ->
            record.isImage && record.status == DownloadStatus.COMPLETED &&
                record.filePath?.let(::File)?.isFile == true &&
                record.livePhotoMotion?.let { motion -> motion.filePath?.let(::File)?.isFile == true } != false
        } == true
    }
    val availableRecords = remember(records, collectionPrivate, entryValid, query) {
        if (collectionPrivate == null || !entryValid) {
            emptyList()
        } else {
            imageViewerRecords(records, collectionPrivate, query) { record ->
                record.filePath?.let(::File)?.isFile == true &&
                    record.livePhotoMotion?.let { motion -> motion.filePath?.let(::File)?.isFile == true } != false
            }
        }
    }
    if (availableRecords.isEmpty()) {
        MissingImageViewer(
            record = initialRecord,
            onBack = ::closeViewer,
            onOpenSource = {
                if (initialRecord == null || !DouyinIntents.openVideo(context, initialRecord)) {
                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.viewer_source_failed)) }
                }
            },
            onDelete = {
                val record = initialRecord ?: return@MissingImageViewer
                onDelete(record.id) { result ->
                    if (result.isSuccess) closeViewer()
                    else scope.launch { snackbar.showSnackbar(resources.getString(R.string.viewer_delete_failed)) }
                }
            },
            snackbar = snackbar,
        )
        return
    }

    var currentId by rememberSaveable(initialRecordId) { mutableStateOf(initialRecordId) }
    val initialPage = availableRecords.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { availableRecords.size }
    val thumbnailState = rememberLazyListState()
    val zoomStates = remember { mutableStateMapOf<String, CoilZoomState>() }
    val imageStates = remember { mutableStateMapOf<String, ViewerImageState>() }
    val metadataCache = remember { mutableMapOf<ImageMetadataCacheKey, com.videodown.douyin.core.ImageMetadata>() }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var showingInfo by remember { mutableStateOf(false) }
    var showingDateGrid by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    var intervalExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    var playingLivePhotoId by remember { mutableStateOf<String?>(null) }
    var organizingId by remember { mutableStateOf<String?>(null) }
    var fileExportIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var slideshowRunning by rememberSaveable { mutableStateOf(false) }
    var slideshowInterval by rememberSaveable {
        mutableIntStateOf(normalizedSlideshowIntervalSeconds(ServiceLocator.preferences.imageSlideshowIntervalSeconds))
    }
    var appStarted by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val organization by organizationViewModel.state.collectAsStateWithLifecycle()

    val currentIndex = availableRecords.indexOfFirst { it.id == currentId }
        .takeIf { it >= 0 } ?: pagerState.currentPage.coerceIn(0, availableRecords.lastIndex)
    val currentRecord = availableRecords[currentIndex]
    val currentZoom = zoomStates[currentRecord.id]
    val pagerEnabled = currentZoom?.zoomable?.userTransform?.let { transform ->
        transform.scaleX <= 1.01f && transform.scaleY <= 1.01f
    } ?: true
    val overlayOpen = showingInfo || showingDateGrid || moreExpanded || intervalExpanded || pendingDelete != null ||
        saving || organizingId != null || fileExportIds.isNotEmpty() || organization.busy
    val slideshowTimerEnabled = canRunSlideshowTimer(
        running = slideshowRunning,
        recordCount = availableRecords.size,
        overlayOpen = overlayOpen,
        appStarted = appStarted,
        imageAtBaseScale = pagerEnabled,
    )
    val fontScale = LocalDensity.current.fontScale
    val windowHeightDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp().value
    }
    val showThumbnails = availableRecords.size > 1 &&
        ImageViewerLayoutPolicy.showThumbnailStrip(windowHeightDp.toInt())
    val actionLabelMaxLines = ImageViewerLayoutPolicy.actionLabelMaxLines(fontScale)
    val zoomInLabel = stringResource(R.string.viewer_zoom_in)
    val zoomResetLabel = stringResource(R.string.viewer_zoom_reset)
    val postPosition = imagePostPosition(availableRecords, currentRecord)

    // 进入或翻到 Live Photo 时立即播放；播放器自身 REPEAT_MODE_ONE 持续循环。
    // 用户手动停止后保持静帧，直到离开该页并再次回来才重新自动播放。
    LaunchedEffect(currentRecord.id) {
        playingLivePhotoId = currentRecord.id.takeIf { currentRecord.isLivePhoto }
    }

    fun wakeControls() {
        controlsVisible = true
        interactionTick++
    }

    LaunchedEffect(availableRecords.map { it.id }, currentId) {
        val page = availableRecords.indexOfFirst { it.id == currentId }
        if (page >= 0 && page != pagerState.currentPage) pagerState.scrollToPage(page)
    }
    LaunchedEffect(pagerState, availableRecords) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                availableRecords.getOrNull(page)?.let { record ->
                    currentId = record.id
                    thumbnailState.animateScrollToItem(page)
                }
            }
    }
    // 日期网格是模态的：先收起后面的控制栏，避免两套底部工具条在弹层下沿叠在一起。
    LaunchedEffect(showingDateGrid) {
        if (showingDateGrid) controlsVisible = false
    }
    LaunchedEffect(controlsVisible, overlayOpen, currentId, interactionTick, autoHideMillis) {
        if (controlsVisible && !overlayOpen && autoHideMillis > 0L) {
            delay(autoHideMillis)
            controlsVisible = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            appStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(slideshowTimerEnabled, slideshowInterval, currentId, availableRecords.map { it.id }) {
        if (!slideshowTimerEnabled) return@LaunchedEffect
        delay(slideshowInterval * slideshowSecondMillis)
        // 不抢正在进行的手势；若用户滑回同一页（currentId 不变），也从稳定后重新计满一轮。
        if (pagerState.isScrollInProgress) {
            snapshotFlow { pagerState.isScrollInProgress }.first { scrolling -> !scrolling }
            delay(slideshowInterval * slideshowSecondMillis)
        }
        if (pagerState.isScrollInProgress) return@LaunchedEffect
        val next = (currentIndex + 1) % availableRecords.size
        pagerState.animateScrollToPage(next)
    }
    LaunchedEffect(availableRecords.size) {
        if (availableRecords.size < 2) slideshowRunning = false
    }

    DisposableEffect(activity, slideshowRunning) {
        val window = activity?.window
        if (slideshowRunning) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (slideshowRunning) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(activity) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousBehavior = controller?.systemBarsBehavior
        onDispose {
            if (previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
        }
    }
    LaunchedEffect(activity, controlsVisible, overlayOpen) {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (controlsVisible || overlayOpen) show(WindowInsetsCompat.Type.systemBars())
                else hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    fun show(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun save(record: DownloadRecord, replaceExisting: Boolean = false) {
        if (saving) return
        saving = true
        scope.launch {
            val result = runCatching { ServiceLocator.galleryExporter.export(record, replaceExisting) }
            saving = false
            result.fold(
                onSuccess = { outcome ->
                    if (outcome is GalleryExportOutcome.AlreadySaved) {
                        // 相册里已有同一份：让用户自己决定要不要再存一份。
                        val choice = snackbar.showSnackbar(
                            message = resources.getString(R.string.library_already_saved),
                            actionLabel = resources.getString(R.string.library_resave_action),
                        )
                        if (choice == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            save(record, replaceExisting = true)
                        }
                    } else {
                        show(resources.getString(R.string.library_saved_to_gallery))
                    }
                },
                onFailure = { show(resources.getString(R.string.library_save_failed, galleryErrorMessage(context, it))) },
            )
        }
    }

    /** 日期网格里批量保存：逐张导出，最后给一条"成功/失败"汇总，而不是刷 N 条提示。 */
    fun saveMany(ids: Set<String>) {
        if (saving || ids.isEmpty()) return
        saving = true
        scope.launch {
            var saved = 0
            var failed = 0
            ids.forEach { id ->
                val record = availableRecords.firstOrNull { it.id == id } ?: return@forEach
                val result = runCatching { ServiceLocator.galleryExporter.export(record) }
                if (result.isSuccess) saved++ else failed++
            }
            saving = false
            show(
                if (failed == 0) resources.getString(R.string.viewer_saved_selected, saved)
                else resources.getString(R.string.viewer_save_selected_partial, saved, failed),
            )
        }
    }

    fun toggleFavorite(record: DownloadRecord) {
        organizationViewModel.update(record.id, favorite = !record.favorite) {
            show(resources.getString(if (record.favorite) R.string.viewer_unfavorited else R.string.viewer_favorited))
        }
    }

    /** 移入/移出保险箱后这张图就不再属于当前分区：像删除一样前进或退出。 */
    fun toggleVault(record: DownloadRecord) {
        val nextId = imageAfterDelete(availableRecords, record.id)
        organizationViewModel.update(record.id, isPrivate = !record.isPrivate) {
            if (nextId == null) closeViewer() else currentId = nextId
            show(resources.getString(if (record.isPrivate) R.string.vault_removed else R.string.vault_added))
        }
    }

    var pendingPermissionId by remember { mutableStateOf<String?>(null) }
    var pendingBatchSave by remember { mutableStateOf<Set<String>?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val record = availableRecords.firstOrNull { it.id == pendingPermissionId }
        val batch = pendingBatchSave
        pendingPermissionId = null
        pendingBatchSave = null
        when {
            !granted -> show(resources.getString(R.string.library_need_storage_permission))
            batch != null -> saveMany(batch)
            record == null -> show(resources.getString(R.string.viewer_missing))
            else -> save(record)
        }
    }

    fun saveFromUser(record: DownloadRecord) {
        wakeControls()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermissionId = record.id
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save(record)
        }
    }

    fun saveManyFromUser(ids: Set<String>) {
        // 由日期网格触发，弹层还开着：不把控制栏唤回，避免两套工具条再次叠在一起。
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBatchSave = ids
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveMany(ids)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("image-viewer")) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = pagerEnabled,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().testTag("image-viewer-pager"),
        ) { page ->
            val record = availableRecords[page]
            val file = File(record.filePath.orEmpty())
            val zoomState = rememberCoilZoomState()
            DisposableEffect(record.id, zoomState) {
                zoomStates[record.id] = zoomState
                onDispose { zoomStates.remove(record.id, zoomState) }
            }
            val imageState = imageStates[record.id] ?: ViewerImageState.LOADING
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CoilZoomAsyncImage(
                    model = file,
                    contentDescription = null,
                    zoomState = zoomState,
                    contentScale = ContentScale.Fit,
                    onTap = {
                        controlsVisible = !controlsVisible
                        interactionTick++
                    },
                    onLoading = { imageStates[record.id] = ViewerImageState.LOADING },
                    onSuccess = { imageStates[record.id] = ViewerImageState.READY },
                    onError = { imageStates[record.id] = ViewerImageState.FAILED },
                    modifier = Modifier.fillMaxSize()
                        .sharedCover(record.id, sharedTransitionScope, animatedVisibilityScope)
                        .semantics {
                            contentDescription = resources.getString(
                                R.string.viewer_image_semantics, page + 1, availableRecords.size, record.title,
                            )
                            customActions = listOf(
                                CustomAccessibilityAction(zoomInLabel) {
                                    scope.launch { zoomState.zoomable.switchScale() }
                                    true
                                },
                                CustomAccessibilityAction(zoomResetLabel) {
                                    scope.launch { zoomState.zoomable.reset() }
                                    true
                                },
                            )
                        }
                        .testTag("image-viewer-${record.id}"),
                )
                when (imageState) {
                    ViewerImageState.LOADING -> ViewerImageLoading(visible = true)
                    ViewerImageState.FAILED -> ViewerImageFailure(
                        record = record,
                        file = file,
                        onOpenExternal = {
                            if (!DouyinIntents.playExternally(context, file, videoMimeType(file.extension))) {
                                show(resources.getString(R.string.viewer_source_failed))
                            }
                        },
                        onDelete = { pendingDelete = record },
                    )
                    ViewerImageState.READY -> Unit
                }
            }
        }

        if (playingLivePhotoId == currentRecord.id) {
            currentRecord.livePhotoMotion?.filePath?.let(::File)?.takeIf(File::isFile)?.let { motion ->
                LivePhotoMotionPlayer(
                    file = motion,
                    posterFile = currentRecord.filePath?.let(::File)?.takeIf(File::isFile),
                    onTap = {
                        controlsVisible = !controlsVisible
                        interactionTick++
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .82f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = ::closeViewer,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = .16f)),
                ) {
                    AppIcon(AppIcons.Back, stringResource(R.string.action_back), tint = Color.White)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (collectionPrivate == true) {
                            AppIcon(
                                AppIcons.Storage,
                                stringResource(R.string.viewer_vault_badge),
                                tint = Color.White.copy(alpha = .85f),
                                modifier = Modifier.padding(end = 4.dp).size(14.dp),
                            )
                        }
                        Text(
                            currentRecord.title.ifBlank { stringResource(R.string.viewer_title) },
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        listOfNotNull(
                            stringResource(R.string.viewer_count, currentIndex + 1, availableRecords.size),
                            postPosition?.let { (position, total) ->
                                stringResource(R.string.viewer_post_position, position, total)
                            },
                        ).joinToString(" · "),
                        color = Color.White.copy(alpha = .72f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                currentRecord.livePhotoMotion?.filePath?.let(::File)?.takeIf(File::isFile)?.let {
                    TextButton(onClick = {
                        playingLivePhotoId = if (playingLivePhotoId == currentRecord.id) null else currentRecord.id
                        wakeControls()
                    }) {
                        AppIcon(
                            if (playingLivePhotoId == currentRecord.id) AppIcons.Pause else AppIcons.Play,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(if (playingLivePhotoId == currentRecord.id) R.string.viewer_live_photo_stop else R.string.viewer_live_photo_play),
                            color = Color.White,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f))))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(top = 28.dp, bottom = 8.dp),
            ) {
                if (availableRecords.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                slideshowRunning = !slideshowRunning
                                wakeControls()
                            },
                            modifier = Modifier.testTag("image-slideshow-toggle"),
                        ) {
                            AppIcon(
                                if (slideshowRunning) AppIcons.Pause else AppIcons.Play,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                stringResource(if (slideshowRunning) R.string.viewer_slideshow_pause else R.string.viewer_slideshow_start),
                                color = Color.White,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        Box {
                            TextButton(onClick = { intervalExpanded = true; wakeControls() }) {
                                Text(stringResource(R.string.viewer_slideshow_interval, slideshowInterval), color = Color.White)
                                AppIcon(AppIcons.ChevronDown, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(expanded = intervalExpanded, onDismissRequest = { intervalExpanded = false }) {
                                IMAGE_SLIDESHOW_INTERVALS_SECONDS.forEach { seconds ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.viewer_slideshow_interval, seconds)) },
                                        onClick = {
                                            slideshowInterval = seconds
                                            ServiceLocator.preferences.imageSlideshowIntervalSeconds = seconds
                                            intervalExpanded = false
                                            wakeControls()
                                        },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { showingDateGrid = true; wakeControls() }) {
                            AppIcon(AppIcons.Grid, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.viewer_date_grid),
                                color = Color.White,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    if (showThumbnails) {
                        LazyRow(
                            state = thumbnailState,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().height(64.dp).testTag("image-viewer-thumbnails"),
                        ) {
                            itemsIndexed(availableRecords, key = { _, item -> item.id }) { index, record ->
                                val selected = record.id == currentRecord.id
                                Box {
                                    AsyncImage(
                                        model = File(record.filePath.orEmpty()),
                                        contentDescription = stringResource(R.string.viewer_thumbnail, index + 1, record.title),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(56.dp)
                                            .clip(MaterialTheme.shapes.medium)
                                            .border(
                                                BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .28f)),
                                                MaterialTheme.shapes.medium,
                                            )
                                            .background(Color.White.copy(alpha = .08f))
                                            .clickable {
                                                currentId = record.id
                                                wakeControls()
                                                scope.launch { pagerState.animateScrollToPage(index) }
                                            }
                                            .testTag("image-thumbnail-${record.id}"),
                                    )
                                    if (record.isLivePhoto) {
                                        AppIcon(
                                            AppIcons.Play,
                                            stringResource(R.string.viewer_live_photo_badge),
                                            tint = Color.White,
                                            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).size(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerAction(
                        label = stringResource(if (saving) R.string.library_saving else R.string.library_save_to_gallery),
                        icon = AppIcons.SaveAlbum,
                        busy = saving,
                        enabled = !saving,
                        maxLines = actionLabelMaxLines,
                        modifier = Modifier.weight(1f),
                    ) { saveFromUser(currentRecord) }
                    ViewerAction(
                        label = stringResource(R.string.action_share),
                        icon = AppIcons.Share,
                        maxLines = actionLabelMaxLines,
                        modifier = Modifier.weight(1f),
                    ) {
                        wakeControls()
                        if (!DouyinIntents.shareRecord(context, currentRecord)) show(resources.getString(R.string.library_share_failed))
                    }
                    ViewerAction(
                        label = stringResource(R.string.viewer_info),
                        icon = AppIcons.Info,
                        maxLines = actionLabelMaxLines,
                        modifier = Modifier.weight(1f),
                    ) {
                        wakeControls(); showingInfo = true
                    }
                    Box(Modifier.weight(1f)) {
                        ViewerAction(
                            label = stringResource(R.string.viewer_more),
                            icon = AppIcons.More,
                            maxLines = actionLabelMaxLines,
                        ) {
                            wakeControls(); moreExpanded = true
                        }
                        DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (currentRecord.favorite) R.string.viewer_unfavorite else R.string.viewer_favorite)) },
                                leadingIcon = {
                                    AppIcon(
                                        if (currentRecord.favorite) AppIcons.FavoriteFilled else AppIcons.Favorite,
                                        null,
                                    )
                                },
                                enabled = !organization.busy,
                                onClick = { moreExpanded = false; toggleFavorite(currentRecord) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_organize)) },
                                leadingIcon = { AppIcon(AppIcons.Rename, null) },
                                enabled = !organization.busy,
                                onClick = { moreExpanded = false; organizingId = currentRecord.id },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (currentRecord.isPrivate) R.string.vault_remove else R.string.vault_add)) },
                                leadingIcon = { AppIcon(AppIcons.Storage, null) },
                                enabled = !organization.busy,
                                onClick = { moreExpanded = false; toggleVault(currentRecord) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_export_folder)) },
                                leadingIcon = { AppIcon(AppIcons.ExportFolder, null) },
                                enabled = !organization.busy,
                                onClick = {
                                    moreExpanded = false
                                    fileExportViewModel.reset()
                                    fileExportIds = listOf(currentRecord.id)
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_open_source)) },
                                leadingIcon = { AppIcon(AppIcons.ExternalLink, null) },
                                onClick = {
                                    moreExpanded = false
                                    if (!DouyinIntents.openVideo(context, currentRecord)) show(resources.getString(R.string.viewer_source_failed))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { AppIcon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { moreExpanded = false; pendingDelete = currentRecord },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(bottom = 82.dp),
        )
    }

    if (showingInfo) {
        ImageViewerInfoSheet(
            record = currentRecord,
            zoomState = currentZoom,
            metadataCache = metadataCache,
            onDismiss = { showingInfo = false; wakeControls() },
        )
    }
    if (showingDateGrid) {
        ImageViewerDateGridSheet(
            records = availableRecords,
            selectedId = currentRecord.id,
            onSelect = { record ->
                showingDateGrid = false
                currentId = record.id
                wakeControls()
                scope.launch { pagerState.animateScrollToPage(availableRecords.indexOf(record)) }
            },
            onDelete = { ids ->
                val remaining = availableRecords.filterNot { it.id in ids }
                val fallback = remaining.getOrNull(currentIndex.coerceAtMost(remaining.lastIndex.coerceAtLeast(0)))?.id
                onDeleteMany(ids)
                if (currentRecord.id in ids) {
                    if (fallback == null) closeViewer() else currentId = fallback
                }
            },
            onSave = { ids -> saveManyFromUser(ids) },
            onDismiss = { showingDateGrid = false; wakeControls() },
        )
    }
    organizingId?.let { id ->
        availableRecords.firstOrNull { it.id == id }?.let { record ->
            LibraryOrganizationDialog(record, organizationViewModel) { organizingId = null }
        }
    }
    if (fileExportIds.isNotEmpty()) {
        FileExportDialog(fileExportIds, fileExportViewModel) { fileExportIds = emptyList() }
    }
    pendingDelete?.let { record ->
        AppConfirmDialog(
            onDismissRequest = { pendingDelete = null; wakeControls() },
            icon = AppIcons.Delete,
            title = stringResource(R.string.viewer_delete_title),
            body = stringResource(R.string.viewer_delete_body, record.title),
            confirmText = stringResource(R.string.action_confirm_delete),
            onConfirm = {
                val nextId = imageAfterDelete(availableRecords, record.id)
                pendingDelete = null
                onDelete(record.id) { result ->
                    if (result.isSuccess) {
                        if (nextId == null) closeViewer() else { currentId = nextId; wakeControls() }
                    } else {
                        show(resources.getString(R.string.viewer_delete_failed))
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            destructive = true,
        )
    }
}

/** 兼容设备探针和独立预览；真实应用路由使用上面的全库入口。 */
@Composable
fun ImageViewerScreen(record: DownloadRecord?, onBack: () -> Unit) {
    ImageViewerScreen(
        records = listOfNotNull(record),
        initialRecordId = record?.id.orEmpty(),
        onBack = onBack,
        onDelete = { _, done -> done(Result.failure(UnsupportedOperationException("preview"))) },
    )
}

@Composable
private fun ViewerAction(
    label: String,
    icon: AppIcons,
    enabled: Boolean = true,
    busy: Boolean = false,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 2.dp),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f)),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else AppIcon(icon, label, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Text(
            label,
            color = Color.White.copy(alpha = if (enabled) .9f else .45f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LivePhotoMotionPlayer(file: File, posterFile: File?, onTap: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    var firstFrameRendered by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrameRendered = true }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.play()
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("live-photo-motion")) {
        AppPlayerView(
            player = player,
            useController = false,
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
            modifier = Modifier.fillMaxSize(),
        )
        if (!firstFrameRendered && posterFile != null) {
            AsyncImage(
                model = posterFile,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().testTag("live-photo-poster"),
            )
        }
        // 单击只切控制栏；停止交给顶栏按钮，避免"想隐藏控制栏却把实况停了"。
        Box(Modifier.fillMaxSize().clickable(onClick = onTap).testTag("live-photo-tap"))
    }
}

@Composable
private fun VaultLockedViewer(state: VaultViewState, onBack: () -> Unit, onRetry: () -> Unit) {
    val message = when (state) {
        VaultViewState.CHECKING -> stringResource(R.string.viewer_unlocking)
        VaultViewState.DENIED -> stringResource(R.string.viewer_unlock_denied)
        VaultViewState.UNAVAILABLE -> stringResource(R.string.viewer_unlock_unavailable)
        VaultViewState.GRANTED -> return
    }
    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
            AppIcon(AppIcons.Back, stringResource(R.string.action_back), tint = Color.White)
        }
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state == VaultViewState.CHECKING) CircularProgressIndicator(color = Color.White)
            else AppIcon(AppIcons.Storage, null, tint = Color.White, modifier = Modifier.size(36.dp))
            Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
            if (state != VaultViewState.CHECKING) TextButton(onClick = onRetry) { Text(stringResource(R.string.viewer_unlock_retry)) }
        }
    }
}

@Composable
private fun MissingImageViewer(
    record: DownloadRecord?,
    onBack: () -> Unit,
    onOpenSource: () -> Unit,
    onDelete: () -> Unit,
    snackbar: SnackbarHostState,
) {
    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
            AppIcon(AppIcons.Back, stringResource(R.string.action_back), tint = Color.White)
        }
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(AppIcons.Warning, null, tint = Color.White, modifier = Modifier.size(36.dp))
            Text(stringResource(R.string.viewer_missing), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp))
            if (record != null) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenSource) { Text(stringResource(R.string.viewer_open_source)) }
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.viewer_delete_stale), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
