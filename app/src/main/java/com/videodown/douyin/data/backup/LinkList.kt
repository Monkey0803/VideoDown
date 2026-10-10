package com.videodown.douyin.data.backup

import com.videodown.douyin.data.remote.VideoLinkExtractor
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.VaultPolicy

/**
 * A plain-text list of the links behind the download history.
 *
 * Plain text on purpose: it can be read, edited and pasted straight into 批量导入, which is the only
 * thing an imported list is used for. Lines starting with `#` are comments and are ignored when
 * reading, so the header does not become a bogus link.
 */
object LinkList {
    const val HEADER = "# 拾影链接清单 v1"  // l10n-ignore: 持久化内容（记录标题/文件名/目录名/画质标签）：随语言改变会让同一内容落到不同名字

    /** The link a record came from, or `null` when it predates source-page tracking. */
    fun sourceUrl(record: DownloadRecord): String? = sequenceOf(
        record.sourcePageUrl,
        record.media?.pageUrl,
        record.sourceUrl,
    ).filterNotNull().firstOrNull { it.startsWith("http://") || it.startsWith("https://") }

    /**
     * One link per line, de-duplicated but in history order.
     *
     * Records without a usable link are skipped rather than written as blank lines.
     */
    fun format(records: List<DownloadRecord>): String = buildString {
        appendLine(HEADER)
        // A private record's page would reveal what the vault holds, so its link never leaves.
        records.filter(VaultPolicy::linkExportable).mapNotNull(::sourceUrl).distinct().forEach { appendLine(it) }
    }

    /** Every link in [text], de-duplicated; comments and prose are tolerated. */
    fun parse(text: String?): List<String> {
        val body = text?.lineSequence()
            ?.filterNot { it.trimStart().startsWith("#") }
            ?.joinToString("\n")
            .orEmpty()
        if (body.isBlank()) return emptyList()
        return VideoLinkExtractor.extractAll(body).map { it.url }.distinct()
    }
}
