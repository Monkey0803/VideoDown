package com.videodown.douyin.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage

/**
 * 有可加载地址且尚未成功时才扫光；没有模型时绝不扫光，避免常驻无限动画。
 */
internal fun shouldShimmer(model: Any?, loaded: Boolean): Boolean {
    if (loaded) return false
    return when (model) {
        null -> false
        is String -> model.isNotBlank()
        else -> true
    }
}

/**
 * 列表/网格/封面共用的媒体缩略图：占位图标/文字 + 封面 + 可选扫光 + 叠层槽。
 *
 * 调用方仍负责尺寸、宽高比、`sharedCover` 与点击语义；[overlay] 承载选择圈、清晰度、
 * 观看进度、时长、平台角标等所有原有叠层，所以迁移前后视觉与交互一一对应。
 *
 * 不用于播放器视频面与看图器内容：前者涉及共享元素/全屏/PiP，后者的缩放、边框与
 * 内容预览都不是"缩略图"语义。
 */
@Composable
internal fun MediaCover(
    model: Any?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    placeholderIcon: AppIcons? = null,
    placeholderText: String? = null,
    contentDescription: String? = null,
    shimmer: Boolean = false,
    onImageSize: ((width: Int, height: Int) -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var loaded by remember(model) { mutableStateOf(false) }
    val showShimmer = shimmer && shouldShimmer(model, loaded)
    Box(
        modifier = modifier.clip(shape).background(backgroundColor)
            .then(if (showShimmer) Modifier.shimmer() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // 占位在封面之下：有图时被图盖住，无图时作为兜底，与各页面原来的画法一致。
        placeholderIcon?.let {
            AppIcon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        placeholderText?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onSuccess = { result ->
                loaded = true
                // Coil 3：State.Success.result 是 SuccessResult，图片本体在它的 image 上。
                val image = result.result.image
                onImageSize?.invoke(image.width, image.height)
            },
        )
        overlay()
    }
}
