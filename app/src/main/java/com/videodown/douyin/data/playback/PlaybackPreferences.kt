package com.videodown.douyin.data.playback

import android.content.Context
import com.videodown.douyin.domain.model.PlaybackCheckpoint
import com.videodown.douyin.domain.model.PlaybackOptions
import com.videodown.douyin.domain.model.SleepTimer
import com.videodown.douyin.domain.model.WatchProgress
import com.videodown.douyin.domain.repository.PlaybackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Kept apart from download history; playback never rewrites transfer records. */
class PlaybackPreferences(context: Context, name: String = "playback_preferences") : PlaybackRepository {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * Cached so lists can observe 「继续观看」 instead of polling per record.
     *
     * Rebuilt on every save: the media service writes a checkpoint every couple of seconds while
     * playing, and enumerating a few dozen preference keys is far cheaper than a file stat per row.
     */
    private val _watchProgress = MutableStateFlow(readWatchProgress())
    override fun watchProgress(): StateFlow<Map<String, WatchProgress>> = _watchProgress.asStateFlow()

    private fun key(audio: Boolean) = if (audio) "audio" else "video"
    override fun options(audio: Boolean) = PlaybackOptions(
        preferences.getFloat("${key(audio)}.speed", 1f),
        preferences.getBoolean("${key(audio)}.loop", false),
    ).sanitized()
    override fun setOptions(audio: Boolean, options: PlaybackOptions) {
        val safe = options.sanitized()
        preferences.edit().putFloat("${key(audio)}.speed", safe.speed).putBoolean("${key(audio)}.loop", safe.loop).apply()
    }
    override fun checkpoint(id: String, fileSize: Long, modifiedAt: Long): PlaybackCheckpoint {
        if (preferences.getLong("$id.size", -1) != fileSize || preferences.getLong("$id.modified", -1) != modifiedAt) return PlaybackCheckpoint()
        return PlaybackCheckpoint(preferences.getLong("$id.position", 0), preferences.getLong("$id.duration", 0), preferences.getBoolean("$id.ended", false))
    }
    override fun saveCheckpoint(id: String, fileSize: Long, modifiedAt: Long, checkpoint: PlaybackCheckpoint) {
        preferences.edit().putLong("$id.size", fileSize).putLong("$id.modified", modifiedAt)
            .putLong("$id.position", checkpoint.positionMs.coerceAtLeast(0)).putLong("$id.duration", checkpoint.durationMs.coerceAtLeast(0))
            .putBoolean("$id.ended", checkpoint.ended).apply()
        _watchProgress.value = readWatchProgress()
    }

    /** Shared by audio playback only, so it is deliberately not keyed by audio/video. */
    override fun sleepTimerDeadline(): Long = preferences.getLong(SLEEP_TIMER_KEY, SleepTimer.OFF)

    override fun setSleepTimerDeadline(deadlineMs: Long) {
        preferences.edit().putLong(SLEEP_TIMER_KEY, deadlineMs.coerceAtLeast(SleepTimer.OFF)).apply()
    }

    /**
     * Reads every stored position by pairing the `…​.position` keys with their siblings.
     *
     * Only these four suffixes are matched, so the `audio.` / `video.` option keys and the sleep
     * timer key can never be mistaken for a record.
     */
    private fun readWatchProgress(): Map<String, WatchProgress> {
        val stored = preferences.all
        return stored.keys.filter { it.endsWith(POSITION_SUFFIX) }.associate { key ->
            val id = key.removeSuffix(POSITION_SUFFIX)
            id to WatchProgress(
                positionMs = stored[key] as? Long ?: 0L,
                durationMs = stored["$id$DURATION_SUFFIX"] as? Long ?: 0L,
                fileSize = stored["$id$SIZE_SUFFIX"] as? Long ?: -1L,
                modifiedAt = stored["$id$MODIFIED_SUFFIX"] as? Long ?: -1L,
            )
        }
    }

    private companion object {
        const val SLEEP_TIMER_KEY = "sleep.deadline"
        const val POSITION_SUFFIX = ".position"
        const val DURATION_SUFFIX = ".duration"
        const val SIZE_SUFFIX = ".size"
        const val MODIFIED_SUFFIX = ".modified"
    }
}
