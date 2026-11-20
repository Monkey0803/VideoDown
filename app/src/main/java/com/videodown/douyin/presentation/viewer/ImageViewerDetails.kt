package com.videodown.douyin.presentation.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.panpf.zoomimage.CoilZoomState
import com.videodown.douyin.R
import com.videodown.douyin.core.ImageMetadata
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.data.image.ImageFileProbe
import com.videodown.douyin.data.image.ImageMetadataReader
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.MediaSignature
import com.videodown.douyin.presentation.adaptive.LocalWindowWidth
import com.videodown.douyin.presentation.components.AppConfirmDialog
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.components.CoverSelectionMark
import com.videodown.douyin.presentation.videoPlatformLabelRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal enum class ViewerImageState { LOADING, READY, FAILED }

internal data class ImageMetadataCacheKey(val path: String, val modifiedAt: Long)

/**
 * 大图解码需要时间时先给转圈，而不是让整屏保持纯黑。
 *
 * 延迟 250ms 再出现，避免缓存命中时闪一下。
 */
@Composable
internal fun ViewerImageLoading(visible: Boolean) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        show = false
        if (visible) {
            delay(250)
            show = true
        }
    }
    if (show) {
        CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
    }
}

/** 图片解码失败时的兜底：说清是哪张、为什么，并给出打开方式与删除。 */
@Composable
internal fun ViewerImageFailure(
    record: DownloadRecord,
    file: File,
    onOpenExternal: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(
            AppIcons.Warning,
            null,
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(36.dp),
        )
        Text(
            stringResource(R.string.viewer_image_failed),
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            stringResource(R.string.viewer_image_failed_hint),
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = .7f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            listOfNotNull(
                file.name.takeIf { it.isNotBlank() },
                file.length().takeIf { it > 0 }?.let(::formatBytes),
                record.filePath?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = .55f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenExternal) {
                Text(stringResource(R.string.viewer_open_external), color = androidx.compose.ui.graphics.Color.White)
            }
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageViewerInfoSheet(
    record: DownloadRecord,
    zoomState: CoilZoomState?,
    metadataCache: MutableMap<ImageMetadataCacheKey, ImageMetadata>,
    onDismiss: () -> Unit,
) {
    val file = File(record.filePath.orEmpty())
    val context = LocalContext.current
    val cacheKey = remember(file.absolutePath, file.lastModified()) {
        ImageMetadataCacheKey(file.absolutePath, file.lastModified())
    }
    var metadata by remember(cacheKey) { mutableStateOf(metadataCache[cacheKey]) }
    var metadataLoading by remember(cacheKey) { mutableStateOf(metadata == null) }
    // 解码完成前也能给出原图尺寸：读文件头，不需要整图解码。
    var probedSize by remember(cacheKey) { mutableStateOf<MediaSignature.ImageSize?>(null) }
    var copied by remember(cacheKey) { mutableStateOf(false) }
    LaunchedEffect(cacheKey) {
        if (metadata == null) {
            metadata = withContext(Dispatchers.IO) { ImageMetadataReader.read(file) }
                .also { metadataCache[cacheKey] = it }
        }
        metadataLoading = false
    }
    LaunchedEffect(cacheKey) {
        if (probedSize == null) probedSize = withContext(Dispatchers.IO) { ImageFileProbe.dimensions(file) }
    }
    val origin = zoomState?.zoomable?.contentOriginSize
    val dimensions = origin?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width} × ${it.height}" }
        ?: probedSize?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width} × ${it.height}" }
    val unknown = stringResource(R.string.viewer_detail_unknown)
    val path = record.filePath.orEmpty()
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
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.viewer_detail_path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(92.dp),
                    )
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                    )
                }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(record.title, path))
                    copied = true
                }) {
                    Text(stringResource(if (copied) R.string.viewer_path_copied else R.string.viewer_copy_path))
                }
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
            metadata?.takeIf { it.latitude != null && it.longitude != null }?.let { location ->
                item(key = "metadata-map") {
                    TextButton(onClick = {
                        val lat = location.latitude ?: return@TextButton
                        val lng = location.longitude ?: return@TextButton
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) {
                        AppIcon(AppIcons.ExternalLink, null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.viewer_open_location), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageViewerDateGridSheet(
    records: List<DownloadRecord>,
    selectedId: String,
    onSelect: (DownloadRecord) -> Unit,
    onDelete: (Set<String>) -> Unit,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = remember(records) { imageDateGroups(records) }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG) }
    val columns = ImageViewerLayoutPolicy.dateGridColumns(LocalWindowWidth.current)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = imageDateGridIndex(groups, selectedId))
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.viewer_date_grid_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    selecting = !selecting
                    selected = emptySet()
                }) { Text(stringResource(if (selecting) R.string.viewer_select_exit else R.string.viewer_select)) }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).testTag("image-date-grid"),
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
                        val isSelected = record.id in selected
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f)
                                .clip(MaterialTheme.shapes.medium)
                                .border(
                                    androidx.compose.foundation.BorderStroke(
                                        if (record.id == selectedId) 3.dp else 1.dp,
                                        if (record.id == selectedId) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                    MaterialTheme.shapes.medium,
                                )
                                .clickable {
                                    if (selecting) {
                                        selected = if (isSelected) selected - record.id else selected + record.id
                                    } else {
                                        onSelect(record)
                                    }
                                }
                                .testTag("date-grid-${record.id}"),
                        ) {
                            AsyncImage(
                                model = File(record.filePath.orEmpty()),
                                contentDescription = record.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (selecting) {
                                CoverSelectionMark(
                                    selected = isSelected,
                                    enabled = true,
                                    contentDescription = record.title,
                                    onToggle = {
                                        selected = if (isSelected) selected - record.id else selected + record.id
                                    },
                                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                                )
                            }
                            if (record.isLivePhoto) {
                                AppIcon(
                                    AppIcons.Play,
                                    stringResource(R.string.viewer_live_photo_badge),
                                    tint = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.viewer_selected, selected.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(enabled = selected.isNotEmpty(), onClick = {
                        onSave(selected)
                        selected = emptySet()
                    }) { Text(stringResource(R.string.viewer_save_selected, selected.size)) }
                    TextButton(enabled = selected.isNotEmpty(), onClick = { confirmingDelete = true }) {
                        Text(
                            stringResource(R.string.viewer_delete_selected, selected.size),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
    if (confirmingDelete && selected.isNotEmpty()) {
        AppConfirmDialog(
            onDismissRequest = { confirmingDelete = false },
            icon = AppIcons.Delete,
            title = stringResource(R.string.viewer_delete_selected_title),
            body = stringResource(R.string.viewer_delete_selected_body, selected.size),
            confirmText = stringResource(R.string.action_confirm_delete),
            onConfirm = {
                val ids = selected
                confirmingDelete = false
                selected = emptySet()
                onDelete(ids)
            },
            dismissText = stringResource(R.string.action_cancel),
            destructive = true,
        )
    }
}

@Composable
internal fun ImageDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(92.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
