package com.videodown.douyin.presentation.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import com.videodown.douyin.presentation.components.TabPageHeader
import com.videodown.douyin.presentation.components.AppDialog
import com.videodown.douyin.presentation.components.AppDialogButtonRow
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppPrimaryButton
import com.videodown.douyin.presentation.components.InlineNotice
import com.videodown.douyin.presentation.components.NoticeTone
import com.videodown.douyin.presentation.components.MediaCover
import com.videodown.douyin.presentation.components.sharedCover
import com.videodown.douyin.presentation.components.CoverSelectionMark
import com.videodown.douyin.presentation.components.SelectAllToggle
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.components.AdaptivePage
import com.videodown.douyin.presentation.adaptive.LocalWindowWidth
import com.videodown.douyin.presentation.adaptive.WindowLayoutPolicy
import com.videodown.douyin.presentation.adaptive.pagePadding
import com.videodown.douyin.presentation.theme.BrandColors
import com.videodown.douyin.presentation.theme.AppSpacing

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
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.RecoveryAction
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.failureActions
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.presentation.login.XLoginScreen
import com.videodown.douyin.presentation.login.XLoginRecoveryAction
import com.videodown.douyin.presentation.login.xLoginRecoveryAction
import com.videodown.douyin.presentation.appErrorText
import com.videodown.douyin.presentation.failureDescription
import com.videodown.douyin.presentation.failureTitle
import com.videodown.douyin.presentation.recoveryActionLabel
import com.videodown.douyin.domain.model.selectionKey
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.presentation.storedErrorMessage
import com.videodown.douyin.presentation.subtitleSelectionText
import com.videodown.douyin.presentation.videoPlatformLabelRes
import com.videodown.douyin.presentation.watchBadgeText
import com.videodown.douyin.presentation.platformName
import com.videodown.douyin.presentation.UiDensityPolicy
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
import coil3.compose.AsyncImage
import com.videodown.douyin.core.DouyinIntents
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.presentation.theme.rememberEntranceAlpha
import com.videodown.douyin.core.watchBadge
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.core.formatDuration
import com.videodown.douyin.core.readClipboardText
import com.videodown.douyin.data.remote.VideoLinkExtractor
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.SubtitleLanguageSelection
import com.videodown.douyin.domain.model.SubtitleSelection
import com.videodown.douyin.domain.model.VaultPolicy
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.model.WatchProgress
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
    // Appended so the existing positional call sites in device tests keep compiling.
    watchProgress: Map<String, WatchProgress> = emptyMap(),
    batchImportSeed: String? = null,
    onBatchImportSeedConsumed: () -> Unit = {},
    /** 设备用例用本地页面替代真实登录页，避免联网；生产调用不传。 */
    xLoginStartUrl: String = com.videodown.douyin.data.generic.XSession.LOGIN_URL,
    /** 可扩展站点专区入口；追加参数保持既有设备测试的调用兼容。 */
    onOpenSites: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 资源按当前组合读取：context.getString 不会随语言/配置切换重算。
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputArea = remember { BringIntoViewRequester() }
    val pagePad = pagePadding()
    var inputFocused by remember { mutableStateOf(false) }
    var showBrowser by remember(state.parseRequestId) { mutableStateOf(false) }
    var showXLogin by remember(state.parseRequestId) { mutableStateOf(false) }
    var xSessionDetected by remember(state.parseRequestId) { mutableStateOf<Boolean?>(null) }
    var showBatchImport by rememberSaveable { mutableStateOf(false) }
    if (showBatchImport) BatchImportDialog(batchImportModel, { showBatchImport = false }, onOpenHistory)
    LaunchedEffect(openBatchImport) {
        if (openBatchImport) { showBatchImport = true; onBatchImportOpened() }
    }
    // Links imported from a backup file land in the same dialog as pasted text.
    LaunchedEffect(batchImportSeed) {
        val seed = batchImportSeed?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        batchImportModel.input(seed)
        showBatchImport = true
        onBatchImportSeedConsumed()
    }
    LaunchedEffect(state.browserRequestUrl) {
        if (state.browserRequestUrl != null) { showBrowser = true; viewModel.onBrowserRequestConsumed() }
    }
    val browserUrl = com.videodown.douyin.data.remote.VideoLinkExtractor.extract(state.input)?.url
    val inputPlatform = VideoLinkExtractor.extract(state.input)?.platform
    LaunchedEffect(state.errorKind, inputPlatform) {
        xSessionDetected = if (inputPlatform == VideoPlatform.X && state.errorKind == DownloadFailureKind.LOGIN_REQUIRED) {
            runCatching { ServiceLocator.browserSessions.xSignedIn() }.getOrDefault(false)
        } else {
            null
        }
    }
    BackHandler(enabled = state.video != null && !showBrowser) {
        viewModel.clearResult()
    }
    if (showBrowser && browserUrl != null) {
        com.videodown.douyin.presentation.browser.BrowserAssistScreen(browserUrl,
            onClose = { showBrowser = false },
            onResult = { result -> showBrowser = false; viewModel.acceptBrowserResult(result) })
    }
    if (showXLogin) {
        // 登录成功后立刻用同一份输入重试：这就是「一次登录，后续不再登录」的捷径。
        XLoginScreen(startUrl = xLoginStartUrl, onClose = { showXLogin = false },
            onFinished = { signedIn -> showXLogin = false; if (signedIn) viewModel.parse() })
    }
    LaunchedEffect(state.queuedCount) {
        // 入队成功后留在结果页，便于继续选择或返回解析；全局下载条持续提供任务入口。
        if (state.queuedCount != null) viewModel.onQueuedConsumed()
    }
    LaunchedEffect(state.isDownloadFinished, state.activeRecord?.id) {
        // 只提示一次：文件默认在应用专属目录，卸载会丢；用户知道后不再重复打扰。
        if (state.isDownloadFinished && state.activeRecord != null &&
            !ServiceLocator.preferences.downloadLocationHintSeen
        ) {
            ServiceLocator.preferences.downloadLocationHintSeen = true
            val result = snackbar.showSnackbar(
                message = resources.getString(R.string.home_download_location_hint),
                actionLabel = resources.getString(R.string.action_open_settings),
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenSettings()
        }
    }
    state.duplicates?.let { pending ->
        val single = pending.requests.size == 1
        val existing = pending.records.values.first()
        AppDialog(
            onDismissRequest = viewModel::dismissDuplicates,
            icon = AppIcons.Warning,
            title = stringResource(if (single) R.string.home_duplicate_single else R.string.home_duplicate_many, pending.records.size),
            actions = {
                FilledTonalButton(
                    onClick = {
                        if (single) {
                            viewModel.dismissDuplicates()
                            if (existing.isPrivate) {
                                // 私密记录不深链：提示里连“保险箱里有这条”都不确认，只允许关闭。
                            } else if (existing.status == DownloadStatus.COMPLETED && existing.filePath?.let { File(it).isFile } == true) {
                                onPlayDownloaded(existing.id)
                            } else {
                                onOpenHistory()
                            }
                        } else {
                            viewModel.confirmDuplicates(downloadAgain = false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (single) stringResource(
                            when {
                                existing.isPrivate -> R.string.action_close
                                existing.status == DownloadStatus.COMPLETED -> R.string.home_view_saved
                                else -> R.string.home_view_tasks
                            },
                        ) else stringResource(R.string.home_skip_duplicates),
                    )
                }
                AppDialogButtonRow {
                    TextButton(onClick = { viewModel.confirmDuplicates(downloadAgain = true) }) { Text(stringResource(R.string.home_download_anyway)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = viewModel::dismissDuplicates) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        ) {
            Text(stringResource(if (single) R.string.home_duplicate_body_single else R.string.home_duplicate_body_many),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            pending.records.values.forEach { record ->
                Text("${record.title} · ${record.resolutionLabel} · " + when (record.status) {
                    DownloadStatus.COMPLETED -> stringResource(R.string.home_existing_saved)
                    DownloadStatus.PENDING, DownloadStatus.RUNNING -> stringResource(R.string.home_existing_downloading)
                    else -> stringResource(R.string.home_existing_resumable)
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    state.archiveDuplicate?.let { existing ->
        AppDialog(
            onDismissRequest = viewModel::dismissArchiveDuplicate,
            icon = AppIcons.Warning,
            title = stringResource(R.string.home_archive_duplicate_title),
            actions = {
                FilledTonalButton(onClick = viewModel::confirmArchiveDuplicate, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_archive_duplicate_confirm))
                }
                AppDialogButtonRow {
                    TextButton(onClick = viewModel::dismissArchiveDuplicate) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        ) {
            Text(stringResource(R.string.home_archive_duplicate_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val shownTitle = VaultPolicy.title(existing, unlocked = false)
                ?: stringResource(R.string.vault_locked_title)
            Text("$shownTitle · " + when (existing.status) {
                DownloadStatus.COMPLETED -> stringResource(R.string.home_existing_saved)
                DownloadStatus.PENDING, DownloadStatus.RUNNING -> stringResource(R.string.home_existing_downloading)
                else -> stringResource(R.string.home_existing_resumable)
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var wasImeVisible by remember { mutableStateOf(false) }

    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    fun pasteIntoInput() {
        val copied = readClipboardText(context)
        if (copied == null) {
            scope.launch { snackbar.showSnackbar(resources.getString(R.string.home_clipboard_empty)) }
            return
        }
        viewModel.onInputChange(copied)
        dismissKeyboard()
    }

    fun pasteAndParse() {
        val copied = readClipboardText(context)
        if (copied == null) {
            scope.launch { snackbar.showSnackbar(resources.getString(R.string.home_clipboard_empty)) }
            return
        }
        viewModel.onInputChange(copied)
        dismissKeyboard()
        viewModel.parse()
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
    val firstUseGuideDismissed by ServiceLocator.preferences.firstUseGuideDismissedChanges.collectAsStateWithLifecycle()
    val rootView = LocalView.current
    fun offerCopiedLink() {
        if (!clipboardDetect) return
        val copied = readClipboardText(context, plainTextOnly = true) ?: return
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
    val errorText = state.error?.let { homeErrorText(it) }
    val infoText = state.info?.let { homeInfoText(it) }
    LaunchedEffect(errorText, infoText, state.errorKind) {
        (errorText ?: infoText)?.let {
            // Persistent inline recovery already explains this parsing failure.
            // A duplicate snackbar can cover its action buttons at the bottom.
            if (errorText == null || state.errorKind == null || state.video != null) snackbar.showSnackbar(it)
            viewModel.onErrorConsumed()
        }
    }

    Scaffold(
        modifier = Modifier.pointerInput(focusManager, keyboard) {
            detectTapGestures(onTap = { dismissKeyboard() })
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (state.video == null) {
            val showFirstUseGuide = UiDensityPolicy.showFirstUseGuide(
                hasSavedMedia = recentRecords.isNotEmpty(),
                input = state.input,
                hasClipboardOffer = state.clipboardOffer != null,
                dismissed = firstUseGuideDismissed,
            )
            val completed = recentRecords.filter {
                it.status == com.videodown.douyin.domain.model.DownloadStatus.COMPLETED &&
                    it.filePath?.let { path -> File(path).isFile } == true
            }.take(2)
            // 输入页出现时淡入；返回解析页同样重放一次。
            val entrance = rememberEntranceAlpha()
            AdaptivePage(Modifier.fillMaxSize().padding(padding)) {
            // 解析主面板与「站点 / 最近保存」在 Expanded 下并排；其余尺寸保持单列，
            // 双栏只放宽工作区，不改变任何业务流程与 testTag。
            val dualPane = WindowLayoutPolicy.homeDualPane(LocalWindowWidth.current)
            val parsePanel: @Composable () -> Unit = {
                // 单一 surfaceContainerLow 工作区：外层不再叠描边，输入区用一档 tonal 容器区分，
                // 层级由颜色而不是「卡片套输入框」的边框数量建立。
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(AppSpacing.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.home_parse_video), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = ::pasteIntoInput,
                                modifier = Modifier.testTag("home-paste-link"),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(stringResource(R.string.home_paste_link), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        state.clipboardOffer?.let { copied ->
                            ClipboardOfferBanner(
                                platformLabel = VideoLinkExtractor.extract(copied)?.platform?.let { platformName(context, it) },
                                onParse = { dismissKeyboard(); viewModel.acceptClipboardOffer() },
                                onDismiss = { dismissKeyboard(); viewModel.dismissClipboardOffer() },
                            )
                        }
                        Column(Modifier.bringIntoViewRequester(inputArea).padding(bottom = 12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(top = 13.dp).clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surfaceContainer).heightIn(min = 72.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                TextField(
                                    value = state.input,
                                    onValueChange = viewModel::onInputChange,
                                    placeholder = { Text(stringResource(R.string.home_input_placeholder),
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
                            }
                            AppPrimaryButton(
                                text = stringResource(if (state.isParsing) R.string.home_parsing else R.string.home_parse_now),
                                onClick = { dismissKeyboard(); viewModel.parse() },
                                enabled = state.canParse,
                                modifier = Modifier.padding(top = 12.dp),
                                loading = state.isParsing,
                            )
                            if (state.isParsing) {
                                TextButton(
                                    onClick = viewModel::cancelParse,
                                    modifier = Modifier.align(Alignment.CenterHorizontally).testTag("home-cancel-parse"),
                                ) { Text(stringResource(R.string.home_cancel_parse)) }
                            }
                        }
                        if (browserUrl != null) {
                            TextButton(contentPadding = PaddingValues(vertical = 8.dp), enabled = !state.isParsing, onClick = { dismissKeyboard(); showBrowser = true }) {
                                Text(stringResource(R.string.home_open_web))
                            }
                        }
                        state.errorKind?.let { kind ->
                            val advice = failureActions(kind)
                            Text(failureTitle(kind), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                            Text(failureDescription(kind), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            listOfNotNull(advice.primary.takeUnless { it == RecoveryAction.NONE }, advice.secondary).forEach { action ->
                                TextButton(contentPadding = PaddingValues(vertical = 8.dp), enabled = !state.isParsing && (action != RecoveryAction.BROWSER || browserUrl != null), onClick = {
                                    dismissKeyboard()
                                    when (action) {
                                        RecoveryAction.BROWSER -> showBrowser = true
                                        RecoveryAction.STORAGE -> onOpenStorage()
                                        RecoveryAction.SETTINGS -> onOpenSettings()
                                        RecoveryAction.SYSTEM_SETTINGS -> Unit
                                        else -> viewModel.parse()
                                    }
                                }) { Text(if (action == RecoveryAction.RETRY) stringResource(R.string.home_retry_parse) else recoveryActionLabel(action)) }
                            }
                        }
                        // Cookie 存在不代表仍有访问权限：明确缺少登录时优先重登，403 只给中性检查入口。
                        xLoginRecoveryAction(inputPlatform, state.errorKind, xSessionDetected)?.let { action ->
                            TextButton(contentPadding = PaddingValues(vertical = 8.dp), enabled = !state.isParsing,
                                onClick = { dismissKeyboard(); showXLogin = true }) {
                                Text(stringResource(when (action) {
                                    XLoginRecoveryAction.SIGN_IN_AND_RETRY -> R.string.x_login_action
                                    XLoginRecoveryAction.REAUTHENTICATE_AND_RETRY -> R.string.x_relogin_action
                                    XLoginRecoveryAction.CHECK_SIGN_IN_AND_ACCESS -> R.string.x_check_login_access_action
                                }))
                            }
                        }
                        TextButton(contentPadding = PaddingValues(vertical = 8.dp), onClick = { dismissKeyboard(); showBatchImport = true }) {
                            AppIcon(AppIcons.BatchImport, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.home_batch_import))
                        }
                        Text(stringResource(R.string.home_supported_sources), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                }
            val guideSection: @Composable () -> Unit = {
                if (showFirstUseGuide) {
                    FirstUseGuide(
                        onPasteAndParse = ::pasteAndParse,
                        onDismiss = { ServiceLocator.preferences.firstUseGuideDismissed = true },
                    )
                }
                }
            val sitesSection: @Composable () -> Unit = {
                if (UiDensityPolicy.showExpandedHomeSections(showFirstUseGuide)) {
                    SiteSection(onOpenSites)
                } else {
                    CompactSiteEntry(onOpenSites)
                }
                }
            val recentSection: @Composable () -> Unit = {
                if (UiDensityPolicy.showRecentSection(completed.size)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 27.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.home_recent), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { dismissKeyboard(); onOpenLibrary() }) {
                        Text(stringResource(R.string.home_view_all_arrow), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), modifier = Modifier.padding(bottom = AppSpacing.lg)) {
                        completed.forEach { record ->
                            val watch = watchBadge(record, watchProgress)
                            MediaCover(
                                model = record.coverUrl,
                                modifier = Modifier.weight(1f).height(150.dp).clip(MaterialTheme.shapes.large)
                                    .sharedCover(record.id, sharedTransitionScope, animatedVisibilityScope, inOverlay = true)
                                    .clickable { dismissKeyboard(); onPlayDownloaded(record.id) },
                                shape = RectangleShape,
                                backgroundColor = Color.Transparent,
                            ) {
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .68f)))))
                                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                                    Text(record.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(record.resolutionLabel.takeIf(String::isNotBlank), watch?.let { watchBadgeText(it.fraction) })
                                            .joinToString(" · "),
                                        color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall,
                                    )
                                }
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
                    }
                }                }
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(horizontal = pagePad).alpha(entrance),
            ) {
                if (dualPane) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1.1f)) {
                            TabPageHeader(stringResource(R.string.app_name), stringResource(R.string.home_header_subtitle))
                            parsePanel()
                            guideSection()
                        }
                        Column(Modifier.weight(0.9f)) {
                            sitesSection()
                            recentSection()
                        }
                    }
                } else {
                    TabPageHeader(stringResource(R.string.app_name), stringResource(R.string.home_header_subtitle))
                    parsePanel()
                    guideSection()
                    sitesSection()
                    recentSection()
                }
            }
            }
        } else {
            // 结果页淡入；两页仍由 if/else 互斥，语义树里不会同时存在。
            val entrance = rememberEntranceAlpha()
            AdaptivePage(Modifier.fillMaxSize().padding(padding)) {
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
                onToggleSubtitles = viewModel::toggleSaveSubtitles,
                onDownloadArchive = viewModel::downloadArchive,
                onConfirmArchiveDuplicate = viewModel::confirmArchiveDuplicate,
                onDismissArchiveDuplicate = viewModel::dismissArchiveDuplicate,
                onPlayDownloaded = onPlayDownloaded,
                onOpenAuthor = { video ->
                    if (!DouyinIntents.openAuthorProfile(context, video.author)) {
                        scope.launch { snackbar.showSnackbar(resources.getString(R.string.home_open_author_failed, platformName(context, video.platform))) }
                    }
                },
                onPreview = { video ->
                    if (!DouyinIntents.openVideo(context, video)) {
                        scope.launch { snackbar.showSnackbar(resources.getString(R.string.home_open_video_failed, platformName(context, video.platform))) }
                    }
                },
                modifier = Modifier.fillMaxSize().alpha(entrance),
            )
            }
        }
    }
}

@Composable
private fun CompactSiteEntry(onOpenSites: () -> Unit) {
    TextButton(
        onClick = onOpenSites,
        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.xs).testTag("site-kubb-card"),
    ) {
        AppIcon(AppIcons.Browser, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(stringResource(R.string.home_sites_title))
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.home_view_all_arrow))
    }
}

@Composable
private fun SiteSection(onOpenSites: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.lg, bottom = AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.home_sites_title), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpenSites) { Text(stringResource(R.string.home_view_all_arrow)) }
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSites).testTag("site-kubb-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(MaterialTheme.shapes.large)
                    .background(Brush.linearGradient(BrandColors.siteBadgeGradient)),
                contentAlignment = Alignment.Center,
            ) { Text("K", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.padding(start = 13.dp).weight(1f)) {
                Text("KUBB.TV", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(stringResource(R.string.home_sites_summary), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            AppIcon(AppIcons.Forward, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun FirstUseGuide(onPasteAndParse: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp).testTag("first-use-guide"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(17.dp)) {
            Text(
                stringResource(R.string.home_first_use_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(R.string.home_first_use_steps),
                modifier = Modifier.padding(top = AppSpacing.xxs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(R.string.home_clipboard_privacy),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            FilledTonalButton(
                onClick = onPasteAndParse,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
            ) {
                AppIcon(AppIcons.Paste, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_paste_and_parse))
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("first-use-guide-dismiss"),
            ) {
                Text(stringResource(R.string.action_got_it))
            }
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
    onToggleSubtitles: (Boolean) -> Unit,
    onDownloadArchive: () -> Unit,
    onConfirmArchiveDuplicate: () -> Unit,
    onDismissArchiveDuplicate: () -> Unit,
    onPlayDownloaded: (String) -> Unit,
    onOpenAuthor: (DouyinVideo) -> Unit,
    onPreview: (DouyinVideo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val video = state.video ?: return
    val pagePad = pagePadding()
    var showingVideos by rememberSaveable(state.videos.firstOrNull()?.selectionKey()) { mutableStateOf(false) }
    var expandedTitle by rememberSaveable(video.selectionKey(), video.title) { mutableStateOf(false) }
    var titleOverflows by remember(video.selectionKey(), video.title) { mutableStateOf(false) }
    var previewImageIndex by rememberSaveable(video.selectionKey()) { mutableStateOf<Int?>(null) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val screenHeight = with(LocalDensity.current) { windowHeight.toDp().value }
    val batchSelected = state.videos.size > 1 && state.selectedMedia.isNotEmpty()
    val selectedIsImage = state.selectedVariant?.kind == MediaKind.IMAGE
    val hasMixedDownloadFormats = video.variants.map { it.kind }.distinct().size > 1
    val selectableKeys = state.videos.filter { state.variantChoices[it.selectionKey()] != null }.map { it.selectionKey() }.toSet()
    val allSelected = selectableKeys.isNotEmpty() && state.selectedMedia == selectableKeys
    val resultScroll = rememberScrollState()
    LaunchedEffect(video.selectionKey()) { resultScroll.scrollTo(0) }
    // 图片作品的存前预览：按当前选择取原图地址，勾选状态直接写回批量选择。
    val previewImages = remember(state.videos, state.variantChoices) {
        state.videos.mapNotNull { item ->
            val variant = state.variantChoices[item.selectionKey()] ?: item.sortedVariants.firstOrNull()
            if (item.isImagePost || variant?.kind == MediaKind.IMAGE) {
                val url = variant?.url ?: item.coverUrl ?: return@mapNotNull null
                ImagePreviewItem(item.selectionKey(), url, item.mediaLabel ?: item.title)
            } else null
        }
    }
    if (showingVideos) ModalBottomSheet(
        onDismissRequest = { showingVideos = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = (screenHeight * .8f).dp).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_select_videos, state.videos.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SelectAllToggle(
                    label = stringResource(R.string.action_select_all),
                    summary = null,
                    allSelected = allSelected,
                    indeterminate = state.selectedMedia.isNotEmpty() && !allSelected,
                    enabled = selectableKeys.isNotEmpty() && !state.isSubmitting && state.duplicates == null,
                    onToggle = { onSelectAllMedia() },
                )
            }
            Text(stringResource(R.string.home_selected_hint, state.selectedMedia.size), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), modifier = Modifier.weight(1f, fill = false).testTag("video-selection-grid"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(state.videos, key = { _, item -> item.selectionKey() }) { index, item ->
                    val current = item.selectionKey() == video.selectionKey()
                    val imageItem = item.isImagePost || state.variantChoices[item.selectionKey()]?.kind == MediaKind.IMAGE
                    val label = item.mediaLabel ?: stringResource(R.string.home_video_index, index + 1)
                    val previewLabel = stringResource(R.string.home_preview_label, label)
                    val checkLabel = stringResource(R.string.home_check_label, label)
                    Card(onClick = { onSelectVideo(index); showingVideos = false }, enabled = !state.isSubmitting && state.duplicates == null,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = previewLabel; selected = current },
                        border = BorderStroke(if (current) 2.dp else 1.dp, if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        MediaCover(
                            model = item.coverUrl,
                            modifier = Modifier.fillMaxWidth().aspectRatio(if (imageItem) 1f else 16f / 9f),
                            shape = RectangleShape,
                        ) {
                            CoverSelectionMark(
                                selected = item.selectionKey() in state.selectedMedia,
                                enabled = !state.isSubmitting && state.duplicates == null && state.variantChoices[item.selectionKey()] != null,
                                contentDescription = checkLabel,
                                onToggle = { onToggleMedia(item.selectionKey()) },
                                modifier = Modifier.align(Alignment.TopEnd).padding(AppSpacing.xxs),
                            )
                        }
                        Column(Modifier.padding(8.dp)) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            Text(state.variantChoices[item.selectionKey()]?.label ?: stringResource(R.string.home_no_variant), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            TextButton(onClick = { showingVideos = false }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.home_done_selecting)) }
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = pagePad, vertical = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                AppIcon(AppIcons.Back, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.home_result_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenHistory, modifier = Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                AppIcon(AppIcons.Download, stringResource(R.string.history_header_title), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (state.videos.size > 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = pagePad), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_position_hint, state.videos.indexOfFirst { it.selectionKey() == video.selectionKey() } + 1, state.videos.size, state.selectedMedia.size),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { showingVideos = true }, enabled = !state.isSubmitting && state.duplicates == null) {
                    Text(stringResource(R.string.home_view_all))
                    Spacer(Modifier.width(4.dp))
                    AppIcon(AppIcons.ChevronDown, null, modifier = Modifier.size(18.dp))
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(resultScroll)) {
            MediaCover(
                model = video.coverUrl,
                modifier = Modifier.fillMaxWidth().padding(horizontal = pagePad).height((screenHeight * .28f).coerceIn(140f, 260f).dp),
                shape = MaterialTheme.shapes.extraLarge,
                contentDescription = stringResource(R.string.home_video_cover),
            ) {
                Box(Modifier.align(Alignment.TopStart).padding(16.dp).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .95f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppIcon(AppIcons.Success, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.home_parsed_platform, stringResource(videoPlatformLabelRes(video.platform))), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = {
                    if (video.isImagePost || selectedIsImage) {
                        previewImageIndex = previewImages
                            .indexOfFirst { it.selectionKey == video.selectionKey() }
                            .takeIf { it >= 0 }
                    } else {
                        onPreview(video)
                    }
                }, modifier = Modifier.align(Alignment.Center)
                    .size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = .28f))) {
                    if (video.isImagePost || selectedIsImage) {
                        // 图片作品先在应用内预览并勾选要存的图；原帖入口留在作者行。
                        AppIcon(AppIcons.Fullscreen, stringResource(R.string.home_image_preview), tint = Color.White, modifier = Modifier.size(28.dp))
                    } else {
                        AppIcon(AppIcons.Play, stringResource(R.string.home_preview_on_platform, stringResource(videoPlatformLabelRes(video.platform))), tint = Color.White, modifier = Modifier.size(35.dp))
                    }
                }
                if (!video.isImagePost && !selectedIsImage) {
                    Text(formatDuration(video.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(MaterialTheme.shapes.small)
                            .background(Color.Black.copy(alpha = .5f)).padding(horizontal = 7.dp, vertical = 5.dp))
                }
            }
            Column(Modifier.padding(horizontal = pagePad, vertical = AppSpacing.lg)) {
                Text(video.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, maxLines = if (expandedTitle) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expandedTitle) titleOverflows = it.hasVisualOverflow })
                if (titleOverflows || expandedTitle) TextButton(onClick = { expandedTitle = !expandedTitle }) {
                    Text(stringResource(if (expandedTitle) R.string.home_collapse_title else R.string.home_expand_title))
                }
                Row(Modifier.fillMaxWidth().padding(top = AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(video.author.avatarUrl, stringResource(R.string.home_author_avatar), Modifier.size(26.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(8.dp))
                    Text("@ ${video.author.nickname.ifBlank { stringResource(R.string.home_unknown_author) }}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (video.isImagePost || selectedIsImage) {
                        TextButton(onClick = { onPreview(video) }) {
                            Text(stringResource(R.string.home_open_post), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    TextButton(onClick = { onOpenAuthor(video) }, enabled = video.author.profileUrl != null) {
                        Text(stringResource(R.string.home_view_author), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(4.dp))
                        AppIcon(AppIcons.ExternalLink, null, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                if (video.isImagePost) {
                    // 图片只有原图一档：不显示画质选择，也不给"无音轨"这类视频概念。
                    Row(Modifier.fillMaxWidth().padding(top = AppSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.quality_original), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text(video.sortedVariants.firstOrNull()?.sizeBytes?.takeIf { it > 0L }?.let(::formatBytes)
                                ?: stringResource(if (state.isLoadingSizes) R.string.home_size_loading else R.string.home_size_unknown),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                Row(Modifier.fillMaxWidth().padding(top = AppSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (hasMixedDownloadFormats) R.string.home_choose_format else R.string.home_choose_quality),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.home_size_note), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.selectedVariant?.let { selected ->
                    Text(
                        stringResource(R.string.home_recommended_choice, selected.label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = AppSpacing.xs),
                    )
                }
                val variants = video.sortedVariants
                variants.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { variant ->
                            val selected = state.selectedVariant?.let {
                                it.label == variant.label && it.url == variant.url
                            } == true
                            val qualityLabel = stringResource(R.string.home_quality_label, variant.label)
                            Card(
                                onClick = { onSelectVariant(variant) }, enabled = !state.isSubmitting,
                                modifier = Modifier.weight(1f).semantics { contentDescription = qualityLabel },
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
                                        ?: stringResource(if (state.isLoadingSizes) R.string.home_size_loading else R.string.home_size_unknown),
                                        maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (video.audioVariants.isNotEmpty()) {
                    Text(stringResource(R.string.home_audio_only), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp))
                    video.audioVariants.forEach { variant ->
                        FilterChip(selected = state.selectedVariant == variant, enabled = !state.isSubmitting && state.duplicates == null,
                            onClick = { onSelectVariant(variant) }, label = { Text(variant.label) })
                    }
                    Text(stringResource(R.string.home_audio_only_hint), style = MaterialTheme.typography.labelMedium)
                } else if (!selectedIsImage) {
                    Text(stringResource(R.string.home_no_audio_track), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                }
                }
                // Only offered when the source really has a subtitle track and the target is a video:
                // there is nothing to embed into an extracted audio file.
                val subtitleTarget = state.video ?: state.videos.firstOrNull { it.selectionKey() in state.selectedMedia }
                val subtitleChoice = subtitleTarget?.let {
                    SubtitleLanguageSelection.preferred(it.subtitleLanguages, it.autoCaptionLanguages)
                } ?: SubtitleSelection.NONE
                if (subtitleChoice.available && state.selectedVariant?.media?.audioOnly != true && !selectedIsImage) {
                    Text(stringResource(R.string.home_subtitles), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp))
                    FilterChip(selected = state.saveSubtitles,
                        enabled = !state.isSubmitting && state.duplicates == null,
                        onClick = { onToggleSubtitles(!state.saveSubtitles) },
                        label = { Text(stringResource(if (state.saveSubtitles) R.string.home_subtitles_selected else R.string.home_subtitles_offer, subtitleSelectionText(subtitleChoice))) },
                        modifier = Modifier.testTag("save-subtitles"))
                    Text(stringResource(R.string.home_subtitles_hint),
                        style = MaterialTheme.typography.labelMedium)
                }
                Text(stringResource(if (video.isImagePost || selectedIsImage) R.string.home_result_image_note else R.string.home_result_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 18.dp))
            }
        }
        if (state.canDownloadArchive) {
            TextButton(
                onClick = onDownloadArchive,
                enabled = !state.isSubmitting,
                modifier = Modifier.padding(horizontal = pagePad).testTag("download-archive"),
            ) {
                Text(stringResource(R.string.home_download_archive, state.archiveTargets.size))
            }
        }
        AppPrimaryButton(
            text = when {
                state.isSubmitting -> stringResource(R.string.home_submitting)
                batchSelected -> stringResource(R.string.home_save_selected, state.selectedMedia.size)
                state.isDownloadFinished -> stringResource(R.string.home_view_saved)
                state.activeDownloadId != null -> stringResource(R.string.home_view_tasks)
                else -> stringResource(
                    when {
                        state.selectedVariant?.media?.audioOnly == true -> R.string.home_save_audio
                        video.isImagePost || selectedIsImage -> R.string.home_save_current_image
                        state.videos.size > 1 -> R.string.home_save_current
                        else -> R.string.home_save_local
                    },
                )
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
            modifier = Modifier.padding(horizontal = pagePad, vertical = AppSpacing.sm),
        )
    }
    previewImageIndex?.let { index ->
        ImagePostPreview(
            items = previewImages,
            initialIndex = index,
            selectedKeys = state.selectedMedia,
            onToggleSelection = onToggleMedia,
            onDismiss = { previewImageIndex = null },
        )
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
    InlineNotice(
        title = if (platformLabel.isNullOrBlank()) {
            stringResource(R.string.home_clipboard_detected)
        } else {
            stringResource(R.string.home_clipboard_detected_platform, platformLabel)
        },
        message = stringResource(R.string.home_clipboard_hint),
        modifier = Modifier.padding(top = 13.dp).testTag("clipboard-offer"),
        icon = AppIcons.Link,
        tone = NoticeTone.INFO,
        actions = {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("clipboard-offer-dismiss")) {
                Text(stringResource(R.string.action_ignore))
            }
            TextButton(onClick = onParse, modifier = Modifier.testTag("clipboard-offer-parse")) {
                Text(stringResource(R.string.action_parse))
            }
        },
    )
}

/** The wording for a neutral outcome. */
@Composable
private fun homeInfoText(info: HomeInfo): String = when (info) {
    HomeInfo.AlreadyQueued -> stringResource(R.string.home_info_already_queued)
    // Resolved through the same mapper as the task list, so a stored key is never shown raw.
    is HomeInfo.StoredFailure -> storedErrorMessage(info.stored).orEmpty()
    is HomeInfo.Queued -> stringResource(R.string.home_info_queued, info.count)
    is HomeInfo.QueuedPartial -> stringResource(R.string.home_info_queued_partial, info.count)
    HomeInfo.ArchiveQueued -> stringResource(R.string.home_archive_queued)
}

/** The wording for a parse failure. */
@Composable
private fun homeErrorText(error: HomeError): String = when (error) {
    is HomeError.FromApp -> appErrorText(error.error)
    HomeError.EmptyInput -> stringResource(R.string.home_error_empty_input)
    HomeError.NoVariant -> stringResource(R.string.home_error_no_variant)
    HomeError.QueueFailed -> stringResource(R.string.home_error_queue_failed)
    HomeError.WeChatChannels -> stringResource(R.string.engine_wechat_channels)
}
