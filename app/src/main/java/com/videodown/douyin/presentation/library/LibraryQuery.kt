package com.videodown.douyin.presentation.library

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.VideoPlatform

enum class LibrarySort(val label: String) { NEWEST("最新保存"), OLDEST("最早保存"), LARGEST("文件最大") }

data class LibraryQuery(val text: String = "", val platform: VideoPlatform? = null, val sort: LibrarySort = LibrarySort.NEWEST, val favoritesOnly: Boolean = false, val folder: String? = null) {
    fun apply(records: List<DownloadRecord>): List<DownloadRecord> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val matching = records.filter { record ->
            (platform == null || record.platform == platform) && (!favoritesOnly || record.favorite) &&
                (folder == null || record.folder == folder) && words.all { word ->
                record.title.contains(word, ignoreCase = true) || record.authorNickname.contains(word, ignoreCase = true)
            }
        }
        return when (sort) {
            LibrarySort.NEWEST -> matching.sortedWith(compareByDescending<DownloadRecord> { it.updatedAt }.thenBy { it.id })
            LibrarySort.OLDEST -> matching.sortedWith(compareBy<DownloadRecord> { it.updatedAt }.thenBy { it.id })
            LibrarySort.LARGEST -> matching.sortedWith(compareByDescending<DownloadRecord> { it.downloadedBytes }.thenByDescending { it.updatedAt }.thenBy { it.id })
        }
    }
}
