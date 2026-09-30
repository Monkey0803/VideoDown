package com.videodown.douyin.presentation.library

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
import androidx.compose.material3.AlertDialog
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
import com.videodown.douyin.core.galleryErrorMessage
import com.videodown.douyin.core.DouyinIntents
import com.videodown.douyin.data.gallery.GalleryExporter
import com.videodown.douyin.domain.model.DownloadRecord
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
                onSuccess = { if (record.isAudio) "已保存到音乐" else "已保存到相册" },
                onFailure = { "保存失败：${it.galleryErrorMessage()}" },
            ))
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val record = records.firstOrNull { it.id == pendingPermissionId }
        pendingPermissionId = null
        when {
            !granted -> showMessage("需要存储权限才能保存到相册")
            record == null -> showMessage("视频已不存在")
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
                Text(if (selected.isAudio) "音频操作" else "视频操作", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp))
                Text(selected.title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                LibraryActionRow(if (selected.favorite) "取消收藏" else "收藏视频", if (selected.favorite) AppIcons.FavoriteFilled else AppIcons.Favorite, enabled = !organizing) {
                    onFavorite(selected); onDismiss()
                }
                LibraryActionRow("重命名与分类", AppIcons.Rename, enabled = !organizing) { onOrganize(selected); onDismiss() }
                LibraryActionRow("导出到文件夹", AppIcons.ExportFolder, enabled = !organizing) { onFileExport(selected); onDismiss() }
                if (!selected.isAudio) LibraryActionRow("提取音频", AppIcons.ExtractAudio, enabled = !organizing) { onExtractAudio(selected); onDismiss() }
                LibraryActionRow(if (selected.isAudio) "播放音频" else "播放视频", AppIcons.Play) {
                    onDismiss()
                    if (selected.filePath?.let { File(it).isFile } == true) onPlay(selected.id)
                    else showMessage("本地视频文件不存在")
                }
                LibraryActionRow(if (selected.isAudio) "分享音频" else "分享视频", AppIcons.Share) {
                    onDismiss()
                    val file = selected.filePath?.let(::File)
                    if (file == null || !file.isFile || !DouyinIntents.shareFile(context, file)) {
                        showMessage("分享失败，请检查视频文件及可用应用")
                    }
                }
                LibraryActionRow(
                    if (exportingId == selected.id) "正在保存…" else if (selected.isAudio) "保存到音乐" else "保存到相册",
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
                LibraryActionRow("删除视频", AppIcons.Delete,
                    enabled = exportingId != selected.id, tint = MaterialTheme.colorScheme.error) {
                    pendingDeleteId = selected.id
                    onDismiss()
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除视频？") },
            text = {
                Text("将删除“${pendingDelete.title}”的下载记录和本地文件，无法恢复。已保存到系统相册的副本不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteId = null
                    onDelete(pendingDelete.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            },
        )
    }

    Column(modifier) {
        if (exportingId != null) {
            Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.inverseOnSurface, strokeWidth = 2.dp)
                    Text("正在保存到相册…", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
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
