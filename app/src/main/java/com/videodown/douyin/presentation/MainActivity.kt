package com.videodown.douyin.presentation

import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import android.content.Intent
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
import com.videodown.douyin.data.remote.ClipboardLinkPolicy
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.ThemeMode

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var openLibrary by mutableStateOf(false)
    private var openTasks by mutableStateOf(false)
    private var openBatchImport by mutableStateOf(false)
    private var openPlayerId by mutableStateOf<String?>(null)
    /** 「粘贴解析」 arrived before the window had input focus, so the clipboard cannot be read yet. */
    private var pendingQuickParse = false
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
                if (playing) "暂停" else "播放", if (playing) "暂停播放" else "继续播放", action)))
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

    // All app-owned copy is Simplified Chinese. Keep framework and player resources
    // in the same language, including after rotation or a system language change.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(Configuration().apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
            setLayoutDirection(Locale.SIMPLIFIED_CHINESE)
        })
    }

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
        if (!hasFocus || !pendingQuickParse) return
        pendingQuickParse = false
        copiedClipboardLink()?.let { sharedText = it }
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
            com.videodown.douyin.LauncherShortcuts.ACTION_BATCH_IMPORT -> openBatchImport = true
            com.videodown.douyin.LauncherShortcuts.ACTION_QUICK_PARSE ->
                if (hasWindowFocus()) copiedClipboardLink()?.let { sharedText = it } else pendingQuickParse = true
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf(String::isNotBlank)
                ?.let { sharedText = it }
            Intent.ACTION_VIEW -> intent.dataString?.takeIf(String::isNotBlank)
                ?.let { sharedText = it }
        }
    }

    /**
     * Reads the clipboard for the explicit 「粘贴解析」 shortcut.
     *
     * Only a known platform link qualifies, and the generic-confirmation banner of the passive
     * detector is skipped because tapping the shortcut already is the confirmation.
     */
    private fun copiedClipboardLink(): String? {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
        if (!clipboard.hasPrimaryClip()) return null
        if (clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true) return null
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return null
        return ClipboardLinkPolicy.candidate(text, null)?.text
    }
}

private object Routes {
    const val HOME = "home"
    const val TASKS = "tasks"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val PLAYER = "player/{recordId}"
    fun player(recordId: String) = "player/$recordId"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoDownApp(sharedText: String?, onSharedTextConsumed: () -> Unit,
    openLibrary: Boolean, onLibraryOpened: () -> Unit,
    historyViewModel: HistoryViewModel = viewModel(),
    homeViewModel: HomeViewModel = viewModel(),
    openPlayerId: String? = null, onPlayerOpened: () -> Unit = {},
    openTasks: Boolean = false, onTasksOpened: () -> Unit = {},
    openBatchImport: Boolean = false, onBatchImportOpened: () -> Unit = {}) {
    val navController = rememberNavController()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.isImeVisible
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var requestedStorage by remember { mutableStateOf(false) }
    val historyState by historyViewModel.state.collectAsStateWithLifecycle()
    val tabs = listOf(Routes.HOME, Routes.TASKS, Routes.LIBRARY, Routes.SETTINGS)
    val labels = listOf("解析", "任务", "已存", "我的")
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

    LaunchedEffect(openPlayerId) {
        openPlayerId?.let { id -> navController.navigate(Routes.player(id)) { launchSingleTop = true }; onPlayerOpened() }
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
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    tabs.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = route == destination,
                            onClick = { navigateTab(destination) },
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
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = if (route == Routes.PLAYER) {
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            } else {
                Modifier.padding(padding).consumeWindowInsets(padding).background(MaterialTheme.colorScheme.background)
            },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    sharedText = sharedText,
                    onSharedTextConsumed = onSharedTextConsumed,
                    onOpenHistory = { navigateTab(Routes.TASKS) },
                    onOpenLibrary = { navigateTab(Routes.LIBRARY) },
                    onPlayDownloaded = { navController.navigate(Routes.player(it)) },
                    recentRecords = historyState.records,
                    viewModel = homeViewModel,
                    onOpenStorage = { requestedStorage = true; navigateTab(Routes.SETTINGS) },
                    onOpenSettings = { navigateTab(Routes.SETTINGS) },
                    openBatchImport = openBatchImport,
                    onBatchImportOpened = onBatchImportOpened,
                )
            }
            composable(Routes.TASKS) {
                HistoryScreen(
                    onPlay = { navController.navigate(Routes.player(it)) },
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
                LibraryScreen(
                    historyViewModel = historyViewModel,
                    onPlay = { navController.navigate(Routes.player(it)) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(openStorage = requestedStorage, onStorageOpened = { requestedStorage = false }) }
            composable(
                route = Routes.PLAYER,
                arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
            ) { playerEntry ->
                PlayerScreen(
                    recordId = playerEntry.arguments?.getString("recordId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
