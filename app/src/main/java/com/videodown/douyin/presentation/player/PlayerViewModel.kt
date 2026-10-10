package com.videodown.douyin.presentation.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.PlaybackCheckpoint
import com.videodown.douyin.domain.model.PlaybackOptions
import com.videodown.douyin.domain.model.SleepRemaining
import com.videodown.douyin.domain.model.SleepTimer
import com.videodown.douyin.domain.repository.PlaybackRepository
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PlayerUiState(
    val record: DownloadRecord? = null,
    val fileExists: Boolean = false,
    val loading: Boolean = true,
    val resumePositionMs: Long = 0,
    val options: PlaybackOptions = PlaybackOptions(),
    /** Remaining-time label while the audio sleep timer runs; `null` when no timer is set. */
    /** What is left of the sleep timer; the screen words it. */
    val sleepTimerRemaining: SleepRemaining? = null,
)

class PlayerViewModel(
    private val history: DownloadHistoryRepository = ServiceLocator.historyRepository,
    private val playback: PlaybackRepository = ServiceLocator.playbackRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var loadedId: String? = null
    private var sleepJob: Job? = null

    /** Starts or clears the audio sleep timer; the media service is what actually stops playback. */
    fun setSleepTimer(minutes: Int) {
        if (_state.value.record?.isAudio != true) return
        playback.setSleepTimerDeadline(SleepTimer.deadlineIn(minutes, System.currentTimeMillis()))
        refreshSleepTimer()
    }

    /**
     * Mirrors the persisted deadline into the button label, once a second while a timer runs.
     *
     * The service owns enforcement, so this also covers the case where the service already paused
     * playback and cleared the deadline.
     */
    private fun refreshSleepTimer() {
        sleepJob?.cancel()
        val deadline = playback.sleepTimerDeadline()
        if (deadline <= SleepTimer.OFF) {
            _state.value = _state.value.copy(sleepTimerRemaining = null)
            return
        }
        sleepJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                if (SleepTimer.expired(deadline, now)) {
                    playback.setSleepTimerDeadline(SleepTimer.OFF)
                    _state.value = _state.value.copy(sleepTimerRemaining = null)
                    break
                }
                _state.value = _state.value.copy(sleepTimerRemaining = SleepTimer.remainingRounded(deadline, now))
                delay(1000)
            }
        }
    }

    fun options(options: PlaybackOptions) {
        val record = _state.value.record ?: return
        val safe = options.sanitized()
        playback.setOptions(record.isAudio, safe)
        _state.value = _state.value.copy(options = safe)
    }

    fun saveProgress(mediaId: String?, positionMs: Long, durationMs: Long, ended: Boolean) {
        val record = _state.value.record?.takeIf { it.id == mediaId } ?: return
        val file = record.filePath?.let(::File) ?: return
        if (file.isFile) {
            val checkpoint = PlaybackCheckpoint(positionMs, durationMs, ended)
            playback.saveCheckpoint(record.id, file.length(), file.lastModified(), checkpoint)
            _state.value = _state.value.copy(resumePositionMs = checkpoint.resumePosition())
        }
    }

    fun load(recordId: String) {
        if (loadedId == recordId) return
        loadedId = recordId
        viewModelScope.launch {
            val record = history.getById(recordId)
            if (loadedId != recordId) return@launch
            _state.value = PlayerUiState(
                record = record,
                fileExists = record?.filePath?.let { File(it).exists() } == true,
                loading = false,
                resumePositionMs = record?.filePath?.let { path -> File(path).let { file -> playback.checkpoint(record.id, file.length(), file.lastModified()).resumePosition() } } ?: 0,
                options = playback.options(record?.isAudio == true),
            )
            if (record?.isAudio == true) refreshSleepTimer()
        }
    }
}
