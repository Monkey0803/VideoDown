package com.videodown.douyin.presentation.settings

import com.videodown.douyin.presentation.components.TabPageHeader
import com.videodown.douyin.presentation.components.AppConfirmDialog
import com.videodown.douyin.presentation.components.AppDialog
import com.videodown.douyin.presentation.components.AppDialogButtonRow
import com.videodown.douyin.presentation.components.AppDialogRadioRow
import com.videodown.douyin.presentation.components.AdaptivePage
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.presentation.preferredQualityLabelRes
import com.videodown.douyin.presentation.settingsChangeText
import com.videodown.douyin.presentation.speedLimitText
import com.videodown.douyin.presentation.themeModeLabelRes
import com.videodown.douyin.presentation.appLanguageLabelRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.core.PreferredQuality
import com.videodown.douyin.core.AppLanguage
import com.videodown.douyin.core.appLanguageSwitchSupported
import com.videodown.douyin.core.applyAppLanguage
import com.videodown.douyin.core.currentAppLanguage
import com.videodown.douyin.domain.model.speedLimitOptions
import com.videodown.douyin.core.ThemeMode
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.presentation.login.XLoginScreen
import com.videodown.douyin.data.download.DownloadCompletionNotifier
import com.videodown.douyin.core.SettingsChange
import com.videodown.douyin.data.backup.SavedOrder
import com.videodown.douyin.data.backup.SettingsSnapshot
import com.videodown.douyin.presentation.theme.AppSpacing

private enum class SettingsPage { ROOT, APPEARANCE, DOWNLOAD_NETWORK, WEBSITES_ACCOUNTS, BACKUP_STORAGE, ABOUT_UPDATE }

private data class PendingSettingsRestore(val snapshot: SettingsSnapshot, val changes: List<SettingsChange>)

