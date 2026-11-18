package com.videodown.douyin.presentation.viewer

import com.videodown.douyin.presentation.theme.AppMotion

/**
 * 退出看图器后恢复系统栏的时机。
 *
 * 导航退场和共享图片仍在运行时不能先露出浅色系统栏；关闭系统动画时则直接恢复。
 */
internal fun imageViewerSystemBarsRestoreDelayMillis(
    wasImageViewer: Boolean,
    isImageViewer: Boolean,
    animationsEnabled: Boolean,
): Long? = when {
    !wasImageViewer || isImageViewer -> null
    animationsEnabled -> AppMotion.DEFAULT_MS.toLong()
    else -> 0L
}
