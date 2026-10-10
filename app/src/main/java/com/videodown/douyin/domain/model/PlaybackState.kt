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

/**
 * How far one saved file has been watched, plus the file identity it was recorded against.
 *
 * The identity is carried along so a list can cheaply reject a stale entry: a re-downloaded or
 * re-encoded file keeps the record id but changes size and modification time, and a bar for the old
 * file would be a lie.
 */
data class WatchProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val fileSize: Long = -1L,
    val modifiedAt: Long = -1L,
) {
    fun matchesFile(size: Long, modifiedAt: Long): Boolean = fileSize == size && this.modifiedAt == modifiedAt

    /**
     * Fraction watched, or `null` when a progress bar would be meaningless: an unknown duration, a
     * position at the very start, or a file already watched to the end.
     */
    fun fraction(): Float? = when {
        durationMs <= 0L || fileSize <= 0L -> null
        positionMs <= 0L || positionMs >= durationMs -> null
        else -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** `已看 32%`, or `null` when there is nothing worth showing. */
}
