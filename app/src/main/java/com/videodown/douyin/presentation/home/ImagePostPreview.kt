package com.videodown.douyin.presentation.home

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.rememberCoilZoomState
import com.videodown.douyin.R
import com.videodown.douyin.presentation.components.AppFullScreenDialog
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.components.CoverSelectionMark

/** 解析结果里可直接预览的一张图片；[selectionKey] 复用结果页的选择键。 */
internal data class ImagePreviewItem(
    val selectionKey: String,
    val url: String,
    val label: String,
)

private enum class PreviewLoad { LOADING, READY, FAILED }

/**
 * 图片作品的"存前预览"。
 *
 * 结果页以前只能跳到原帖去确认要存哪几张；这里给应用内的大图翻页 + 逐张勾选，
 * 勾选状态直接写回结果页的批量选择，关掉预览即可批量保存。
 */
@Composable
internal fun ImagePostPreview(
    items: List<ImagePreviewItem>,
    initialIndex: Int,
    selectedKeys: Set<String>,
    onToggleSelection: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, items.lastIndex)) { items.size }
    val loadStates = remember { mutableStateMapOf<String, PreviewLoad>() }
    AppFullScreenDialog(onDismissRequest = onDismiss, surfaceColor = Color.Black) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CoilZoomAsyncImage(
                    model = item.url,
                    contentDescription = item.label,
                    zoomState = rememberCoilZoomState(),
                    contentScale = ContentScale.Fit,
                    onLoading = { loadStates[item.selectionKey] = PreviewLoad.LOADING },
                    onSuccess = { loadStates[item.selectionKey] = PreviewLoad.READY },
                    onError = { loadStates[item.selectionKey] = PreviewLoad.FAILED },
                    modifier = Modifier.fillMaxSize(),
                )
                when (loadStates[item.selectionKey] ?: PreviewLoad.LOADING) {
                    PreviewLoad.LOADING -> CircularProgressIndicator(color = Color.White)
                    PreviewLoad.FAILED -> Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(AppIcons.Warning, null, tint = Color.White, modifier = Modifier.size(32.dp))
                        Text(
                            stringResource(R.string.viewer_image_failed),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            stringResource(R.string.viewer_image_failed_hint),
                            color = Color.White.copy(alpha = .7f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    PreviewLoad.READY -> Unit
                }
            }
        }
        val currentIndex = pagerState.currentPage.coerceIn(0, items.lastIndex)
        val current = items[currentIndex]
        Row(
            Modifier.fillMaxWidth()
                .background(Color.Black.copy(alpha = .55f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                AppIcon(AppIcons.Close, stringResource(R.string.action_close), tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    current.label,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.viewer_count, currentIndex + 1, items.size),
                    color = Color.White.copy(alpha = .72f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            CoverSelectionMark(
                selected = current.selectionKey in selectedKeys,
                enabled = true,
                contentDescription = stringResource(R.string.home_preview_select),
                onToggle = { onToggleSelection(current.selectionKey) },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Text(
            stringResource(R.string.home_preview_hint),
            color = Color.White.copy(alpha = .82f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = 22.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color.Black.copy(alpha = .45f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
