package com.videodown.douyin.data.download

import android.content.Context
import android.os.Environment
import com.videodown.douyin.domain.model.DownloadRecord
import java.io.File

/**
 * Resolves where downloaded videos live.
 *
 * Files go to app-scoped external storage (`Android/data/<pkg>/files/Movies/Douyin`), which
 * needs no runtime permission on any supported API level and stays reachable by
 * `FileProvider` for playback and "open with" handoff.
 */
class DownloadPaths private constructor(private val baseDirectory: () -> File) {
    constructor(context: Context) : this({
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    })

    internal constructor(directory: File) : this({ directory })

    val downloadDir: File
        get() {
            return File(baseDirectory(), "Douyin").apply { if (!exists()) mkdirs() }
        }

    fun engineDirectory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]+")))
        return File(downloadDir, "engine-$id")
    }

    fun finalFileFor(record: DownloadRecord): File = File(downloadDir, record.fileName)

    fun partialFileFor(record: DownloadRecord): File = File(downloadDir, "${record.fileName}.part")

    fun buildFileName(title: String, resolutionLabel: String, awemeId: String): String {
        val safeTitle = title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
            .ifBlank { "douyin_video" }
        val safeResolution = resolutionLabel
            .replace(Regex("[^A-Za-z0-9pP]"), "")
            .ifBlank { "video" }
        val safeId = awemeId.replace(Regex("[^A-Za-z0-9_-]"), "").take(100).ifBlank { "video" }
        return "${safeTitle}_${safeResolution}_$safeId.mp4"
    }

    /** Path used by `FileProvider` for the given absolute file path. */
    fun fileProviderUri(file: File): String = file.toURI().toString()
}
