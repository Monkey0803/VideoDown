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

data class LibraryOrganizationState(val busy: Boolean = false, val error: LibraryOrganizeError? = null)
class LibraryOrganizationViewModel(private val repository: LibraryMetadataRepository = ServiceLocator.libraryMetadataRepository) : ViewModel() {
    private val mutable = MutableStateFlow(LibraryOrganizationState())
    val state = mutable.asStateFlow()
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun update(
        id: String,
        title: String? = null,
        favorite: Boolean? = null,
        folder: String? = null,
        isPrivate: Boolean? = null,
        onSaved: () -> Unit = {},
    ) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val result = repository.update(id, title, favorite, folder, isPrivate)
                if (result.isSuccess) onSaved()
                else mutable.update { it.copy(error = LibraryOrganizeError.Failed) }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutable.update { it.copy(error = LibraryOrganizeError.Incomplete) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}

/** Why the organise dialog could not save; the dialog words it. */
enum class LibraryOrganizeError { Failed, Incomplete }
