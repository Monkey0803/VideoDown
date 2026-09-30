package com.videodown.douyin.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.repository.FileExportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FileExportState(
    val busy: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failedIds: Set<String> = emptySet(),
    val message: String? = null,
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
            var success = 0
            try {
                selected.forEach { id ->
                    val result = try { repository.export(id, treeUri) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { Result.failure(error) }
                    if (result.isSuccess) success++ else failed.add(id)
                    mutable.update { it.copy(done = it.done + 1, failedIds = failed.toSet()) }
                }
                val unresolved = repository.pendingCleanupCount()
                val recoveryMessage = if (unresolved > 0) "；另有 $unresolved 个中断副本暂时无法清理，请重新授权原文件夹后重试" else ""
                mutable.update { it.copy(finished = true, message = "已导出 $success 项" +
                    (if (failed.isNotEmpty()) "，${failed.size} 项失败；请检查目标权限、文件和空间，可重新选择文件夹后重试" else "，原文件已保留") + recoveryMessage) }
            } catch (error: CancellationException) {
                mutable.update { it.copy(finished = true, failedIds = selected.drop(it.done).toSet() + failed,
                    message = "已停止，已导出 $success 项；未完成项可重试") }
                throw error
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
