package com.videodown.douyin.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadQueueState
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.canRetryDownload
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.presentation.library.LibraryQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryFilter { ALL, RUNNING, COMPLETED }

/** The batch a user ran; the screen turns this into copy. */
enum class BatchOperation {
    DELETE, PAUSE, RESUME, SAVE_TO_GALLERY, SAVE_TO_MUSIC, SAVE_TO_GALLERY_AND_MUSIC;

    /**
     * Whether a finished batch should leave only its failures selected.
     *
     * Pausing and resuming change nothing about what exists, so the selection is left alone; every
     * other batch either removed items or wrote copies, and retrying just the failures is one tap.
     */
    val keepsFailuresSelected: Boolean get() = this != PAUSE && this != RESUME
}

/** A one-off notice raised by the ViewModel; the screen renders it. */
enum class HistoryNotice { PAUSED, DELETED, CLEARED }

/**
 * One item a batch operation could not handle.
 *
 * [galleryFailureKey] is a key (see `core/ErrorResources.kt`), not a sentence: the batch result
 * outlives the screen that showed it, and the wording belongs to the screen.
 */
data class BatchFailure(val id: String, val title: String?, val galleryFailureKey: String? = null)
data class BatchResult(val operation: BatchOperation, val succeeded: Int, val skipped: Int, val failures: List<BatchFailure>)

data class HistoryUiState(
    val records: List<DownloadRecord> = emptyList(),
    val progress: Map<String, DownloadProgress> = emptyMap(),
    val queue: DownloadQueueState = DownloadQueueState(),
    val filter: HistoryFilter = HistoryFilter.ALL,
    val taskQuery: TaskQuery = TaskQuery(),
    val message: String? = null,
    /** Set by the ViewModel itself; the screen owns the wording. */
    val notice: HistoryNotice? = null,
    val libraryQuery: LibraryQuery = LibraryQuery(),
    val batchBusy: Boolean = false,
    val batchDone: Int = 0,
    val batchTotal: Int = 0,
    val batchResult: BatchResult? = null,
) {
    val visibleRecords: List<DownloadRecord>
        get() {
            val matching = when (filter) {
                HistoryFilter.ALL -> records
                HistoryFilter.RUNNING -> records.filter { statusFor(it) == DownloadStatus.RUNNING || statusFor(it) == DownloadStatus.PENDING }
                HistoryFilter.COMPLETED -> records.filter { statusFor(it) == DownloadStatus.COMPLETED }
            }
            return TaskListOrder.apply(
                records = matching,
                text = taskQuery.text,
                sort = taskQuery.sort,
                statusOf = { record -> statusFor(record) },
                queuePosition = { id -> queue.position(id) },
            )
        }

    val activeCount: Int
        get() = records.count { statusFor(it) == DownloadStatus.RUNNING || statusFor(it) == DownloadStatus.PENDING }

    fun progressFor(id: String): DownloadProgress? = progress[id]

    /** Live progress wins; otherwise fall back to the persisted byte count. */
    fun fractionFor(record: DownloadRecord): Float =
        progress[record.id]?.fraction ?: record.progressFraction

    fun downloadedFor(record: DownloadRecord): Long =
        progress[record.id]?.downloadedBytes ?: record.downloadedBytes

    fun totalFor(record: DownloadRecord): Long =
        progress[record.id]?.totalBytes ?: record.totalBytes

    fun statusFor(record: DownloadRecord): DownloadStatus =
        progress[record.id]?.status ?: record.status
}

