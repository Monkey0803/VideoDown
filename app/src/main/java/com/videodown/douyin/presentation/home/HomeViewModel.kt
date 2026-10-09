package com.videodown.douyin.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.downloadFailureKind
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.data.remote.ClipboardLinkPolicy
import com.videodown.douyin.data.remote.VideoSizeProbe
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.model.selectionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadRequest(val video: DouyinVideo, val variant: VideoVariant)
data class DuplicateDownloads(val requests: List<DownloadRequest>, val records: Map<String, DownloadRecord>)

data class HomeUiState(
    val input: String = "",
    val isParsing: Boolean = false,
    val video: DouyinVideo? = null,
    val videos: List<DouyinVideo> = emptyList(),
    val selectedVariant: VideoVariant? = null,
    val isLoadingSizes: Boolean = false,
    val selectedMedia: Set<String> = emptySet(),
    val variantChoices: Map<String, VideoVariant> = emptyMap(),
    val isSubmitting: Boolean = false,
    val duplicates: DuplicateDownloads? = null,
    val queuedCount: Int? = null,
    val errorMessage: String? = null,
    val errorKind: DownloadFailureKind? = null,
    val browserRequestUrl: String? = null,
    /** Retained after fast parse completion so old browser overlays can be dismissed. */
    val parseRequestId: Long = 0,
    val infoMessage: String? = null,
    /** Id of the record created by the most recent "download" tap. */
    val activeDownloadId: String? = null,
    val activeProgress: DownloadProgress? = null,
    val activeRecord: DownloadRecord? = null,
    /** Link copied elsewhere and offered for parsing; shown as a dismissible banner. */
    val clipboardOffer: String? = null,
) {
    val canParse: Boolean get() = input.isNotBlank() && !isParsing
    val canDownload: Boolean get() = video != null && selectedVariant != null && !isSubmitting && duplicates == null

    val isDownloadActive: Boolean
        get() = activeProgress?.isActive == true

    val isDownloadFinished: Boolean
        get() = activeRecord?.status == DownloadStatus.COMPLETED

    val isDownloadPaused: Boolean
        get() = activeRecord?.status == DownloadStatus.PAUSED || activeRecord?.status == DownloadStatus.FAILED
}

