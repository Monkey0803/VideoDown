package com.videodown.douyin.presentation.browser

/**
 * 网页解析面板收起时那一行该说什么。
 *
 * 抽成纯状态机是为了能在 JVM 上把每种组合钉住——这里最容易出的错是「什么都不说」或「说错」：
 * 状态行原来直接显示 `AppError.message`（引擎失败带的是 `reasonKey`、`message` 是空串，于是留白），
 * 扫描还没结束时又显示「可在网页中登录并播放…」，看起来像已经扫完了。
 *
 * 优先级固定为：显式消息 → 页面加载中 → 解析中 → 未开始扫描 → 扫描中 → 有候选 → 视频号 → 空结果。
 * 显式消息（正在读取 / 失败原因 / 取消 / 网页错误）永远优先，扫描结论不能把它盖掉。
 */
sealed interface BrowserPanelStatus {
    /** 显式消息，已经由调用方本地化。 */
    data class Message(val text: String) : BrowserPanelStatus

    /** 页面还在加载。 */
    data object Loading : BrowserPanelStatus

    /** 页面已加载，但这一轮扫描还没触发。 */
    data object Hint : BrowserPanelStatus

    /** 扫描或解析进行中。 */
    data object Scanning : BrowserPanelStatus

    /** 扫完且有候选；计数是**聚合后**的条数，不是 URL 数。 */
    data class Found(val videos: Int, val images: Int) : BrowserPanelStatus

    /** 扫完、没有候选，且这是视频号页面：换一句更准确的说明。 */
    data object ChannelsOnly : BrowserPanelStatus

    /** 扫完、没有候选。 */
    data object Empty : BrowserPanelStatus

    val total: Int get() = if (this is Found) videos + images else 0
}

fun browserPanelStatus(
    message: String?,
    pageLoading: Boolean,
    parsing: Boolean,
    scanStarted: Boolean,
    scanSettled: Boolean,
    videos: Int,
    images: Int,
    weChatChannels: Boolean,
): BrowserPanelStatus = when {
    message != null -> BrowserPanelStatus.Message(message)
    pageLoading -> BrowserPanelStatus.Loading
    parsing -> BrowserPanelStatus.Scanning
    !scanStarted -> BrowserPanelStatus.Hint
    !scanSettled -> BrowserPanelStatus.Scanning
    videos + images > 0 -> BrowserPanelStatus.Found(videos, images)
    weChatChannels -> BrowserPanelStatus.ChannelsOnly
    else -> BrowserPanelStatus.Empty
}