class HistoryViewModel(
    private val history: DownloadHistoryRepository = ServiceLocator.historyRepository,
    private val downloader: Downloader = ServiceLocator.downloader,
    private val exportRecord: suspend (DownloadRecord) -> Result<Unit> = { ServiceLocator.galleryExporter.export(it).map { Unit } },
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Force the store to hydrate from disk before observing, so the list is never
            // stuck empty when this screen is the first thing the user opens.
            history.snapshot()
            combine(history.observeAll(), downloader.progress, downloader.queue) { records, progress, queue ->
                Triple(records, progress, queue)
            }.collect { (records, progress, queue) ->
                _state.update { it.copy(records = records, progress = progress, queue = queue) }
            }
        }
    }

    fun moveInQueue(id: String, offset: Int) {
        if (_state.value.batchBusy) return
        viewModelScope.launch { downloader.moveInQueue(id, offset) }
    }

    /** 任务页拖拽后的待下载顺序；只有待下载任务参与，正在进行与已结束的不受影响。 */
    fun reorderQueue(orderedPendingIds: List<String>) {
        if (_state.value.batchBusy || orderedPendingIds.isEmpty()) return
        viewModelScope.launch { downloader.reorderQueue(orderedPendingIds) }
    }

    fun setLibraryQuery(query: LibraryQuery) { _state.update { it.copy(libraryQuery = query) } }

    /**
     * 已存页拖拽后的新顺序，以及自定义排序的「惰性 seed」。
     *
     * seed 由界面发起（只有界面知道哪些条目真的可见），这里只负责落盘。
     */
    fun reorderLibrary(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        viewModelScope.launch { history.reorderLibrary(orderedIds) }
    }

    fun deleteSelected(ids: Set<String>) = runBatch(ids, BatchOperation.DELETE) { record -> downloader.cancel(record.id, removeRecord = true) }
    fun pauseSelected(ids: Set<String>) = runBatch(ids, BatchOperation.PAUSE, { it == DownloadStatus.RUNNING || it == DownloadStatus.PENDING }) {
        downloader.pause(it.id)
    }
    fun resumeSelected(ids: Set<String>) = runBatch(ids, BatchOperation.RESUME, { it == DownloadStatus.PAUSED || it == DownloadStatus.FAILED }, { it.canRetryDownload }) {
        downloader.resume(it.id)
    }
    fun exportSelected(ids: Set<String>, operation: BatchOperation = BatchOperation.SAVE_TO_GALLERY) = runBatch(ids, operation, { it == DownloadStatus.COMPLETED }) {
        exportRecord(it).getOrThrow()
    }

    private fun runBatch(ids: Set<String>, operation: BatchOperation, eligible: (DownloadStatus) -> Boolean = { true },
        eligibleRecord: (DownloadRecord) -> Boolean = { true }, action: suspend (DownloadRecord) -> Unit) {
        if (_state.value.batchBusy || ids.isEmpty()) return
        val targets = ids.toSet()
        _state.update { it.copy(batchBusy = true, batchDone = 0, batchTotal = targets.size, batchResult = null, message = null) }
        viewModelScope.launch {
            var succeeded = 0
            var skipped = 0
            val failures = mutableListOf<BatchFailure>()
            try {
                targets.forEach { id ->
                    var title = _state.value.records.firstOrNull { it.id == id }?.title
                    try {
                        val record = history.getById(id)
                        if (record != null) title = record.title
                        if (record == null || !eligible(_state.value.statusFor(record)) || !eligibleRecord(record)) skipped++
                        else { action(record); succeeded++ }
                    } catch (error: CancellationException) { throw error
                    } catch (error: Exception) {
                        // Only the gallery path has a specific reason; everything else is generic.
                        failures += BatchFailure(id, title, com.videodown.douyin.core.galleryFailureKindOf(error).storageKey)
                    }
                    _state.update { it.copy(batchDone = it.batchDone + 1) }
                }
                _state.update { it.copy(batchResult = BatchResult(operation, succeeded, skipped, failures.toList())) }
            } finally { _state.update { it.copy(batchBusy = false) } }
        }
    }

    fun setFilter(filter: HistoryFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun setTaskQuery(query: TaskQuery) {
        _state.update { it.copy(taskQuery = query) }
    }

    fun showMessage(message: String) { _state.update { it.copy(message = message) } }

    fun onMessageConsumed() {
        _state.update { it.copy(message = null) }
    }

    fun onNoticeConsumed() { _state.update { it.copy(notice = null) } }

    fun resume(id: String) {
        if (_state.value.batchBusy) return
        viewModelScope.launch {
            downloader.resume(id)
        }
    }

    fun pause(id: String) {
        if (_state.value.batchBusy) return
        viewModelScope.launch {
            downloader.pause(id)
            _state.update { it.copy(notice = HistoryNotice.PAUSED) }
        }
    }

    /** Per-task rate cap in KB/s; `0` follows the global setting. */
    fun setSpeedLimit(id: String, kbps: Int) {
        if (_state.value.batchBusy) return
        viewModelScope.launch { downloader.setSpeedLimit(id, kbps) }
    }

    fun restart(id: String) {
        if (_state.value.batchBusy) return
        viewModelScope.launch { downloader.restart(id) }
    }

    fun delete(id: String) {
        if (_state.value.batchBusy) return
        viewModelScope.launch {
            downloader.cancel(id, removeRecord = true)
            _state.update { it.copy(notice = HistoryNotice.DELETED) }
        }
    }

    fun resumeAll() {
        if (_state.value.batchBusy) return
        viewModelScope.launch { downloader.resumeAll() }
    }

    fun pauseAll() {
        if (_state.value.batchBusy) return
        viewModelScope.launch { downloader.pauseAll() }
    }

    fun clearAll() {
        if (_state.value.batchBusy) return
        viewModelScope.launch {
            downloader.pauseAll()
            // Cancellation also removes engine fragment directories, which are not MP4 .part files.
            history.snapshot().forEach { downloader.cancel(it.id, removeRecord = true) }
            history.clear(deleteFiles = true)
            _state.update { it.copy(notice = HistoryNotice.CLEARED) }
        }
    }
}
