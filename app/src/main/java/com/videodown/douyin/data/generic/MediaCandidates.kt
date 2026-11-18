package com.videodown.douyin.data.generic

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 网页候选按「同一份媒体」聚合之后的一项。
 *
 * 为什么需要聚合：站点常为同一张图给出多个格式/尺寸的 URL（X 就是 `?format=png`、`?format=jpg`、
 * `?name=large` 各来一条）。面板若按 URL 平铺，用户看到的就是四行只有扩展名不同的「图片」，
 * 既分不清是哪张图，也不知道该点哪个。聚合后一行代表一份媒体，格式作为行内可选项。
 */
data class CandidateGroup(
    /** 归一化身份：视频就是完整 URL，图片是「主机 + 去扩展名路径 + 与格式无关的 query」。 */
    val id: String,
    /** 默认下载项：面积最大、其次格式质量最好、其次字节数最大。 */
    val representative: BrowserMediaDetector.Candidate,
    /** 每种扩展名保留一个最佳变体，按格式质量排序；视频只有一项。 */
    val variants: List<BrowserMediaDetector.Candidate>,
    /** 缩略图用小体积变体，避免为 56dp 预览下载整张原图。 */
    val thumbnail: BrowserMediaDetector.Candidate,
) {
    val isImage: Boolean get() = representative.isImage

    /** 按扩展名挑变体；找不到时回落到代表项。 */
    fun variantOf(extension: String?): BrowserMediaDetector.Candidate =
        variants.firstOrNull { it.extension?.equals(extension, ignoreCase = true) == true } ?: representative
}

/**
 * 只影响「同一份资源的编码/尺寸」的 query 参数，不参与身份判定。
 *
 * 这些参数在 X、微博、B 站图床上都只是格式或尺寸开关；把它们算进身份就会把同一张图拆成多条。
 * 其它参数（例如 `id`）仍然保留，避免把两个不同资源误合并。
 */
private val FORMAT_ONLY_PARAMS = setOf(
    "format", "fm", "name", "w", "h", "q", "quality", "size", "fit", "auto", "width", "height", "resize", "dpr",
)

private val IMAGE_EXTENSION_SUFFIX = Regex("""\.(jpe?g|png|webp|gif|avif|heic|bmp)$""", RegexOption.IGNORE_CASE)

private const val GROUP_PREFIX_IMAGE = "image|"
private const val GROUP_PREFIX_VIDEO = "video|"

/** 视频永远按完整 URL 独立成组：不同清晰度是不同资源，合并没有意义。 */
fun groupCandidates(candidates: List<BrowserMediaDetector.Candidate>): List<CandidateGroup> {
    val buckets = LinkedHashMap<String, MutableList<BrowserMediaDetector.Candidate>>()
    for (candidate in candidates) {
        buckets.getOrPut(identityOf(candidate)) { mutableListOf() }.add(candidate)
    }
    val groups = buckets.map { (id, items) ->
        if (items.first().isImage) imageGroup(id, items) else videoGroup(id, items.first())
    }
    val videos = groups.filterNot { it.isImage }
    val images = groups.filter { it.isImage }
        .sortedWith(compareByDescending<CandidateGroup> { areaOf(it.representative) }.thenBy { it.id })
    return videos + images
}

/** 归一化身份。图片去扩展名并丢弃纯格式参数；视频保持 URL 原样。 */
internal fun identityOf(candidate: BrowserMediaDetector.Candidate): String {
    if (!candidate.isImage) return GROUP_PREFIX_VIDEO + candidate.url
    val parsed = candidate.url.toHttpUrlOrNull() ?: return GROUP_PREFIX_IMAGE + candidate.url
    val path = parsed.encodedPath.replace(IMAGE_EXTENSION_SUFFIX, "")
    val query = parsed.queryParameterNames
        .filterNot { it.lowercase() in FORMAT_ONLY_PARAMS }
        .sorted()
        .joinToString("&") { name -> "$name=${parsed.queryParameter(name).orEmpty()}" }
    return buildString {
        append(GROUP_PREFIX_IMAGE).append(parsed.host.lowercase()).append(path)
        if (query.isNotEmpty()) append('|').append(query)
    }
}

private fun imageGroup(id: String, items: List<BrowserMediaDetector.Candidate>): CandidateGroup {
    val byExtension = LinkedHashMap<String, BrowserMediaDetector.Candidate>()
    for (candidate in items) {
        val extension = extensionOf(candidate)
        val existing = byExtension[extension]
        if (existing == null || compareQuality(candidate, existing) < 0) byExtension[extension] = candidate
    }
    val variants = byExtension.values.sortedWith(
        compareByDescending<BrowserMediaDetector.Candidate> { formatRank(it) }
            .thenByDescending { areaOf(it) }
            .thenByDescending { it.sizeBytes ?: 0L }
            .thenBy { it.url },
    )
    val representative = variants.minWithOrNull(QUALITY_ORDER) ?: items.first()
    // 缩略图优先取已知最小字节数的变体；全都没有大小时回落到代表项。
    val thumbnail = variants.filter { (it.sizeBytes ?: 0L) > 0L }.minByOrNull { it.sizeBytes ?: Long.MAX_VALUE }
        ?: representative
    return CandidateGroup(id, representative, variants, thumbnail)
}

private fun videoGroup(id: String, candidate: BrowserMediaDetector.Candidate) =
    CandidateGroup(id, candidate, listOf(candidate), candidate)

/** 越小越「好」：面积大、格式质量高、字节数大。 */
private val QUALITY_ORDER = compareByDescending<BrowserMediaDetector.Candidate> { areaOf(it) }
    .thenByDescending { formatRank(it) }
    .thenByDescending { it.sizeBytes ?: 0L }
    .thenBy { it.url }

private fun compareQuality(left: BrowserMediaDetector.Candidate, right: BrowserMediaDetector.Candidate): Int =
    QUALITY_ORDER.compare(left, right)

private fun areaOf(candidate: BrowserMediaDetector.Candidate): Long =
    (candidate.width ?: 0).toLong() * (candidate.height ?: 0).toLong()

/** PNG 无损排最前，其次 WebP，再有损 JPEG；未知格式垫底。 */
private fun formatRank(candidate: BrowserMediaDetector.Candidate): Int = when (extensionOf(candidate)) {
    "png" -> 3
    "webp" -> 2
    "jpg", "jpeg" -> 1
    else -> 0
}

private fun extensionOf(candidate: BrowserMediaDetector.Candidate): String =
    (candidate.extension ?: candidate.url.substringBefore('?').substringAfterLast('.', "")).lowercase()
