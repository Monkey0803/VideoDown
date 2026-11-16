package com.videodown.douyin.domain.model

import kotlinx.serialization.Serializable

/**
 * 归档任务里的一张图片。
 *
 * URL 会过期，所以重新解析时按列表顺序整体替换即可——ZIP 里的条目名只依赖下标，不依赖 URL。
 * 大小未知时保留 `null`，归档任务用「已知大小之和」做进度与空间预估。
 */
@Serializable
data class ArchiveEntry(
    val url: String,
    val extension: String = MediaKind.IMAGE.defaultExtension,
    /** CDN 防盗链需要的来源页；为空时用品台 referer。 */
    val referer: String? = null,
    val sizeBytes: Long? = null,
)
