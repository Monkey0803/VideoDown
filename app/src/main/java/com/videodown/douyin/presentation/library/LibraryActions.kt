package com.videodown.douyin.presentation.library

import com.videodown.douyin.presentation.components.AppConfirmDialog
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.videodown.douyin.presentation.galleryErrorMessage
import com.videodown.douyin.core.DouyinIntents
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.LibraryFileInspector
import com.videodown.douyin.data.gallery.GalleryExportOutcome
import com.videodown.douyin.domain.model.StoredFileHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.videodown.douyin.R
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.VaultPolicy
import kotlinx.coroutines.launch
import java.io.File

/** Kept outside the lazy items so an operation isn't cancelled when its card scrolls away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryActions(
    records: List<DownloadRecord>,
    selectedRecordId: String?,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit,
    onDelete: (String) -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    onOrganize: (DownloadRecord) -> Unit = {},
    onFavorite: (DownloadRecord) -> Unit = {},
    organizing: Boolean = false,
    onFileExport: (DownloadRecord) -> Unit = {},
    onExtractAudio: (DownloadRecord) -> Unit = {},
    onToggleVault: (DownloadRecord) -> Unit = {},
    /** Whether the vault is open, which decides how the vault action reads. */
    vaultUnlocked: Boolean = false,
    /** 文件缺失或损坏时的两个修复入口：按原地址重新下载，或回到解析页重新解析。 */
    onRedownload: (DownloadRecord) -> Unit = {},
    onReparse: (DownloadRecord) -> Unit = {},
    inspector: LibraryFileInspector = ServiceLocator.fileInspector,
) {
    val context = LocalContext.current
    // 资源按当前组合读取：context.getString 不会随语言/配置切换重算。
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // 与「我的 → 自动存入相册」共用同一份导出回执，重复保存才能被认出来。
    val exporter = ServiceLocator.galleryExporter
    var exportingId by remember { mutableStateOf<String?>(null) }
    // 回执说「已经保存过」时进入这里，只有用户确认后才再写一份。
    // 保存记录本身而不是 id：底部弹窗关闭后列表参数可能已经不含这条记录。
    var pendingResave by remember { mutableStateOf<DownloadRecord?>(null) }
    // 用户主动「检查文件」期间禁用入口；结论不健康时弹出修复对话框。
    var checkingId by remember { mutableStateOf<String?>(null) }
    var healthIssue by remember { mutableStateOf<Pair<DownloadRecord, StoredFileHealth>?>(null) }
    var pendingPermissionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = records.firstOrNull { it.id == selectedRecordId }
    val pendingDelete = records.firstOrNull { it.id == pendingDeleteId }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun export(record: DownloadRecord, replaceExisting: Boolean = false) {
        if (exportingId != null) return
        exportingId = record.id
        onDismiss()
        scope.launch {
            val result = try {
                exporter.export(record, replaceExisting)
            } finally {
                exportingId = null
            }
            result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is GalleryExportOutcome.AlreadySaved -> pendingResave = record
                        is GalleryExportOutcome.Saved -> snackbar.showSnackbar(resources.getString(
                            if (record.isAudio) R.string.library_saved_to_music else R.string.library_saved_to_gallery,
                        ))
                    }
                },
                onFailure = { snackbar.showSnackbar(resources.getString(R.string.library_save_failed, galleryErrorMessage(context, it))) },
            )
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val record = records.firstOrNull { it.id == pendingPermissionId }
        pendingPermissionId = null
        when {
            !granted -> showMessage(resources.getString(R.string.library_need_storage_permission))
            record == null -> showMessage(resources.getString(R.string.library_video_missing))
            else -> export(record)
        }
    }

    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                Text(stringResource(
                    when {
                        selected.isAudio -> R.string.library_actions_audio
                        selected.isImage -> R.string.library_actions_image
                        selected.isArchive -> R.string.library_actions_archive
                        else -> R.string.library_actions_video
                    },
                ), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp))
                Text(VaultPolicy.title(selected, vaultUnlocked) ?: resources.getString(R.string.vault_locked_title), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                LibraryActionRow(stringResource(if (selected.favorite) R.string.library_unfavorite else R.string.library_favorite), if (selected.favorite) AppIcons.FavoriteFilled else AppIcons.Favorite, enabled = !organizing) {
                    onFavorite(selected); onDismiss()
                }
                LibraryActionRow(stringResource(R.string.library_organize), AppIcons.Rename, enabled = !organizing) { onOrganize(selected); onDismiss() }
                LibraryActionRow(stringResource(if (selected.isPrivate) R.string.vault_remove else R.string.vault_add), AppIcons.Storage, enabled = !organizing) {
                    onToggleVault(selected); onDismiss()
                }
                if (selected.status == com.videodown.douyin.domain.model.DownloadStatus.COMPLETED) {
                    LibraryActionRow(
                        stringResource(if (checkingId == selected.id) R.string.library_checking else R.string.library_check_file),
                        AppIcons.Info,
                        enabled = checkingId == null,
                    ) {
                        val record = selected
                        checkingId = record.id
                        onDismiss()
                        scope.launch {
                            // 结构校验会读文件头尾的样本，放到 IO 线程，不卡列表。
                            val health = withContext(Dispatchers.IO) { inspector.inspect(record) }
                            checkingId = null
                            when (health) {
                                StoredFileHealth.HEALTHY -> showMessage(resources.getString(R.string.library_check_healthy))
                                StoredFileHealth.UNKNOWN -> showMessage(resources.getString(R.string.library_check_unknown))
                                else -> healthIssue = record to health
                            }
                        }
                    }
                }
                LibraryActionRow(stringResource(R.string.library_export_folder), AppIcons.ExportFolder, enabled = !organizing) { onFileExport(selected); onDismiss() }
                if (selected.isVideo) LibraryActionRow(stringResource(R.string.library_extract_audio), AppIcons.ExtractAudio, enabled = !organizing) { onExtractAudio(selected); onDismiss() }
                LibraryActionRow(stringResource(
                    when {
                        selected.isAudio -> R.string.library_play_audio
                        selected.isImage -> R.string.library_open_image
                        selected.isArchive -> R.string.library_open_archive
                        else -> R.string.library_play_video
                    },
                ), when {
                    selected.isAudio -> AppIcons.Audio
                    selected.isImage -> AppIcons.Grid
                    selected.isArchive -> AppIcons.ExportFolder
                    else -> AppIcons.Play
                }) {
                    onDismiss()
                    if (selected.filePath?.let { File(it).isFile } == true) onPlay(selected.id)
                    else showMessage(resources.getString(R.string.library_file_missing))
                }
                LibraryActionRow(stringResource(
                    when {
                        selected.isAudio -> R.string.library_share_audio
                        selected.isImage -> R.string.library_share_image
                        selected.isArchive -> R.string.library_share_archive
                        else -> R.string.library_share_video
                    },
                ), AppIcons.Share) {
                    onDismiss()
                    if (!DouyinIntents.shareRecord(context, selected)) {
                        showMessage(resources.getString(R.string.library_share_failed))
                    }
                }
                // 压缩包在完成时已写入公共下载目录，这里不再提供"保存到相册"（它不是相册条目）。
                if (!selected.isArchive) LibraryActionRow(
                    stringResource(if (exportingId == selected.id) R.string.library_saving else if (selected.isAudio) R.string.library_save_to_music else R.string.library_save_to_gallery),
                    if (selected.isAudio) AppIcons.Audio else AppIcons.SaveAlbum,
                    enabled = exportingId == null && pendingPermissionId == null,
                ) {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingPermissionId = selected.id
                        onDismiss()
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        export(selected)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                LibraryActionRow(stringResource(R.string.library_delete), AppIcons.Delete,
                    enabled = exportingId != selected.id, tint = MaterialTheme.colorScheme.error) {
                    pendingDeleteId = selected.id
                    onDismiss()
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    pendingResave?.let { resave ->
        AppConfirmDialog(
            onDismissRequest = { pendingResave = null },
            icon = AppIcons.SaveAlbum,
            title = stringResource(R.string.library_resave_title),
            body = stringResource(R.string.library_resave_body),
            confirmText = stringResource(R.string.library_resave_confirm),
            onConfirm = {
                pendingResave = null
                export(resave, replaceExisting = true)
            },
            dismissText = stringResource(R.string.action_cancel),
        )
    }

    healthIssue?.let { (record, health) ->
        AppConfirmDialog(
            onDismissRequest = { healthIssue = null },
            icon = AppIcons.Warning,
            title = stringResource(R.string.library_check_title),
            body = stringResource(
                when (health) {
                    StoredFileHealth.MISSING -> R.string.library_check_missing
                    StoredFileHealth.CHANGED -> R.string.library_check_changed
                    else -> R.string.library_check_corrupt
                },
            ),
            confirmText = stringResource(R.string.action_retry_download),
            onConfirm = {
                healthIssue = null
                onRedownload(record)
            },
            dismissText = stringResource(R.string.action_cancel),
            extraActions = {
                TextButton(onClick = {
                    healthIssue = null
                    onReparse(record)
                }) { Text(stringResource(R.string.recovery_reparse)) }
            },
        )
    }

    if (pendingDelete != null) {
        AppConfirmDialog(
            onDismissRequest = { pendingDeleteId = null },
            icon = AppIcons.Delete,
            title = stringResource(R.string.library_delete_title),
            body = stringResource(R.string.library_delete_body, pendingDelete.title),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                pendingDeleteId = null
                onDelete(pendingDelete.id)
            },
            dismissText = stringResource(R.string.action_cancel),
            destructive = true,
        )
    }

    Column(modifier) {
        if (exportingId != null) {
            Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.inverseOnSurface, strokeWidth = 2.dp)
                    Text(stringResource(R.string.library_saving_to_gallery), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        SnackbarHost(snackbar)
    }
}

@Composable
private fun LibraryActionRow(
    label: String,
    icon: AppIcons,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val color = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(icon, contentDescription = null, tint = color)
        Text(label, color = color, style = MaterialTheme.typography.titleMedium)
    }
}