class HomeViewModel(
    private val videoRepository: VideoRepository = ServiceLocator.videoRepository,
    private val downloader: Downloader = ServiceLocator.downloader,
    private val selectDefaultVariant: (DouyinVideo) -> VideoVariant? = { ServiceLocator.preferences.selectVariant(it) },
    private val fetchSize: suspend (VideoVariant) -> Long? = { VideoSizeProbe(ServiceLocator.douyinApiClient.okHttpClient).sizeBytes(it) },
    private val clipboardDetectEnabled: () -> Boolean = { ServiceLocator.preferences.clipboardDetect },
    private val lastClipboardOffer: () -> String? = { ServiceLocator.preferences.lastClipboardOffer },
    private val rememberDismissedClipboard: (String) -> Unit = { ServiceLocator.preferences.lastClipboardOffer = it },
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var parseJob: Job? = null
    private var sizeJob: Job? = null
    private var observeJob: Job? = null
    private var submissionJob: Job? = null
    private var resultGeneration = 0L

    fun requestBrowserAssistance(url: String) {
        clearResult()
        _state.update { it.copy(input = url, browserRequestUrl = url) }
    }
    fun onBrowserRequestConsumed() { _state.update { it.copy(browserRequestUrl = null) } }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value, errorMessage = null, errorKind = null) }
    }

    fun onErrorConsumed() {
        _state.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    /**
     * Offers a copied link for parsing instead of parsing it outright.
     *
     * Reading the clipboard is only ever a hint: acting on it without a tap would download a video
     * the user never asked for. The offer is also skipped whenever the screen is already busy, so a
     * share intent or an in-flight parse is never interrupted.
     */
    fun offerClipboardText(text: String) {
        if (!clipboardDetectEnabled()) return
        val current = _state.value
        if (current.clipboardOffer != null || current.isParsing || current.video != null || current.input.isNotBlank()) return
        val candidate = ClipboardLinkPolicy.candidate(text, lastClipboardOffer()) ?: return
        _state.update { it.copy(clipboardOffer = candidate.text) }
    }

    fun acceptClipboardOffer() {
        val offer = _state.value.clipboardOffer ?: return
        _state.update { it.copy(clipboardOffer = null) }
        parseSharedText(offer)
    }

    fun dismissClipboardOffer() {
        val offer = _state.value.clipboardOffer ?: return
        ClipboardLinkPolicy.candidate(offer, null)?.let { rememberDismissedClipboard(it.url) }
        _state.update { it.copy(clipboardOffer = null) }
    }

    /** Called when the app receives a share intent; parses immediately. */
    fun parseSharedText(text: String) {
        _state.update { it.copy(input = text, errorMessage = null, video = null, selectedVariant = null,
            activeDownloadId = null, activeProgress = null, activeRecord = null, clipboardOffer = null) }
        parse()
    }

    fun parse() {
        val text = _state.value.input.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(errorMessage = "请先粘贴视频网址或分享文案") }
            return
        }

        resultGeneration++
        submissionJob?.cancel()
        parseJob?.cancel()
        sizeJob?.cancel()
        observeJob?.cancel()
        _state.update {
            it.copy(parseRequestId = it.parseRequestId + 1, isParsing = true, isLoadingSizes = false, errorMessage = null, errorKind = null, browserRequestUrl = null, infoMessage = null,
                video = null, videos = emptyList(), selectedMedia = emptySet(), variantChoices = emptyMap(),
                duplicates = null, isSubmitting = false, queuedCount = null, selectedVariant = null, activeDownloadId = null, activeProgress = null,
                activeRecord = null)
        }

        parseJob = viewModelScope.launch {
            when (val result = videoRepository.parseVideos(text)) {
                is AppResult.Success -> {
                    val video = result.value.selectedVideo
                    _state.update {
                        it.copy(
                            isParsing = false,
                            video = video,
                            videos = result.value.videos,
                            selectedMedia = emptySet(),
                            variantChoices = result.value.videos.mapNotNull { item -> selectDefaultVariant(item)?.let { item.selectionKey() to it } }.toMap(),
                            selectedVariant = selectDefaultVariant(video),
                            isLoadingSizes = video.sortedVariants.any { (it.sizeBytes ?: 0L) <= 0L },
                            errorMessage = if (video.variants.isEmpty()) "未能解析出可下载的视频地址" else null,
                        )
                    }
                    if (_state.value.isLoadingSizes) loadSizes(video)
                }

                is AppResult.Failure -> {
                    _state.update { it.copy(isParsing = false, errorMessage = result.error.message,
                        errorKind = if (result.error is com.videodown.douyin.core.AppError.InvalidLink) null else result.error.downloadFailureKind()) }
                }
            }
        }
    }

    fun acceptBrowserResult(videos: com.videodown.douyin.domain.model.ParsedVideos) {
        resultGeneration++
        submissionJob?.cancel(); parseJob?.cancel(); sizeJob?.cancel(); observeJob?.cancel()
        val video = videos.selectedVideo
        _state.update { it.copy(video = video, videos = videos.videos, selectedVariant = selectDefaultVariant(video),
            selectedMedia = emptySet(),
            variantChoices = videos.videos.mapNotNull { item -> selectDefaultVariant(item)?.let { item.selectionKey() to it } }.toMap(),
            duplicates = null, isSubmitting = false, queuedCount = null,
            isParsing = false, isLoadingSizes = false, errorMessage = null, errorKind = null, browserRequestUrl = null, activeDownloadId = null,
            activeProgress = null, activeRecord = null) }
    }

    fun selectVideo(index: Int) {
        if (_state.value.isSubmitting || _state.value.duplicates != null) return
        val video = _state.value.videos.getOrNull(index) ?: return
        if (_state.value.video?.awemeId == video.awemeId && _state.value.video?.platform == video.platform) return
        sizeJob?.cancel()
        observeJob?.cancel()
        _state.update { it.copy(video = video, selectedVariant = it.variantChoices[video.selectionKey()] ?: selectDefaultVariant(video),
            isLoadingSizes = video.sortedVariants.any { variant -> (variant.sizeBytes ?: 0) <= 0 },
            errorMessage = null, infoMessage = null, activeDownloadId = null, activeProgress = null, activeRecord = null) }
        if (_state.value.isLoadingSizes) loadSizes(video)
    }

    private fun loadSizes(video: DouyinVideo) {
        sizeJob = viewModelScope.launch {
            val semaphore = Semaphore(3)
            coroutineScope {
                video.sortedVariants.filter { (it.sizeBytes ?: 0L) <= 0L }.map { variant ->
                    async {
                        val bytes = semaphore.withPermit { fetchSize(variant) }
                        if (bytes != null) {
                            _state.update { current ->
                                val activeVideo = current.video ?: return@update current
                                if (activeVideo.platform != video.platform || activeVideo.awemeId != video.awemeId) return@update current
                                val variants = activeVideo.variants.map {
                                    if (it.url == variant.url) it.copy(sizeBytes = bytes) else it
                                }
                                val selected = current.selectedVariant?.let { selection ->
                                    variants.firstOrNull {
                                        it.label == selection.label && it.url == selection.url
                                    } ?: selection
                                }
                                val updated = activeVideo.copy(variants = variants)
                                current.copy(video = updated, selectedVariant = selected,
                                    videos = current.videos.map { if (it.platform == video.platform && it.awemeId == video.awemeId) updated else it })
                            }
                        }
                    }
                }.awaitAll()
            }
            _state.update { it.copy(isLoadingSizes = false) }
        }
    }

    fun selectVariant(variant: VideoVariant) {
        if (_state.value.isSubmitting || _state.value.duplicates != null) return
        val video = _state.value.video ?: return
        if ((video.variants + video.audioVariants).contains(variant)) _state.update { it.copy(selectedVariant = variant,
            variantChoices = it.variantChoices + (video.selectionKey() to variant),
            activeDownloadId = null, activeRecord = null, activeProgress = null) }
    }

    fun toggleMedia(key: String) {
        if (_state.value.isSubmitting || _state.value.duplicates != null) return
        _state.update { it.copy(selectedMedia = if (key in it.selectedMedia) it.selectedMedia - key else it.selectedMedia + key) }
    }

    fun selectAllMedia() {
        if (_state.value.isSubmitting || _state.value.duplicates != null) return
        _state.update { current ->
            val keys = current.videos.filter { current.variantChoices[it.selectionKey()] != null }.map { it.selectionKey() }.toSet()
            current.copy(selectedMedia = if (current.selectedMedia == keys) emptySet() else keys)
        }
    }

    fun download() {
        val video = _state.value.video ?: return
        val variant = _state.value.selectedVariant ?: return
        submit(listOf(DownloadRequest(video, variant)))
    }

    fun downloadSelected() {
        val state = _state.value
        submit(state.videos.distinctBy { it.selectionKey() }.filter { it.selectionKey() in state.selectedMedia }.mapNotNull { video ->
            state.variantChoices[video.selectionKey()]?.let { DownloadRequest(video, it) }
        })
    }

    private fun submit(requests: List<DownloadRequest>) {
        if (requests.isEmpty() || _state.value.isSubmitting || _state.value.duplicates != null) return
        _state.update { it.copy(isSubmitting = true, infoMessage = null, errorMessage = null) }
        val generation = resultGeneration
        submissionJob = viewModelScope.launch {
            try {
                val duplicates = requests.mapNotNull { request -> downloader.findDuplicate(request.video, request.variant)?.let {
                    request.video.selectionKey() to it
                } }.toMap()
                if (duplicates.isNotEmpty()) _state.update { it.copy(duplicates = DuplicateDownloads(requests, duplicates)) }
                else enqueue(requests, generation)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                _state.update { it.copy(errorMessage = "无法加入下载队列，请重试") }
            } finally { if (generation == resultGeneration) _state.update { it.copy(isSubmitting = false) } }
        }
    }

    fun dismissDuplicates() { _state.update { it.copy(duplicates = null) } }
    fun onQueuedConsumed() { _state.update { it.copy(queuedCount = null) } }

    fun confirmDuplicates(downloadAgain: Boolean) {
        val pending = _state.value.duplicates ?: return
        if (_state.value.isSubmitting) return
        _state.update { it.copy(duplicates = null, isSubmitting = true) }
        val generation = resultGeneration
        submissionJob = viewModelScope.launch {
            try {
                // Recheck: an existing file/task may have been removed while the dialog was open.
                val requests = if (downloadAgain) pending.requests else pending.requests.filter {
                    downloader.findDuplicate(it.video, it.variant) == null
                }
                enqueue(requests, generation)
                if (requests.isEmpty()) _state.update { it.copy(infoMessage = "所选视频已有记录，无需重复下载") }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { _state.update { it.copy(errorMessage = "无法加入下载队列，请重试") }
            } finally { if (generation == resultGeneration) _state.update { it.copy(isSubmitting = false) } }
        }
    }

    private suspend fun enqueue(requests: List<DownloadRequest>, generation: Long) {
        var count = 0
        try {
            requests.forEach { request ->
                val id = downloader.start(request.video, request.variant)
                count++
                if (generation == resultGeneration && _state.value.video?.selectionKey() == request.video.selectionKey()) {
                    _state.update { it.copy(activeDownloadId = id) }
                    observeDownload(id)
                }
            }
        } catch (error: Exception) {
            if (count > 0 && generation == resultGeneration) _state.update { it.copy(infoMessage = "已加入 $count 个下载任务，其余未加入，请重试") }
            throw error
        }
        if (count > 0 && generation == resultGeneration) _state.update { it.copy(queuedCount = count, infoMessage = "已加入 $count 个下载任务") }
    }

    private fun observeDownload(id: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            launch {
                downloader.observeRecord(id).collect { record ->
                    _state.update {
                        if (it.activeDownloadId == id) it.copy(activeRecord = record,
                            infoMessage = if (record?.errorMessage != it.activeRecord?.errorMessage)
                                record?.errorMessage else it.infoMessage) else it
                    }
                }
            }
            launch {
                downloader.progress.collect { map ->
                    _state.update {
                        if (it.activeDownloadId == id) it.copy(activeProgress = map[id]) else it
                    }
                }
            }
        }
    }

    fun pauseActive() {
        val id = _state.value.activeDownloadId ?: return
        viewModelScope.launch { downloader.pause(id) }
    }

    fun resumeActive() {
        val id = _state.value.activeDownloadId ?: return
        viewModelScope.launch { downloader.resume(id) }
    }

    fun restartActive() {
        val id = _state.value.activeDownloadId ?: return
        viewModelScope.launch { downloader.restart(id) }
    }

    fun clearResult() {
        resultGeneration++
        submissionJob?.cancel()
        parseJob?.cancel()
        sizeJob?.cancel()
        observeJob?.cancel()
        _state.update {
            it.copy(
                video = null,
                videos = emptyList(), selectedMedia = emptySet(), variantChoices = emptyMap(),
                duplicates = null, isSubmitting = false, queuedCount = null,
                isParsing = false,
                selectedVariant = null,
                isLoadingSizes = false,
                activeDownloadId = null,
                activeProgress = null,
                activeRecord = null,
                errorMessage = null, errorKind = null, browserRequestUrl = null,
                infoMessage = null,
                clipboardOffer = null,
            )
        }
    }
}
