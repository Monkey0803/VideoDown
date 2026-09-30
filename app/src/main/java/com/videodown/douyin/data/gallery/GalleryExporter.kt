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
            require(record.status == DownloadStatus.COMPLETED) { "视频尚未下载完成" }
            val source = record.filePath?.let(::File)
            require(source != null && source.isFile) { "本地视频文件不存在" }

            require(source.extension.lowercase() in if (record.isAudio) setOf("m4a", "mp3", "aac", "ogg", "opus", "wav", "flac") else setOf("mp4", "m4v", "webm", "mov")) { "此容器暂不支持保存到相册，请使用分享文件" }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
                put(MediaStore.Video.Media.MIME_TYPE, com.videodown.douyin.domain.model.videoMimeType(source.extension))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, if (record.isAudio) "Music/拾影" else "Movies/拾影")
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
                ?: throw IOException("无法创建相册视频")
            try {
                val output = resolver.openOutputStream(target, "w")
                    ?: throw IOException("无法写入相册视频")
                output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    if (resolver.update(target, ready, null, null) <= 0) {
                        throw IOException("无法完成相册保存")
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