@Composable
fun SettingsScreen(openStorage: Boolean = false, onStorageOpened: () -> Unit = {},
    onImportLinks: (String) -> Unit = {},
    storageViewModel: StorageViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    updateViewModel: UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    preferences: com.videodown.douyin.core.AppPreferences = ServiceLocator.preferences) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    // 资源按当前组合读取：context.getString 不会随语言/配置切换重算。
    val resources = LocalResources.current
    val wifiOnly by preferences.wifiOnlyChanges.collectAsStateWithLifecycle()
    val autoResume by preferences.autoResumeChanges.collectAsStateWithLifecycle()
    val maxConcurrent by preferences.maxConcurrentChanges.collectAsStateWithLifecycle()
    var showConcurrency by rememberSaveable { mutableStateOf(false) }
    val themeMode by preferences.themeModeChanges.collectAsStateWithLifecycle()
    var showThemeMode by rememberSaveable { mutableStateOf(false) }
    // 语言状态由系统保管（Android 13+ 的按应用语言），回到页面时重新读一次即可。
    var appLanguage by remember { mutableStateOf(currentAppLanguage(context)) }
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    var notifyOnCompletion by rememberSaveable { mutableStateOf(preferences.notifyOnCompletion) }
    var notificationsAvailable by remember { mutableStateOf(DownloadCompletionNotifier.areNotificationsEnabled(context)) }
    var showNotificationSettings by rememberSaveable { mutableStateOf(false) }
    var enableAfterSettings by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAvailable = DownloadCompletionNotifier.areNotificationsEnabled(context)
        if (granted && notificationsAvailable) {
            preferences.notifyOnCompletion = true
            notifyOnCompletion = true
        } else {
            showNotificationSettings = true
        }
    }
    val individualNotifications by preferences.individualTaskNotificationsChanges.collectAsStateWithLifecycle()
    var enableIndividualAfterSettings by rememberSaveable { mutableStateOf(false) }
    var showIndividualNotificationSettings by rememberSaveable { mutableStateOf(false) }
    val individualNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAvailable = DownloadCompletionNotifier.areNotificationsEnabled(context)
        if (granted && notificationsAvailable) preferences.individualTaskNotifications = true
        else showIndividualNotificationSettings = true
    }
    val dynamicColor by preferences.dynamicColorChanges.collectAsStateWithLifecycle()
    val clipboardDetect by preferences.clipboardDetectChanges.collectAsStateWithLifecycle()
    val autoSaveToGallery by preferences.autoSaveToGalleryChanges.collectAsStateWithLifecycle()
    val globalSpeedLimit by preferences.globalSpeedLimitChanges.collectAsStateWithLifecycle()
    val playlistParsing by preferences.playlistParsingChanges.collectAsStateWithLifecycle()
    var showSpeedLimit by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsAvailable = DownloadCompletionNotifier.areNotificationsEnabled(context)
        appLanguage = currentAppLanguage(context)
        if (enableAfterSettings) {
            if (notificationsAvailable) preferences.notifyOnCompletion = true
            enableAfterSettings = false
        }
        if (enableIndividualAfterSettings) {
            if (notificationsAvailable) preferences.individualTaskNotifications = true
            enableIndividualAfterSettings = false
        }
        notifyOnCompletion = preferences.notifyOnCompletion
    }
    val engineVersion by ServiceLocator.videoEngine.version.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { ServiceLocator.videoEngine.inspectVersion() }
    val sessionScope = rememberCoroutineScope()
    var sessionMessage by remember { mutableStateOf<String?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var clearSessionTaskCount by remember { mutableStateOf<Int?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingSettingsRestore?>(null) }
    // X 登录态由 WebView 持有；这里只显示「本机现在有没有」，有效期交给 X 决定，不存过期时间。
    var showXLogin by remember { mutableStateOf(false) }
    var xSignedIn by remember { mutableStateOf<Boolean?>(null) }
    suspend fun refreshXSession() { xSignedIn = ServiceLocator.browserSessions.xSignedIn() }
    LaunchedEffect(Unit) { refreshXSession() }
    val exportLinksFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) sessionScope.launch {
            val records = ServiceLocator.historyRepository.snapshot()
            val text = com.videodown.douyin.data.backup.LinkList.format(records)
            val count = text.trim().lines().size - 1
            backupMessage = if (com.videodown.douyin.data.backup.BackupDocuments.write(context, uri, text)) {
                if (count > 0) resources.getString(R.string.settings_links_exported, count)
                else resources.getString(R.string.settings_links_exported_empty)
            } else resources.getString(R.string.settings_write_failed)
        }
    }
    val importLinksFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sessionScope.launch {
            val links = com.videodown.douyin.data.backup.LinkList.parse(
                com.videodown.douyin.data.backup.BackupDocuments.read(context, uri),
            )
            if (links.isEmpty()) backupMessage = resources.getString(R.string.settings_import_empty)
            else onImportLinks(links.joinToString("\n"))
        }
    }
    val exportSettingsFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) sessionScope.launch {
            val text = encodeSettingsBackup(preferences, ServiceLocator.historyRepository)
            backupMessage = if (com.videodown.douyin.data.backup.BackupDocuments.write(context, uri, text)) resources.getString(R.string.settings_backup_written)
                else resources.getString(R.string.settings_write_failed)
        }
    }
    val importSettingsFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sessionScope.launch {
            val snapshot = com.videodown.douyin.data.backup.SettingsBackupCodec.decode(
                com.videodown.douyin.data.backup.BackupDocuments.read(context, uri),
            )
            if (snapshot == null) backupMessage = resources.getString(R.string.settings_backup_invalid)
            else {
                val records = ServiceLocator.historyRepository.snapshot()
                val changes = previewSettingsBackup(
                    incoming = snapshot,
                    current = preferences.snapshot(SavedOrder.of(records)),
                    libraryOrderChanges = SavedOrder.restorableOrder(records, snapshot.savedOrder) != null,
                )
                if (changes.isEmpty()) backupMessage = resources.getString(R.string.settings_backup_same)
                else pendingRestore = PendingSettingsRestore(snapshot, changes)
            }
        }
    }
    var quality by rememberSaveable { mutableStateOf(preferences.preferredQuality) }
    var showQuality by rememberSaveable { mutableStateOf(false) }
    var showStorage by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    val installedVersion = remember { ServiceLocator.appVersion.versionName }

    LaunchedEffect(openStorage) {
        if (openStorage) {
            page = SettingsPage.BACKUP_STORAGE
            showStorage = true
            onStorageOpened()
        }
    }
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }
    if (showXLogin) {
        XLoginScreen(onClose = { showXLogin = false },
            onFinished = { showXLogin = false; sessionScope.launch { refreshXSession() } })
    }
    if (showConcurrency) {
        var chosen by remember(maxConcurrent) { mutableStateOf(maxConcurrent) }
        AppDialog(
            onDismissRequest = { showConcurrency = false },
            icon = AppIcons.Queue,
            title = stringResource(R.string.settings_concurrency_title),
            content = {
                Text(stringResource(R.string.settings_concurrency_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    (1..5).forEach { count ->
                        AppDialogRadioRow(selected = chosen == count, onSelect = { chosen = count }, minHeight = 48.dp) {
                            Text(stringResource(R.string.settings_concurrency_count, count),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            actions = {
                AppDialogButtonRow {
                    TextButton(onClick = { showConcurrency = false }) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { preferences.maxConcurrentDownloads = chosen; showConcurrency = false }) { Text(stringResource(R.string.action_ok)) }
                }
            },
        )
    }
    if (showThemeMode) {
        var selectedMode by rememberSaveable(themeMode) { mutableStateOf(themeMode) }
        AppDialog(
            onDismissRequest = { showThemeMode = false },
            icon = AppIcons.Appearance,
            title = stringResource(R.string.settings_theme_title),
            content = {
                Text(stringResource(R.string.settings_theme_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { mode ->
                        AppDialogRadioRow(selected = selectedMode == mode, onSelect = { selectedMode = mode }) {
                            Text(stringResource(themeModeLabelRes(mode)), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            actions = {
                AppDialogButtonRow {
                    TextButton(onClick = { showThemeMode = false }) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        preferences.themeMode = selectedMode
                        showThemeMode = false
                    }) { Text(stringResource(R.string.action_ok)) }
                }
            },
        )
    }

    if (showLanguage) {
        if (appLanguageSwitchSupported()) {
            var selectedLanguage by rememberSaveable(appLanguage) { mutableStateOf(appLanguage) }
            AppDialog(
                onDismissRequest = { showLanguage = false },
                icon = AppIcons.Appearance,
                title = stringResource(R.string.settings_language_title),
                content = {
                    Text(stringResource(R.string.settings_language_body), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.selectableGroup()) {
                        AppLanguage.entries.forEach { language ->
                            AppDialogRadioRow(selected = selectedLanguage == language, onSelect = { selectedLanguage = language }) {
                                Text(stringResource(appLanguageLabelRes(language)), style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                },
                actions = {
                    AppDialogButtonRow {
                        TextButton(onClick = { showLanguage = false }) { Text(stringResource(R.string.action_cancel)) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = {
                            // 系统会因按应用语言变化重建 Activity，界面语言随之切换。
                            appLanguage = selectedLanguage
                            applyAppLanguage(context, selectedLanguage)
                            showLanguage = false
                        }) { Text(stringResource(R.string.action_ok)) }
                    }
                },
            )
        } else {
            // Android 12 及以下没有按应用语言，只解释并给出系统设置的入口。
            AppConfirmDialog(
                onDismissRequest = { showLanguage = false },
                icon = AppIcons.Appearance,
                title = stringResource(R.string.settings_language_title),
                body = stringResource(R.string.settings_language_unsupported),
                confirmText = stringResource(R.string.action_open_settings),
                onConfirm = {
                    showLanguage = false
                    runCatching { context.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) }
                },
                dismissText = stringResource(R.string.action_cancel),
            )
        }
    }

    if (showNotificationSettings) {
        AppConfirmDialog(
            onDismissRequest = { showNotificationSettings = false },
            icon = AppIcons.Notification,
            title = stringResource(R.string.settings_notify_title),
            body = stringResource(R.string.settings_notify_body),
            confirmText = stringResource(R.string.action_open_settings),
            onConfirm = {
                showNotificationSettings = false
                enableAfterSettings = true
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            },
            dismissText = stringResource(R.string.action_cancel),
        )
    }
    if (showIndividualNotificationSettings) {
        AppConfirmDialog(
            onDismissRequest = { showIndividualNotificationSettings = false },
            icon = AppIcons.Notification,
            title = stringResource(R.string.settings_individual_title),
            body = stringResource(R.string.settings_individual_body),
            confirmText = stringResource(R.string.action_open_settings),
            onConfirm = {
                showIndividualNotificationSettings = false
                enableIndividualAfterSettings = true
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            },
            dismissText = stringResource(R.string.action_cancel),
        )
    }
    if (showQuality) {
        var selectedQuality by rememberSaveable(quality) { mutableStateOf(quality) }
        AppDialog(
            onDismissRequest = { showQuality = false },
            icon = AppIcons.Quality,
            title = stringResource(R.string.settings_quality_title),
            content = {
                Text(stringResource(R.string.settings_quality_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    PreferredQuality.entries.forEach { option ->
                        AppDialogRadioRow(selected = selectedQuality == option, onSelect = { selectedQuality = option }, minHeight = 64.dp) {
                            Text(stringResource(preferredQualityLabelRes(option)), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(when (option) {
                                PreferredQuality.HIGHEST -> stringResource(R.string.settings_quality_highest)
                                PreferredQuality.STANDARD -> stringResource(R.string.settings_quality_standard)
                                PreferredQuality.SAVER -> stringResource(R.string.settings_quality_saver)
                            }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            },
            actions = {
                AppDialogButtonRow {
                    TextButton(onClick = { showQuality = false }) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        preferences.preferredQuality = selectedQuality
                        quality = selectedQuality
                        showQuality = false
                    }) { Text(stringResource(R.string.action_ok)) }
                }
            },
        )
    }
    if (showSpeedLimit) {
        var selectedLimit by rememberSaveable(globalSpeedLimit) { mutableStateOf(globalSpeedLimit) }
        AppDialog(
            onDismissRequest = { showSpeedLimit = false },
            icon = AppIcons.Speed,
            title = stringResource(R.string.settings_speed_title),
            content = {
                Text(stringResource(R.string.settings_speed_body),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    speedLimitOptions.forEach { option ->
                        AppDialogRadioRow(selected = selectedLimit == option, onSelect = { selectedLimit = option }) {
                            Text(speedLimitText(option), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            actions = {
                AppDialogButtonRow {
                    TextButton(onClick = { showSpeedLimit = false }) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        preferences.globalSpeedLimitKbps = selectedLimit
                        showSpeedLimit = false
                    }) { Text(stringResource(R.string.action_ok)) }
                }
            },
        )
    }
    if (showStorage) StorageDialog(storageViewModel) { showStorage = false }
    if (showUpdate) UpdateDialog(updateViewModel, englishUi = LocalConfiguration.current.locales[0].language == "en") { showUpdate = false }
    if (showAbout) {
        AppConfirmDialog(
            onDismissRequest = { showAbout = false },
            icon = AppIcons.Info,
            title = stringResource(R.string.settings_about_title),
            body = stringResource(R.string.settings_about_body, installedVersion, engineVersion),
            confirmText = stringResource(R.string.action_got_it),
            onConfirm = { showAbout = false },
        )
    }
    clearSessionTaskCount?.let { taskCount ->
        AppConfirmDialog(
            onDismissRequest = { clearSessionTaskCount = null },
            icon = AppIcons.ClearSession,
            title = stringResource(R.string.settings_clear_session_confirm_title),
            body = stringResource(R.string.settings_clear_session_confirm_body, taskCount),
            confirmText = stringResource(R.string.settings_clear_session_confirm_action),
            onConfirm = {
                clearSessionTaskCount = null
                sessionScope.launch {
                    ServiceLocator.historyRepository.snapshot().filter { it.media?.useBrowserSession == true &&
                        it.status in setOf(com.videodown.douyin.domain.model.DownloadStatus.RUNNING, com.videodown.douyin.domain.model.DownloadStatus.PENDING)
                    }.forEach { ServiceLocator.downloader.pause(it.id) }
                    ServiceLocator.browserSessions.clear()
                    sessionMessage = resources.getString(R.string.settings_session_cleared)
                    refreshXSession()
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            destructive = true,
        )
    }
    pendingRestore?.let { restore ->
        AppDialog(
            onDismissRequest = { pendingRestore = null },
            icon = AppIcons.Storage,
            title = stringResource(R.string.settings_restore_confirm_title),
            content = {
                Text(stringResource(R.string.settings_restore_confirm_body, restore.changes.size),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                restore.changes.forEach { change ->
                    Text("• ${settingsChangeLabel(change)}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            },
            actions = {
                AppDialogButtonRow {
                    TextButton(onClick = { pendingRestore = null }) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        pendingRestore = null
                        sessionScope.launch {
                            val applied = applySettingsBackup(restore.snapshot, preferences, ServiceLocator.historyRepository)
                            backupMessage = resources.getString(R.string.settings_restored,
                                applied.joinToString(resources.getString(R.string.list_separator)) {
                                    settingsChangeText(context, it, preferences)
                                })
                        }
                    }) { Text(stringResource(R.string.settings_restore_confirm_action)) }
                }
            },
        )
    }

    AdaptivePage(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState())
        .padding(horizontal = AppSpacing.lg)) {
        when (page) {
        SettingsPage.ROOT -> {
        TabPageHeader(stringResource(R.string.settings_header_title), stringResource(R.string.settings_header_subtitle))
        SettingsCard {
            SettingsHubRow(
                icon = { AppIcon(AppIcons.Appearance, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_hub_appearance),
                subtitle = stringResource(R.string.settings_hub_appearance_value),
                onClick = { page = SettingsPage.APPEARANCE },
                modifier = Modifier.testTag("settings-open-appearance"),
            )
            SettingsHubRow(
                icon = { AppIcon(AppIcons.Download, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_hub_download),
                subtitle = stringResource(R.string.settings_hub_download_value),
                onClick = { page = SettingsPage.DOWNLOAD_NETWORK },
                modifier = Modifier.testTag("settings-open-download"),
            )
            SettingsHubRow(
                icon = { AppIcon(AppIcons.Browser, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_hub_accounts),
                subtitle = stringResource(R.string.settings_hub_accounts_value),
                onClick = { page = SettingsPage.WEBSITES_ACCOUNTS },
                modifier = Modifier.testTag("settings-open-accounts"),
            )
            SettingsHubRow(
                icon = { AppIcon(AppIcons.Storage, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_hub_storage),
                subtitle = stringResource(R.string.settings_hub_storage_value),
                onClick = { page = SettingsPage.BACKUP_STORAGE },
                modifier = Modifier.testTag("settings-open-storage"),
            )
            SettingsHubRow(
                icon = { AppIcon(AppIcons.Info, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_hub_about),
                subtitle = stringResource(R.string.settings_hub_about_value),
                onClick = { page = SettingsPage.ABOUT_UPDATE },
                modifier = Modifier.testTag("settings-open-about"),
            )
        }
        }
        SettingsPage.APPEARANCE -> {
        SettingsSubpageHeader(stringResource(R.string.settings_hub_appearance)) { page = SettingsPage.ROOT }
        SectionLabel(stringResource(R.string.settings_section_appearance))
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Appearance, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_theme_title), value = stringResource(themeModeLabelRes(themeMode)), onClick = { showThemeMode = true })
            SettingsRow(icon = { AppIcon(AppIcons.Appearance, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_language_title), value = stringResource(appLanguageLabelRes(appLanguage)),
                onClick = { showLanguage = true })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ToggleRow(
                    icon = { AppIcon(AppIcons.Appearance, null, tint = MaterialTheme.colorScheme.primary) },
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = stringResource(if (dynamicColor) R.string.settings_dynamic_on else R.string.settings_dynamic_off),
                    checked = dynamicColor,
                    onCheckedChange = { preferences.dynamicColor = it },
                )
            }
        }
        }
        SettingsPage.DOWNLOAD_NETWORK -> {
        SettingsSubpageHeader(stringResource(R.string.settings_hub_download)) { page = SettingsPage.ROOT }
        SectionLabel(stringResource(R.string.settings_section_download))
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Queue, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_concurrency_title), value = stringResource(R.string.settings_concurrency_count, maxConcurrent),
                onClick = { showConcurrency = true })
            SettingsRow(icon = { AppIcon(AppIcons.Quality, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_quality_title), value = stringResource(preferredQualityLabelRes(quality)), onClick = { showQuality = true })
            SettingsRow(icon = { AppIcon(AppIcons.Speed, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_speed_title), value = speedLimitText(globalSpeedLimit), onClick = { showSpeedLimit = true })
            ToggleRow(
                icon = { AppIcon(AppIcons.Wifi, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_wifi),
                subtitle = stringResource(if (wifiOnly) R.string.settings_wifi_on else R.string.settings_wifi_off),
                checked = wifiOnly,
                onCheckedChange = { preferences.wifiOnly = it },
            )
            ToggleRow(
                icon = { AppIcon(AppIcons.ResumeDownload, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_auto_resume),
                subtitle = stringResource(if (autoResume) R.string.settings_auto_resume_on else R.string.settings_auto_resume_off),
                checked = autoResume,
                onCheckedChange = { preferences.autoResume = it },
            )
            ToggleRow(
                icon = { AppIcon(AppIcons.Notification, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_notify_row),
                subtitle = when {
                    !notificationsAvailable -> stringResource(R.string.settings_notify_unavailable)
                    notifyOnCompletion -> stringResource(R.string.settings_notify_on)
                    else -> stringResource(R.string.settings_notify_off)
                },
                checked = notifyOnCompletion && notificationsAvailable,
                onCheckedChange = { enabled ->
                    when {
                        !enabled -> {
                            preferences.notifyOnCompletion = false
                            notifyOnCompletion = false
                        }
                        DownloadCompletionNotifier.areNotificationsEnabled(context) -> {
                            notificationsAvailable = true
                            preferences.notifyOnCompletion = true
                            notifyOnCompletion = true
                        }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> showNotificationSettings = true
                    }
                },
            )
            ToggleRow(
                icon = { AppIcon(AppIcons.Notification, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_individual_row),
                subtitle = when {
                    !notificationsAvailable -> stringResource(R.string.settings_notify_unavailable)
                    individualNotifications -> stringResource(R.string.settings_individual_on)
                    else -> stringResource(R.string.settings_individual_off)
                },
                checked = individualNotifications && notificationsAvailable,
                onCheckedChange = { enabled ->
                    when {
                        !enabled -> {
                            preferences.individualTaskNotifications = false
                            // 服务可能已经退出（全部暂停），留下的暂停卡要当场收掉，不能等下次服务启动。
                            com.videodown.douyin.data.download.IndividualTaskNotifications.cancelAll(context)
                        }
                        DownloadCompletionNotifier.areNotificationsEnabled(context) -> {
                            notificationsAvailable = true
                            preferences.individualTaskNotifications = true
                        }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                            individualNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> showIndividualNotificationSettings = true
                    }
                },
            )
            ToggleRow(
                icon = { AppIcon(AppIcons.SaveAlbum, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_gallery_row),
                subtitle = stringResource(if (autoSaveToGallery) R.string.settings_gallery_on else R.string.settings_gallery_off),
                checked = autoSaveToGallery,
                onCheckedChange = { preferences.autoSaveToGallery = it },
            )
            ToggleRow(
                icon = { AppIcon(AppIcons.BatchImport, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_playlist_row),
                subtitle = if (playlistParsing)
                    stringResource(R.string.settings_playlist_on, com.videodown.douyin.data.generic.EngineRequest.PLAYLIST_LIMIT)
                else stringResource(R.string.settings_playlist_off),
                checked = playlistParsing,
                onCheckedChange = { preferences.playlistParsing = it },
            )
            ToggleRow(
                icon = { AppIcon(AppIcons.Link, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_clipboard_row),
                subtitle = stringResource(if (clipboardDetect) R.string.settings_clipboard_on else R.string.settings_clipboard_off),
                checked = clipboardDetect,
                onCheckedChange = { preferences.clipboardDetect = it },
            )
        }
        Text(stringResource(R.string.settings_quality_note), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = AppSpacing.xxs, top = AppSpacing.xs))
        }
        SettingsPage.WEBSITES_ACCOUNTS -> {
        SettingsSubpageHeader(stringResource(R.string.settings_hub_accounts)) { page = SettingsPage.ROOT }
        SectionLabel(stringResource(R.string.settings_section_engine))
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Settings, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_engine_row), value = stringResource(R.string.settings_engine_value),
                onClick = { showAbout = true })
            Text(engineVersion, modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs), style = MaterialTheme.typography.labelMedium)
            SettingsRow(icon = { AppIcon(AppIcons.Profile, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_x_login_row),
                value = stringResource(if (xSignedIn == true) R.string.settings_x_login_signed_in else R.string.settings_x_login_signed_out),
                onClick = { showXLogin = true })
            Text(stringResource(R.string.settings_x_login_note), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.xxs))
            SettingsRow(icon = { AppIcon(AppIcons.ClearSession, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_clear_session), value = sessionMessage ?: stringResource(R.string.settings_session_local),
                onClick = {
                    sessionScope.launch {
                        clearSessionTaskCount = ServiceLocator.historyRepository.snapshot().count { it.media?.useBrowserSession == true &&
                            it.status in setOf(com.videodown.douyin.domain.model.DownloadStatus.RUNNING, com.videodown.douyin.domain.model.DownloadStatus.PENDING)
                        }
                    }
                })
        }
        }
        SettingsPage.BACKUP_STORAGE -> {
        SettingsSubpageHeader(stringResource(R.string.settings_hub_storage)) { page = SettingsPage.ROOT }
        SectionLabel(stringResource(R.string.settings_section_backup))
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.ExportFolder, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_export_links), value = stringResource(R.string.settings_file_text),
                onClick = { exportLinksFile.launch(resources.getString(R.string.backup_links_file_name)) })
            SettingsRow(icon = { AppIcon(AppIcons.BatchImport, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_import_links), value = stringResource(R.string.settings_import_value),
                onClick = { importLinksFile.launch(com.videodown.douyin.data.backup.BackupDocuments.READABLE_TYPES) })
            SettingsRow(icon = { AppIcon(AppIcons.Storage, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_backup_settings), value = stringResource(R.string.settings_file_json),
                onClick = { exportSettingsFile.launch(resources.getString(R.string.backup_settings_file_name)) })
            SettingsRow(icon = { AppIcon(AppIcons.Settings, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_restore_settings), value = stringResource(R.string.settings_restore_value),
                onClick = { importSettingsFile.launch(com.videodown.douyin.data.backup.BackupDocuments.READABLE_TYPES) })
        }
        backupMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = AppSpacing.xxs, top = AppSpacing.xs))
        }
        SectionLabel(stringResource(R.string.settings_section_storage))
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Storage, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_storage_row), value = stringResource(R.string.settings_storage_value),
                onClick = { showStorage = true })
        }
        Spacer(Modifier.height(AppSpacing.lg))
        }
        SettingsPage.ABOUT_UPDATE -> {
        SettingsSubpageHeader(stringResource(R.string.settings_hub_about)) { page = SettingsPage.ROOT }
        SectionLabel(stringResource(R.string.settings_section_about))
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Download, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_update_row), value = "v$installedVersion", onClick = { showUpdate = true })
            SettingsRow(icon = { AppIcon(AppIcons.Info, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_about_row), value = "›", onClick = { showAbout = true })
        }
        Spacer(Modifier.height(AppSpacing.lg))
        }
        }
    }
    }
}

@Composable
private fun SettingsSubpageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sm, bottom = AppSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("settings-back")) {
            AppIcon(AppIcons.Back, stringResource(R.string.action_back))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = AppSpacing.xs),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp, modifier = Modifier.padding(top = AppSpacing.lg, bottom = AppSpacing.xs))
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        content()
    }
}

@Composable
private fun SettingsHubRow(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit,
    modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        AppIcon(AppIcons.Forward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsRow(icon: @Composable () -> Unit, title: String, value: String, onClick: (() -> Unit)?,
    modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        icon()
        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ToggleRow(icon: @Composable () -> Unit, title: String, subtitle: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun settingsChangeLabel(change: SettingsChange): String = stringResource(when (change) {
    SettingsChange.THEME_MODE -> R.string.settings_theme_title
    SettingsChange.PREFERRED_QUALITY -> R.string.settings_quality_title
    SettingsChange.LIBRARY_LAYOUT -> R.string.settings_restore_change_layout
    SettingsChange.LIBRARY_SORT -> R.string.settings_restore_change_sort
    SettingsChange.CONCURRENCY -> R.string.settings_concurrency_title
    SettingsChange.SPEED_LIMIT -> R.string.settings_speed_title
    SettingsChange.DYNAMIC_COLOR -> R.string.settings_dynamic_color
    SettingsChange.WIFI_ONLY -> R.string.settings_wifi
    SettingsChange.AUTO_RESUME -> R.string.settings_auto_resume
    SettingsChange.NOTIFY_ON_COMPLETION -> R.string.settings_notify_row
    SettingsChange.INDIVIDUAL_NOTIFICATIONS -> R.string.settings_individual_row
    SettingsChange.CLIPBOARD_DETECT -> R.string.settings_clipboard_row
    SettingsChange.AUTO_SAVE_TO_GALLERY -> R.string.settings_gallery_row
    SettingsChange.SUBTITLES -> R.string.home_subtitles
    SettingsChange.PLAYLIST_PARSING -> R.string.settings_playlist_row
    SettingsChange.UPDATE_SOURCE -> R.string.settings_restore_change_update_source
    SettingsChange.LIBRARY_ORDER -> R.string.settings_change_library_order
})
