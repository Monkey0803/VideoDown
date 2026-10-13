package com.videodown.douyin.presentation

import com.videodown.douyin.core.LibrarySort
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.core.AppLanguage
import com.videodown.douyin.core.AppPreferences
import com.videodown.douyin.core.LibraryLayout
import com.videodown.douyin.core.PreferredQuality
import com.videodown.douyin.core.SettingsChange
import com.videodown.douyin.core.ThemeMode
import com.videodown.douyin.presentation.history.TaskSort

/**
 * The wording for every enum the UI shows.
 *
 * `core` and `domain` keep the values (they carry behaviour and are framework-free); this file is
 * the single place that turns them into text, so a new value cannot ship without a translation.
 */
internal fun videoPlatformLabelRes(platform: com.videodown.douyin.domain.model.VideoPlatform): Int = when (platform) {
    com.videodown.douyin.domain.model.VideoPlatform.DOUYIN -> R.string.platform_douyin
    com.videodown.douyin.domain.model.VideoPlatform.WEIBO -> R.string.platform_weibo
    com.videodown.douyin.domain.model.VideoPlatform.KUAISHOU -> R.string.platform_kuaishou
    com.videodown.douyin.domain.model.VideoPlatform.X -> R.string.platform_x
    com.videodown.douyin.domain.model.VideoPlatform.GENERIC -> R.string.platform_generic
}

internal fun themeModeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_mode_system
    ThemeMode.LIGHT -> R.string.theme_mode_light
    ThemeMode.DARK -> R.string.theme_mode_dark
}

internal fun libraryLayoutLabelRes(layout: LibraryLayout): Int = when (layout) {
    LibraryLayout.WATERFALL -> R.string.layout_waterfall
    LibraryLayout.LIST -> R.string.layout_list
}

/**
 * 语言名一律用「自己的写法」（简体中文 / English），两种语言文件里都一样：
 * 看不懂当前界面语言的人才找得到自己要选的那一项。
 */
internal fun appLanguageLabelRes(language: AppLanguage): Int = when (language) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.CHINESE -> R.string.settings_language_chinese
    AppLanguage.ENGLISH -> R.string.settings_language_english
}

internal fun preferredQualityLabelRes(quality: PreferredQuality): Int = when (quality) {
    PreferredQuality.HIGHEST -> R.string.quality_highest
    PreferredQuality.STANDARD -> R.string.quality_standard
    PreferredQuality.SAVER -> R.string.quality_saver
}

internal fun librarySortLabelRes(sort: LibrarySort): Int = when (sort) {
    LibrarySort.NEWEST -> R.string.sort_newest
    LibrarySort.OLDEST -> R.string.sort_oldest
    LibrarySort.LARGEST -> R.string.sort_largest
    // 手动拖拽出来的顺序；只有在这一档才显示拖拽把手。
    LibrarySort.CUSTOM -> R.string.sort_custom
}

internal fun taskSortLabelRes(sort: TaskSort): Int = when (sort) {
    TaskSort.STATUS -> R.string.task_sort_status
    TaskSort.RECENT -> R.string.task_sort_recent
    TaskSort.OLDEST -> R.string.task_sort_oldest
    TaskSort.LARGEST -> R.string.task_sort_largest
}

/**
 * The resource for a stored limit, or `null` when the rate is shown as a raw number.
 *
 * Kept separate so a unit test can assert the "unlimited" wording exists without rendering.
 */
internal fun speedLimitRes(kbps: Int): Int? = if (kbps <= 0) R.string.speed_unlimited else null

/** A stored KB/s value: an offered option, or its raw rate rather than hiding it. */
@Composable
fun speedLimitText(kbps: Int): String =
    speedLimitRes(kbps)?.let { stringResource(it) } ?: speedLimitRate(kbps)

/** The same, for callers that have a `Context` but no composable scope. */
fun speedLimitText(context: Context, kbps: Int): String =
    speedLimitRes(kbps)?.let { context.getString(it) } ?: speedLimitRate(kbps)

/**
 * Rates are numbers plus ASCII units, so they need no translation.
 *
 * Only the values the picker actually offers are shown in MB/s; anything else (an older stored
 * value, or one written by a future build) keeps its exact KB/s number rather than being rounded
 * into a prettier but less accurate label.
 */
internal fun speedLimitRate(kbps: Int): String = when {
    kbps in com.videodown.douyin.domain.model.speedLimitOptions && kbps >= 1024 -> "${kbps / 1024} MB/s"
    else -> "$kbps KB/s"
}

