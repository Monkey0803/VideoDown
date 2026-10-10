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

/** What a storage action did; the dialog words it. */
sealed interface StorageNotice {
    data object InspectFailed : StorageNotice
    data object CleanIncomplete : StorageNotice
    data class Cleaned(val deletedEntries: Int, val deletedRecords: Int, val freedBytes: Long, val skipped: Int, val failed: Int) : StorageNotice
}

data class StorageUiState(val usage: StorageUsage? = null, val busy: Boolean = false, val notice: StorageNotice? = null)

class StorageViewModel(private val storage: StorageRepository = ServiceLocator.storageRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(StorageUiState())
    val state = mutableState.asStateFlow()
    fun refresh() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                val usage = storage.inspect()
                mutableState.update { it.copy(usage = usage) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutableState.update { it.copy(notice = StorageNotice.InspectFailed) }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    fun clean(plan: StorageUsage) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                val result = storage.clean(plan)
                // Numbers rather than a sentence: the dialog composes it.
                mutableState.update { it.copy(notice = StorageNotice.Cleaned(
                    deletedEntries = result.deletedEntries,
                    deletedRecords = result.deletedRecords,
                    freedBytes = result.freedBytes,
                    skipped = result.skipped,
                    failed = result.failed,
                )) }
                val usage = storage.inspect()
                mutableState.update { it.copy(usage = usage) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutableState.update { it.copy(notice = StorageNotice.CleanIncomplete) }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
