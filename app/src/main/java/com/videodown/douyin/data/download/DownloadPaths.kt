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

    fun buildFileName(title: String, resolutionLabel: String, awemeId: String, extension: String = "mp4"): String {
        val safeTitle = sanitizeTitle(title, "douyin_video")
        val safeResolution = resolutionLabel
            .replace(Regex("[^A-Za-z0-9pP]"), "")
            .ifBlank { "video" }
        val safeId = sanitizeId(awemeId, "video")
        return "${safeTitle}_${safeResolution}_$safeId.${extension.trimStart('.').ifBlank { "mp4" }}"
    }

    /**
     * 打包下载的文件名。
     *
     * 文件名不进资源表：它只是磁盘上的标识，随语言变化会让同一份文件在不同语言下改名。
     * 因此这里只用 ASCII 的 `image{count}`，界面上再把它说成「N 张」。
     */
    fun buildArchiveFileName(title: String, count: Int, awemeId: String): String {
        val safeTitle = sanitizeTitle(title, "archive")
        val safeId = sanitizeId(awemeId, "images")
        return "${safeTitle}_image${count.coerceAtLeast(1)}_$safeId.zip"
    }

    private fun sanitizeTitle(title: String, fallback: String): String = title
        .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(60)
        .ifBlank { fallback }

    private fun sanitizeId(awemeId: String, fallback: String): String =
        awemeId.replace(Regex("[^A-Za-z0-9_-]"), "").take(100).ifBlank { fallback }

    /** Path used by `FileProvider` for the given absolute file path. */
    fun fileProviderUri(file: File): String = file.toURI().toString()
}