/** One line of the "restored" report; the values are read back from [preferences]. */
@Composable
fun settingsChangeText(change: SettingsChange, preferences: AppPreferences): String = when (change) {
    SettingsChange.THEME_MODE -> stringResource(R.string.settings_change_theme, stringResource(themeModeLabelRes(preferences.themeMode)))
    SettingsChange.PREFERRED_QUALITY -> stringResource(R.string.settings_change_quality, stringResource(preferredQualityLabelRes(preferences.preferredQuality)))
    SettingsChange.LIBRARY_LAYOUT -> stringResource(libraryLayoutLabelRes(preferences.libraryLayout))
    SettingsChange.LIBRARY_SORT -> stringResource(librarySortLabelRes(preferences.librarySort))
    SettingsChange.CONCURRENCY -> stringResource(R.string.settings_concurrency_count, preferences.maxConcurrentDownloads)
    SettingsChange.SPEED_LIMIT -> stringResource(R.string.settings_change_speed, speedLimitText(preferences.globalSpeedLimitKbps))
    SettingsChange.DYNAMIC_COLOR -> stringResource(R.string.settings_dynamic_color)
    SettingsChange.WIFI_ONLY -> stringResource(R.string.settings_wifi)
    SettingsChange.AUTO_RESUME -> stringResource(R.string.settings_auto_resume)
    SettingsChange.NOTIFY_ON_COMPLETION -> stringResource(R.string.settings_notify_row)
    SettingsChange.INDIVIDUAL_NOTIFICATIONS -> stringResource(R.string.settings_individual_row)
    SettingsChange.CLIPBOARD_DETECT -> stringResource(R.string.settings_clipboard_row)
    SettingsChange.AUTO_SAVE_TO_GALLERY -> stringResource(R.string.settings_gallery_row)
    SettingsChange.SUBTITLES -> stringResource(R.string.home_subtitles)
    SettingsChange.PLAYLIST_PARSING -> stringResource(R.string.settings_playlist_row)
    SettingsChange.UPDATE_SOURCE -> stringResource(R.string.settings_update_row)
}

/** The same wording for callers with a `Context` but no composable scope. */
fun settingsChangeText(context: Context, change: SettingsChange, preferences: AppPreferences): String = when (change) {
    SettingsChange.THEME_MODE -> context.getString(R.string.settings_change_theme, context.getString(themeModeLabelRes(preferences.themeMode)))
    SettingsChange.PREFERRED_QUALITY -> context.getString(R.string.settings_change_quality, context.getString(preferredQualityLabelRes(preferences.preferredQuality)))
    SettingsChange.LIBRARY_LAYOUT -> context.getString(libraryLayoutLabelRes(preferences.libraryLayout))
    SettingsChange.LIBRARY_SORT -> context.getString(librarySortLabelRes(preferences.librarySort))
    SettingsChange.CONCURRENCY -> context.getString(R.string.settings_concurrency_count, preferences.maxConcurrentDownloads)
    SettingsChange.SPEED_LIMIT -> context.getString(R.string.settings_change_speed, speedLimitText(context, preferences.globalSpeedLimitKbps))
    SettingsChange.DYNAMIC_COLOR -> context.getString(R.string.settings_dynamic_color)
    SettingsChange.WIFI_ONLY -> context.getString(R.string.settings_wifi)
    SettingsChange.AUTO_RESUME -> context.getString(R.string.settings_auto_resume)
    SettingsChange.NOTIFY_ON_COMPLETION -> context.getString(R.string.settings_notify_row)
    SettingsChange.INDIVIDUAL_NOTIFICATIONS -> context.getString(R.string.settings_individual_row)
    SettingsChange.CLIPBOARD_DETECT -> context.getString(R.string.settings_clipboard_row)
    SettingsChange.AUTO_SAVE_TO_GALLERY -> context.getString(R.string.settings_gallery_row)
    SettingsChange.SUBTITLES -> context.getString(R.string.home_subtitles)
    SettingsChange.PLAYLIST_PARSING -> context.getString(R.string.settings_playlist_row)
    SettingsChange.UPDATE_SOURCE -> context.getString(R.string.settings_update_row)
}

/** The platform name for callers that have a `Context` but no composable scope. */
fun platformName(context: Context, platform: com.videodown.douyin.domain.model.VideoPlatform): String =
    context.getString(videoPlatformLabelRes(platform))
