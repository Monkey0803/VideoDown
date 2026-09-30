package com.videodown.douyin.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.repository.LibraryMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryOrganizationState(val busy: Boolean = false, val error: String? = null)
class LibraryOrganizationViewModel(private val repository: LibraryMetadataRepository = ServiceLocator.libraryMetadataRepository) : ViewModel() {
    private val mutable = MutableStateFlow(LibraryOrganizationState())
    val state = mutable.asStateFlow()
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun update(id: String, title: String? = null, favorite: Boolean? = null, folder: String? = null, onSaved: () -> Unit = {}) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val result = repository.update(id, title, favorite, folder)
                if (result.isSuccess) onSaved()
                else mutable.update { it.copy(error = "整理失败，请检查名称、文件和可用空间后重试") }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutable.update { it.copy(error = "整理未完成，请稍后重试") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
