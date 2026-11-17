package com.videodown.douyin.presentation.settings

import com.videodown.douyin.core.AppPreferences
import com.videodown.douyin.core.SettingsChange
import com.videodown.douyin.data.backup.SavedOrder
import com.videodown.douyin.data.backup.SettingsBackupCodec
import com.videodown.douyin.data.backup.SettingsSnapshot
import com.videodown.douyin.domain.repository.DownloadHistoryRepository

/**
 * 设置备份的导出与恢复：偏好设置和已存顺序要一起走。
 *
 * 顺序不是偏好设置——它挂在记录上（`DownloadRecord.sortIndex`）——所以两个方向都要额外读一次库：
 * 导出时把手动排过的那段写进文件，恢复时按本机记录落位，并把 [SettingsChange.LIBRARY_ORDER] 补进
 * 返回值（否则一次「只改了顺序」的恢复会被报成"备份里的设置与当前一致"）。
 *
 * 备份里的 id 在本机一个都不认识时（换机恢复）顺序保持原样，也不报这条改动；记录本身不随这份
 * 备份走，见 [SavedOrder]。
 */

/** 编码一份设置备份，含已存列表的手动顺序；从没排过时文件里不带顺序。 */
internal suspend fun encodeSettingsBackup(
    preferences: AppPreferences,
    history: DownloadHistoryRepository,
): String = SettingsBackupCodec.encode(
    preferences.snapshot(savedOrder = SavedOrder.of(history.snapshot())),
)

/** 落实一份设置备份：先写偏好设置，再按本机记录恢复已存顺序，返回实际改动的项。 */
internal suspend fun applySettingsBackup(
    snapshot: SettingsSnapshot,
    preferences: AppPreferences,
    history: DownloadHistoryRepository,
): List<SettingsChange> {
    val applied = preferences.apply(snapshot).toMutableList()
    val records = history.snapshot()
    SavedOrder.restorableOrder(records, snapshot.savedOrder)?.let { order ->
        history.reorderLibrary(order)
        applied += SettingsChange.LIBRARY_ORDER
    }
    return applied
}

/**
 * 在真正写入前计算会发生的变化，供恢复确认框展示。
 *
 * 这里只比较备份实际携带且清洗后仍有效的字段；缺失或非法字段继续保持当前值。
 */
internal fun previewSettingsBackup(
    incoming: SettingsSnapshot,
    current: SettingsSnapshot,
    libraryOrderChanges: Boolean,
): List<SettingsChange> {
    val next = incoming.sanitized()
    val changes = mutableListOf<SettingsChange>()
    fun <T> changed(value: T?, currentValue: T?, change: SettingsChange) {
        if (value != null && value != currentValue) changes += change
    }
    changed(next.themeMode, current.themeMode, SettingsChange.THEME_MODE)
    changed(next.preferredQuality, current.preferredQuality, SettingsChange.PREFERRED_QUALITY)
    changed(next.libraryLayout, current.libraryLayout, SettingsChange.LIBRARY_LAYOUT)
    changed(next.librarySort, current.librarySort, SettingsChange.LIBRARY_SORT)
    changed(next.maxConcurrentDownloads, current.maxConcurrentDownloads, SettingsChange.CONCURRENCY)
    changed(next.globalSpeedLimitKbps, current.globalSpeedLimitKbps, SettingsChange.SPEED_LIMIT)
    changed(next.dynamicColor, current.dynamicColor, SettingsChange.DYNAMIC_COLOR)
    changed(next.wifiOnly, current.wifiOnly, SettingsChange.WIFI_ONLY)
    changed(next.autoResume, current.autoResume, SettingsChange.AUTO_RESUME)
    changed(next.notifyOnCompletion, current.notifyOnCompletion, SettingsChange.NOTIFY_ON_COMPLETION)
    changed(next.individualTaskNotifications, current.individualTaskNotifications, SettingsChange.INDIVIDUAL_NOTIFICATIONS)
    changed(next.clipboardDetect, current.clipboardDetect, SettingsChange.CLIPBOARD_DETECT)
    changed(next.autoSaveToGallery, current.autoSaveToGallery, SettingsChange.AUTO_SAVE_TO_GALLERY)
    changed(next.subtitlesEnabled, current.subtitlesEnabled, SettingsChange.SUBTITLES)
    changed(next.playlistParsing, current.playlistParsing, SettingsChange.PLAYLIST_PARSING)
    changed(next.updateManifestUrl, current.updateManifestUrl, SettingsChange.UPDATE_SOURCE)
    if (libraryOrderChanges) changes += SettingsChange.LIBRARY_ORDER
    return changes
}
