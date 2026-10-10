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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.videodown.douyin.presentation.galleryErrorMessage
import com.videodown.douyin.core.DouyinIntents
import com.videodown.douyin.data.gallery.GalleryExporter
import androidx.compose.ui.res.stringResource
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = remember(context) { GalleryExporter(context.applicationContext) }
    var exportingId by remember { mutableStateOf<String?>(null) }
    var pendingPermissionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = records.firstOrNull { it.id == selectedRecordId }
    val pendingDelete = records.firstOrNull { it.id == pendingDeleteId }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun export(record: DownloadRecord) {
        if (exportingId != null) return
        exportingId = record.id
        onDismiss()
        scope.launch {
            val result = try {
                exporter.export(record)
            } finally {
                exportingId = null
            }
            snackbar.showSnackbar(result.fold(
                onSuccess = { context.getString(if (record.isAudio) R.string.library_saved_to_music else R.string.library_saved_to_gallery) },
                onFailure = { context.getString(R.string.library_save_failed, galleryErrorMessage(context, it)) },
            ))
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val record = records.firstOrNull { it.id == pendingPermissionId }
        pendingPermissionId = null
        when {
            !granted -> showMessage(context.getString(R.string.library_need_storage_permission))
            record == null -> showMessage(context.getString(R.string.library_video_missing))
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
                Text(stringResource(if (selected.isAudio) R.string.library_actions_audio else R.string.library_actions_video), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp))
                Text(VaultPolicy.title(selected, vaultUnlocked) ?: context.getString(R.string.vault_locked_title), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                LibraryActionRow(stringResource(if (selected.favorite) R.string.library_unfavorite else R.string.library_favorite), if (selected.favorite) AppIcons.FavoriteFilled else AppIcons.Favorite, enabled = !organizing) {
                    onFavorite(selected); onDismiss()
                }
                LibraryActionRow(stringResource(R.string.library_organize), AppIcons.Rename, enabled = !organizing) { onOrganize(selected); onDismiss() }
                LibraryActionRow(stringResource(if (selected.isPrivate) R.string.vault_remove else R.string.vault_add), AppIcons.Storage, enabled = !organizing) {
                    onToggleVault(selected); onDismiss()
                }
                LibraryActionRow(stringResource(R.string.library_export_folder), AppIcons.ExportFolder, enabled = !organizing) { onFileExport(selected); onDismiss() }
                if (!selected.isAudio) LibraryActionRow(stringResource(R.string.library_extract_audio), AppIcons.ExtractAudio, enabled = !organizing) { onExtractAudio(selected); onDismiss() }
                LibraryActionRow(stringResource(if (selected.isAudio) R.string.library_play_audio else R.string.library_play_video), AppIcons.Play) {
                    onDismiss()
                    if (selected.filePath?.let { File(it).isFile } == true) onPlay(selected.id)
                    else showMessage(context.getString(R.string.library_file_missing))
                }
                LibraryActionRow(stringResource(if (selected.isAudio) R.string.library_share_audio else R.string.library_share_video), AppIcons.Share) {
                    onDismiss()
                    val file = selected.filePath?.let(::File)
                    if (file == null || !file.isFile || !DouyinIntents.shareFile(context, file)) {
                        showMessage(context.getString(R.string.library_share_failed))
                    }
                }
                LibraryActionRow(
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
