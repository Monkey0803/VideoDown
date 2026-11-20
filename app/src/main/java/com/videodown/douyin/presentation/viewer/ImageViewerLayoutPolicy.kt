package com.videodown.douyin.presentation.viewer

import com.videodown.douyin.presentation.adaptive.WindowWidth

/**
 * 看图器的响应式判定。
 *
 * 与其它页面一样集中成纯逻辑：日期网格列数跟着窗口宽度走，短横屏/分屏收起缩略图带，
 * 大字号下动作标签允许两行而不是被固定宽度截断。
 */
object ImageViewerLayoutPolicy {
    /** 低于这个高度（横屏、分屏）就没有必要再挤一条 64dp 缩略图带。 */
    const val THUMBNAIL_MIN_HEIGHT_DP = 480

    /** 日期网格列数：手机 3 列、中等窗口 4 列、大屏 6 列。 */
    fun dateGridColumns(width: WindowWidth): Int = when (width) {
        WindowWidth.COMPACT -> 3
        WindowWidth.MEDIUM -> 4
        WindowWidth.EXPANDED -> 6
    }

    /**
     * 是否显示缩略图带。
     *
     * 短窗口里图片本身最需要高度；此时仍可用日期网格与左右滑动换图。
     */
    fun showThumbnailStrip(windowHeightDp: Int): Boolean = windowHeightDp >= THUMBNAIL_MIN_HEIGHT_DP

    /** 大字号下标签换行，避免"保存到相册"变成"保存到…"。 */
    fun actionLabelMaxLines(fontScale: Float): Int = if (fontScale >= 1.3f) 2 else 1
}
