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
