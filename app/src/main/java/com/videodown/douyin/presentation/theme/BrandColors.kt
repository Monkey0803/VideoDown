package com.videodown.douyin.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * 固定品牌色。
 *
 * 只给「必须保持品牌识别」的少数元素使用：站点徽标的紫蓝渐变、品牌图标渐变、已存选择圈的暖橙。
 * 普通选择态、描边、容器一律走 `MaterialTheme.colorScheme`，Material You 取色不会被这些固定值破坏。
 * 播放器/看图器为媒体可读性使用的黑白遮罩属于明确例外，仍就近使用 `Color.Black` / `Color.White`。
 */
internal object BrandColors {
    /** 站点（KUBB.TV 等）徽标的固定品牌渐变。 */
    val siteBadgeGradient = listOf(Color(0xFF6D5DFB), Color(0xFF3E9BFF))

    /** 品牌图标渐变（启动图标同源的暖橙）。 */
    val brandIconGradient = listOf(Color(0xFFFFAD75), Color(0xFFF2784B))

    /** 品牌图标上的前景色。 */
    val onBrandIcon = Color(0xFFFFF8F5)

    /** 封面选择圈：选中时的暖橙，配合深色对勾保证在任意封面上的可读性。 */
    val selectionIndicator = Color(0xFFF2784B)
    val onSelectionIndicator = Color(0xFF33251F)
}
