package com.videodown.douyin.presentation.settings

import com.videodown.douyin.presentation.components.TabPageHeader
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.presentation.preferredQualityLabelRes
import com.videodown.douyin.presentation.settingsChangeText
import com.videodown.douyin.presentation.speedLimitText
import com.videodown.douyin.presentation.themeModeLabelRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.core.PreferredQuality
import com.videodown.douyin.domain.model.speedLimitOptions
import com.videodown.douyin.core.ThemeMode
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadCompletionNotifier

@Composable
fun SettingsScreen(openStorage: Boolean = false, onStorageOpened: () -> Unit = {},
    onImportLinks: (String) -> Unit = {},
    storageViewModel: StorageViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    updateViewModel: UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    preferences: com.videodown.douyin.core.AppPreferences = ServiceLocator.preferences) {
    val context = LocalContext.current
    val wifiOnly by preferences.wifiOnlyChanges.collectAsStateWithLifecycle()
    val autoResume by preferences.autoResumeChanges.collectAsStateWithLifecycle()
    val maxConcurrent by preferences.maxConcurrentChanges.collectAsStateWithLifecycle()
    var showConcurrency by rememberSaveable { mutableStateOf(false) }
    val themeMode by preferences.themeModeChanges.collectAsStateWithLifecycle()
    var showThemeMode by rememberSaveable { mutableStateOf(false) }
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
    val exportLinksFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) sessionScope.launch {
            val records = ServiceLocator.historyRepository.snapshot()
            val text = com.videodown.douyin.data.backup.LinkList.format(records)
            val count = text.trim().lines().size - 1
            backupMessage = if (com.videodown.douyin.data.backup.BackupDocuments.write(context, uri, text)) {
                if (count > 0) context.getString(R.string.settings_links_exported, count)
                else context.getString(R.string.settings_links_exported_empty)
            } else context.getString(R.string.settings_write_failed)
        }
    }
    val importLinksFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sessionScope.launch {
            val links = com.videodown.douyin.data.backup.LinkList.parse(
                com.videodown.douyin.data.backup.BackupDocuments.read(context, uri),
            )
            if (links.isEmpty()) backupMessage = context.getString(R.string.settings_import_empty)
            else onImportLinks(links.joinToString("\n"))
        }
    }
    val exportSettingsFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) sessionScope.launch {
            val text = com.videodown.douyin.data.backup.SettingsBackupCodec.encode(preferences.snapshot())
            backupMessage = if (com.videodown.douyin.data.backup.BackupDocuments.write(context, uri, text)) context.getString(R.string.settings_backup_written)
                else context.getString(R.string.settings_write_failed)
        }
    }
    val importSettingsFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sessionScope.launch {
            val snapshot = com.videodown.douyin.data.backup.SettingsBackupCodec.decode(
                com.videodown.douyin.data.backup.BackupDocuments.read(context, uri),
            )
            if (snapshot == null) backupMessage = context.getString(R.string.settings_backup_invalid)
            else {
                val applied = preferences.apply(snapshot)
                backupMessage = if (applied.isEmpty()) context.getString(R.string.settings_backup_same)
                    else context.getString(R.string.settings_restored,
                        // Each entry is a key; the list is worded from the values that were applied.
                        applied.joinToString(context.getString(R.string.list_separator)) {
                            settingsChangeText(context, it, preferences)
                        })
            }
        }
    }
    var quality by rememberSaveable { mutableStateOf(preferences.preferredQuality) }
    var showQuality by rememberSaveable { mutableStateOf(false) }
    var showStorage by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    val installedVersion = remember { ServiceLocator.appVersion.versionName }

    LaunchedEffect(openStorage) { if (openStorage) { showStorage = true; onStorageOpened() } }
    if (showConcurrency) {
        var chosen by remember(maxConcurrent) { mutableStateOf(maxConcurrent) }
        AlertDialog(onDismissRequest = { showConcurrency = false }, title = { Text(stringResource(R.string.settings_concurrency_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(stringResource(R.string.settings_concurrency_body))
                (1..5).forEach { count -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = chosen == count, role = Role.RadioButton, onClick = { chosen = count }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen == count, onClick = null); Text(stringResource(R.string.settings_concurrency_count, count))
                } }
            } },
            confirmButton = { TextButton(onClick = { preferences.maxConcurrentDownloads = chosen; showConcurrency = false }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { showConcurrency = false }) { Text(stringResource(R.string.action_cancel)) } })
    }
    if (showThemeMode) {
        var selectedMode by rememberSaveable(themeMode) { mutableStateOf(themeMode) }
        AlertDialog(
            onDismissRequest = { showThemeMode = false },
            title = { Text(stringResource(R.string.settings_theme_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.settings_theme_body), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp))
                    Column(Modifier.selectableGroup()) {
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .selectable(selected = selectedMode == mode, role = Role.RadioButton,
                                        onClick = { selectedMode = mode })
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RadioButton(selected = selectedMode == mode, onClick = null)
                                Text(stringResource(themeModeLabelRes(mode)), style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                preferences.themeMode = selectedMode
                showThemeMode = false
            }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { showThemeMode = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showNotificationSettings) {
        AlertDialog(
            onDismissRequest = { showNotificationSettings = false },
            title = { Text(stringResource(R.string.settings_notify_title)) },
            text = { Text(stringResource(R.string.settings_notify_body)) },
            confirmButton = { TextButton(onClick = {
                showNotificationSettings = false
                enableAfterSettings = true
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text(stringResource(R.string.action_open_settings)) } },
            dismissButton = { TextButton(onClick = { showNotificationSettings = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showIndividualNotificationSettings) {
        AlertDialog(
            onDismissRequest = { showIndividualNotificationSettings = false },
            title = { Text(stringResource(R.string.settings_individual_title)) },
            text = { Text(stringResource(R.string.settings_individual_body)) },
            confirmButton = { TextButton(onClick = {
                showIndividualNotificationSettings = false
                enableIndividualAfterSettings = true
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text(stringResource(R.string.action_open_settings)) } },
            dismissButton = { TextButton(onClick = { showIndividualNotificationSettings = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showQuality) {
        var selectedQuality by rememberSaveable(quality) { mutableStateOf(quality) }
        AlertDialog(
            onDismissRequest = { showQuality = false },
            title = { Text(stringResource(R.string.settings_quality_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.settings_quality_body), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp))
                    Column(Modifier.selectableGroup()) {
                        PreferredQuality.entries.forEach { option ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .selectable(
                                        selected = selectedQuality == option,
                                        role = Role.RadioButton,
                                        onClick = { selectedQuality = option },
                                    ).padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RadioButton(selected = selectedQuality == option, onClick = null)
                                Column(Modifier.weight(1f)) {
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
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                preferences.preferredQuality = selectedQuality
                quality = selectedQuality
                showQuality = false
            }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { showQuality = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showSpeedLimit) {
        var selectedLimit by rememberSaveable(globalSpeedLimit) { mutableStateOf(globalSpeedLimit) }
        AlertDialog(
            onDismissRequest = { showSpeedLimit = false },
            title = { Text(stringResource(R.string.settings_speed_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.settings_speed_body),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                    Column(Modifier.selectableGroup()) {
                        speedLimitOptions.forEach { option ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .selectable(selected = selectedLimit == option, role = Role.RadioButton,
                                        onClick = { selectedLimit = option })
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RadioButton(selected = selectedLimit == option, onClick = null)
                                Text(speedLimitText(option), style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                preferences.globalSpeedLimitKbps = selectedLimit
                showSpeedLimit = false
            }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { showSpeedLimit = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showStorage) StorageDialog(storageViewModel) { showStorage = false }
    if (showUpdate) UpdateDialog(updateViewModel) { showUpdate = false }
    if (showAbout) {
        AlertDialog(onDismissRequest = { showAbout = false }, title = { Text(stringResource(R.string.settings_about_title)) },
            text = { Text(stringResource(R.string.settings_about_body, installedVersion, engineVersion)) },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.action_got_it)) } })
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp)) {
        TabPageHeader(stringResource(R.string.settings_header_title), stringResource(R.string.settings_header_subtitle))
        SectionLabel(stringResource(R.string.settings_section_appearance))
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Appearance, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_theme_title), value = stringResource(themeModeLabelRes(themeMode)), onClick = { showThemeMode = true })
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
                        !enabled -> preferences.individualTaskNotifications = false
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
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 3.dp, top = 8.dp))
        SectionLabel(stringResource(R.string.settings_section_engine))
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Settings, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_engine_row), value = stringResource(R.string.settings_engine_value),
                onClick = { showAbout = true })
            Text(engineVersion, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            SettingsRow(icon = { AppIcon(AppIcons.ClearSession, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_clear_session), value = sessionMessage ?: stringResource(R.string.settings_session_local),
                onClick = {
                    sessionScope.launch {
                        ServiceLocator.historyRepository.snapshot().filter { it.media?.useBrowserSession == true &&
                            it.status in setOf(com.videodown.douyin.domain.model.DownloadStatus.RUNNING, com.videodown.douyin.domain.model.DownloadStatus.PENDING)
                        }.forEach { ServiceLocator.downloader.pause(it.id) }
                        ServiceLocator.browserSessions.clear(); sessionMessage = context.getString(R.string.settings_session_cleared)
                    }
                })
        }
        SectionLabel(stringResource(R.string.settings_section_backup))
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.ExportFolder, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_export_links), value = stringResource(R.string.settings_file_text),
                onClick = { exportLinksFile.launch(com.videodown.douyin.data.backup.BackupDocuments.LINK_FILE_NAME) })
            SettingsRow(icon = { AppIcon(AppIcons.BatchImport, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_import_links), value = stringResource(R.string.settings_import_value),
                onClick = { importLinksFile.launch(com.videodown.douyin.data.backup.BackupDocuments.READABLE_TYPES) })
            SettingsRow(icon = { AppIcon(AppIcons.Storage, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_backup_settings), value = stringResource(R.string.settings_file_json),
                onClick = { exportSettingsFile.launch(com.videodown.douyin.data.backup.BackupDocuments.SETTINGS_FILE_NAME) })
            SettingsRow(icon = { AppIcon(AppIcons.Settings, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_restore_settings), value = stringResource(R.string.settings_restore_value),
                onClick = { importSettingsFile.launch(com.videodown.douyin.data.backup.BackupDocuments.READABLE_TYPES) })
        }
        backupMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 3.dp, top = 8.dp))
        }
        SectionLabel(stringResource(R.string.settings_section_storage))
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Storage, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_storage_row), value = stringResource(R.string.settings_storage_value),
                onClick = { showStorage = true })
            SettingsRow(icon = { AppIcon(AppIcons.Download, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_update_row), value = "v$installedVersion", onClick = { showUpdate = true })
            SettingsRow(icon = { AppIcon(AppIcons.Info, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_about_row), value = "›", onClick = { showAbout = true })
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp, modifier = Modifier.padding(top = 23.dp, bottom = 7.dp))
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        content()
    }
}

@Composable
private fun SettingsRow(icon: @Composable () -> Unit, title: String, value: String, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
        .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
