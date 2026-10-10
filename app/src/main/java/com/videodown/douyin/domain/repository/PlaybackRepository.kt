package com.videodown.douyin.domain.repository

import com.videodown.douyin.domain.model.PlaybackCheckpoint
import com.videodown.douyin.domain.model.PlaybackOptions
import com.videodown.douyin.domain.model.SleepTimer
import com.videodown.douyin.domain.model.WatchProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val emptyWatchProgress = MutableStateFlow<Map<String, WatchProgress>>(emptyMap())

interface PlaybackRepository {
    fun options(audio: Boolean): PlaybackOptions
    fun setOptions(audio: Boolean, options: PlaybackOptions)
    fun checkpoint(id: String, fileSize: Long, modifiedAt: Long): PlaybackCheckpoint
    fun saveCheckpoint(id: String, fileSize: Long, modifiedAt: Long, checkpoint: PlaybackCheckpoint)

    /**
     * Watch position of every record that has one, so a list can render 「继续观看」 without a
     * per-record query. Consumers must still check [WatchProgress.matchesFile] before showing a bar.
     */
    fun watchProgress(): StateFlow<Map<String, WatchProgress>> = emptyWatchProgress.asStateFlow()

    /**
     * Wall-clock deadline of the audio sleep timer, or [SleepTimer.OFF].
     *
     * Read by the media service on every tick, so it must stay cheap and must not require the UI.
     */
    fun sleepTimerDeadline(): Long = SleepTimer.OFF

    fun setSleepTimerDeadline(deadlineMs: Long) {}
}
