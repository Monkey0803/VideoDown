package com.videodown.douyin.core

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.WatchProgress
import java.io.File

/**
 * A watched badge for a saved record.
 *
 * Carries the fraction only; the screen words it, so the badge text follows the app language.
 */
data class WatchBadge(val fraction: Float)

/**
 * Resolves the badge for [record], or `null` when showing one would mislead.
 *
 * A stored position is only used when it still describes this exact file: a re-downloaded or
 * re-encoded file keeps its record id but changes size and modification time, and a bar for the old
 * file would send the user to the wrong place.
 */
fun watchBadge(record: DownloadRecord, watch: Map<String, WatchProgress>): WatchBadge? {
    val progress = watch[record.id] ?: return null
    val file = record.filePath?.let(::File) ?: return null
    if (!file.isFile) return null
    if (!progress.matchesFile(file.length(), file.lastModified())) return null
    val fraction = progress.fraction() ?: return null
    return WatchBadge(fraction)
}
