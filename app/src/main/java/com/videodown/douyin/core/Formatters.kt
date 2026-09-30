package com.videodown.douyin.core

import java.util.Locale
import java.util.concurrent.TimeUnit

/** Formats a byte count as `1.2 M`, `640 KB`, ... */
fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "--"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f M", mb)
    return String.format(Locale.US, "%.2f G", mb / 1024.0)
}

/** Formats a transfer rate as `1.2 M/s`. */
fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0L) "--" else "${formatBytes(bytesPerSecond)}/s"

/** Formats a duration given in milliseconds as `1:23` or `1:02:03`. */
fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0:00"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Relative time for the history list. */
fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - timestamp
    if (diff < 0) return "刚刚"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        hours < 24 -> "$hours 小时前"
        days < 30 -> "$days 天前"
        else -> "$days 天前"
    }
}
