package com.videodown.douyin.data.generic

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import android.media.MediaExtractor
import android.media.MediaFormat
import com.videodown.douyin.data.download.DownloadNetworkPolicy
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.model.ExpectedTrack
import com.videodown.douyin.domain.model.MediaIntegrity
import com.videodown.douyin.domain.model.MediaIntegrityPolicy
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EngineMediaDownloader(
    private val runtime: VideoEngineRuntime,
    private val sessions: BrowserSessionStore,
    private val privateDirectory: File,
    private val history: DownloadHistoryRepository? = null,
    private val maintenanceMutex: Mutex = Mutex(),
) : EngineTransfer {
    override suspend fun download(record: DownloadRecord, paths: DownloadPaths, policy: DownloadNetworkPolicy,
                         rateLimitBytesPerSecond: Long?,
                         progress: suspend (Long, Long, Long, TransferPhase) -> Unit): File = withContext(Dispatchers.IO) {
        val media = requireNotNull(record.media)
        val directory = paths.engineDirectory(record.id).apply { mkdirs() }
        if (media.localRecordId != null) {
            val stagedSource = File(directory, "source.snapshot")
            var copyAudio = false
            try {
                val (stream, size) = maintenanceMutex.withLock {
                    val original = requireNotNull(history).getById(media.localRecordId) ?: error(AppText.get(R.string.engine_original_missing))
                    require(original.status == DownloadStatus.COMPLETED && !original.isAudio) { AppText.get(R.string.engine_original_unusable) }
                    val file = original.filePath?.let(::File) ?: error(AppText.get(R.string.engine_original_missing))
                    require(file.isFile && !Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(paths.downloadDir.toPath()) &&
                        file.canonicalFile.parentFile == paths.downloadDir.canonicalFile) { AppText.get(R.string.engine_original_file_unusable) }
                    file.inputStream() to file.length()
                }
                // An opened file descriptor remains valid if the source is renamed or unlinked.
                // Copy outside the gate so pause/delete can cancel this job without deadlocking.
                stream.use { input ->
                    copyAudio = validateAudioTrack(input.fd) == "audio/mp4a-latm"
                    input.channel.position(0)
                    stagedSource.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count); copied += count
                            if (copied % (1024 * 1024) < buffer.size) progress(copied, size, 0, TransferPhase.EXTRACTING)
                        }
                    }
                }
                progress(0, -1, 0, TransferPhase.EXTRACTING)
                var completed = false
                runtime.execute(buildJsonObject {
                    put("mode", "extract_audio"); put("input", stagedSource.absolutePath); put("copy_audio", copyAudio)
                    put("output", File(directory, "media.m4a").absolutePath)
                }) { if (it.text("event") == "complete") completed = true }
                check(completed) { AppText.get(R.string.engine_audio_extract_incomplete) }
                val output = File(directory, "media.m4a")
                progress(output.length(), output.length(), 0, TransferPhase.VERIFYING)
                try {
                    validateAudio(output)
                } catch (error: VideoEngineException) {
                    // 未发布的成品删掉，避免下一次重试把同一个坏文件当成成品；源快照仍在。
                    output.delete()
                    throw error
                }
                currentCoroutineContext().ensureActive()
                return@withContext output
            } catch (error: java.io.IOException) {
                val messages = generateSequence<Throwable>(error) { it.cause }.take(8).map { it.message.orEmpty() }.toList()
                throw VideoEngineException(if (messages.any { it.contains("ENOSPC", true) || it.contains("no space left", true) }) "SPACE" else "STORAGE_ACCESS")
            } finally { stagedSource.delete() }
        }
        privateDirectory.mkdirs()
        val cookies = File(privateDirectory, "download-${record.id}.txt")
        var downloaded = 0L
        var total = -1L
        try {
            if (media.useBrowserSession) sessions.export(
                BrowserSessionStore.extractorCookieUrls(media.pageUrl.ifBlank { record.sourceUrl }, record.downloadReferer) +
                    listOfNotNull(record.sourceUrl, media.audioUrl), cookies)
            var completed = false
            runtime.execute(
                EngineRequest.download(
                    url = media.pageUrl.ifBlank { record.sourceUrl },
                    output = File(directory, "media.%(ext)s").absolutePath,
                    format = media.formatSelector,
                    audioOnly = media.audioOnly,
                    mediaId = media.mediaId,
                    referer = record.downloadReferer,
                    userAgent = media.userAgent,
                    browserSession = media.useBrowserSession,
                    cookieFile = cookies.absolutePath,
                    subtitles = record.subtitleLanguages,
                    automaticCaptions = record.subtitleAutomatic,
                    rateLimitBytesPerSecond = rateLimitBytesPerSecond,
                ),
                policy,
            ) { event ->
                when (event.text("event")) {
                    "progress" -> {
                        downloaded = event.number("downloaded")?.toLong() ?: downloaded
                        total = event.number("total")?.toLong() ?: -1
                        progress(downloaded, total, event.number("speed")?.toLong() ?: 0, TransferPhase.DOWNLOADING)
                    }
                    "phase" -> progress(downloaded, total, 0, if (media.audioOnly) TransferPhase.EXTRACTING else TransferPhase.MERGING)
                    "complete" -> completed = true
                }
            }
            currentCoroutineContext().ensureActive()
            check(completed) { AppText.get(R.string.engine_download_unconfirmed) }
            progress(downloaded, total, 0, TransferPhase.VERIFYING)
            val file = (if (media.audioOnly) File(directory, "media.m4a").takeIf { it.isFile && it.length() > 0 } else directory.listFiles()?.singleOrNull { it.name.matches(Regex("media\\.(mp4|webm|mkv|mov|m4v|ts)")) && it.length() > 0 })
                ?: error(AppText.get(R.string.engine_no_complete_video))
            val expectedTracks = if (media.audioOnly) setOf(ExpectedTrack.AUDIO)
                else MediaIntegrityPolicy.expectedTracks(media)
            try {
                validateMediaFile(file, expectedTracks)
            } catch (error: VideoEngineException) {
                discardUnpublishedProduct(directory)
                throw error
            }
            currentCoroutineContext().ensureActive()
            // Publication and history commit are one non-cancellable step in DownloadEngine.
            file
        } finally { cookies.delete() }
    }
}

