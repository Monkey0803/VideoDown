package com.videodown.douyin.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.downloadFailureKind
import com.videodown.douyin.data.remote.VideoLinkExtractor
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.VideoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportStatus { PENDING, PARSING, READY, FAILED }
data class ImportMedia(val key: String, val video: DouyinVideo, val choice: VideoVariant?, val selected: Boolean = true)
/** Why one imported link could not be prepared; the dialog words it. */
enum class ImportRowError { NO_QUALITY, PARSE_FAILED }

/** What an import run did; the dialog words it. */
sealed interface ImportNotice {
    data object NoLinks : ImportNotice
    data object TooManyLinks : ImportNotice
    data object RecordsUnavailable : ImportNotice
    data class Queued(val queued: Int, val skipped: Int, val failedTitles: List<String>) : ImportNotice
}

data class ImportRow(val url: String, val status: ImportStatus = ImportStatus.PENDING,
    val media: List<ImportMedia> = emptyList(), val error: ImportRowError? = null, val failureKind: DownloadFailureKind? = null)
data class BatchImportState(val input: String = "", val rows: List<ImportRow> = emptyList(), val parsing: Boolean = false,
    val submitting: Boolean = false, val duplicateCount: Int = 0, val notice: ImportNotice? = null) {
    val selectedCount get() = rows.sumOf { row -> row.media.count { it.selected && it.choice != null } }
    val locked get() = parsing || submitting || duplicateCount > 0
}

class BatchImportViewModel(
    private val repository: VideoRepository = ServiceLocator.videoRepository,
    private val downloader: Downloader = ServiceLocator.downloader,
    private val defaultVariant: (DouyinVideo) -> VideoVariant? = { ServiceLocator.preferences.selectVariant(it) },
) : ViewModel() {
    private val mutable = MutableStateFlow(BatchImportState())
    val state = mutable.asStateFlow()
    private var parseJob: Job? = null
    fun input(value: String) { if (!mutable.value.locked) mutable.update { it.copy(input = value) } }
    fun prepareAndParse() {
        if (mutable.value.locked) return
        val links = VideoLinkExtractor.extractAll(mutable.value.input)
        if (links.isEmpty() || links.size > 50) {
            mutable.update { it.copy(notice = if (links.isEmpty()) ImportNotice.NoLinks else ImportNotice.TooManyLinks) }; return
        }
        mutable.update { it.copy(rows = links.map { link -> ImportRow(link.url) }, notice = null) }
        parseRemaining()
    }
    fun parseRemaining() {
        if (mutable.value.locked) return
        val urls = mutable.value.rows.filter { it.status != ImportStatus.READY }.map { it.url }
        if (urls.isEmpty()) return
        mutable.update { it.copy(parsing = true, notice = null) }
        parseJob = viewModelScope.launch {
            try {
                urls.forEach { url ->
                    change(url) { it.copy(status = ImportStatus.PARSING, error = null, failureKind = null) }
                    try {
                        when (val result = repository.parseVideos(url)) {
                            is AppResult.Success -> {
                                val media = result.value.videos.distinctBy { it.selectionKey() }.map { video ->
                                    ImportMedia("$url|${video.selectionKey()}", video, defaultVariant(video))
                                }
                                change(url) { it.copy(status = if (media.any { item -> item.choice != null }) ImportStatus.READY else ImportStatus.FAILED,
                                    media = media, error = if (media.none { item -> item.choice != null }) ImportRowError.NO_QUALITY else null) }
                            }
                            is AppResult.Failure -> change(url) { it.copy(status = ImportStatus.FAILED,
                                // The failure is typed; the row words it (a parse failure, in practice).
                                error = ImportRowError.PARSE_FAILED, failureKind = result.error.downloadFailureKind()) }
                        }
                    } catch (error: CancellationException) { throw error
                    } catch (_: Exception) { change(url) { it.copy(status = ImportStatus.FAILED, error = ImportRowError.PARSE_FAILED) } }
                }
            } finally {
                mutable.update { it.copy(parsing = false, rows = it.rows.map { row ->
                    if (row.status == ImportStatus.PARSING) row.copy(status = ImportStatus.PENDING) else row }) }
            }
        }
    }
    fun stop() { parseJob?.cancel() }
    fun select(key: String, value: Boolean) = editMedia(key) { it.copy(selected = value) }
    fun choose(key: String, variant: VideoVariant) = editMedia(key) {
        if (variant in (it.video.variants + it.video.audioVariants)) it.copy(choice = variant) else it
    }
    fun selectAll(value: Boolean) {
        if (!mutable.value.locked) mutable.update { it.copy(rows = it.rows.map { row ->
            row.copy(media = row.media.map { item -> item.copy(selected = value && item.choice != null) }) }) }
    }
    fun dismissDuplicates() { if (!mutable.value.submitting) mutable.update { it.copy(duplicateCount = 0) } }
    fun submit(downloadAgain: Boolean? = null) {
        if (mutable.value.parsing || mutable.value.submitting || (mutable.value.duplicateCount > 0 && downloadAgain == null)) return
        val requests = mutable.value.rows.flatMap { it.media }.filter { it.selected && it.choice != null }
        if (requests.isEmpty()) return
        mutable.update { it.copy(submitting = true, duplicateCount = 0, notice = null) }
        viewModelScope.launch {
            try {
                if (downloadAgain == null) {
                    val duplicateCount = requests.count { downloader.findDuplicate(it.video, it.choice!!) != null }
                    if (duplicateCount > 0) { mutable.update { it.copy(duplicateCount = duplicateCount) }; return@launch }
                }
                var queued = 0; var skipped = 0
                val failures = mutableListOf<String>()
                requests.forEach { item ->
                    try {
                        if (downloadAgain != true && downloader.findDuplicate(item.video, item.choice!!) != null) skipped++
                        else { downloader.start(item.video, item.choice!!); queued++ }
                        mutateMedia(item.key) { it.copy(selected = false) }
                    } catch (error: CancellationException) { throw error
                    } catch (_: Exception) { failures += item.video.title }
                }
                // Numbers and titles, not a sentence: the dialog composes the message.
                mutable.update { it.copy(notice = ImportNotice.Queued(queued, skipped, failures.toList())) }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutable.update { it.copy(notice = ImportNotice.RecordsUnavailable) }
            } finally { mutable.update { it.copy(submitting = false) } }
        }
    }
    private fun change(url: String, transform: (ImportRow) -> ImportRow) {
        mutable.update { it.copy(rows = it.rows.map { row -> if (row.url == url) transform(row) else row }) }
    }
    private fun editMedia(key: String, transform: (ImportMedia) -> ImportMedia) {
        if (!mutable.value.locked) mutateMedia(key, transform)
    }
    private fun mutateMedia(key: String, transform: (ImportMedia) -> ImportMedia) {
        mutable.update { it.copy(rows = it.rows.map { row -> row.copy(media = row.media.map { item ->
            if (item.key == key) transform(item) else item }) }) }
    }
}
