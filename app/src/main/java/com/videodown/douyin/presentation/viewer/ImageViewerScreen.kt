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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.CoilZoomState
import com.github.panpf.zoomimage.rememberCoilZoomState
import com.videodown.douyin.R
import com.videodown.douyin.core.DouyinIntents
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.gallery.GalleryExportOutcome
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.core.ImageMetadata
import com.videodown.douyin.data.image.ImageMetadataReader
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.components.AppConfirmDialog
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppPlayerView
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.components.sharedCover
import com.videodown.douyin.presentation.galleryErrorMessage
import com.videodown.douyin.presentation.videoPlatformLabelRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val DEFAULT_AUTO_HIDE_MILLIS = 3_000L

private enum class VaultViewState { GRANTED, CHECKING, DENIED, UNAVAILABLE }

/**
 * 已存图片中心。
 *
 * ZoomImage 继续独占缩放、平移、双击与子采样；本页只在基础比例开放横向翻页，避免放大后
 * 的拖动被 Pager 抢走。普通图片与保险箱图片由 [imageViewerRecords] 在进入时严格分库。
 */
@Composable
fun ImageViewerScreen(
    records: List<DownloadRecord>,
    initialRecordId: String,
    onBack: () -> Unit,
    onDelete: (String, (Result<Unit>) -> Unit) -> Unit,
    autoHideMillis: Long = DEFAULT_AUTO_HIDE_MILLIS,
    slideshowSecondMillis: Long = 1_000L,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val initialRecord = records.firstOrNull { it.id == initialRecordId }
    // 入口被删除后 records 中已找不到它，因此在首次进入时固定普通库/保险箱边界与文件状态。
    val collectionPrivate = remember(initialRecordId) { initialRecord?.isPrivate }
    val initialFileWasValid = remember(initialRecordId) {
        initialRecord?.let { record ->
            record.isImage && record.status == DownloadStatus.COMPLETED &&
                record.filePath?.let(::File)?.isFile == true &&
                record.livePhotoMotion?.let { motion -> motion.filePath?.let(::File)?.isFile == true } != false
        } == true
    }
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

    val availableRecords = if (collectionPrivate == null || !initialFileWasValid) {
        emptyList()
    } else {
        imageViewerRecords(records, collectionPrivate) { record ->
            record.filePath?.let(::File)?.isFile == true &&
                record.livePhotoMotion?.let { motion -> motion.filePath?.let(::File)?.isFile == true } != false
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
    val metadataCache = remember { mutableMapOf<ImageMetadataCacheKey, ImageMetadata>() }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var showingInfo by remember { mutableStateOf(false) }
    var showingDateGrid by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    var intervalExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    var playingLivePhotoId by remember { mutableStateOf<String?>(null) }
    var slideshowRunning by rememberSaveable { mutableStateOf(false) }
    var slideshowInterval by rememberSaveable {
        mutableIntStateOf(normalizedSlideshowIntervalSeconds(ServiceLocator.preferences.imageSlideshowIntervalSeconds))
    }
    var appStarted by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }

    val currentIndex = availableRecords.indexOfFirst { it.id == currentId }
        .takeIf { it >= 0 } ?: pagerState.currentPage.coerceIn(0, availableRecords.lastIndex)
    val currentRecord = availableRecords[currentIndex]
    val currentFile = File(currentRecord.filePath.orEmpty())
    val currentZoom = zoomStates[currentRecord.id]
    val pagerEnabled = currentZoom?.zoomable?.userTransform?.let { transform ->
        transform.scaleX <= 1.01f && transform.scaleY <= 1.01f
    } ?: true
    val overlayOpen = showingInfo || showingDateGrid || moreExpanded || intervalExpanded || pendingDelete != null || saving
    val slideshowTimerEnabled = canRunSlideshowTimer(
        running = slideshowRunning,
        recordCount = availableRecords.size,
        overlayOpen = overlayOpen,
        appStarted = appStarted,
        imageAtBaseScale = pagerEnabled,
    )

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

    var pendingPermissionId by remember { mutableStateOf<String?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val record = availableRecords.firstOrNull { it.id == pendingPermissionId }
        pendingPermissionId = null
        when {
            !granted -> show(resources.getString(R.string.library_need_storage_permission))
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

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("image-viewer")) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = pagerEnabled,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().testTag("image-viewer-pager"),
        ) { page ->
            val record = availableRecords[page]
            val zoomState = rememberCoilZoomState()
            DisposableEffect(record.id, zoomState) {
                zoomStates[record.id] = zoomState
                onDispose { zoomStates.remove(record.id, zoomState) }
            }
            CoilZoomAsyncImage(
                model = File(record.filePath.orEmpty()),
                contentDescription = record.title,
                zoomState = zoomState,
                contentScale = ContentScale.Fit,
                onTap = {
                    controlsVisible = !controlsVisible
                    interactionTick++
                },
                modifier = Modifier.fillMaxSize()
                    .sharedCover(record.id, sharedTransitionScope, animatedVisibilityScope)
                    .testTag("image-viewer-${record.id}"),
            )
        }

        if (playingLivePhotoId == currentRecord.id) {
            currentRecord.livePhotoMotion?.filePath?.let(::File)?.takeIf(File::isFile)?.let { motion ->
                LivePhotoMotionPlayer(
                    file = motion,
                    posterFile = currentRecord.filePath?.let(::File)?.takeIf(File::isFile),
                    onStop = { playingLivePhotoId = null; wakeControls() },
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
                    Text(
                        currentRecord.title.ifBlank { stringResource(R.string.viewer_title) },
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.viewer_count, currentIndex + 1, availableRecords.size),
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
                    LazyRow(
                        state = thumbnailState,
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().height(64.dp).testTag("image-viewer-thumbnails"),
                    ) {
                        itemsIndexed(availableRecords, key = { _, item -> item.id }) { index, record ->
                            val selected = record.id == currentRecord.id
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
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerAction(
                        label = stringResource(if (saving) R.string.library_saving else R.string.library_save_to_gallery),
                        icon = AppIcons.SaveAlbum,
                        busy = saving,
                        enabled = !saving,
                    ) { saveFromUser(currentRecord) }
                    ViewerAction(stringResource(R.string.action_share), AppIcons.Share) {
                        wakeControls()
                        if (!DouyinIntents.shareRecord(context, currentRecord)) show(resources.getString(R.string.library_share_failed))
                    }
                    ViewerAction(stringResource(R.string.viewer_info), AppIcons.Info) {
                        wakeControls(); showingInfo = true
                    }
                    Box {
                        ViewerAction(stringResource(R.string.viewer_more), AppIcons.More) {
                            wakeControls(); moreExpanded = true
                        }
                        DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
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
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(bottom = 82.dp))
    }

    if (showingInfo) {
        ImageInfoSheet(
            record = currentRecord,
            zoomState = currentZoom,
            metadataCache = metadataCache,
            onDismiss = { showingInfo = false; wakeControls() },
        )
    }
    if (showingDateGrid) {
        ImageDateGridSheet(
            records = availableRecords,
            selectedId = currentRecord.id,
            onSelect = { record ->
                showingDateGrid = false
                currentId = record.id
                wakeControls()
                scope.launch { pagerState.animateScrollToPage(availableRecords.indexOf(record)) }
            },
            onDismiss = { showingDateGrid = false; wakeControls() },
        )
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
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f)),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else AppIcon(icon, label, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Text(
            label,
            color = Color.White.copy(alpha = if (enabled) .9f else .45f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class ImageMetadataCacheKey(val path: String, val modifiedAt: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageInfoSheet(
    record: DownloadRecord,
    zoomState: CoilZoomState?,
    metadataCache: MutableMap<ImageMetadataCacheKey, ImageMetadata>,
    onDismiss: () -> Unit,
) {
    val file = File(record.filePath.orEmpty())
    val cacheKey = remember(file.absolutePath, file.lastModified()) {
        ImageMetadataCacheKey(file.absolutePath, file.lastModified())
    }
    var metadata by remember(cacheKey) { mutableStateOf(metadataCache[cacheKey]) }
    var metadataLoading by remember(cacheKey) { mutableStateOf(metadata == null) }
    LaunchedEffect(cacheKey) {
        if (metadata == null) {
            metadata = withContext(Dispatchers.IO) { ImageMetadataReader.read(file) }
                .also { metadataCache[cacheKey] = it }
        }
        metadataLoading = false
    }
    val origin = zoomState?.zoomable?.contentOriginSize
    val dimensions = origin?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width} × ${it.height}" }
    val unknown = stringResource(R.string.viewer_detail_unknown)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        val metadataFields = metadata?.fields.orEmpty()
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(.88f)
                .testTag("image-info-content"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
        ) {
            item(key = "file-details") {
                Text(stringResource(R.string.viewer_info), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(record.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                ImageDetailRow(stringResource(R.string.viewer_detail_author), record.displayAuthor.ifBlank { unknown })
                ImageDetailRow(stringResource(R.string.viewer_detail_platform), stringResource(videoPlatformLabelRes(record.platform)))
                ImageDetailRow(stringResource(R.string.viewer_detail_format), file.extension.uppercase().ifBlank { unknown })
                ImageDetailRow(stringResource(R.string.viewer_detail_dimensions), dimensions ?: unknown)
                ImageDetailRow(stringResource(R.string.viewer_detail_size), if (file.isFile) formatBytes(file.length()) else unknown)
                record.livePhotoMotion?.filePath?.let(::File)?.takeIf(File::isFile)?.let { motion ->
                    ImageDetailRow(stringResource(R.string.viewer_detail_live_photo), formatBytes(motion.length()))
                }
                ImageDetailRow(stringResource(R.string.viewer_detail_saved_at), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(record.updatedAt))
            }
            item(key = "metadata-title") {
                Text(
                    stringResource(R.string.viewer_exif_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
                )
            }
            when {
                metadataLoading -> item(key = "metadata-loading") {
                    Text(
                        stringResource(R.string.viewer_exif_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                metadataFields.isEmpty() -> item(key = "metadata-empty") {
                    Text(
                        stringResource(R.string.viewer_exif_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                else -> items(metadataFields, key = { "${it.label}\u0000${it.value}" }) { field ->
                    ImageDetailRow(field.label, field.value)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageDateGridSheet(
    records: List<DownloadRecord>,
    selectedId: String,
    onSelect: (DownloadRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = remember(records) { imageDateGroups(records) }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            Text(
                stringResource(R.string.viewer_date_grid_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().testTag("image-date-grid"),
            ) {
                groups.forEach { group ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "date-${group.date}") {
                        Text(
                            group.date.format(dateFormatter),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp),
                        )
                    }
                    gridItems(group.records, key = { it.id }) { record ->
                        AsyncImage(
                            model = File(record.filePath.orEmpty()),
                            contentDescription = record.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                .clip(MaterialTheme.shapes.medium)
                                .border(
                                    BorderStroke(
                                        if (record.id == selectedId) 3.dp else 1.dp,
                                        if (record.id == selectedId) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                    MaterialTheme.shapes.medium,
                                )
                                .clickable { onSelect(record) }
                                .testTag("date-grid-${record.id}"),
                        )
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LivePhotoMotionPlayer(file: File, posterFile: File?, onStop: () -> Unit) {
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
        Box(Modifier.fillMaxSize().clickable(onClick = onStop))
    }
}

@Composable
private fun ImageDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(92.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
