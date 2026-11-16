package com.videodown.douyin.data.playback

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 为本地视频的拖动预览按需抽帧；缓存只存在于当前播放会话。 */
class VideoFrameProvider(private val source: File) : Closeable {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(source.absolutePath) }
    private val mutex = Mutex()
    private val cache = object : LruCache<Long, Bitmap>(8) {
        override fun sizeOf(key: Long, value: Bitmap): Int = 1
        override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
        }
    }

    suspend fun preview(positionMs: Long, width: Int = 320, height: Int = 180): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bucket = (positionMs.coerceAtLeast(0L) / 1_000L) * 1_000L
                mutex.withLock {
                    cache.get(bucket) ?: extract(bucket, width, height).also { cache.put(bucket, it) }
                }
            }
        }

    private fun extract(positionMs: Long, width: Int, height: Int): Bitmap {
        val timeUs = positionMs * 1_000L
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                val scale = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height, 1f)
                val scaled = Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled !== frame) frame.recycle()
                scaled
            }
        }
        return requireNotNull(bitmap) { "No frame available" }
    }

    override fun close() {
        cache.evictAll()
        retriever.release()
    }
}

/** 把当前时间点的原始视频帧发布到系统图片库。 */
class VideoFrameExporter(private val context: Context) {
    suspend fun export(source: File, positionMs: Long): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            require(source.isFile) { "Video file is missing" }
            val frame = MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(source.absolutePath)
                    requireNotNull(retriever.getFrameAtTime(positionMs.coerceAtLeast(0L) * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST)) { "No frame available" }
                } finally {
                    retriever.release()
                }
            }
            try { publish(frame) } finally { frame.recycle() }
        }
    }

    private fun publish(frame: Bitmap): Uri {
        val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "拾影") // l10n-ignore: MediaStore 目录名是稳定路径
            check(directory.exists() || directory.mkdirs()) { "Cannot create picture directory" }
            val file = File(directory, name)
            file.outputStream().use { check(frame.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            return Uri.fromFile(file)
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/拾影") // l10n-ignore: MediaStore 目录名是稳定路径
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use {
                check(frame.compress(Bitmap.CompressFormat.JPEG, 95, it))
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
