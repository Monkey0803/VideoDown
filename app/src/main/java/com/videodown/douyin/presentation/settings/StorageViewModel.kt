package com.videodown.douyin.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.StorageUsage
import com.videodown.douyin.domain.repository.StorageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageUiState(val usage: StorageUsage? = null, val busy: Boolean = false, val message: String? = null)

class StorageViewModel(private val storage: StorageRepository = ServiceLocator.storageRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(StorageUiState())
    val state = mutableState.asStateFlow()
    fun refresh() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val usage = storage.inspect()
                mutableState.update { it.copy(usage = usage) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutableState.update { it.copy(message = "无法读取存储空间，请稍后刷新") }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    fun clean(plan: StorageUsage) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val result = storage.clean(plan)
                mutableState.update { it.copy(message = "已清理 ${result.deletedEntries} 项残留文件和 ${result.deletedRecords} 条无效记录，释放 ${com.videodown.douyin.core.formatBytes(result.freedBytes)}" +
                    if (result.failed + result.skipped > 0) "；跳过 ${result.skipped} 项，失败 ${result.failed} 项" else "") }
                val usage = storage.inspect()
                mutableState.update { it.copy(usage = usage) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutableState.update { it.copy(message = "清理未完成，已保留未清理项目，请刷新后重试") }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
