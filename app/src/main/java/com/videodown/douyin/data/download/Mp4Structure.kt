package com.videodown.douyin.data.download

import com.videodown.douyin.domain.model.ExpectedTrack
import java.io.File
import java.io.RandomAccessFile

/**
 * ISO BMFF（MP4 / MOV）容器的结构巡检。
 *
 * 只回答两件事：顶层盒子链是否**完整**，以及 `moov` 里有哪些**轨道类型**。不依赖
 * `android.media.MediaExtractor`，因为平台解析器会拒绝一些真实文件：X 的动图 MP4
 * 把 `ctts` 的 flags 写成 1（该字段应为 0），平台的 MPEG4 解析器随即放弃整条轨道，
 * `trackCount` 变成 0 且 `getFileFormat` 直接抛 `IllegalArgumentException`；同一份字节
 * 在 ExoPlayer 里却能正常播放。下载完成的成品需要一个不依赖平台解析器的兜底判定。
 *
 * **不覆盖**：尾部样本与声明时长（那是 I2 的 `MediaIntegrityPolicy`，需要能打开容器的
 * 提取器）；这里只确认盒子声明长度与实际文件一致，以及任务要求的轨道在容器里存在。
 */
internal sealed interface Mp4Structure {

    /** 不是 ISO BMFF：没有 `ftyp / styp`，或顶层盒子链根本读不出来。 */
    data object NotMp4 : Mp4Structure

    /**
     * 是 ISO BMFF，但容器不完整：某个盒子的声明长度越过文件末尾，或缺少 `moov / mdat`。
     * 下载到一半的成品与「服务器提前断流」都属于这一档。
     */
    data object Incomplete : Mp4Structure

    /** 结构完整；[tracks] 是 `moov` 下各 `trak` 的 handler 类型。 */
    data class Complete(val tracks: Set<ExpectedTrack>) : Mp4Structure

    companion object {
        /** `moov` 里最多认这么多 `trak`，避免构造出来的畸形文件把巡检拖成全文件扫描。 */
        private const val MAX_TRAKS = 64

        /** 无意义但合法的填充盒子，出现在 `ftyp` 之前时直接跳过。 */
        private val LEADING_FILLER = setOf("free", "skip", "wide", "uuid")

        /**
         * 巡检 [file]；只读盒子头与 `moov` 里的 handler，不读媒体数据，也不做哈希。
         *
         * 读取失败（例如文件在巡检途中被删除）按 [NotMp4] 处理：调用方只需要一个
         * 「能不能确认这份文件可用」的结论。
         */
        fun inspect(file: File): Mp4Structure {
            if (!file.isFile || file.length() < 8L) return NotMp4
            return try {
                RandomAccessFile(file, "r").use(::readTopLevel)
            } catch (_: java.io.IOException) {
                NotMp4
            }
        }

        private fun readTopLevel(raf: RandomAccessFile): Mp4Structure {
            val length = raf.length()
            var offset = 0L
            var sawBrand = false
            var sawMovie = false
            var sawData = false
            var tracks: Set<ExpectedTrack> = emptySet()

            while (offset < length) {
                val box = readBox(raf, offset, length) ?: return if (sawBrand) Mp4Structure.Incomplete else Mp4Structure.NotMp4
                if (box.type != "ftyp" && box.type != "styp" && !sawBrand) {
                    // ftyp 必须是第一个有内容的盒子；少数编码器会先写 free/skip 填充。
                    if (box.type !in LEADING_FILLER) return Mp4Structure.NotMp4
                }
                when (box.type) {
                    "ftyp", "styp" -> sawBrand = true
                    "moov" -> {
                        sawMovie = true
                        tracks = readTracks(raf, box) ?: return Mp4Structure.Incomplete
                    }
                    "mdat", "moof" -> sawData = true
                }
                offset = box.end
            }
            return when {
                !sawBrand -> Mp4Structure.NotMp4
                !sawMovie || !sawData -> Mp4Structure.Incomplete
                else -> Mp4Structure.Complete(tracks)
            }
        }

        /** 读 `moov` 下每个 `trak` 的 handler；结构读不通时返回 null（容器不完整）。 */
        private fun readTracks(raf: RandomAccessFile, moov: Box): Set<ExpectedTrack>? {
            val tracks = mutableSetOf<ExpectedTrack>()
            var traks = 0
            var offset = moov.payloadStart
            while (offset < moov.end) {
                val child = readBox(raf, offset, moov.end) ?: return null
                if (child.type == "trak") {
                    if (++traks > MAX_TRAKS) return null
                    when (readHandler(raf, child) ?: return null) {
                        "vide" -> tracks += ExpectedTrack.VIDEO
                        "soun" -> tracks += ExpectedTrack.AUDIO
                    }
                }
                offset = child.end
            }
            return if (offset == moov.end) tracks else null
        }

        /** `trak -> mdia -> hdlr`，返回 handler 的四字符类型（`vide / soun / ...`）。 */
        private fun readHandler(raf: RandomAccessFile, trak: Box): String? {
            var mdia: Box? = null
            var offset = trak.payloadStart
            while (offset < trak.end) {
                val child = readBox(raf, offset, trak.end) ?: return null
                if (child.type == "mdia") {
                    mdia = child
                    break
                }
                offset = child.end
            }
            val media = mdia ?: return null

            offset = media.payloadStart
            while (offset < media.end) {
                val child = readBox(raf, offset, media.end) ?: return null
                if (child.type == "hdlr") {
                    // hdlr: version/flags(4) + pre_defined(4) + handler_type(4)
                    if (child.end - child.payloadStart < 12L) return null
                    raf.seek(child.payloadStart + 8L)
                    return readType(raf)
                }
                offset = child.end
            }
            return null
        }

        /** 读一个盒子头；声明长度越界或类型不是四字符可打印 ASCII 时返回 null。 */
        private fun readBox(raf: RandomAccessFile, offset: Long, limit: Long): Box? {
            if (offset + 8L > limit) return null
            raf.seek(offset)
            val size32 = raf.readInt().toLong() and 0xFFFF_FFFFL
            val type = readType(raf) ?: return null
            var headerSize = 8L
            val size = when (size32) {
                1L -> {
                    if (offset + 16L > limit) return null
                    headerSize = 16L
                    raf.readLong()
                }
                // size == 0 表示「延伸到文件末尾」，只可能是最后一个盒子。
                0L -> limit - offset
                else -> size32
            }
            if (size < headerSize || offset + size > limit) return null
            return Box(type, offset + headerSize, offset + size)
        }

        private fun readType(raf: RandomAccessFile): String? {
            val bytes = ByteArray(4)
            if (raf.read(bytes) != 4) return null
            if (bytes.any { it < 0x20 || it > 0x7E }) return null
            return String(bytes, Charsets.US_ASCII)
        }

        private class Box(val type: String, val payloadStart: Long, val end: Long)
    }
}
