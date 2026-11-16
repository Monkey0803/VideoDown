package com.videodown.douyin.presentation.sites

import android.os.Bundle
import android.os.Looper
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.AppError
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.generic.GenericVideoRepository
import com.videodown.douyin.data.site.OnlinePlaybackSessionStore
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.SiteSource
import com.videodown.douyin.domain.model.SitePageMetadata
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.model.canonicalSitePageUrl
import com.videodown.douyin.domain.model.siteWatchId
import com.videodown.douyin.domain.model.withSitePageMetadata
import com.videodown.douyin.domain.repository.SiteWatchHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SiteMediaChoice(val video: DouyinVideo, val variant: VideoVariant)

data class SiteBrowserState(
    val parsing: Boolean = false,
    val error: AppError? = null,
    val choices: List<SiteMediaChoice> = emptyList(),
    val readySessionId: String? = null,
)

class SiteBrowserViewModel(
    private val repository: GenericVideoRepository = ServiceLocator.genericRepository,
    private val sessions: OnlinePlaybackSessionStore = ServiceLocator.onlinePlaybackSessions,
    private val history: SiteWatchHistoryRepository = ServiceLocator.siteWatchHistory,
) : ViewModel() {
    private val _state = MutableStateFlow(SiteBrowserState())
    val state: StateFlow<SiteBrowserState> = _state.asStateFlow()
    private var parseJob: Job? = null
    private var currentTarget: String? = null
    private val pageMetadata = mutableMapOf<String, SitePageMetadata>()
    var browserSnapshot: BrowserSnapshot? = null
        private set
    private var retainedWebView: WebView? = null

    fun retainedWebView(): WebView? = retainedWebView

    fun retainWebView(view: WebView) {
        retainedWebView = view
    }

    fun destroyRetainedWebView() {
        val view = retainedWebView ?: return
        retainedWebView = null
        val destroy = { view.stopLoading(); view.loadUrl("about:blank"); view.clearHistory(); view.removeAllViews(); view.destroy() }
        if (Looper.myLooper() == Looper.getMainLooper()) destroy() else view.post(destroy)
    }

    fun saveBrowserState(state: Bundle, scrollX: Int, scrollY: Int) {
        browserSnapshot = BrowserSnapshot(state, scrollX, scrollY)
    }

    fun isResolvingOrResolved(): Boolean = parseJob?.isActive == true ||
        _state.value.choices.isNotEmpty() || _state.value.readySessionId != null

    fun onPageStarted() {
        parseJob?.cancel()
        parseJob = null
        currentTarget = null
        _state.value = SiteBrowserState()
    }

    fun updatePageMetadata(siteId: String, pageUrl: String, metadata: SitePageMetadata) {
        val canonical = canonicalSitePageUrl(pageUrl)
        pageMetadata[canonical] = metadata
        _state.update { state ->
            state.copy(choices = state.choices.map { choice ->
                choice.copy(video = choice.video.withSitePageMetadata(metadata))
            })
        }
        viewModelScope.launch {
            val id = siteWatchId(siteId, canonical)
            val record = history.find(id) ?: return@launch
            val enriched = DouyinVideo(
                awemeId = id,
                title = record.title,
                coverUrl = record.coverUrl,
            ).withSitePageMetadata(metadata)
            if (enriched.title != record.title || enriched.coverUrl != record.coverUrl) {
                history.upsert(record.copy(title = enriched.title, coverUrl = enriched.coverUrl))
            }
        }
    }

    fun parse(
        pageUrl: String,
        targetUrl: String,
        userAgent: String,
        source: SiteSource,
        requireSelection: Boolean = false,
    ) {
        val canonicalPage = canonicalSitePageUrl(pageUrl)
        if (!source.allowsNavigation(canonicalPage) || !source.acceptsMediaUrl(targetUrl)) return
        if (currentTarget == targetUrl && (_state.value.parsing || _state.value.readySessionId != null)) return
        currentTarget = targetUrl
        parseJob?.cancel()
        _state.value = SiteBrowserState(parsing = true)
        parseJob = viewModelScope.launch {
            try {
                when (val result = repository.parse(targetUrl, browserSession = false, referer = canonicalPage, userAgent = userAgent)) {
                    is AppResult.Success -> {
                        val metadata = pageMetadata[canonicalPage]
                        val choices = result.value.videos.flatMap { parsed ->
                            val video = parsed.withSitePageMetadata(metadata)
                            (video.variants + video.audioVariants)
                                .filter { it.mediaKind == MediaKind.VIDEO && it.media?.audioOnly != true }
                                .map { SiteMediaChoice(video, it) }
                        }.distinctBy { it.video.awemeId to it.variant.label }
                        if (choices.size == 1 && !requireSelection) choose(source.id, canonicalPage, userAgent, choices.single())
                        else _state.value = SiteBrowserState(choices = choices, error = if (choices.isEmpty()) null else null)
                    }
                    is AppResult.Failure -> _state.value = SiteBrowserState(error = result.error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    fun choose(siteId: String, pageUrl: String, userAgent: String, choice: SiteMediaChoice) {
        viewModelScope.launch {
            val canonical = canonicalSitePageUrl(pageUrl)
            val video = choice.video.withSitePageMetadata(pageMetadata[canonical])
            val resume = history.find(siteWatchId(siteId, canonical))?.resumePositionMs ?: 0L
            val session = sessions.create(siteId, canonical, video, choice.variant, canonical, userAgent, resume)
            _state.value = SiteBrowserState(readySessionId = session.id)
        }
    }

    fun consumeSession() { _state.update { it.copy(readySessionId = null) } }
    fun dismissChoices() { _state.update { it.copy(choices = emptyList()) } }
    fun clearError() { _state.update { it.copy(error = null) } }

    override fun onCleared() {
        parseJob?.cancel()
        destroyRetainedWebView()
        super.onCleared()
    }
}

data class BrowserSnapshot(
    val state: Bundle,
    val scrollX: Int,
    val scrollY: Int,
)
