package com.videodown.douyin.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.repository.FileExportRepository
import com.videodown.douyin.domain.repository.FileExportFailureKind
import com.videodown.douyin.domain.repository.fileExportFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the check for interrupted copies ended; the screen words each case. */
sealed interface ExportCleanup {
    data object None : ExportCleanup
    data class Pending(val count: Int) : ExportCleanup
    data object Unknown : ExportCleanup
}

/**
 * What an export run did.
 *
 * Reported as numbers and reasons rather than as a sentence: the wording belongs to the screen, and
 * a new failure kind must not be able to slip past the wording unnoticed.
 */
data class FileExportResult(
    val succeeded: Int,
    val failed: Int,
    val reasons: Set<FileExportFailureKind>,
    val cleanup: ExportCleanup,
    val cancelled: Boolean,
)

data class FileExportState(
    val busy: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failedIds: Set<String> = emptySet(),
    val failureCounts: Map<FileExportFailureKind, Int> = emptyMap(),
    val invalidTreeUri: String? = null,
    /** Set only when the folder itself could not be used before any export started. */
    val message: String? = null,
    val result: FileExportResult? = null,
    val finished: Boolean = false,
)
class FileExportViewModel(private val repository: FileExportRepository = ServiceLocator.fileExportRepository) : ViewModel() {
    val sessionId: String = java.util.UUID.randomUUID().toString()
    private val mutable = MutableStateFlow(FileExportState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun reset() { if (!mutable.value.busy) mutable.value = FileExportState() }
    fun message(value: String) { mutable.update { it.copy(message = value) } }
    fun cancel() { job?.cancel() }
    fun export(ids: List<String>, treeUri: String) {
        if (mutable.value.busy) return
        val selected = ids.distinct()
        mutable.value = FileExportState(busy = true, total = selected.size)
        job = viewModelScope.launch {
            val failed = mutableSetOf<String>()
            val failures = mutableMapOf<FileExportFailureKind, Int>()
            var success = 0
            try {
                selected.forEach { id ->
                    val result = try { repository.export(id, treeUri) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { Result.failure(error) }
                    if (result.isSuccess) success++ else {
                        failed.add(id)
                        val kind = fileExportFailureKind(checkNotNull(result.exceptionOrNull()))
                        failures[kind] = failures.getOrDefault(kind, 0) + 1
                    }
                    mutable.update { it.copy(done = it.done + 1, failedIds = failed.toSet(), failureCounts = failures.toMap(),
                        invalidTreeUri = if (FileExportFailureKind.ACCESS_LOST in failures) treeUri else null) }
                }
                val unresolved = try { repository.pendingCleanupCount() }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { -1 }
                // Nothing here is a sentence: the screen words the numbers and the reason kinds.
                mutable.update { it.copy(finished = true, result = FileExportResult(
                    succeeded = success,
                    failed = failed.size,
                    reasons = failures.keys.toSet(),
                    cleanup = when {
                        unresolved > 0 -> ExportCleanup.Pending(unresolved)
                        unresolved < 0 -> ExportCleanup.Unknown
                        else -> ExportCleanup.None
                    },
                    cancelled = false,
                )) }
            } catch (error: CancellationException) {
                mutable.update { it.copy(finished = true, failedIds = selected.drop(it.done).toSet() + failed,
                    result = FileExportResult(
                        succeeded = success,
                        failed = failed.size,
                        reasons = failures.keys.toSet(),
                        cleanup = ExportCleanup.None,
                        cancelled = true,
                    )) }
                throw error
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
