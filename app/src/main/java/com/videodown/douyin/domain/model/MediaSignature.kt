package com.videodown.douyin.domain.model

/**
 * 按文件头识别图片格式与尺寸。
 *
 * 为什么需要它：直链解析、网页候选和下载完成后的校验都只拿到**字节**，而声明的扩展名和
 * `Content-Type` 都可能缺失或说谎（CDN 常见 `.jpg` 实际是 WebP）。这里只做纯 Kotlin 的魔数判断，
 * 不碰 `BitmapFactory`，所以 JVM 用例可以直接覆盖。
 */
object MediaSignature {

    /** 校验下载结果或探测直链时只需要一小段前缀；JPEG 的尺寸段可能更靠后，另给 [SIZE_PROBE_BYTES]。 */
    const val HEAD_BYTES = 64

    /** JPEG 的 SOF 段出现在缩略表之后，取 64 KiB 足够覆盖绝大多数相机直出图。 */
    const val SIZE_PROBE_BYTES = 64 * 1024

    /** 图片扩展名；返回 `null` 表示这段字节不是已知的图片格式。 */
    fun imageExtension(bytes: ByteArray, contentType: String? = null): String? = magicExtension(bytes)
        ?: contentType?.substringBefore(';')?.trim()?.lowercase()?.let(::extensionForContentType)

    /** `image/jpeg` → `jpg`；不是图片类型时返回 `null`。 */
    fun extensionForContentType(contentType: String?): String? = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/avif" -> "avif"
        "image/heic", "image/heif" -> "heic"
        "image/bmp" -> "bmp"
        else -> null
    }

    /** 从 URL 路径猜图片扩展名；`jpeg` 归一成 `jpg`，非图片路径返回 `null`。 */
    fun extensionFromUrl(url: String): String? =
        URL_EXTENSION.find(url.substringBefore('?'))?.groupValues?.get(1)?.lowercase()?.let { if (it == "jpeg") "jpg" else it }

    /** 是否是图片格式的「已知扩展名」；用于结果页与相册过滤。 */
    fun isImageExtension(extension: String): Boolean =
        extension.trimStart('.').lowercase() in IMAGE_EXTENSIONS

    val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "bmp")

    private val URL_EXTENSION = Regex("""\.(jpe?g|png|webp|gif|avif|heic|bmp)(?=$|[?#])""", RegexOption.IGNORE_CASE)

    /** ZIP 的本地文件头；`PK\x03\x04` 也覆盖空压缩包之外的所有普通 ZIP。 */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /**
     * 图片尺寸；无法判定（例如 AVIF/HEIC 的 `ispe` 盒）时返回 `null`。
     *
     * 网页抓图用宽高过滤图标与头像，所以这里宁可返回 `null` 也不猜。
     */
    fun imageSize(bytes: ByteArray): ImageSize? = when (magicExtension(bytes)) {
        "png" -> pngSize(bytes)
        "gif" -> gifSize(bytes)
        "jpg" -> jpegSize(bytes)
        "webp" -> webpSize(bytes)
        else -> null
    }

    data class ImageSize(val width: Int, val height: Int)

    private fun magicExtension(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "jpg"
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) return "png"
        if (bytes.size >= 6) {
            val head = bytes.copyOfRange(0, 6).toString(Charsets.ISO_8859_1)
            if (head == "GIF87a" || head == "GIF89a") return "gif"
        }
        if (bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.ISO_8859_1) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.ISO_8859_1) == "WEBP") return "webp"
        if (bytes.size >= 12 && bytes.copyOfRange(4, 8).toString(Charsets.ISO_8859_1) == "ftyp") {
            when (bytes.copyOfRange(8, 12).toString(Charsets.ISO_8859_1).lowercase()) {
                "avif", "avis" -> return "avif"
                "heic", "heix", "hevc", "hevx", "mif1", "msf1", "heim", "heis" -> return "heic"
            }
        }
        if (bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return "bmp"
        return null
    }

    private fun pngSize(bytes: ByteArray): ImageSize? {
        if (bytes.size < 24) return null
        val width = int32Be(bytes, 16)
        val height = int32Be(bytes, 20)
        return width.takeIf { it > 0 }?.let { ImageSize(it, height) }?.takeIf { height > 0 }
    }

    private fun gifSize(bytes: ByteArray): ImageSize? {
        if (bytes.size < 10) return null
        val width = int16Le(bytes, 6)
        val height = int16Le(bytes, 8)
        return width.takeIf { it > 0 }?.let { ImageSize(it, height) }?.takeIf { height > 0 }
    }

    /** 扫描 JPEG 的 SOFn 段；高度在段内 +5..7、宽度在 +7..9（大端）。 */
    private fun jpegSize(bytes: ByteArray): ImageSize? {
        var index = 2
        while (index + 9 < bytes.size) {
            if (bytes[index] != 0xFF.toByte()) { index++; continue }
            val marker = bytes[index + 1].toInt() and 0xFF
            // 填充字节 0xFF 与独立标记（RSTn/SOI/EOI）没有长度字段。
            if (marker == 0xFF) { index++; continue }
            if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01) { index += 2; continue }
            val length = int16Be(bytes, index + 2)
            if (length < 2) return null
            if (marker in SOF_MARKERS) {
                if (index + 9 > bytes.size) return null
                val height = int16Be(bytes, index + 5)
                val width = int16Be(bytes, index + 7)
                return if (width > 0 && height > 0) ImageSize(width, height) else null
            }
            index += 2 + length
        }
        return null
    }

    private fun webpSize(bytes: ByteArray): ImageSize? {
        if (bytes.size < 30) return null
        val chunk = bytes.copyOfRange(12, 16).toString(Charsets.ISO_8859_1)
        return when (chunk) {
            // 扩展格式：画布尺寸在 VP8X 头里，宽度/高度各 24 位（存的是尺寸减一）。
            "VP8X" -> {
                val width = 1 + (bytes[24].toInt() and 0xFF) + ((bytes[25].toInt() and 0xFF) shl 8) + ((bytes[26].toInt() and 0xFF) shl 16)
                val height = 1 + (bytes[27].toInt() and 0xFF) + ((bytes[28].toInt() and 0xFF) shl 8) + ((bytes[29].toInt() and 0xFF) shl 16)
                ImageSize(width, height)
            }
            // 无损：14 位宽 + 14 位高。
            "VP8L" -> {
                if (bytes.size < 25) return null
                val bits = (bytes[21].toInt() and 0xFF) or ((bytes[22].toInt() and 0xFF) shl 8) or
                    ((bytes[23].toInt() and 0xFF) shl 16) or ((bytes[24].toInt() and 0xFF) shl 24)
                val width = (bits and 0x3FFF) + 1
                val height = ((bits shr 14) and 0x3FFF) + 1
                ImageSize(width, height)
            }
            else -> null
        }
    }

    private fun int16Be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun int16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun int32Be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** SOF0–SOF3、SOF5–SOF7、SOF9–SOF11、SOF13–SOF15；跳过 DHT(C4)/JPG(C8)/DAC(CC)。 */
    private val SOF_MARKERS = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
}
