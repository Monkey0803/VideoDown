package com.videodown.douyin.presentation.theme

import androidx.compose.ui.unit.dp

/** 拾影统一间距阶梯；页面只从这六档组合，避免继续产生零散 magic number。 */
internal object AppSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

/** 底部导航切换到侧边导航的最小窗口宽度。 */
internal val NavigationRailBreakpoint = 840.dp
