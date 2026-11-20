package com.videodown.douyin.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 封面浮层上的小圆底：主题色圆形 + 居中图标，供「更多」与拖拽手柄共用。
 *
 * 刻意不用黑白遮罩：`BrandColors` 把黑白遮罩限定在播放器/看图器这类「媒体可读性」场景，
 * 普通卡片上的控件一律走 `MaterialTheme.colorScheme`，Material You 动态取色也能自动跟随。
 * 只画可见的圆底，触控命中区由调用方负责（例如 48dp 的 `IconButton`），所以可见尺寸可以比
 * 命中区小，视觉上不会变成压在照片上的大块深色圆。
 */
@Composable
internal fun CoverChip(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.size(size).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
