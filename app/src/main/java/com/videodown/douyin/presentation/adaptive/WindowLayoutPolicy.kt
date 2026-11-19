package com.videodown.douyin.presentation.adaptive

/**
 * 窗口宽度尺寸类。
 *
 * 断点与 Material 3 的 `WindowWidthSizeClass` 一致（600dp / 840dp）；页面只判断这个枚举，
 * 不再各自比较 `screenWidthDp`。纯 Kotlin，JVM 用例与 Compose 页面共用同一份判定。
 */
enum class WindowWidth { COMPACT, MEDIUM, EXPANDED }

/** 一级导航形态。四个入口在 Medium/Expanded 用 Rail，够密才需要抽屉。 */
enum class NavigationLayout { BOTTOM_BAR, RAIL, DRAWER }

/**
 * 正文宽度档位。
 *
 * - [READABLE]：可读正文宽度，任务页、结果页等按行阅读的内容。
 * - [WORKSPACE]：工作区宽度，仅在 Expanded 下放宽，给已存 Feed 列或首页双栏用。
 */
enum class ContentWidth { READABLE, WORKSPACE }

/**
 * 折痕 / 铰链几何（dp，相对页面可用区域，原点左上）。
 *
 * 只保留策略需要的字段：[separating] 为假表示折痕只是视觉折线、不遮挡内容。
 */
data class FoldFeature(
    val separating: Boolean,
    val verticalHinge: Boolean,
    val leftDp: Int,
    val topDp: Int,
    val rightDp: Int,
    val bottomDp: Int,
)

/** 折叠姿态下允许放置内容的区域；[offsetXDp] / [offsetYDp] 是相对页面左上角的位移。 */
data class FoldPane(val widthDp: Int, val heightDp: Int, val offsetXDp: Int, val offsetYDp: Int)

/**
 * 纯逻辑的窗口布局策略。
 *
 * 所有判定集中在这里：导航形态、正文/工作区宽度、页面边距、已存 Feed 列数、首页双栏、
 * 设置页 list-detail 以及折叠姿态的内容区。Compose 页面只读结论，JVM 用例直接测这里。
 */
object WindowLayoutPolicy {
    const val MEDIUM_MIN_WIDTH_DP = 600
    const val EXPANDED_MIN_WIDTH_DP = 840
    const val READABLE_MAX_WIDTH_DP = 840
    const val WORKSPACE_MAX_WIDTH_DP = 1040
    const val RAIL_WIDTH_DP = 80

    /** 一级入口超过这个数量，Expanded 才换成标准抽屉；四个入口用 Rail 更快。 */
    const val DRAWER_MIN_ITEM_COUNT = 7

    fun classify(widthDp: Int): WindowWidth = when {
        widthDp < MEDIUM_MIN_WIDTH_DP -> WindowWidth.COMPACT
        widthDp < EXPANDED_MIN_WIDTH_DP -> WindowWidth.MEDIUM
        else -> WindowWidth.EXPANDED
    }

    /**
     * 一级导航：Compact 用底部 Navigation Bar；Medium/Expanded 用 Navigation Rail。
     *
     * 只有一级入口超过 [DRAWER_MIN_ITEM_COUNT] 个时才建议抽屉——当前应用固定四个入口，
     * 走 Rail，用抽屉反而让常用入口多一次点击。
     */
    fun navigation(width: WindowWidth, topLevelItemCount: Int): NavigationLayout = when {
        width == WindowWidth.COMPACT -> NavigationLayout.BOTTOM_BAR
        topLevelItemCount >= DRAWER_MIN_ITEM_COUNT -> NavigationLayout.DRAWER
        else -> NavigationLayout.RAIL
    }

    fun contentMaxWidthDp(content: ContentWidth, width: WindowWidth): Int = when (content) {
        ContentWidth.READABLE -> READABLE_MAX_WIDTH_DP
        // 工作区只在 Expanded 放宽；Medium 仍按正文宽度居中，避免卡片被拉长。
        ContentWidth.WORKSPACE ->
            if (width == WindowWidth.EXPANDED) WORKSPACE_MAX_WIDTH_DP else READABLE_MAX_WIDTH_DP
    }

    /** 页面左右边距：Compact 16dp，Medium/Expanded 24dp。 */
    fun pagePaddingDp(width: WindowWidth): Int =
        if (width == WindowWidth.COMPACT) 16 else 24

    /** 已存 Feed 列数：Compact 2 列、Medium 3 列、Expanded 4 列。 */
    fun feedColumns(width: WindowWidth): Int = when (width) {
        WindowWidth.COMPACT -> 2
        WindowWidth.MEDIUM -> 3
        WindowWidth.EXPANDED -> 4
    }

    /** 首页在 Expanded 拆成「解析主面板 + 站点/最近保存」双栏。 */
    fun homeDualPane(width: WindowWidth): Boolean = width == WindowWidth.EXPANDED

    /** 设置页在 Expanded 用「分类列表 + 详情」list-detail。 */
    fun settingsListDetail(width: WindowWidth): Boolean = width == WindowWidth.EXPANDED

    /** 已存内容在 Expanded 使用工作区宽度（1040dp），其余页面保持正文宽度。 */
    fun libraryContentWidth(width: WindowWidth): ContentWidth = ContentWidth.WORKSPACE

    /**
     * 把可放置内容的区域收敛到折痕的一侧。
     *
     * 半折叠 tabletop（横向折痕）取较高的那半，book（纵向折痕）取较宽的那侧，关键操作因此
     * 不会跨在折痕上。没有 separating 折痕时返回整块可用区域。
     */
    fun foldPane(widthDp: Int, heightDp: Int, fold: FoldFeature?): FoldPane {
        val safeWidth = widthDp.coerceAtLeast(0)
        val safeHeight = heightDp.coerceAtLeast(0)
        if (fold == null || !fold.separating) {
            return FoldPane(safeWidth, safeHeight, 0, 0)
        }
        return if (fold.verticalHinge) {
            val leftWidth = fold.leftDp.coerceIn(0, safeWidth)
            val rightWidth = (safeWidth - fold.rightDp).coerceIn(0, safeWidth)
            when {
                leftWidth <= 0 && rightWidth <= 0 -> FoldPane(safeWidth, safeHeight, 0, 0)
                rightWidth > leftWidth -> FoldPane(rightWidth, safeHeight, fold.rightDp.coerceIn(0, safeWidth), 0)
                else -> FoldPane(leftWidth, safeHeight, 0, 0)
            }
        } else {
            val topHeight = fold.topDp.coerceIn(0, safeHeight)
            val bottomHeight = (safeHeight - fold.bottomDp).coerceIn(0, safeHeight)
            when {
                topHeight <= 0 && bottomHeight <= 0 -> FoldPane(safeWidth, safeHeight, 0, 0)
                bottomHeight > topHeight -> FoldPane(safeWidth, bottomHeight, 0, fold.bottomDp.coerceIn(0, safeHeight))
                else -> FoldPane(safeWidth, topHeight, 0, 0)
            }
        }
    }

    /**
     * list-detail 的主栏宽度：设置页分类列表用固定可读宽度，剩余给详情。
     *
     * Compact 返回 0，调用方回落单栏——避免把详情挤成不可用的窄条。
     */
    fun listPaneWidthDp(width: WindowWidth): Int = when (width) {
        WindowWidth.COMPACT -> 0
        WindowWidth.MEDIUM -> 280
        WindowWidth.EXPANDED -> 320
    }
}
