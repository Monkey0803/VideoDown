package com.videodown.douyin.data.remote

/**
 * Why parsing an X post produced nothing.
 *
 * Typed so the wording can change (or be translated) without changing behaviour; the strings used
 * to be built inside the parser and matched on by callers.
 */
enum class XParseFailure {
    NOT_FOUND, UNSUPPORTED_FORMAT, NO_VIDEO, LOGIN_REQUIRED, UNAVAILABLE, RESTRICTED, INVALID, RATE_LIMITED, NETWORK;

    val storageKey: String get() = X_FAILURE_PREFIX + name.lowercase()
}

const val X_FAILURE_PREFIX = "x:"

fun xFailureOf(key: String?): XParseFailure? =
    key?.takeIf { it.startsWith(X_FAILURE_PREFIX) }
        ?.removePrefix(X_FAILURE_PREFIX)
        ?.let { value -> XParseFailure.entries.firstOrNull { it.name.lowercase() == value } }