/**
 * 删除未发布的成品，但保留仍可安全复用的分片。
 *
 * yt-dlp 用 `media.mp4.part` / `.ytdl` 记录未完成的片段；校验失败时把它们留下，下一次重试可以续传，
 * 只删已经「看起来下载完成」的 `media.*` 成品，避免继续把坏文件当成完整结果。
 */
private fun discardUnpublishedProduct(directory: File) {
    directory.listFiles()?.forEach { file ->
        if (file.isFile && file.name.startsWith("media.") &&
            !file.name.endsWith(".part") && !file.name.endsWith(".ytdl")
        ) {
            file.delete()
        }
    }
}

internal fun validateVideo(file: File) = validateMediaFile(file, setOf(ExpectedTrack.VIDEO))

internal fun validateAudio(file: File) {
    require(file.isFile && file.length() > 0) { AppText.get(R.string.engine_no_valid_audio) }
    validateMediaFile(file, setOf(ExpectedTrack.AUDIO), requireAudioOnly = true)
}

/**
 * I2：成品媒体的轨道与尾部完整性。
 *
 * 在原有的「容器可打开、首样本存在、无 DRM」之外再检查两件事：
 *  - 任务要求的轨道都在（分轨合并后少一条音轨是最常见的形态）；
 *  - 容器声明时长的情况下，接近结尾处（90%）仍有有效样本；服务器提前断流、截断 MP4/WebM
 *    会在这里失败，而不再被当成成功。
 *
 * 不做逐帧完整解码：尾部只从 90% 处向后读有限个样本（[MediaIntegrityPolicy.MAX_TAIL_SAMPLES]），
 * 关键帧间隔大时也不会退化成全文件扫描。
 */
