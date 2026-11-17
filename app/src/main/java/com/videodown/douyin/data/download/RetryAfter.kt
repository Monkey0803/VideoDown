package com.videodown.douyin.data.download

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal const val MAX_RETRY_AFTER_MS = 15L * 60L * 1000L

/** 把 HTTP Retry-After 归一成从现在起的毫秒数，并限制服务端给出的过长等待。 */
internal fun retryAfterDelayMillis(value: String?, nowEpochMillis: Long = System.currentTimeMillis()): Long? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    val delay = text.toLongOrNull()?.takeIf { it >= 0L }?.let { seconds ->
        if (seconds > Long.MAX_VALUE / 1000L) Long.MAX_VALUE else seconds * 1000L
    } ?: runCatching {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowEpochMillis
    }.getOrNull()
    return delay?.coerceIn(0L, MAX_RETRY_AFTER_MS)
}
