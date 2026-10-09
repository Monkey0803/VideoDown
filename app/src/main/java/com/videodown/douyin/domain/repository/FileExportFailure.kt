package com.videodown.douyin.domain.repository

import java.io.IOException

enum class FileExportFailureKind { ACCESS_LOST, SPACE_FULL, OTHER }

/** The chosen tree cannot be used; a new system folder selection is required. */
class FileExportTargetUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Providers may transport errno as an IOException message rather than preserving its type. */
fun fileExportFailureKind(error: Throwable): FileExportFailureKind {
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    val full = Regex("\\b(?:ENOSPC|EDQUOT)\\b|no space left on device|disk quota exceeded", RegexOption.IGNORE_CASE)
    if (causes.any { full.containsMatchIn(it.message.orEmpty()) }) return FileExportFailureKind.SPACE_FULL
    if (causes.any { it is SecurityException || it is FileExportTargetUnavailable }) return FileExportFailureKind.ACCESS_LOST
    return FileExportFailureKind.OTHER
}
