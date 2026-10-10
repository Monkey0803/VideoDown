package com.videodown.douyin.data.generic

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import android.media.MediaExtractor
import android.media.MediaFormat
import com.videodown.douyin.data.download.DownloadNetworkPolicy
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.domain.model.*
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
) {
    suspend fun download(record: DownloadRecord, paths: DownloadPaths, policy: DownloadNetworkPolicy,
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
                validateAudio(output)
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
            if (media.audioOnly) validateAudio(file) else validateVideo(file)
            currentCoroutineContext().ensureActive()
            // Publication and history commit are one non-cancellable step in DownloadEngine.
            file
        } finally { cookies.delete() }
    }
}

internal fun validateVideo(file: File) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        val videoTrack = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error(AppText.get(R.string.engine_no_video_track))
        extractor.selectTrack(videoTrack)
        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw VideoEngineException("DRM")
        // A valid first sample can have a negative presentation timestamp (edit-list preroll).
        require(extractor.sampleTrackIndex == videoTrack) { AppText.get(R.string.engine_incomplete_video_data) }
        // Reject malformed/empty tracks and encrypted samples before native file-level metadata.
        if (extractor.drmInitData != null || !extractor.psshInfo.isNullOrEmpty()) throw VideoEngineException("DRM")
    } finally { extractor.release() }
}

internal fun validateAudio(file: File) {
    require(file.isFile && file.length() > 0) { AppText.get(R.string.engine_no_valid_audio) }
    file.inputStream().use { validateAudioTrack(it.fd, requireAudioOnly = true) }
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
