package com.videodown.douyin.presentation.viewer

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus

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
