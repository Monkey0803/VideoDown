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
import com.videodown.douyin.core.galleryErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryFilter(val label: String) {
    ALL("全部"),
    RUNNING("进行中"),
    COMPLETED("已完成"),
}

data class BatchFailure(val id: String, val title: String, val reason: String)
data class BatchResult(val operation: String, val succeeded: Int, val skipped: Int, val failures: List<BatchFailure>)

data class HistoryUiState(
    val records: List<DownloadRecord> = emptyList(),
    val progress: Map<String, DownloadProgress> = emptyMap(),
    val queue: DownloadQueueState = DownloadQueueState(),
    val filter: HistoryFilter = HistoryFilter.ALL,
    val taskQuery: TaskQuery = TaskQuery(),
    val message: String? = null,
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

    fun setLibraryQuery(query: LibraryQuery) { _state.update { it.copy(libraryQuery = query) } }

    fun deleteSelected(ids: Set<String>) = runBatch(ids, "删除") { record -> downloader.cancel(record.id, removeRecord = true) }
    fun pauseSelected(ids: Set<String>) = runBatch(ids, "暂停", { it == DownloadStatus.RUNNING || it == DownloadStatus.PENDING }) {
        downloader.pause(it.id)
    }
    fun resumeSelected(ids: Set<String>) = runBatch(ids, "继续", { it == DownloadStatus.PAUSED || it == DownloadStatus.FAILED }, { it.canRetryDownload }) {
        downloader.resume(it.id)
    }
    fun exportSelected(ids: Set<String>, operation: String = "保存到相册") = runBatch(ids, operation, { it == DownloadStatus.COMPLETED }) {
        exportRecord(it).getOrThrow()
    }

    private fun runBatch(ids: Set<String>, label: String, eligible: (DownloadStatus) -> Boolean = { true },
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
                    var title = _state.value.records.firstOrNull { it.id == id }?.title ?: "视频"
                    try {
                        val record = history.getById(id)
                        if (record != null) title = record.title
                        if (record == null || !eligible(_state.value.statusFor(record)) || !eligibleRecord(record)) skipped++
                        else { action(record); succeeded++ }
                    } catch (error: CancellationException) { throw error
                    } catch (error: Exception) { failures += BatchFailure(id, title, if (label in setOf("保存到相册", "保存到音乐", "保存到相册/音乐")) error.galleryErrorMessage() else "操作失败，请重试") }
                    _state.update { it.copy(batchDone = it.batchDone + 1) }
                }
                _state.update { it.copy(batchResult = BatchResult(label, succeeded, skipped, failures.toList()), message = buildString {
                    append("$label：成功 $succeeded 项")
                    if (skipped > 0) append("，跳过 $skipped 项")
                    if (failures.isNotEmpty()) append("，失败 ${failures.size} 项（${failures.map { it.reason }.distinct().take(2).joinToString("；")}）")
                }) }
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
            _state.update { it.copy(message = "已暂停，可稍后继续") }
        }
    }

    fun restart(id: String) {
        if (_state.value.batchBusy) return
        viewModelScope.launch { downloader.restart(id) }
    }

    fun delete(id: String) {
        if (_state.value.batchBusy) return
        viewModelScope.launch {
            downloader.cancel(id, removeRecord = true)
            _state.update { it.copy(message = "已删除记录和文件") }
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
            _state.update { it.copy(message = "已清空下载历史") }
        }
    }
}
