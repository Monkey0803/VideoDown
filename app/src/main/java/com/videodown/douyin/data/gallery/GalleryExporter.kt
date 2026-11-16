package com.videodown.douyin.data.gallery

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaSignature
import com.videodown.douyin.domain.model.videoMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies a completed private download into the system gallery.
 *
 * 视频进 `Movies/拾影`、音频进 `Music/拾影`、图片进 `Pictures/拾影`；三者共用同一套
 * `IS_PENDING` 流程与失败分类，差别只在 MediaStore 集合与目录名。压缩包不是相册条目，
 * 由 `ArchivePublisher` 写入下载目录。
 */
class GalleryExporter(private val context: Context) {
    suspend fun export(record: DownloadRecord): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            if (record.status != DownloadStatus.COMPLETED) throw GalleryExportException(GalleryExportFailure.NOT_COMPLETED)
            val source = record.filePath?.let(::File)
            if (source == null || !source.isFile) throw GalleryExportException(GalleryExportFailure.FILE_MISSING)

            val target = when {
                record.isImage -> ImageTarget
                record.isAudio -> AudioTarget
                record.isArchive -> throw GalleryExportException(GalleryExportFailure.UNSUPPORTED_CONTAINER)
                else -> VideoTarget
            }
            if (source.extension.lowercase() !in target.containers) {
                throw GalleryExportException(GalleryExportFailure.UNSUPPORTED_CONTAINER)
            }

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, videoMimeType(source.extension))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)  // l10n-ignore: MediaStore 目录名是路径而非文案，随语言改变会把同一份文件写到不同目录
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                target.collection.api29
            } else {
                target.collection.legacy
            }
            val inserted = resolver.insert(collection, values)
                ?: throw GalleryExportException(GalleryExportFailure.CREATE_FAILED)
            try {
                val output = resolver.openOutputStream(inserted, "w")
                    ?: throw GalleryExportException(GalleryExportFailure.WRITE_FAILED)
                output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    if (resolver.update(inserted, ready, null, null) <= 0) {
                        throw GalleryExportException(GalleryExportFailure.FINISH_FAILED)
                    }
                }
                inserted
            } catch (error: Throwable) {
                resolver.delete(inserted, null, null)
                throw error
            }
        }
    }

    private sealed interface ExportTarget {
        val containers: Set<String>
        val relativePath: String
        val collection: MediaCollection
    }

    private data class MediaCollection(val api29: Uri, val legacy: Uri)

    private object VideoTarget : ExportTarget {
        override val containers = setOf("mp4", "m4v", "webm", "mov")
        override val relativePath = "Movies/拾影"  // l10n-ignore: MediaStore 目录名是路径而非文案，随语言改变会把同一份文件写到不同目录
        override val collection = MediaCollection(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
    }

    private object AudioTarget : ExportTarget {
        override val containers = setOf("m4a", "mp3", "aac", "ogg", "opus", "wav", "flac")
        override val relativePath = "Music/拾影"  // l10n-ignore: MediaStore 目录名是路径而非文案，随语言改变会把同一份文件写到不同目录
        override val collection = MediaCollection(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        )
    }

    private object ImageTarget : ExportTarget {
        override val containers = MediaSignature.IMAGE_EXTENSIONS
        override val relativePath = "Pictures/拾影"  // l10n-ignore: MediaStore 目录名是路径而非文案，随语言改变会把同一份文件写到不同目录
        override val collection = MediaCollection(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        )
    }
}
