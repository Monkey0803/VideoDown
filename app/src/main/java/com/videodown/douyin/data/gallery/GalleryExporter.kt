package com.videodown.douyin.data.gallery

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Copies a completed private download into the user's Movies collection. */
class GalleryExporter(private val context: Context) {
    suspend fun export(record: DownloadRecord): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            if (record.status != DownloadStatus.COMPLETED) throw GalleryExportException(GalleryExportFailure.NOT_COMPLETED)
            val source = record.filePath?.let(::File)
            if (source == null || !source.isFile) throw GalleryExportException(GalleryExportFailure.FILE_MISSING)

            val containers = if (record.isAudio) setOf("m4a", "mp3", "aac", "ogg", "opus", "wav", "flac") else setOf("mp4", "m4v", "webm", "mov")
            if (source.extension.lowercase() !in containers) throw GalleryExportException(GalleryExportFailure.UNSUPPORTED_CONTAINER)
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
                put(MediaStore.Video.Media.MIME_TYPE, com.videodown.douyin.domain.model.videoMimeType(source.extension))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                    put(MediaStore.Video.Media.RELATIVE_PATH, if (record.isAudio) "Music/拾影" else "Movies/拾影")  // l10n-ignore: MediaStore 目录名是路径而非文案，随语言改变会把同一份文件写到不同目录
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (record.isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                if (record.isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val target = resolver.insert(collection, values)
                ?: throw GalleryExportException(GalleryExportFailure.CREATE_FAILED)
            try {
                val output = resolver.openOutputStream(target, "w")
                    ?: throw GalleryExportException(GalleryExportFailure.WRITE_FAILED)
                output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    if (resolver.update(target, ready, null, null) <= 0) {
                        throw GalleryExportException(GalleryExportFailure.FINISH_FAILED)
                    }
                }
                target
            } catch (error: Throwable) {
                resolver.delete(target, null, null)
                throw error
            }
        }
    }
}
