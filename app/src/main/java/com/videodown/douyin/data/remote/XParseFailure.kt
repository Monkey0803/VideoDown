package com.videodown.douyin.data.remote

/**
 * Why parsing an X post produced nothing.
 *
 * Typed so the wording can change (or be translated) without changing behaviour; the strings used
 * to be built inside the parser and matched on by callers.
 */
enum class XParseFailure {
    NOT_FOUND, UNSUPPORTED_FORMAT, NO_VIDEO, LOGIN_REQUIRED, UNAVAILABLE, RESTRICTED, INVALID, RATE_LIMITED, NETWORK,

    /**
     * 已经用本机保存的 X 登录态重试，仍被拒绝。
     *
     * 与 [LOGIN_REQUIRED] 的区别：那条是「没有登录态」，这条是「有但用不了」，成因可能是登录已失效，
     * 也可能是账号看不到这条内容——接口不区分，文案两件事都说，并指引重新登录。
     */
    SESSION_AUTH_FAILED;

    val storageKey: String get() = X_FAILURE_PREFIX + name.lowercase()
}

const val X_FAILURE_PREFIX = "x:"

fun xFailureOf(key: String?): XParseFailure? =
    key?.takeIf { it.startsWith(X_FAILURE_PREFIX) }
        ?.removePrefix(X_FAILURE_PREFIX)
        ?.let { value -> XParseFailure.entries.firstOrNull { it.name.lowercase() == value } }
