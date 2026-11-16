package com.videodown.douyin.presentation

/** 纯逻辑策略，供 Compose 页面与 JVM 用例共同使用。 */
object UiDensityPolicy {
    fun showQueueSummary(running: Int, pending: Int): Boolean = running > 0 || pending > 0

    fun showFirstUseGuide(hasSavedMedia: Boolean, input: String, hasClipboardOffer: Boolean): Boolean =
        !hasSavedMedia && input.isBlank() && !hasClipboardOffer
}
