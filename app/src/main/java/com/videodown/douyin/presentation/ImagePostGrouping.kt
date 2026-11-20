package com.videodown.douyin.presentation

import com.videodown.douyin.domain.model.DownloadRecord

/**
 * 一次解析出来的多张图片共享同一个原帖地址。
 *
 * 没有原帖地址时用记录 id 兜底（等于不分组），而不是 awemeId：旧记录和测试夹具的 awemeId
 * 经常是同一个占位值，拿它分组会把无关图片误并成一组。
 */
internal fun imagePostKey(record: DownloadRecord): String =
    record.sourcePageUrl.ifBlank { record.id }

/** 每个作品在当前集合里有多少张图片；用于已存的"N 张"角标。 */
internal fun imagePostSizes(records: List<DownloadRecord>): Map<String, Int> =
    records.asSequence()
        .filter { it.isImage }
        .groupingBy(::imagePostKey)
        .eachCount()

/** 当前图片是作品里的第几张（从 1 开始）；单张作品返回 null。 */
internal fun imagePostPosition(records: List<DownloadRecord>, record: DownloadRecord): Pair<Int, Int>? {
    if (!record.isImage) return null
    val group = records.filter { it.isImage && imagePostKey(it) == imagePostKey(record) }
    if (group.size < 2) return null
    val position = group.indexOfFirst { it.id == record.id }
    return if (position < 0) null else (position + 1) to group.size
}
