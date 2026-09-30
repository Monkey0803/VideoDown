package com.videodown.douyin.data.playback

import android.content.Context
import com.videodown.douyin.domain.model.PlaybackCheckpoint
import com.videodown.douyin.domain.model.PlaybackOptions
import com.videodown.douyin.domain.repository.PlaybackRepository

/** Kept apart from download history; playback never rewrites transfer records. */
class PlaybackPreferences(context: Context, name: String = "playback_preferences") : PlaybackRepository {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
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
    }
}
