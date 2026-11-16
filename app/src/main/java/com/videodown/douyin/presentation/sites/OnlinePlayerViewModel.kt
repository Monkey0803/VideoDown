package com.videodown.douyin.presentation.sites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.site.OnlinePlaybackSessionStore
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.OnlinePlaybackSession
import com.videodown.douyin.domain.model.SiteWatchRecord
import com.videodown.douyin.domain.model.siteWatchId
import com.videodown.douyin.domain.model.toDownloadVideo
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.SiteWatchHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnlinePlayerState(
    val session: OnlinePlaybackSession? = null,
    val loading: Boolean = true,
    val duplicate: DownloadRecord? = null,
    val downloadQueued: Boolean = false,
    val downloadFailed: Boolean = false,
)

class OnlinePlayerViewModel(
    private val sessions: OnlinePlaybackSessionStore = ServiceLocator.onlinePlaybackSessions,
    private val history: SiteWatchHistoryRepository = ServiceLocator.siteWatchHistory,
    private val downloader: Downloader = ServiceLocator.downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(OnlinePlayerState())
    val state: StateFlow<OnlinePlayerState> = _state.asStateFlow()
    private var loadedId: String? = null

    fun load(id: String) {
        if (loadedId == id) return
        loadedId = id
        _state.value = OnlinePlayerState(session = sessions.get(id), loading = false)
    }

    fun saveProgress(positionMs: Long, durationMs: Long, ended: Boolean) {
        val session = _state.value.session ?: return
        val safeDuration = durationMs.takeIf { it > 0L } ?: session.video.durationMs
        val record = SiteWatchRecord(
            id = siteWatchId(session.siteId, session.pageUrl),
            siteId = session.siteId,
            pageUrl = session.pageUrl,
            title = session.video.title,
            coverUrl = session.video.coverUrl,
            durationMs = safeDuration.coerceAtLeast(0L),
            positionMs = if (ended) 0L else positionMs.coerceAtLeast(0L),
        )
        viewModelScope.launch { history.upsert(record) }
    }

    fun download() {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            try {
                val downloadVideo = session.toDownloadVideo()
                val duplicate = downloader.findDuplicate(downloadVideo, session.variant)
                if (duplicate != null) {
                    val refreshed = downloader.refreshMetadata(duplicate.id, downloadVideo) ?: duplicate
                    _state.update { it.copy(duplicate = refreshed) }
                }
                else enqueue(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(downloadFailed = true) }
            }
        }
    }

    fun dismissDuplicate() { _state.update { it.copy(duplicate = null) } }
    fun confirmDuplicate() {
        val session = _state.value.session ?: return
        _state.update { it.copy(duplicate = null) }
        viewModelScope.launch {
            try { enqueue(session) } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { _state.update { it.copy(downloadFailed = true) } }
        }
    }

    fun consumeMessage() { _state.update { it.copy(downloadQueued = false, downloadFailed = false) } }

    private suspend fun enqueue(session: OnlinePlaybackSession) {
        downloader.start(session.toDownloadVideo(), session.variant)
        _state.update { it.copy(downloadQueued = true) }
    }

    override fun onCleared() {
        loadedId?.let(sessions::remove)
        super.onCleared()
    }
}
