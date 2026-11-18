package com.videodown.douyin.presentation.home

/**
 * 批量导入弹窗在「可用高度」不足时的降级档位。
 *
 * 弹窗是固定的三段式（头部 / 内容 / 页脚），可用高度决定列表还能留下多少：
 * 竖屏通常是 [ImportLayout.FULL]；横屏无键盘是 [ImportLayout.COMPACT]；横屏 + 软键盘
 * 只剩约 129dp 时是 [ImportLayout.MINIMAL]（AppDialog 的注释里有同一组实测数据）。
 *
 * 阈值是估算：估小只是提前降级、少显示一行说明，估大才会让页脚被裁掉。
 * 真实横屏 + 键盘由设备用例兜底，所以这里只负责「哪一档」这一件纯逻辑。
 */
internal enum class ImportLayout { FULL, COMPACT, MINIMAL }

internal object BatchImportLayoutPolicy {
    /** 能同时放下图标头部、副标题、输入区与状态条的最低可用高度。 */
    const val FULL_MIN_HEIGHT_DP = 480

    /** 至少能放下单行头部、输入摘要与页脚的最低可用高度。 */
    const val COMPACT_MIN_HEIGHT_DP = 320

    fun layoutFor(availableHeightDp: Int): ImportLayout = when {
        availableHeightDp >= FULL_MIN_HEIGHT_DP -> ImportLayout.FULL
        availableHeightDp >= COMPACT_MIN_HEIGHT_DP -> ImportLayout.COMPACT
        else -> ImportLayout.MINIMAL
    }

    /** 图标块只在 FULL 出现：紧凑档位要先保住列表高度。 */
    fun showsHeaderIcon(layout: ImportLayout): Boolean = layout == ImportLayout.FULL

    /** 副标题只在 FULL 出现；50 条上限的说明在输入区仍有入口。 */
    fun showsHeaderSubtitle(layout: ImportLayout): Boolean = layout == ImportLayout.FULL

    /** 状态条（全选 / 进度 / 继续解析）在 MINIMAL 隐藏，避免把列表挤成一条缝。 */
    fun showsStatusStrip(layout: ImportLayout): Boolean = layout != ImportLayout.MINIMAL
}
