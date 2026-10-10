package com.videodown.douyin.domain.model

/**
 * Wall-clock deadline for the audio sleep timer.
 *
 * A deadline rather than a countdown, so it keeps its meaning while 拾影 is in the background: the
 * media service only compares it against the current time, and the UI can render the remainder
 * without owning the timer.
 */
object SleepTimer {
    /** No timer. */
    const val OFF = 0L

    /** Minute choices offered in the player. */
    val minutes = listOf(15, 30, 60)

    fun deadlineIn(minutes: Int, now: Long): Long =
        if (minutes <= 0) OFF else now + minutes * 60_000L

    fun remainingMs(deadline: Long, now: Long): Long =
        if (deadline <= OFF) 0L else (deadline - now).coerceAtLeast(0L)

    fun expired(deadline: Long, now: Long): Boolean = deadline > OFF && now >= deadline

    /**
     * A rounded-up count of what is left, or `null` when there is no timer.
     *
     * Returns the amount rather than a sentence: the player words it, and never reports zero (a
     * sub-second remainder counts as one second left).
     */
    fun remainingRounded(deadline: Long, now: Long): SleepRemaining? {
        if (deadline <= OFF) return null
        val remaining = remainingMs(deadline, now)
        val wholeMinutes = remaining / 60_000L
        return if (wholeMinutes >= 1L) SleepRemaining.Minutes(wholeMinutes)
        else SleepRemaining.Seconds(((remaining + 999L) / 1000L).coerceAtLeast(1L))
    }
}

/** How much time a sleep timer has left, in the unit the player shows. */
sealed interface SleepRemaining {
    data class Minutes(val value: Long) : SleepRemaining
    data class Seconds(val value: Long) : SleepRemaining
}
