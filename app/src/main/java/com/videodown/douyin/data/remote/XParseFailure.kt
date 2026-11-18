package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.DownloadFailureKind

/**
 * Why parsing an X post produced nothing.
 *
 * Typed so the wording can change (or be translated) without changing behaviour; the strings used
 * to be built inside the parser and matched on by callers.
 */
enum class XParseFailure {
    NOT_FOUND, UNSUPPORTED_FORMAT, NO_VIDEO, LOGIN_REQUIRED, UNAVAILABLE,

    /**
     * 内容被 X 的年龄/敏感内容门禁挡住。
     *
     * 匿名接口只会回一个 `TweetTombstone`，区分不出「未登录」还是「已登录但账号看不到」；因此按
     * 需要登录处理，由调用方在真正带着登录态重试之后再决定是否升级成 [SESSION_AUTH_FAILED]。
     */
    RESTRICTED,

    INVALID, RATE_LIMITED, NETWORK,

    /**
     * 已经用本机保存的 X 登录态重试，仍被拒绝。
     *
     * 与 [LOGIN_REQUIRED] 的区别：那条是「没有登录态」，这条是「有但用不了」，成因可能是登录已失效，
     * 也可能是账号看不到这条内容——接口不区分，文案两件事都说，并指引重新登录。
     */
    SESSION_AUTH_FAILED;

    val storageKey: String get() = X_FAILURE_PREFIX + name.lowercase()

    /**
     * 界面分类：帖子里没有视频，与「需要登录」是两件事，不能都落进通用的「不支持」。
     *
     * 其余原因保持 `null`，由调用方按文案说明，避免把「链接过期」之类的分类硬套到推文上。
     */
    val failureKind: DownloadFailureKind? get() = when (this) {
        NO_VIDEO -> DownloadFailureKind.NO_VIDEO
        LOGIN_REQUIRED, RESTRICTED, SESSION_AUTH_FAILED -> DownloadFailureKind.LOGIN_REQUIRED
        else -> null
    }
}

const val X_FAILURE_PREFIX = "x:"

fun xFailureOf(key: String?): XParseFailure? =
    key?.takeIf { it.startsWith(X_FAILURE_PREFIX) }
        ?.removePrefix(X_FAILURE_PREFIX)
        ?.let { value -> XParseFailure.entries.firstOrNull { it.name.lowercase() == value } }
