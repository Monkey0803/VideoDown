package com.videodown.douyin.data.download

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.videodown.douyin.data.gallery.GalleryAutoSave
import com.videodown.douyin.data.gallery.GalleryExportException
import com.videodown.douyin.data.gallery.GalleryExportFailure
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.videoMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把打包下载的 ZIP 复制到公共下载目录。
 *
 * 与相册导出分开：ZIP 不是相册条目，用户点「打包下载」就是要一个能拿到电脑上的压缩包，
 * 所以它固定去 `Download/拾影`，不经过「下载后自动存入相册」开关。
 */
class ArchivePublisher(private val context: Context) {

    suspend fun publish(record: DownloadRecord): GalleryAutoSave = withContext(Dispatchers.IO) {
        val source = record.filePath?.let(::File)
        if (source == null || !source.isFile) {
            return@withContext GalleryAutoSave.Failed(GalleryExportFailure.FILE_MISSING.storageKey)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishToDownloads(source) else publishLegacy(source)
            GalleryAutoSave.Saved
        } catch (error: Throwable) {
            val failure = (error as? GalleryExportException)?.failure ?: GalleryExportFailure.WRITE_FAILED
            GalleryAutoSave.Failed(failure.storageKey)
        }
    }

    private fun publishToDownloads(source: File) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, videoMimeType("zip"))
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)  // l10n-ignore: MediaStore 目录名是路径而非文案
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = resolver.insert(collection, values) ?: throw GalleryExportException(GalleryExportFailure.CREATE_FAILED)
        try {
            resolver.openOutputStream(target, "w")?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: throw GalleryExportException(GalleryExportFailure.WRITE_FAILED)
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(target, ready, null, null) <= 0) {
                throw GalleryExportException(GalleryExportFailure.FINISH_FAILED)
            }
        } catch (error: Throwable) {
            resolver.delete(target, null, null)
            throw error
        }
    }

    /** Android 9 及以下写公共目录需要存储权限；没有授权就只保留应用内副本并说明原因。 */
    private fun publishLegacy(source: File) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw GalleryExportException(GalleryExportFailure.PERMISSION)
        }
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) throw GalleryExportException(GalleryExportFailure.WRITE_FAILED)
        source.copyTo(File(directory, source.name), overwrite = true)
    }

    private companion object {
        const val RELATIVE_PATH = "Download/拾影"  // l10n-ignore: MediaStore 目录名是路径而非文案
        const val DIRECTORY_NAME = "拾影"  // l10n-ignore: 公共下载目录名是路径而非文案
    }
}
