package com.videodown.douyin.presentation.viewer

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
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
 */
internal fun imageViewerRecords(
    records: List<DownloadRecord>,
    initialRecordId: String,
    fileExists: (DownloadRecord) -> Boolean,
): List<DownloadRecord> {
    val initial = records.firstOrNull { it.id == initialRecordId } ?: return emptyList()
    if (!initial.isImage || initial.status != DownloadStatus.COMPLETED || !fileExists(initial)) return emptyList()
    return imageViewerRecords(records, initial.isPrivate, fileExists)
}

/** 按进入页面时确定的库筛选图片；删除入口记录后仍可继续浏览同一库。 */
internal fun imageViewerRecords(
    records: List<DownloadRecord>,
    privateScope: Boolean,
    fileExists: (DownloadRecord) -> Boolean,
): List<DownloadRecord> {
    return records.asSequence()
        .filter { it.isImage && it.status == DownloadStatus.COMPLETED }
        .filter { it.isPrivate == privateScope }
        .filter(fileExists)
        .sortedWith(
            compareByDescending<DownloadRecord> { it.updatedAt }
                .thenByDescending { it.createdAt }
                .thenBy { it.id },
        )
        .toList()
}

/** 删除当前图片后优先前进；当前已经是末尾时退到上一张，最后一张则退出。 */
internal fun imageAfterDelete(records: List<DownloadRecord>, deletedId: String): String? {
    val index = records.indexOfFirst { it.id == deletedId }
    if (index < 0 || records.size <= 1) return null
    return records.getOrNull(index + 1)?.id ?: records.getOrNull(index - 1)?.id
}
