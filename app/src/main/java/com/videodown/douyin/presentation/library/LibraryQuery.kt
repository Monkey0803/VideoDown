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
    /**
     * 顺序是否由用户决定：只有「自定义排序 + 没有任何搜索/过滤」。
     *
     * 其余档位的先后是规则算出来的（最新/最早/最大），拖了没有意义；被搜索或过滤筛过的子集也不代表
     * 整个列表的顺序，拖它会让用户无法预测到底重排了什么。切到自定义排序时要不要按当前顺序 seed 一次，
     * 用的也是这一条。
     */
    val ordersManually: Boolean
        get() = sort == LibrarySort.CUSTOM && text.isBlank() && platform == null && folder == null &&
            !favoritesOnly && !vaultOnly

    /**
     * 拖拽把手是否出现、能不能拖。
     *
     * 在 [ordersManually] 之外还要排除多选与批量操作：那时列表正被"挑出来处理"，拖动的结果既不是
     * 用户想固定的顺序，也可能和正在跑的任务打架。
     */
    fun canReorder(selecting: Boolean, batchBusy: Boolean): Boolean = ordersManually && !selecting && !batchBusy

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