internal fun validateMediaFile(
    file: File,
    expected: Set<ExpectedTrack>,
    requireAudioOnly: Boolean = false,
) {
    require(file.isFile && file.length() > 0L) { AppText.get(R.string.engine_no_complete_video) }
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        // Reject encrypted tracks before native file-level metadata.
        if (extractor.drmInitData != null || !extractor.psshInfo.isNullOrEmpty()) throw VideoEngineException("DRM")
        val formats = (0 until extractor.trackCount).map {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
        }
        val present = buildSet {
            if (formats.any { it.startsWith("video/") }) add(ExpectedTrack.VIDEO)
            if (formats.any { it.startsWith("audio/") }) add(ExpectedTrack.AUDIO)
        }
        val missing = expected - present
        if (missing.isNotEmpty()) throw VideoEngineException("MISSING_TRACK")
        if (requireAudioOnly && ExpectedTrack.VIDEO in present) throw VideoEngineException("UNSUPPORTED")

        expected.forEach { track ->
            val mimePrefix = if (track == ExpectedTrack.VIDEO) "video/" else "audio/"
            val index = formats.indexOfFirst { it.startsWith(mimePrefix) }
            val format = extractor.getTrackFormat(index)
            extractor.selectTrack(index)
            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw VideoEngineException("DRM")
            // A valid first sample can have a negative presentation timestamp (edit-list preroll).
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            if (extractor.readSampleData(buffer, 0) <= 0) {
                throw VideoEngineException(if (track == ExpectedTrack.VIDEO) "NO_VIDEO" else "NO_AUDIO")
            }
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val verdict = MediaIntegrityPolicy.evaluate(
                expected = setOf(track),
                present = setOf(track),
                durationUs = durationUs,
                tailSampleUs = readTailSample(extractor, durationUs),
            )
            if (verdict is MediaIntegrity.Truncated) throw VideoEngineException("TRUNCATED")
            extractor.unselectTrack(index)
        }
    } finally { extractor.release() }
}

/**
 * 从声明时长的 90% 处向前找同步帧，再向后推进，返回最后一个有效样本的时间戳。
 *
 * 返回 null 表示接近结尾处一个样本都读不到；时长未知时也返回 null，由
 * [MediaIntegrityPolicy.evaluate] 判成「时长未知」而不是截断。
 */
private fun readTailSample(extractor: MediaExtractor, durationUs: Long): Long? {
    if (durationUs <= 0L) return null
    val threshold = MediaIntegrityPolicy.tailThresholdUs(durationUs)
    extractor.seekTo(threshold, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
    var last = -1L
    var visited = 0
    while (visited < MediaIntegrityPolicy.MAX_TAIL_SAMPLES) {
        if (extractor.readSampleData(buffer, 0) <= 0) break
        last = extractor.sampleTime
        if (last >= threshold) return last
        if (!extractor.advance()) break
        visited++
    }
    return last.takeIf { it >= 0L }
}

internal fun validateAudioTrack(descriptor: FileDescriptor, requireAudioOnly: Boolean = false): String {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(descriptor)
        if (extractor.drmInitData != null || !extractor.psshInfo.isNullOrEmpty()) throw VideoEngineException("DRM")
        val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        if (requireAudioOnly) require(formats.none { it.startsWith("video/") }) { AppText.get(R.string.engine_still_has_video) }
        val track = formats.indexOfFirst { it.startsWith("audio/") }
        if (track < 0) throw VideoEngineException("NO_AUDIO")
        extractor.selectTrack(track)
        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw VideoEngineException("DRM")
        require(extractor.readSampleData(java.nio.ByteBuffer.allocate(1024 * 1024), 0) > 0) { AppText.get(R.string.engine_audio_no_data) }
        return formats[track]
    } finally { extractor.release() }
}
