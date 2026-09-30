package com.videodown.douyin.domain.model

val playbackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

data class PlaybackOptions(val speed: Float = 1f, val loop: Boolean = false) {
    fun sanitized() = copy(speed = speed.takeIf { it in playbackSpeeds } ?: 1f)
}

data class PlaybackCheckpoint(val positionMs: Long = 0, val durationMs: Long = 0, val ended: Boolean = false) {
    fun resumePosition(): Long = when {
        ended || positionMs < 0 || (durationMs > 0 && positionMs >= durationMs) -> 0
        else -> positionMs
    }
}
