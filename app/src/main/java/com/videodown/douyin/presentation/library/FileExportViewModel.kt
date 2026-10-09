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

data class FileExportState(
    val busy: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failedIds: Set<String> = emptySet(),
    val failureCounts: Map<FileExportFailureKind, Int> = emptyMap(),
    val invalidTreeUri: String? = null,
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
                val recoveryMessage = when {
                    unresolved > 0 -> "；另有 $unresolved 个中断副本暂时无法清理，请重新授权原文件夹后重试"
                    unresolved < 0 -> "；无法检查中断副本，请重新授权原文件夹后重试清理"
                    else -> ""
                }
                val reasons = buildList {
                    if (FileExportFailureKind.ACCESS_LOST in failures) add("文件夹不可写或授权已失效，请重新选择文件夹")
                    if (FileExportFailureKind.SPACE_FULL in failures) add("目标空间不足，请释放空间或选择其他文件夹后重试")
                    if (FileExportFailureKind.OTHER in failures) add("请检查本地文件和目标文件夹后重试")
                }.joinToString("；")
                mutable.update { it.copy(finished = true, message = "已导出 $success 项" +
                    (if (failed.isNotEmpty()) "，${failed.size} 项失败；$reasons；本地原文件未修改" else "，原文件已保留") + recoveryMessage) }
            } catch (error: CancellationException) {
                mutable.update { it.copy(finished = true, failedIds = selected.drop(it.done).toSet() + failed,
                    message = "已停止，已导出 $success 项；未完成项可重试") }
                throw error
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
