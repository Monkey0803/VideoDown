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

/**
 * An estimate of the time left, or `null` when one cannot be made.
 *
 * Reported as parts rather than as a sentence, because the wording belongs to the screen. Never
 * guesses: an unknown remaining size or a stalled transfer yields no estimate at all, which is
 * better than showing a number that keeps jumping or is simply wrong.
 */
sealed interface RemainingTime {
    data class HoursMinutes(val hours: Long, val minutes: Long) : RemainingTime
    data class MinutesSeconds(val minutes: Long, val seconds: Long) : RemainingTime
    data class Seconds(val seconds: Long) : RemainingTime
}

fun remainingTime(remainingBytes: Long, bytesPerSecond: Long): RemainingTime? {
    if (remainingBytes <= 0L || bytesPerSecond <= 0L) return null
    // Round up without overflowing on a large remaining size.
    val seconds = remainingBytes / bytesPerSecond + if (remainingBytes % bytesPerSecond > 0L) 1L else 0L
    if (seconds <= 0L) return null
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> RemainingTime.HoursMinutes(hours, minutes)
        minutes > 0 -> RemainingTime.MinutesSeconds(minutes, seconds % 60)
        else -> RemainingTime.Seconds(seconds)
    }
}

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

/** How long ago something happened, in the unit the history list shows. */
sealed interface RelativeTime {
    data object JustNow : RelativeTime
    data class Minutes(val value: Long) : RelativeTime
    data class Hours(val value: Long) : RelativeTime
    data class Days(val value: Long) : RelativeTime
}

fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): RelativeTime {
    val diff = now - timestamp
    if (diff < 0) return RelativeTime.JustNow
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> RelativeTime.JustNow
        minutes < 60 -> RelativeTime.Minutes(minutes)
        hours < 24 -> RelativeTime.Hours(hours)
        else -> RelativeTime.Days(days)
    }
}
