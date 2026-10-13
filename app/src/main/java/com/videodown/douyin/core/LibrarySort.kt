package com.videodown.douyin.core

/**
 * 已存列表的排序方式。
 *
 * [CUSTOM] 是用户手动拖出来的顺序，键是 [com.videodown.douyin.domain.model.DownloadRecord.sortIndex]。
 * 放在 core 而不是 presentation，是因为它要被 [AppPreferences] 持久化——与 [ThemeMode]、[PreferredQuality] 同一惯例。
 */
enum class LibrarySort { NEWEST, OLDEST, LARGEST, CUSTOM }
