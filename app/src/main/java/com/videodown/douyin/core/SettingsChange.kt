package com.videodown.douyin.core

/**
 * One setting a restore actually changed.
 *
 * `apply` reports these instead of sentences: the wording belongs to the settings screen, and the
 * values can be read back from the preferences once the restore has been applied.
 */
enum class SettingsChange {
    THEME_MODE, PREFERRED_QUALITY, LIBRARY_LAYOUT, LIBRARY_SORT, CONCURRENCY, SPEED_LIMIT,
    DYNAMIC_COLOR, WIFI_ONLY, AUTO_RESUME, NOTIFY_ON_COMPLETION, INDIVIDUAL_NOTIFICATIONS,
    CLIPBOARD_DETECT, AUTO_SAVE_TO_GALLERY, SUBTITLES, PLAYLIST_PARSING, UPDATE_SOURCE,
    LIBRARY_ORDER,
}
