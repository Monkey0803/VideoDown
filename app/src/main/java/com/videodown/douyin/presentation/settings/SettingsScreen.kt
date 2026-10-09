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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodown.douyin.core.PreferredQuality
import com.videodown.douyin.core.ThemeMode
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadCompletionNotifier

@Composable
fun SettingsScreen(openStorage: Boolean = false, onStorageOpened: () -> Unit = {},
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
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsAvailable = DownloadCompletionNotifier.areNotificationsEnabled(context)
        if (enableAfterSettings) {
            if (notificationsAvailable) preferences.notifyOnCompletion = true
            enableAfterSettings = false
        }
        notifyOnCompletion = preferences.notifyOnCompletion
    }
    val engineVersion by ServiceLocator.videoEngine.version.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { ServiceLocator.videoEngine.inspectVersion() }
    val sessionScope = rememberCoroutineScope()
    var sessionMessage by remember { mutableStateOf("仅保存在本机") }
    var quality by rememberSaveable { mutableStateOf(preferences.preferredQuality) }
    var showQuality by rememberSaveable { mutableStateOf(false) }
    var showStorage by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    val installedVersion = remember { ServiceLocator.appVersion.versionName }

    LaunchedEffect(openStorage) { if (openStorage) { showStorage = true; onStorageOpened() } }
    if (showConcurrency) {
        var chosen by remember(maxConcurrent) { mutableStateOf(maxConcurrent) }
        AlertDialog(onDismissRequest = { showConcurrency = false }, title = { Text("同时下载数量") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text("正在运行的任务会继续；调低数量后，新任务等待空闲名额。下载、合并、校验和重试都占用名额。")
                (1..5).forEach { count -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = chosen == count, role = Role.RadioButton, onClick = { chosen = count }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen == count, onClick = null); Text("$count 个任务")
                } }
            } },
            confirmButton = { TextButton(onClick = { preferences.maxConcurrentDownloads = chosen; showConcurrency = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showConcurrency = false }) { Text("取消") } })
    }
    if (showThemeMode) {
        var selectedMode by rememberSaveable(themeMode) { mutableStateOf(themeMode) }
        AlertDialog(
            onDismissRequest = { showThemeMode = false },
            title = { Text("外观模式") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("选择应用的显示模式", style = MaterialTheme.typography.bodyMedium,
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
                                Text(mode.label, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                preferences.themeMode = selectedMode
                showThemeMode = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showThemeMode = false }) { Text("取消") } },
        )
    }

    if (showNotificationSettings) {
        AlertDialog(
            onDismissRequest = { showNotificationSettings = false },
            title = { Text("开启下载完成通知") },
            text = { Text("系统尚未允许发送下载完成通知，请在系统设置中开启应用通知和「下载完成」提醒。") },
            confirmButton = { TextButton(onClick = {
                showNotificationSettings = false
                enableAfterSettings = true
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("前往设置") } },
            dismissButton = { TextButton(onClick = { showNotificationSettings = false }) { Text("取消") } },
        )
    }
    if (showQuality) {
        var selectedQuality by rememberSaveable(quality) { mutableStateOf(quality) }
        AlertDialog(
            onDismissRequest = { showQuality = false },
            title = { Text("默认画质") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("解析新视频时优先使用此画质", style = MaterialTheme.typography.bodyMedium,
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
                                    Text(option.label, style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text(when (option) {
                                        PreferredQuality.HIGHEST -> "优先选择可用的最高分辨率"
                                        PreferredQuality.STANDARD -> "平衡清晰度与文件大小"
                                        PreferredQuality.SAVER -> "优先选择较低分辨率，节省空间"
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
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showQuality = false }) { Text("取消") } },
        )
    }
    if (showStorage) StorageDialog(storageViewModel) { showStorage = false }
    if (showUpdate) UpdateDialog(updateViewModel) { showUpdate = false }
    if (showAbout) {
        AlertDialog(onDismissRequest = { showAbout = false }, title = { Text("关于拾影") },
            text = { Text("拾影 v$installedVersion 支持主流平台与普通网页视频、网页辅助、分片下载和音视频合并。\n\n$engineVersion\n\n采用 yt-dlp、youtubedl-android（GPL-3.0）与 FFmpeg；不支持 DRM 视频和直播。") },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } })
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp)) {
        TabPageHeader("我的设置", "管理外观、下载偏好与本地存储")
        SectionLabel("外观")
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Appearance, null, tint = MaterialTheme.colorScheme.primary) },
                title = "外观模式", value = themeMode.label, onClick = { showThemeMode = true })
        }
        SectionLabel("下载偏好")
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Queue, null, tint = MaterialTheme.colorScheme.primary) },
                title = "同时下载数量", value = "$maxConcurrent 个任务", onClick = { showConcurrency = true })
            SettingsRow(icon = { AppIcon(AppIcons.Quality, null, tint = MaterialTheme.colorScheme.primary) },
                title = "默认画质", value = quality.label, onClick = { showQuality = true })
            ToggleRow(
                icon = { AppIcon(AppIcons.Wifi, null, tint = MaterialTheme.colorScheme.primary) },
                title = "仅在 Wi-Fi 下下载",
                subtitle = if (wifiOnly) "离开 Wi-Fi 自动暂停，连接后可继续" else "允许使用移动网络下载",
                checked = wifiOnly,
                onCheckedChange = { preferences.wifiOnly = it },
            )
            ToggleRow(
                icon = { AppIcon(AppIcons.ResumeDownload, null, tint = MaterialTheme.colorScheme.primary) },
                title = "自动继续下载",
                subtitle = if (autoResume) "网络恢复或空间充足后自动继续，手动暂停不受影响"
                    else "中断的任务需手动点击继续",
                checked = autoResume,
                onCheckedChange = { preferences.autoResume = it },
            )
            ToggleRow(
                icon = { AppIcon(AppIcons.Notification, null, tint = MaterialTheme.colorScheme.primary) },
                title = "完成时通知",
                subtitle = when {
                    !notificationsAvailable -> "系统通知未开启，打开开关以授权"
                    notifyOnCompletion -> "视频下载完成后提醒你"
                    else -> "下载完成时不提醒"
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
        }
        Text("解析新视频时使用此画质；仍可在结果页逐条选择。", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 3.dp, top = 8.dp))
        SectionLabel("网页与解析引擎")
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Settings, null, tint = MaterialTheme.colorScheme.primary) },
                title = "解析引擎", value = "随应用更新", onClick = { showAbout = true })
            Text(engineVersion, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            SettingsRow(icon = { AppIcon(AppIcons.ClearSession, null, tint = MaterialTheme.colorScheme.primary) },
                title = "清除网页登录态", value = sessionMessage, onClick = {
                    sessionScope.launch {
                        ServiceLocator.historyRepository.snapshot().filter { it.media?.useBrowserSession == true &&
                            it.status in setOf(com.videodown.douyin.domain.model.DownloadStatus.RUNNING, com.videodown.douyin.domain.model.DownloadStatus.PENDING)
                        }.forEach { ServiceLocator.downloader.pause(it.id) }
                        ServiceLocator.browserSessions.clear(); sessionMessage = "已清除，相关任务已暂停"
                    }
                })
        }
        SectionLabel("存储与关于")
        SettingsCard {
            SettingsRow(icon = { AppIcon(AppIcons.Storage, null, tint = MaterialTheme.colorScheme.primary) },
                title = "存储空间", value = "占用与残留清理", onClick = { showStorage = true })
            SettingsRow(icon = { AppIcon(AppIcons.Download, null, tint = MaterialTheme.colorScheme.primary) },
                title = "检查更新", value = "v$installedVersion", onClick = { showUpdate = true })
            SettingsRow(icon = { AppIcon(AppIcons.Info, null, tint = MaterialTheme.colorScheme.primary) },
                title = "关于拾影", value = "›", onClick = { showAbout = true })
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
