package com.videodown.douyin.presentation.viewer

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.library.LibraryQuery
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal val IMAGE_SLIDESHOW_INTERVALS_SECONDS = listOf(3, 5, 10)

internal fun normalizedSlideshowIntervalSeconds(value: Int): Int =
    value.takeIf(IMAGE_SLIDESHOW_INTERVALS_SECONDS::contains) ?: 5

internal data class ImageDateGroup(
    val date: LocalDate,
    val records: List<DownloadRecord>,
)

/** 看图器始终以保存时间分组，避免缺失或错误的拍摄时间改变用户熟悉的库顺序。 */
internal fun imageDateGroups(
    records: List<DownloadRecord>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<ImageDateGroup> = records
    .groupBy { Instant.ofEpochMilli(it.updatedAt).atZone(zoneId).toLocalDate() }
    .map { (date, items) -> ImageDateGroup(date, items) }
    .sortedByDescending(ImageDateGroup::date)

/**
 * 日期网格里选中图片所在的扁平下标（组标题各占一格）。
 *
 * 打开网格时先滚到这里，"快速定位"才真的是定位；找不到选中项时回到顶部。
 */
internal fun imageDateGridIndex(groups: List<ImageDateGroup>, selectedId: String): Int {
    var index = 0
    groups.forEach { group ->
        index++ // 该组的日期标题
        val offset = group.records.indexOfFirst { it.id == selectedId }
        if (offset >= 0) return index + offset
        index += group.records.size
    }
    return 0
}

internal fun canRunSlideshowTimer(
    running: Boolean,
    recordCount: Int,
    overlayOpen: Boolean,
    appStarted: Boolean,
    imageAtBaseScale: Boolean,
): Boolean = running && recordCount > 1 && !overlayOpen && appStarted && imageAtBaseScale

/**
 * 图片中心的候选规则。
 *
 * 入口记录决定浏览的是普通库还是保险箱；两边永不混合。入口文件失效时直接进入缺失态，
 * 不能悄悄跳到另一张图片，让用户误以为点开的仍是原条目。
 *
 * [query] 是进入时「已存」的搜索/筛选/排序：带了它，左右滑动只会在用户刚才看的子集里；
 * 深链、通知等没有列表上下文的入口传 null，退回按保存时间倒序的整库浏览。
 */
internal fun imageViewerRecords(
    records: List<DownloadRecord>,
    initialRecordId: String,
    query: LibraryQuery?,
    fileExists: (DownloadRecord) -> Boolean,
): List<DownloadRecord> {
    val initial = records.firstOrNull { it.id == initialRecordId } ?: return emptyList()
    if (!initial.isImage || initial.status != DownloadStatus.COMPLETED || !fileExists(initial)) return emptyList()
    val scope = records.asSequence()
        .filter { it.isImage && it.status == DownloadStatus.COMPLETED }
        .filter { it.isPrivate == initial.isPrivate }
        .filter(fileExists)
        .toList()
    if (query == null) {
        return scope.sortedWith(
            compareByDescending<DownloadRecord> { it.updatedAt }
                .thenByDescending { it.createdAt }
                .thenBy { it.id },
        )
    }
    // 门禁字段由入口记录的 isPrivate 决定；这里只沿用搜索、筛选与排序。
    return query.ordered(scope.filter { query.matches(it, ignorePrivacy = true) })
}

/** 没有列表上下文的入口（深链、通知、独立预览）：整库浏览。 */
internal fun imageViewerRecords(
    records: List<DownloadRecord>,
    initialRecordId: String,
    fileExists: (DownloadRecord) -> Boolean,
): List<DownloadRecord> = imageViewerRecords(records, initialRecordId, null, fileExists)

/**
 * 按进入页面时固定的分区与列表上下文列出图片。
 *
 * 分区（普通库/保险箱）和搜索/筛选/排序都在进入那一刻固定：删掉入口那张图之后，
 * 仍要能继续浏览同一份列表，而不是掉进"文件不存在"。
 */
internal fun imageViewerRecords(
    records: List<DownloadRecord>,
    privateScope: Boolean,
    query: LibraryQuery?,
    fileExists: (DownloadRecord) -> Boolean,
): List<DownloadRecord> {
    val scope = records.asSequence()
        .filter { it.isImage && it.status == DownloadStatus.COMPLETED }
        .filter { it.isPrivate == privateScope }
        .filter(fileExists)
        .toList()
    if (query == null) {
        return scope.sortedWith(
            compareByDescending<DownloadRecord> { it.updatedAt }
                .thenByDescending { it.createdAt }
                .thenBy { it.id },
        )
    }
    // 门禁字段由 privateScope 决定；这里只沿用搜索、筛选与排序。
    return query.ordered(scope.filter { query.matches(it, ignorePrivacy = true) })
}

/** 按进入页面时确定的库筛选图片；删除入口记录后仍可继续浏览同一库。 */
internal fun imageViewerRecords(
    records: List<DownloadRecord>,
    privateScope: Boolean,
    fileExists: (DownloadRecord) -> Boolean,
): List<DownloadRecord> = imageViewerRecords(records, privateScope, null, fileExists)

/** 删除当前图片后优先前进；当前已经是末尾时退到上一张，最后一张则退出。 */
internal fun imageAfterDelete(records: List<DownloadRecord>, deletedId: String): String? {
    val index = records.indexOfFirst { it.id == deletedId }
    if (index < 0 || records.size <= 1) return null
    return records.getOrNull(index + 1)?.id ?: records.getOrNull(index - 1)?.id
}
