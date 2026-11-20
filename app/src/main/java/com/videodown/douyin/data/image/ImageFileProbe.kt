package com.videodown.douyin.data.image

import com.videodown.douyin.domain.model.MediaSignature
import java.io.File

/**
 * 只读图片头，拿到原图宽高。
 *
 * 信息面板以前只在图片解码完成后才有尺寸；大图或解码失败时永远显示"未知"。这里按文件头
 * 读取，PNG/GIF/WebP/JPEG 都在前 512 KiB 内拿得到，失败就返回 null，绝不整文件解码。
 */
object ImageFileProbe {
    private const val HEADER_BYTES = 512 * 1024

    fun dimensions(file: File): MediaSignature.ImageSize? {
        if (!file.isFile) return null
        return runCatching {
            val limit = minOf(file.length(), HEADER_BYTES.toLong()).toInt()
            if (limit <= 0) return null
            val head = ByteArray(limit)
            var read = 0
            file.inputStream().use { input ->
                while (read < limit) {
                    val count = input.read(head, read, limit - read)
                    if (count <= 0) break
                    read += count
                }
            }
            MediaSignature.imageSize(if (read == limit) head else head.copyOf(read))
        }.getOrNull()
    }
}
