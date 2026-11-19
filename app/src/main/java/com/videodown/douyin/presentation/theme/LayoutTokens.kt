package com.videodown.douyin.presentation.theme

import androidx.compose.ui.unit.dp
import com.videodown.douyin.presentation.adaptive.WindowLayoutPolicy
import com.videodown.douyin.presentation.adaptive.WindowWidth

/** 拾影统一间距阶梯；页面只从这六档组合，避免继续产生零散 magic number。 */
internal object AppSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp

    /**
     * 页面左右边距：Compact 16dp、Medium/Expanded 24dp。
     *
     * 页面用 `pagePadding()`（adaptive 包）取同一结论；这里保留纯函数入口给需要显式尺寸类的场景。
     */
    fun pageHorizontal(width: WindowWidth) = WindowLayoutPolicy.pagePaddingDp(width).dp

    /** 导航 Rail 宽度。 */
    val navigationRailWidth = WindowLayoutPolicy.RAIL_WIDTH_DP.dp
}

/** 圆角阶梯：MaterialTheme.shapes 与少数需要显式圆角的容器共用同一组数值。 */
internal object AppShapes {
    val extraSmallRadius = 4.dp
    val smallRadius = 8.dp
    val mediumRadius = 12.dp
    val largeRadius = 16.dp
    val extraLargeRadius = 28.dp
}
