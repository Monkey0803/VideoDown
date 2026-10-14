package com.videodown.douyin.presentation.browser

import com.videodown.douyin.R

/**
 * 网页解析面板底部该说哪句话。
 *
 * 抽成纯函数是为了能在 JVM 上把每种组合钉住——这里最容易出的错就是"什么都不说"：
 * 状态行原来直接显示 `AppError.message`，而引擎失败带的是 `reasonKey`、`message` 是空串，
 * 于是失败过一次之后底下一片空白，用户不知道发生了什么。
 *
 * 优先级：显式消息（正在读取/失败/取消/网页错误）优先；否则按本轮扫描的结论说话。
 *
 * @return 要显示的字符串资源；`null` 表示"用调用方手上的显式消息"。
 */
internal fun browserStatusRes(
    hasMessage: Boolean,
    scanSettled: Boolean,
    busy: Boolean,
    candidateCount: Int,
    weChatChannels: Boolean,
): Int? = when {
    hasMessage -> null
    !scanSettled || busy -> R.string.browser_initial_hint
    candidateCount > 0 -> R.string.browser_scanned
    // 视频号单独一句：通用兜底那句"打开网页登录或播放后查找"对这个网站是死的。
    weChatChannels -> R.string.browser_channels_no_media
    else -> R.string.browser_nothing_found
}
