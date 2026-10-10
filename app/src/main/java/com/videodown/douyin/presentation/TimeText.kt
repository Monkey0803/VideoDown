package com.videodown.douyin.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.core.RelativeTime
import com.videodown.douyin.core.RemainingTime
import kotlin.math.roundToInt

/**
 * The wording for times and progress badges.
 *
 * `core` reports parts (hours/minutes, a fraction) so it stays framework-free; every sentence about
 * them is written here.
 */
@Composable
fun remainingTimeText(value: RemainingTime): String = when (value) {
    is RemainingTime.HoursMinutes -> stringResource(R.string.remaining_hours_minutes, value.hours, value.minutes)
    is RemainingTime.MinutesSeconds -> stringResource(R.string.remaining_minutes_seconds, value.minutes, value.seconds)
    is RemainingTime.Seconds -> stringResource(R.string.remaining_seconds, value.seconds)
}

@Composable
fun relativeTimeText(value: RelativeTime): String = when (value) {
    RelativeTime.JustNow -> stringResource(R.string.relative_just_now)
    is RelativeTime.Minutes -> stringResource(R.string.relative_minutes, value.value)
    is RelativeTime.Hours -> stringResource(R.string.relative_hours, value.value)
    is RelativeTime.Days -> stringResource(R.string.relative_days, value.value)
}

@Composable
fun watchBadgeText(fraction: Float): String = stringResource(R.string.watch_badge_percent, (fraction * 100).roundToInt())

/** The same wording for callers that have a `Context` but no composable scope. */
fun remainingTimeText(context: Context, value: RemainingTime): String = when (value) {
    is RemainingTime.HoursMinutes -> context.getString(R.string.remaining_hours_minutes, value.hours, value.minutes)
    is RemainingTime.MinutesSeconds -> context.getString(R.string.remaining_minutes_seconds, value.minutes, value.seconds)
    is RemainingTime.Seconds -> context.getString(R.string.remaining_seconds, value.seconds)
}

fun relativeTimeText(context: Context, value: RelativeTime): String = when (value) {
    RelativeTime.JustNow -> context.getString(R.string.relative_just_now)
    is RelativeTime.Minutes -> context.getString(R.string.relative_minutes, value.value)
    is RelativeTime.Hours -> context.getString(R.string.relative_hours, value.value)
    is RelativeTime.Days -> context.getString(R.string.relative_days, value.value)
}

fun watchBadgeText(context: Context, fraction: Float): String =
    context.getString(R.string.watch_badge_percent, (fraction * 100).roundToInt())

/** The wording for a subtitle selection, e.g. `中文、英文` or `自动字幕 · 英文`. */
@Composable
fun subtitleSelectionText(selection: com.videodown.douyin.domain.model.SubtitleSelection): String {
    if (!selection.available) return ""
    val separator = stringResource(R.string.list_separator)
    // `joinToString` takes a plain lambda, so the name is resolved before joining.
    val names = selection.languages.map { subtitleLanguageText(it) }.joinToString(separator)
    return if (selection.automatic) stringResource(R.string.subtitle_automatic_prefix, names) else names
}

@Composable
fun subtitleLanguageText(language: String): String = when (com.videodown.douyin.domain.model.SubtitleLanguageSelection.displayKey(language)) {
    com.videodown.douyin.domain.model.SubtitleLanguageKey.TRADITIONAL_CHINESE -> stringResource(R.string.subtitle_language_traditional_chinese)
    com.videodown.douyin.domain.model.SubtitleLanguageKey.CHINESE -> stringResource(R.string.subtitle_language_chinese)
    com.videodown.douyin.domain.model.SubtitleLanguageKey.ENGLISH -> stringResource(R.string.subtitle_language_english)
    com.videodown.douyin.domain.model.SubtitleLanguageKey.JAPANESE -> stringResource(R.string.subtitle_language_japanese)
    com.videodown.douyin.domain.model.SubtitleLanguageKey.KOREAN -> stringResource(R.string.subtitle_language_korean)
    // An unrecognised tag is shown as the source gives it; there is nothing to translate.
    com.videodown.douyin.domain.model.SubtitleLanguageKey.OTHER -> language
}
