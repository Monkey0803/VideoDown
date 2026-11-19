package com.videodown.douyin.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videodown.douyin.presentation.adaptive.ContentWidth
import com.videodown.douyin.presentation.adaptive.LocalFoldFeature
import com.videodown.douyin.presentation.adaptive.LocalWindowWidth
import com.videodown.douyin.presentation.adaptive.WindowLayoutPolicy
import kotlin.math.roundToInt

/**
 * 主页面的统一内容宽度。
 *
 * [ContentWidth.READABLE]（840dp）用于按行阅读的页面；[ContentWidth.WORKSPACE] 只在 Expanded
 * 放宽到 1040dp（已存 Feed 等多列内容）。没有折痕时内容居中；有可分离折痕时收敛到折痕一侧，
 * 关键操作不跨在折痕上。系统栏安全区仍由外层 Scaffold 统一处理，这里只负责内容尺寸。
 */
@Composable
fun AdaptivePage(
    modifier: Modifier = Modifier,
    contentWidth: ContentWidth = ContentWidth.READABLE,
    content: @Composable BoxScope.() -> Unit,
) {
    val windowWidth = LocalWindowWidth.current
    val fold = LocalFoldFeature.current
    val policyWidth = WindowLayoutPolicy.contentMaxWidthDp(contentWidth, windowWidth)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val folded = fold?.separating == true
        val pane = WindowLayoutPolicy.foldPane(
            widthDp = maxWidth.value.roundToInt(),
            heightDp = maxHeight.value.roundToInt(),
            fold = fold,
        )
        val maxContentWidth = if (folded) minOf(policyWidth, pane.widthDp) else policyWidth
        Box(
            Modifier
                .then(
                    if (folded) Modifier.padding(start = pane.offsetXDp.dp, top = pane.offsetYDp.dp)
                    else Modifier,
                )
                .widthIn(max = maxContentWidth.coerceAtLeast(1).dp)
                // 横向折痕（tabletop）时高度也收敛到折痕一侧，内容不会压在折痕上。
                .then(if (folded) Modifier.heightIn(max = pane.heightDp.coerceAtLeast(1).dp) else Modifier)
                .fillMaxWidth()
                .align(if (folded) Alignment.TopStart else Alignment.TopCenter),
            content = content,
        )
    }
}

/** 工作区页面（已存 Feed）的入口，等价于 `AdaptivePage(contentWidth = WORKSPACE)`。 */
@Composable
fun AdaptiveWorkspacePage(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    AdaptivePage(modifier, contentWidth = ContentWidth.WORKSPACE, content = content)
}
