package com.videodown.douyin.presentation.login

import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.VideoPlatform

/** X 解析失败后登录入口使用的文案意图；不把 Cookie 存在误写成账号一定仍有权限。 */
internal enum class XLoginRecoveryAction {
    SIGN_IN_AND_RETRY,
    REAUTHENTICATE_AND_RETRY,
    CHECK_SIGN_IN_AND_ACCESS,
}

/**
 * 只决定是否展示 X 登录恢复入口以及入口语气。
 *
 * ACCESS_DENIED 无法区分地址失效、账号权限与站点拦截，因此必须保持中性；只有明确要求登录时，
 * 才根据本机是否已检测到登录态区分“登录”和“重新登录”。
 */
internal fun xLoginRecoveryAction(
    platform: VideoPlatform?,
    failureKind: DownloadFailureKind?,
    sessionDetected: Boolean?,
): XLoginRecoveryAction? {
    if (platform != VideoPlatform.X) return null
    return when (failureKind) {
        DownloadFailureKind.LOGIN_REQUIRED -> if (sessionDetected == true) {
            XLoginRecoveryAction.REAUTHENTICATE_AND_RETRY
        } else {
            XLoginRecoveryAction.SIGN_IN_AND_RETRY
        }
        DownloadFailureKind.ACCESS_DENIED -> XLoginRecoveryAction.CHECK_SIGN_IN_AND_ACCESS
        else -> null
    }
}
