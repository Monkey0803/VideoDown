package com.videodown.douyin.data.download

import java.io.File
import java.io.RandomAccessFile

/** 不解码像素的轻量完整性检查；只拒绝可以明确证明已截断的容器。 */
internal fun hasCompleteImageStructure(file: File, extension: String): Boolean = runCatching {
    if (!file.isFile || file.length() <= 0L) return@runCatching false
    RandomAccessFile(file, "r").use { input ->
        when (extension) {
            "jpg", "jpeg" -> tailContains(input, byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
            "png" -> hasPngEnd(input)
            "gif" -> lastByte(input) == 0x3B
            "webp" -> input.length() >= 12L && unsignedIntLe(input, 4) + 8L <= input.length()
            "bmp" -> input.length() >= 14L && unsignedIntLe(input, 2) <= input.length() &&
                unsignedIntLe(input, 10) in 14L until input.length()
            "avif", "heic", "heif" -> completeIsoBoxes(input)
            else -> true
        }
    }
}.getOrDefault(false)

private fun tailContains(input: RandomAccessFile, needle: ByteArray): Boolean {
    val size = minOf(input.length(), 64L * 1024L).toInt()
    if (size < needle.size) return false
    val bytes = ByteArray(size)
    input.seek(input.length() - size)
    input.readFully(bytes)
    return bytes.indices.any { index ->
        index + needle.size <= bytes.size && needle.indices.all { bytes[index + it] == needle[it] }
    }
}

private fun lastByte(input: RandomAccessFile): Int {
    input.seek(input.length() - 1)
    return input.readUnsignedByte()
}

private fun hasPngEnd(input: RandomAccessFile): Boolean {
    if (input.length() < 12L) return false
    input.seek(input.length() - 12L)
    return input.readInt() == 0 && ByteArray(4).also(input::readFully)
        .contentEquals("IEND".toByteArray(Charsets.US_ASCII))
}

private fun unsignedIntLe(input: RandomAccessFile, offset: Long): Long {
    input.seek(offset)
    var value = 0L
    repeat(4) { shift -> value = value or (input.readUnsignedByte().toLong() shl (shift * 8)) }
    return value
}

private fun completeIsoBoxes(input: RandomAccessFile): Boolean {
    var offset = 0L
    var sawFileType = false
    val length = input.length()
    while (offset < length) {
        if (length - offset < 8L) return false
        input.seek(offset)
        val size32 = input.readInt().toLong() and 0xFFFF_FFFFL
        val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        val header = if (size32 == 1L) 16L else 8L
        val size = when (size32) {
            0L -> length - offset
            1L -> {
                if (length - offset < 16L) return false
                input.readLong().takeIf { it >= 16L } ?: return false
            }
            else -> size32
        }
        if (size < header || size > length - offset) return false
        if (type == "ftyp") sawFileType = true
        offset += size
    }
    return sawFileType && offset == length
}
