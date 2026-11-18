package com.videodown.douyin.presentation.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.videodown.douyin.R
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.data.generic.BrowserMediaDetector
import com.videodown.douyin.data.generic.CandidateGroup
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.components.MediaCover
import androidx.core.net.toUri

/** 面板头部整行可点，设备用例靠它展开/收起。 */
internal const val BROWSER_PANEL_TOGGLE_TAG = "browser-panel-toggle"

/** 一行候选的测试标签前缀；行内文字也足够定位，标签用于断言「只剩一行」。 */
internal const val BROWSER_CANDIDATE_TAG_PREFIX = "browser-candidate-"

/** 缩略图只对不超过这个体积的图片加载，避免为 48dp 预览把原图整份下载下来。 */
private const val THUMBNAIL_MAX_BYTES = 4L * 1024 * 1024

/**
 * 网页解析面板的底部区域。
 *
 * 收起时只有一行结论（外加视频/图片计数），展开后才出现候选与操作——页面不再是「面板的附属品」。
 * 所有状态文案由 [BrowserPanelStatus] 决定，这里只做渲染。
 */
@Composable
internal fun BrowserPanel(
    status: BrowserPanelStatus,
    groups: List<CandidateGroup>,
    busy: Boolean,
    expanded: Boolean,
    noteOpen: Boolean,
    desktopSite: Boolean,
    hidePrompts: Boolean,
    selectedFormats: Map<String, String>,
    onToggleExpanded: () -> Unit,
    onReparse: () -> Unit,
    onCancelParse: () -> Unit,
    onToggleNote: () -> Unit,
    onToggleDesktopSite: () -> Unit,
    onToggleHidePrompts: () -> Unit,
    onOpenExternal: () -> Unit,
    onCandidateClick: (BrowserMediaDetector.Candidate) -> Unit,
    onFormatSelect: (String, String) -> Unit,
) {
    val videos = groups.filterNot { it.isImage }
    val images = groups.filter { it.isImage }
    val maxListHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.3f).coerceAtMost(280.dp)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .testTag(BROWSER_PANEL_TOGGLE_TAG)
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusGlyph(status)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = browserPanelStatusText(status),
                        style = if (status is BrowserPanelStatus.Found) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.bodySmall,
                        color = if (status is BrowserPanelStatus.Message) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(BROWSER_STATUS_TAG),
                    )
                    if (status is BrowserPanelStatus.Found) CountsLine(status)
                }
                Spacer(Modifier.width(4.dp))
                AppIcon(
                    icon = if (expanded) AppIcons.ChevronDown else AppIcons.ChevronUp,
                    contentDescription = stringResource(
                        if (expanded) R.string.browser_collapse_panel else R.string.browser_expand_panel,
                    ),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.fillMaxWidth()) {
                    if (groups.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxListHeight)) {
                            if (videos.isNotEmpty()) {
                                item { SectionHeader(R.string.browser_section_videos, videos.size) }
                                items(videos, key = { it.id }) { group ->
                                    CandidateRow(group, selectedFormats[group.id], images = false,
                                        onClick = { onCandidateClick(group.variantOf(selectedFormats[group.id])) },
                                        onFormatSelect = onFormatSelect)
                                }
                            }
                            if (images.isNotEmpty()) {
                                item { SectionHeader(R.string.browser_section_images, images.size) }
                                items(images, key = { it.id }) { group ->
                                    CandidateRow(group, selectedFormats[group.id], images = true,
                                        onClick = { onCandidateClick(group.variantOf(selectedFormats[group.id])) },
                                        onFormatSelect = onFormatSelect)
                                }
                            }
                            item { Spacer(Modifier.height(4.dp)) }
                        }
                    }
                    ActionsRow(
                        busy = busy,
                        desktopSite = desktopSite,
                        hidePrompts = hidePrompts,
                        noteOpen = noteOpen,
                        onReparse = onReparse,
                        onCancelParse = onCancelParse,
                        onToggleNote = onToggleNote,
                        onToggleDesktopSite = onToggleDesktopSite,
                        onToggleHidePrompts = onToggleHidePrompts,
                        onOpenExternal = onOpenExternal,
                    )
                    AnimatedVisibility(noteOpen) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.browser_note_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.browser_scanned),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                stringResource(R.string.browser_note_tips),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusGlyph(status: BrowserPanelStatus) {
    when (status) {
        is BrowserPanelStatus.Message -> AppIcon(AppIcons.Warning, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary)
        BrowserPanelStatus.Loading, BrowserPanelStatus.Scanning ->
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        is BrowserPanelStatus.Found -> AppIcon(AppIcons.Success, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary)
        BrowserPanelStatus.Empty, BrowserPanelStatus.ChannelsOnly, BrowserPanelStatus.Hint ->
            AppIcon(AppIcons.Info, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun browserPanelStatusText(status: BrowserPanelStatus): String = when (status) {
    is BrowserPanelStatus.Message -> status.text
    BrowserPanelStatus.Loading -> stringResource(R.string.browser_loading_page)
    BrowserPanelStatus.Hint -> stringResource(R.string.browser_initial_hint)
    BrowserPanelStatus.Scanning -> stringResource(R.string.browser_scanning)
    is BrowserPanelStatus.Found -> stringResource(R.string.browser_found)
    BrowserPanelStatus.ChannelsOnly -> stringResource(R.string.browser_channels_no_media)
    BrowserPanelStatus.Empty -> stringResource(R.string.browser_nothing_found)
}

@Composable
private fun CountsLine(status: BrowserPanelStatus.Found) {
    val parts = buildList {
        if (status.videos > 0) add(stringResource(R.string.browser_videos_count, status.videos))
        if (status.images > 0) add(stringResource(R.string.browser_images_count, status.images))
    }
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
    )
}

@Composable
private fun SectionHeader(titleRes: Int, count: Int) {
    Text(
        text = "${stringResource(titleRes)} · $count",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun CandidateRow(
    group: CandidateGroup,
    selectedExtension: String?,
    images: Boolean,
    onClick: () -> Unit,
    onFormatSelect: (String, String) -> Unit,
) {
    val variant = group.variantOf(selectedExtension)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(BROWSER_CANDIDATE_TAG_PREFIX + group.id)
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaTile(variant, images)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (images) imageTitle(variant) else videoTitle(variant),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (images) imageMeta(variant) else stringResource(R.string.browser_video_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (images && group.variants.size > 1) {
            FormatPicker(
                group = group,
                selectedExtension = selectedExtension,
                onFormatSelect = onFormatSelect,
            )
        }
        IconButton(onClick = onClick) {
            AppIcon(
                AppIcons.Download,
                contentDescription = stringResource(R.string.browser_download_candidate),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun imageTitle(candidate: BrowserMediaDetector.Candidate): String {
    val label = stringResource(R.string.browser_image_label)
    val width = candidate.width
    val height = candidate.height
    val size = if (width != null && height != null) {
        stringResource(R.string.browser_image_dimensions, width, height)
    } else {
        null
    }
    return if (size == null) label else "$label · $size"
}

@Composable
internal fun imageMeta(candidate: BrowserMediaDetector.Candidate): String {
    val extension = candidate.extension?.uppercase() ?: "IMG"
    val bytes = candidate.sizeBytes?.takeIf { it > 0L } ?: return extension
    return stringResource(R.string.browser_image_meta, extension, formatBytes(bytes))
}

@Composable
private fun videoTitle(candidate: BrowserMediaDetector.Candidate): String =
    stringResource(R.string.browser_video_row, candidate.type, candidate.url.toUri().host.orEmpty())

@Composable
private fun MediaTile(candidate: BrowserMediaDetector.Candidate, images: Boolean) {
    val context = LocalPlatformContext.current
    val size = candidate.sizeBytes
    val request = if (images && (size == null || size <= THUMBNAIL_MAX_BYTES)) {
        remember(candidate.url, candidate.pageUrl) {
            ImageRequest.Builder(context)
                .data(candidate.url)
                .httpHeaders(
                    NetworkHeaders.Builder()
                        .set("Referer", candidate.pageUrl)
                        .set("User-Agent", candidate.userAgent)
                        .build(),
                )
                .build()
        }
    } else null
    MediaCover(
        model = request,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(10.dp),
        placeholderIcon = if (images) null else AppIcons.Play,
        placeholderText = if (images) candidate.extension?.uppercase() ?: "IMG" else null,
    )
}

@Composable
private fun FormatPicker(
    group: CandidateGroup,
    selectedExtension: String?,
    onFormatSelect: (String, String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = group.variantOf(selectedExtension)
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.height(36.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text(
                text = selected.extension?.uppercase() ?: "IMG",
                style = MaterialTheme.typography.labelLarge,
            )
            AppIcon(AppIcons.ChevronDown, contentDescription = stringResource(R.string.browser_choose_format),
                modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            group.variants.forEach { variant ->
                val extension = variant.extension ?: "img"
                DropdownMenuItem(
                    text = { Text(imageMeta(variant)) },
                    onClick = {
                        open = false
                        onFormatSelect(group.id, extension)
                    },
                    leadingIcon = {
                        if (variant.extension.equals(selected.extension, ignoreCase = true)) {
                            AppIcon(AppIcons.Success, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionsRow(
    busy: Boolean,
    desktopSite: Boolean,
    hidePrompts: Boolean,
    noteOpen: Boolean,
    onReparse: () -> Unit,
    onCancelParse: () -> Unit,
    onToggleNote: () -> Unit,
    onToggleDesktopSite: () -> Unit,
    onToggleHidePrompts: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = if (busy) onCancelParse else onReparse) {
            Text(stringResource(if (busy) R.string.browser_cancel_parse else R.string.browser_reparse_with_session))
        }
        TextButton(onClick = onToggleNote) {
            Text(stringResource(R.string.browser_note), color = if (noteOpen) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Text(stringResource(R.string.browser_more))
                AppIcon(AppIcons.ChevronDown, null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_desktop_site)) },
                    leadingIcon = {
                        if (desktopSite) AppIcon(AppIcons.Success, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = { menuOpen = false; onToggleDesktopSite() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_hide_prompts)) },
                    leadingIcon = {
                        if (hidePrompts) AppIcon(AppIcons.Success, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = { menuOpen = false; onToggleHidePrompts() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_open_external)) },
                    leadingIcon = { AppIcon(AppIcons.ExternalLink, null, Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onOpenExternal() },
                )
            }
        }
    }
}
