package com.videodown.douyin.presentation.library

import com.videodown.douyin.core.LibrarySort
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.VideoPlatform

data class LibraryQuery(
    val text: String = "",
    val platform: VideoPlatform? = null,
    /** 只看某一类媒体（视频/音频/图片/压缩包）；`null` 为全部。 */
    val mediaKind: MediaKind? = null,
    val sort: LibrarySort = LibrarySort.NEWEST,
    val favoritesOnly: Boolean = false,
    val folder: String? = null,
    /** The vault view, which only ever contains private records. */
    val vaultOnly: Boolean = false,
    /** Whether the user has passed the vault gate in this session. */
    val vaultUnlocked: Boolean = false,
) {
    /** 搜索框之外启用了多少个筛选条件；排序不算筛选。 */
    val activeFilterCount: Int
        get() = listOfNotNull(
            platform,
            mediaKind,
            folder,
            true.takeIf { favoritesOnly },
            true.takeIf { vaultOnly },
        ).size

    val hasActiveFilters: Boolean
        get() = text.isNotBlank() || activeFilterCount > 0 || sort != LibrarySort.NEWEST

    fun clearFilters(): LibraryQuery = copy(
        text = "",
        platform = null,
        mediaKind = null,
        sort = LibrarySort.NEWEST,
        favoritesOnly = false,
        folder = null,
        vaultOnly = false,
    )

    /**
     * 顺序是否由用户决定：只有「自定义排序 + 没有任何搜索/过滤」。
     *
     * 其余档位的先后是规则算出来的（最新/最早/最大），拖了没有意义；被搜索或过滤筛过的子集也不代表
     * 整个列表的顺序，拖它会让用户无法预测到底重排了什么。切到自定义排序时要不要按当前顺序 seed 一次，
     * 用的也是这一条。
     */
    val ordersManually: Boolean
        get() = sort == LibrarySort.CUSTOM && text.isBlank() && platform == null && folder == null &&
            mediaKind == null && !favoritesOnly && !vaultOnly

    /**
     * 拖拽把手是否出现、能不能拖。
     *
     * 在 [ordersManually] 之外还要排除多选与批量操作：那时列表正被"挑出来处理"，拖动的结果既不是
     * 用户想固定的顺序，也可能和正在跑的任务打架。
     */
    fun canReorder(selecting: Boolean, batchBusy: Boolean): Boolean = ordersManually && !selecting && !batchBusy

    /**
     * 单条记录是否满足搜索与筛选。
     *
     * [ignorePrivacy] 供看图器使用：它进入时已按入口记录固定普通库/保险箱，不能再被这里的
     * 门禁字段筛掉，否则从通知或深链打开私密图片会得到空列表。
     */
    fun matches(record: DownloadRecord, ignorePrivacy: Boolean = false): Boolean {
        val privacyMatches = if (ignorePrivacy) true else
            record.isPrivate == vaultOnly && (!vaultOnly || vaultUnlocked)
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return privacyMatches &&
            (platform == null || record.platform == platform) &&
            (mediaKind == null || record.mediaKind == mediaKind) &&
            (!favoritesOnly || record.favorite) &&
            (folder == null || record.folder == folder) &&
            words.all { word ->
                record.title.contains(word, ignoreCase = true) ||
                    record.authorNickname.contains(word, ignoreCase = true)
            }
    }

    /** 应用搜索与筛选，再按当前排序返回。 */
    fun apply(records: List<DownloadRecord>): List<DownloadRecord> =
        ordered(records.filter { matches(it) })

    /** 只排序不过滤：看图器沿用已存的排序方式，但筛选已经由 [matches] 完成。 */
    fun ordered(records: List<DownloadRecord>): List<DownloadRecord> = when (sort) {
        LibrarySort.NEWEST -> records.sortedWith(
            compareByDescending<DownloadRecord> { it.updatedAt }.thenBy { it.id },
        )
        LibrarySort.OLDEST -> records.sortedWith(
            compareBy<DownloadRecord> { it.updatedAt }.thenBy { it.id },
        )
        LibrarySort.LARGEST -> records.sortedWith(
            compareByDescending<DownloadRecord> { it.downloadedBytes }
                .thenByDescending { it.updatedAt }
                .thenBy { it.id },
        )
        // 手动排过的（sortIndex > 0）按顺序在前，没排过的接在后面——按更新时间降序，
        // 这样刚保存的新视频不会插到用户排好的顺序中间。
        LibrarySort.CUSTOM -> records.sortedWith(
            compareBy<DownloadRecord> { it.sortIndex == 0L }
                .thenBy { it.sortIndex }
                .thenByDescending { it.updatedAt }
                .thenBy { it.id },
        )
    }
}
