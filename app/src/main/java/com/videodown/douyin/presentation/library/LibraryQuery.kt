package com.videodown.douyin.presentation.library

import com.videodown.douyin.core.LibrarySort
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.VideoPlatform

data class LibraryQuery(
    val text: String = "",
    val platform: VideoPlatform? = null,
    val sort: LibrarySort = LibrarySort.NEWEST,
    val favoritesOnly: Boolean = false,
    val folder: String? = null,
    /** The vault view, which only ever contains private records. */
    val vaultOnly: Boolean = false,
    /** Whether the user has passed the vault gate in this session. */
    val vaultUnlocked: Boolean = false,
) {
    fun apply(records: List<DownloadRecord>): List<DownloadRecord> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val matching = records.filter { record ->
            record.isPrivate == vaultOnly && (!vaultOnly || vaultUnlocked) &&
                (platform == null || record.platform == platform) && (!favoritesOnly || record.favorite) &&
                (folder == null || record.folder == folder) && words.all { word ->
                record.title.contains(word, ignoreCase = true) || record.authorNickname.contains(word, ignoreCase = true)
            }
        }
        return when (sort) {
            LibrarySort.NEWEST -> matching.sortedWith(compareByDescending<DownloadRecord> { it.updatedAt }.thenBy { it.id })
            LibrarySort.OLDEST -> matching.sortedWith(compareBy<DownloadRecord> { it.updatedAt }.thenBy { it.id })
            LibrarySort.LARGEST -> matching.sortedWith(compareByDescending<DownloadRecord> { it.downloadedBytes }.thenByDescending { it.updatedAt }.thenBy { it.id })
            // 手动排过的（sortIndex > 0）按顺序在前，没排过的接在后面——按更新时间降序，
            // 这样刚保存的新视频不会插到用户排好的顺序中间。
            LibrarySort.CUSTOM -> matching.sortedWith(
                compareBy<DownloadRecord> { it.sortIndex == 0L }
                    .thenBy { it.sortIndex }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.id },
            )
        }
    }
}
