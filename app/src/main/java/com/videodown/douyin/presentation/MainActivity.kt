package com.videodown.douyin.presentation

import com.videodown.douyin.R
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as AndroidIcon
import android.util.Rational
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.videodown.douyin.domain.model.RecoveryAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.videodown.douyin.presentation.theme.appMotion
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.videodown.douyin.presentation.history.HistoryScreen
import com.videodown.douyin.presentation.history.HistoryViewModel
import com.videodown.douyin.presentation.home.HomeScreen
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.library.LibraryScreen
import com.videodown.douyin.presentation.player.PlayerScreen
import com.videodown.douyin.presentation.settings.SettingsScreen
import com.videodown.douyin.presentation.theme.VideoDownTheme
import com.videodown.douyin.data.download.DownloadCompletionNotifier
import com.videodown.douyin.data.remote.VideoLinkExtractor
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.ThemeMode

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var openLibrary by mutableStateOf(false)
    private var openTasks by mutableStateOf(false)
    private var openBatchImport by mutableStateOf(false)
    /**
     * 「粘贴解析」快捷方式要求进入解析页。
     *
     * 与 [sharedText] 分开：剪贴板里没有可解析的链接时也要落到解析页——
     * 否则快捷方式点了跟没点一样（真机上表现为"打开的页面不对"）。
     */
    private var openParsePage by mutableStateOf(false)
    /** Links imported from a backup file, handed to the batch-import dialog. */
    private var batchImportSeed by mutableStateOf<String?>(null)
    private var openPlayerId by mutableStateOf<String?>(null)
    /**
     * 需要在**拿到输入焦点之后**才能读剪贴板的快捷方式动作。
     *
     * Android 10+ 只允许有输入焦点的应用读剪贴板。快捷方式把应用拉到前台时焦点还没到手，
     * 当场读只会拿到 null（真机实测：投递前后从同一进程读剪贴板，前者是 null）。
     */
    private var pendingClipboardAction: ClipboardAction? = null

    private enum class ClipboardAction { QUICK_PARSE, BATCH_IMPORT }
    var inPictureInPicture by mutableStateOf(false)
        private set
    private var pipPlayer: Player? = null
    private var pipRatio = 16f / 9f
    private val pipListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { updatePipParams() }
    }
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PIP_ACTION) pipPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
        }
    }
    val supportsPip: Boolean get() = Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    fun configurePip(player: Player?, ratio: Float = 16f / 9f) {
        if (pipPlayer !== player) { pipPlayer?.removeListener(pipListener); pipPlayer = player; player?.addListener(pipListener) }
        pipRatio = ratio.coerceIn(1f / 2.39f, 2.39f)
        updatePipParams()
    }
    private fun updatePipParams() {
        if (!supportsPip) return
        val playing = pipPlayer?.isPlaying == true
        val action = PendingIntent.getBroadcast(this, 8702, Intent(PIP_ACTION).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational((pipRatio * 1000).toInt(), 1000))
            .setActions(if (pipPlayer == null) emptyList() else listOf(RemoteAction(
                AndroidIcon.createWithResource(this, if (playing) com.videodown.douyin.R.drawable.ic_shiying_pause else com.videodown.douyin.R.drawable.ic_shiying_play),
                getString(if (playing) R.string.action_pause else R.string.action_play),
                getString(if (playing) R.string.pip_pause_playback else R.string.pip_resume_playback), action)))
        if (Build.VERSION.SDK_INT >= 31) params.setAutoEnterEnabled(playing)
        runCatching { setPictureInPictureParams(params.build()) }
    }
    fun enterVideoPip(): Boolean = supportsPip && pipPlayer != null && runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder()
        .setAspectRatio(Rational((pipRatio * 1000).toInt(), 1000)).build()) }.getOrDefault(false)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && pipPlayer?.isPlaying == true) enterVideoPip()
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }
    override fun onStop() {
        super.onStop()
        // System PiP dismissal stops the activity without necessarily destroying it.
        // Keep screen-off distinct: it can temporarily hide a PiP window.
        val interactive = getSystemService(android.os.PowerManager::class.java).isInteractive
        val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        if (isInPictureInPictureMode && pipPlayer != null && interactive && !locked && !isChangingConfigurations) finish()
    }
    override fun onDestroy() {
        configurePip(null)
        unregisterReceiver(pipReceiver)
        super.onDestroy()
    }
    companion object { private const val PIP_ACTION = "com.videodown.douyin.PIP_PLAY_PAUSE" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(PIP_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        handleIntent(intent)
        setContent {
            val themeMode by ServiceLocator.preferences.themeModeChanges.collectAsStateWithLifecycle()
            val dynamicColor by ServiceLocator.preferences.dynamicColorChanges.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            VideoDownTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            ) {
                VideoDownApp(sharedText, onSharedTextConsumed = { sharedText = null },
                    openLibrary = openLibrary, onLibraryOpened = { openLibrary = false },
                    openTasks = openTasks, onTasksOpened = { openTasks = false },
                    openBatchImport = openBatchImport, onBatchImportOpened = { openBatchImport = false },
                    openParsePage = openParsePage, onParsePageOpened = { openParsePage = false },
                    batchImportSeed = batchImportSeed, onBatchImportSeedConsumed = { batchImportSeed = null },
                    onImportLinks = { text -> batchImportSeed = text; openBatchImport = true },
                    openPlayerId = openPlayerId, onPlayerOpened = {
                        openPlayerId = null
                        // Consume this notification destination so recreation restores the current tab.
                        if (intent.action == com.videodown.douyin.data.playback.AudioPlaybackService.ACTION_OPEN_PLAYER)
                            intent.removeExtra(com.videodown.douyin.data.playback.AudioPlaybackService.EXTRA_RECORD_ID)
                        if (intent.action == DownloadCompletionNotifier.ACTION_OPEN_COMPLETED) {
                            intent.removeExtra(DownloadCompletionNotifier.EXTRA_RECORD_ID)
                            intent.action = null
                            intent.data = null
                        }
                    })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        when (pendingClipboardAction) {
            ClipboardAction.QUICK_PARSE -> {
                pendingClipboardAction = null
                copiedClipboardText()?.let { sharedText = it }
            }
            ClipboardAction.BATCH_IMPORT -> {
                pendingClipboardAction = null
                copiedClipboardText()?.let { batchImportSeed = it }
            }
            null -> Unit
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            DownloadCompletionNotifier.ACTION_OPEN_LIBRARY -> openLibrary = true
            DownloadCompletionNotifier.ACTION_OPEN_COMPLETED -> {
                openPlayerId = intent.getStringExtra(DownloadCompletionNotifier.EXTRA_RECORD_ID)?.takeIf(String::isNotBlank)
                if (openPlayerId == null) openLibrary = true
            }
            com.videodown.douyin.data.playback.AudioPlaybackService.ACTION_OPEN_PLAYER ->
                openPlayerId = intent.getStringExtra(com.videodown.douyin.data.playback.AudioPlaybackService.EXTRA_RECORD_ID)?.takeIf(String::isNotBlank)
            com.videodown.douyin.data.download.DownloadService.ACTION_OPEN_TASKS -> openTasks = true
            com.videodown.douyin.LauncherShortcuts.ACTION_BATCH_IMPORT -> {
                openBatchImport = true
                // 剪贴板里带链接就直接预填进导入框：按下这个快捷方式就是为了导入它们。
                // 复用恢复备份用的那条通道（batchImportSeed → 弹窗输入框）。
                if (hasWindowFocus()) copiedClipboardText()?.let { batchImportSeed = it }
                else pendingClipboardAction = ClipboardAction.BATCH_IMPORT
            }
            com.videodown.douyin.LauncherShortcuts.ACTION_QUICK_PARSE -> {
                // 先记下"要去解析页"：读不到剪贴板时也必须有反馈，不能静默什么都不做。
                openParsePage = true
                if (hasWindowFocus()) copiedClipboardText()?.let { sharedText = it }
                else pendingClipboardAction = ClipboardAction.QUICK_PARSE
            }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf(String::isNotBlank)
                ?.let { sharedText = it }
            Intent.ACTION_VIEW -> intent.dataString?.takeIf(String::isNotBlank)
                ?.let { sharedText = it }
        }
    }

    /**
     * Reads the clipboard for the explicit 「粘贴解析」 shortcut.
     *
     * 与被动提示的区别：用户按快捷键就是确认，所以普通网页链接也接受；被动检测器仍只认已知平台。
     */
    private fun copiedClipboardText(): String? {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
        if (!clipboard.hasPrimaryClip()) return null
        if (clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true) return null
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return null
        // 只要里面有 http(s) 链接就交给解析，**普通网页也算**：用户按下这个快捷方式就是明确要求解析它。
        // 被动提示仍旧只认已知平台（ClipboardLinkPolicy），避免拿浏览器地址乱弹窗；
        // 此前两条路径共用同一策略，普通链接被静默丢掉——真机上表现为"页面对了但输入框是空的"。
        // 纯文本（不含链接）不填，免得把无关内容送进解析。
        return text.takeIf { VideoLinkExtractor.extract(it) != null }
    }
}

private object Routes {
    const val HOME = "home"
    const val TASKS = "tasks"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val PLAYER = "player/{recordId}?cover={cover}"

    /**
     * 封面随路由一起传给播放器。
     *
     * 播放器要异步读库才有 record，而共享元素的源与目标必须在同一帧就位，所以封面不能等 record 到位。
     */
    fun player(recordId: String, coverUrl: String? = null) =
        "player/$recordId?cover=" + android.net.Uri.encode(coverUrl.orEmpty())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoDownApp(sharedText: String?, onSharedTextConsumed: () -> Unit,
    openLibrary: Boolean, onLibraryOpened: () -> Unit,
    historyViewModel: HistoryViewModel = viewModel(),
    homeViewModel: HomeViewModel = viewModel(),
    openPlayerId: String? = null, onPlayerOpened: () -> Unit = {},
    openTasks: Boolean = false, onTasksOpened: () -> Unit = {},
    openBatchImport: Boolean = false, onBatchImportOpened: () -> Unit = {},
    openParsePage: Boolean = false, onParsePageOpened: () -> Unit = {},
    batchImportSeed: String? = null, onBatchImportSeedConsumed: () -> Unit = {},
    /** Links read from a backup file; the app switches to the parse page and opens 批量导入. */
    onImportLinks: (String) -> Unit = {}) {
    val navController = rememberNavController()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.isImeVisible
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var requestedStorage by remember { mutableStateOf(false) }
    val historyState by historyViewModel.state.collectAsStateWithLifecycle()
    // 列表数据本来就在内存里，取封面不需要再读一次库。
    fun openPlayer(id: String) {
        navController.navigate(Routes.player(id, historyState.records.firstOrNull { it.id == id }?.coverUrl))
    }
    // Reactive so returning from the player immediately refreshes 「继续观看」 in both lists.
    val watchProgress by ServiceLocator.playbackRepository.watchProgress().collectAsStateWithLifecycle()
    val tabs = listOf(Routes.HOME, Routes.TASKS, Routes.LIBRARY, Routes.SETTINGS)
    // Read through resources so the tab bar follows the app language.
    val labels = listOf(R.string.tab_parse, R.string.tab_tasks, R.string.tab_library, R.string.tab_profile).map { stringResource(it) }
    val tabTags = listOf("tab-parse", "tab-tasks", "tab-library", "tab-settings")
    val icons = listOf(AppIcons.Parse, AppIcons.Tasks, AppIcons.Library, AppIcons.Profile)
    val selectedIcons = listOf(AppIcons.ParseSelected, AppIcons.TasksSelected, AppIcons.LibrarySelected, AppIcons.ProfileSelected)

    fun navigateTab(destination: String) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        if (route != destination) {
            navController.navigate(destination) {
                popUpTo(Routes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    LaunchedEffect(route) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank() && route != Routes.HOME) navigateTab(Routes.HOME)
    }

    // 快捷方式必须自己把界面带到解析页：批量导入的弹窗只存在于解析页，用户当时可能停在别的 tab。
    // 少了这两段的话，「批量导入」会停在原来的 tab 上什么都不弹（真机实测），标志还会挂到下次进解析页才突然生效。
    LaunchedEffect(openBatchImport) {
        if (openBatchImport && route != Routes.HOME) navigateTab(Routes.HOME)
    }

    LaunchedEffect(openParsePage) {
        if (openParsePage) {
            if (route != Routes.HOME) navigateTab(Routes.HOME)
            onParsePageOpened()
        }
    }

    LaunchedEffect(openPlayerId) {
        openPlayerId?.let { id -> openPlayer(id); onPlayerOpened() }
    }

    LaunchedEffect(openLibrary) {
        if (openLibrary) {
            navigateTab(Routes.LIBRARY)
            onLibraryOpened()
        }
    }

    LaunchedEffect(openTasks) {
        if (openTasks) {
            navigateTab(Routes.TASKS)
            onTasksOpened()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (route in tabs && !keyboardVisible) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    // Tags make the tab bar addressable by tests without depending on the copy,
                    // which now follows the app language.
                    modifier = Modifier.testTag("tab-bar")) {
                    tabs.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = route == destination,
                            onClick = { navigateTab(destination) },
                            modifier = Modifier.testTag(tabTags[index]),
                            icon = { AppIcon(if (route == destination) selectedIcons[index] else icons[index], contentDescription = null) },
                            label = { Text(labels[index]) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { padding ->
        // 页面切换统一走一次淡入淡出：只动透明度，不做位移/缩放，避免依赖坐标的断言读到中间值。
        val motion = appMotion()
        // 共享元素（已存封面 → 播放器封面）需要 SharedTransitionLayout 作祖先，作用域逐屏显式传下去。
        SharedTransitionLayout {
            val sharedTransitionScope = this
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = if (route == Routes.PLAYER) {
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                } else {
                    Modifier.padding(padding).consumeWindowInsets(padding).background(MaterialTheme.colorScheme.background)
                },
                enterTransition = { fadeIn(motion.fast()) },
                exitTransition = { fadeOut(motion.fast()) },
                popEnterTransition = { fadeIn(motion.fast()) },
                popExitTransition = { fadeOut(motion.fast()) },
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        sharedText = sharedText,
                        onSharedTextConsumed = onSharedTextConsumed,
                        onOpenHistory = { navigateTab(Routes.TASKS) },
                        onOpenLibrary = { navigateTab(Routes.LIBRARY) },
                        onPlayDownloaded = { openPlayer(it) },
                        // 首页「最近保存」没有门禁，所以私密条目不进入这里。
                        recentRecords = historyState.records.filterNot { it.isPrivate },
                        viewModel = homeViewModel,
                        onOpenStorage = { requestedStorage = true; navigateTab(Routes.SETTINGS) },
                        onOpenSettings = { navigateTab(Routes.SETTINGS) },
                        openBatchImport = openBatchImport,
                        onBatchImportOpened = onBatchImportOpened,
                        watchProgress = watchProgress,
                        batchImportSeed = batchImportSeed,
                        onBatchImportSeedConsumed = onBatchImportSeedConsumed,
                    )
                }
                composable(Routes.TASKS) {
                    HistoryScreen(
                        onPlay = { openPlayer(it) },
                        viewModel = historyViewModel,
                        onRecovery = { record, action ->
                            val page = record.sourcePageUrl.ifBlank { record.media?.pageUrl.orEmpty() }
                            when (action) {
                                RecoveryAction.STORAGE -> { requestedStorage = true; navigateTab(Routes.SETTINGS) }
                                RecoveryAction.SETTINGS -> navigateTab(Routes.SETTINGS)
                                RecoveryAction.BROWSER -> if (page.isNotBlank()) { homeViewModel.requestBrowserAssistance(page); navigateTab(Routes.HOME) }
                                RecoveryAction.REPARSE -> if (page.isNotBlank()) { homeViewModel.parseSharedText(page); navigateTab(Routes.HOME) }
                                else -> Unit
                            }
                        },
                    )
                }
                composable(Routes.LIBRARY) {
                    val animatedVisibilityScope = this
                    LibraryScreen(
                        historyViewModel = historyViewModel,
                        onPlay = { openPlayer(it) },
                        watchProgress = watchProgress,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        openStorage = requestedStorage,
                        onStorageOpened = { requestedStorage = false },
                        onImportLinks = { text -> onImportLinks(text); navigateTab(Routes.HOME) },
                    )
                }
                composable(
                    route = Routes.PLAYER,
                    arguments = listOf(
                        navArgument("recordId") { type = NavType.StringType },
                        // 封面是可选参数；缺省空串表示没有共享元素源。
                        navArgument("cover") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { playerEntry ->
                    val animatedVisibilityScope = this
                    PlayerScreen(
                        recordId = playerEntry.arguments?.getString("recordId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        // 封面只在导航时就确定，别等播放器异步读库。
                        coverUrl = playerEntry.arguments?.getString("cover")?.takeIf(String::isNotBlank),
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
            }
        }
    }
}
