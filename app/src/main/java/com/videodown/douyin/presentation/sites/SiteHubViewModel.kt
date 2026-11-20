package com.videodown.douyin.presentation.sites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.site.SiteSourceRegistry
import com.videodown.douyin.domain.model.SiteSource
import com.videodown.douyin.domain.model.SiteWatchRecord
import com.videodown.douyin.domain.repository.SiteWatchHistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SiteHubState(
    val sources: List<SiteSource> = emptyList(),
    val history: List<SiteWatchRecord> = emptyList(),
    val loading: Boolean = true,
)

class SiteHubViewModel(
    private val registry: SiteSourceRegistry = ServiceLocator.siteSources,
    private val history: SiteWatchHistoryRepository = ServiceLocator.siteWatchHistory,
) : ViewModel() {
    private val _state = MutableStateFlow(SiteHubState(sources = registry.all))
    val state: StateFlow<SiteHubState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            history.load()
            history.records.collect { records -> _state.update { it.copy(history = records, loading = false) } }
        }
    }

    fun delete(id: String) { viewModelScope.launch { history.delete(id) } }
    fun restore(record: SiteWatchRecord) { viewModelScope.launch { history.upsert(record) } }
    fun clear() { viewModelScope.launch { history.clear() } }
}
