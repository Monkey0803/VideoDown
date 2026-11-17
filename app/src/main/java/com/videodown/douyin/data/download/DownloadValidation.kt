package com.videodown.douyin.data.download

import com.videodown.douyin.data.generic.validateMediaFile
import com.videodown.douyin.domain.model.ExpectedTrack
import com.videodown.douyin.domain.model.MediaIntegrityPolicy
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.MediaSignature
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** 校验失败的键；与 `download:` 家族的其余 key 一样落库、由界面成句。 */
const val DOWNLOAD_NOT_AN_IMAGE = "download:not_an_image"
const val DOWNLOAD_INVALID_ARCHIVE = "download:invalid_archive"

/** 下载完成后的校验失败；[reasonKey] 是持久化用的 key，不是给用户看的句子。 */
class DownloadContentException(val reasonKey: String) : Exception(reasonKey)

/**
 * 按记录类型校验刚下载的文件。
 *
 * 之前所有直链都走 `validateVideo`，于是图片一定被判成「没有视频轨」并删掉；
 * 现在按 [DownloadRecord.mediaKind] 分派，视频校验保持不变。
 */
fun validateDownloadedFile(file: File, record: DownloadRecord) {
    when {
        record.mediaKind == MediaKind.IMAGE -> validateImage(file)
        record.mediaKind == MediaKind.ARCHIVE -> validateArchive(file, record.archiveEntries)
        // 旧音频记录没有 AUDIO 这个 mediaKind，按 `audioOnly` 兜底，与播放器分流一致。
        record.isAudio -> validateMediaFile(file, setOf(ExpectedTrack.AUDIO))
        else -> validateMediaFile(file, MediaIntegrityPolicy.expectedTracks(record.media))
    }
}

/** 图片校验只看文件头：扩展名与 `Content-Type` 都可能是错的。 */
internal fun validateImage(file: File) {
    if (!file.isFile || file.length() <= 0L) throw DownloadContentException(DOWNLOAD_NOT_AN_IMAGE)
    val head = file.readPrefix(MediaSignature.HEAD_BYTES)
    val extension = MediaSignature.imageExtension(head) ?: throw DownloadContentException(DOWNLOAD_NOT_AN_IMAGE)
    if (!hasCompleteImageStructure(file, extension)) throw DownloadContentException(DOWNLOAD_NOT_AN_IMAGE)
}

/** 压缩包校验：ZIP 魔数 + 条目数必须与任务清单一致。 */
internal fun validateArchive(file: File, expectedEntries: List<com.videodown.douyin.domain.model.ArchiveEntry>) {
    if (!file.isFile || file.length() <= 0L) throw DownloadContentException(DOWNLOAD_INVALID_ARCHIVE)
    if (!MediaSignature.isZip(file.readPrefix(4))) throw DownloadContentException(DOWNLOAD_INVALID_ARCHIVE)
    val valid = runCatching {
        ZipFile(file).use { zip ->
            val expectedNames = buildList {
                expectedEntries.forEachIndexed { index, entry ->
                    add(ImageArchiveWriter.entryName(index, entry.extension))
                    entry.livePhotoMotion?.let { add(ImageArchiveWriter.livePhotoMotionEntryName(index, it.extension)) }
                }
            }
            val entries = java.util.Collections.list(zip.entries())
            if (entries.map { it.name } != expectedNames) return@use false
            entries.all { entry ->
                val head = zip.getInputStream(entry).use { it.readPrefix(MediaSignature.HEAD_BYTES) }
                when {
                    entry.name.startsWith("${ImageArchiveWriter.ENTRY_DIRECTORY}/") ->
                        MediaSignature.imageExtension(head) != null
                    entry.name.startsWith("livephotos/") -> isIsoMedia(head)
                    else -> false
                }
            }
        }
    }.getOrDefault(false)
    if (!valid) throw DownloadContentException(DOWNLOAD_INVALID_ARCHIVE)
}

internal fun isIsoMedia(head: ByteArray): Boolean =
    head.size >= 12 && head.copyOfRange(4, 8).contentEquals("ftyp".toByteArray(Charsets.US_ASCII))

private fun File.readPrefix(max: Int): ByteArray = inputStream().use { it.readPrefix(max) }

/** `InputStream.readNBytes` 直到 API 33 才有，这里按 minSdk 26 手写。 */
private fun InputStream.readPrefix(max: Int): ByteArray {
    val buffer = ByteArray(max)
    var read = 0
    while (read < max) {
        val count = read(buffer, read, max - read)
        if (count <= 0) break
        read += count
    }
    return if (read == max) buffer else buffer.copyOf(read)
}
