package com.videodown.douyin.data.playback

import java.io.File

/**
 * A subtitle file sitting next to a downloaded video.
 *
 * Downloads keep only the media file, so a user who wants subtitles drops a matching `.srt` /
 * `.vtt` / `.ass` beside it. Embedded text tracks need nothing here — the player renders those on
 * its own.
 */
data class SubtitleSidecar(val file: File, val mimeType: String)

object SubtitleSidecarResolver {
    /** Extension to MIME type; the same list decides what is looked for. */
    private val mimeTypes = mapOf(
        "srt" to "application/x-subrip",
        "vtt" to "text/vtt",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
    )

    /**
     * The sidecar for [video], or `null` when there is none.
     *
     * Matched case-insensitively on the full file name (`clip.mp4` + `clip.SRT`), which is how
     * external players pair them. A missing or unreadable directory simply means no subtitles.
     */
    fun find(video: File): SubtitleSidecar? {
        val parent = video.parentFile ?: return null
        val baseName = video.name.substringBeforeLast('.', video.name)
        if (baseName.isEmpty()) return null
        val candidates = parent.listFiles() ?: return null
        return candidates
            .filter { it.isFile }
            .firstNotNullOfOrNull { candidate ->
                val extension = candidate.extension.lowercase()
                val mime = mimeTypes[extension] ?: return@firstNotNullOfOrNull null
                val candidateBase = candidate.name.substringBeforeLast('.', candidate.name)
                if (candidateBase.equals(baseName, ignoreCase = true)) SubtitleSidecar(candidate, mime) else null
            }
    }
}
