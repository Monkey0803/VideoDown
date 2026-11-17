package com.videodown.douyin.presentation

/** 纯逻辑策略，供 Compose 页面与 JVM 用例共同使用。 */
object UiDensityPolicy {
    fun showQueueSummary(running: Int, pending: Int): Boolean = running > 0 || pending > 0

    /** 空集合只保留空状态主操作；已有内容或筛选结果为空时才展示管理工具。 */
    fun showManagementControls(itemCount: Int, hasActiveQuery: Boolean): Boolean =
        itemCount > 0 || hasActiveQuery

    /** 首次引导出现时，站点专区降级为紧凑入口，避免两个大卡片同时争夺注意力。 */
    fun showExpandedHomeSections(firstUseGuideVisible: Boolean): Boolean = !firstUseGuideVisible

    fun showRecentSection(recentCount: Int): Boolean = recentCount > 0

    fun showFirstUseGuide(
        hasSavedMedia: Boolean,
        input: String,
        hasClipboardOffer: Boolean,
        dismissed: Boolean,
    ): Boolean = !dismissed && !hasSavedMedia && input.isBlank() && !hasClipboardOffer
}
