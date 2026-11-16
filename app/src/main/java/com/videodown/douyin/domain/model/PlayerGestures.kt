package com.videodown.douyin.domain.model

/** Which half of the video a gesture happened in. */
enum class GestureSide { LEFT, RIGHT }

/** 手势越过触摸阈值后锁定的方向；未达到阈值时保持 [NONE]。 */
enum class GestureAxis { NONE, HORIZONTAL, VERTICAL }

/** 视频画面在播放器窗口内的缩放方式；不依赖 Android，便于 JVM 验证。 */
enum class VideoScaleMode { FIT, CROP, STRETCH;
    fun next(): VideoScaleMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Geometry of the player's touch gestures.
 *
 * Pure on purpose: touch handling is the part of a player that is hardest to observe from a test,
 * so every decision (which half, where to seek to, how much brightness or volume a drag means) is
 * computed here and only applied by the UI.
 *
 * Screen coordinates grow downwards, so an upward drag has a negative `dy` and must *increase*
 * brightness and volume.
 */
object PlayerGestures {
    const val SEEK_STEP_MS = 10_000L
    const val LONG_PRESS_SPEED = 2f

    fun axisFor(dx: Float, dy: Float, touchSlop: Float): GestureAxis {
        if (kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy)) < touchSlop) return GestureAxis.NONE
        return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) GestureAxis.HORIZONTAL else GestureAxis.VERTICAL
    }

    /** 横向拖动映射到时间轴；滑过整屏等于总时长的 25%，并裁剪到媒体边界。 */
    fun dragSeekTarget(startMs: Long, dx: Float, width: Int, durationMs: Long): Long {
        if (width <= 0 || durationMs <= 0L) return startMs.coerceAtLeast(0L)
        val delta = (durationMs * 0.25 * (dx / width)).toLong()
        return (startMs + delta).coerceIn(0L, durationMs)
    }

    fun sideFor(x: Float, width: Int): GestureSide =
        if (width <= 0 || x < width / 2f) GestureSide.LEFT else GestureSide.RIGHT

    /** Seek target for a double tap in [side]; clamped to the media bounds. */
    fun seekTarget(positionMs: Long, durationMs: Long, side: GestureSide): Long {
        val delta = if (side == GestureSide.LEFT) -SEEK_STEP_MS else SEEK_STEP_MS
        val target = positionMs + delta
        if (target <= 0L) return 0L
        if (durationMs > 0L && target >= durationMs) return durationMs
        return target
    }

    /**
     * Signed seek amount for the feedback overlay, e.g. `-10` / `+10`.
     *
     * The sign and the number are data; the screen adds the unit in the app language.
     */
    fun seekLabelSeconds(side: GestureSide): String =
        if (side == GestureSide.LEFT) "-${SEEK_STEP_MS / 1000}" else "+${SEEK_STEP_MS / 1000}"

    /** Brightness after a vertical drag of [dy] pixels; never fully black. */
    fun brightnessAfter(start: Float, dy: Float, height: Int): Float {
        if (height <= 0) return start.coerceIn(MIN_BRIGHTNESS, 1f)
        return (start - dy / height).coerceIn(MIN_BRIGHTNESS, 1f)
    }

    /** Volume fraction after a vertical drag of [dy] pixels. */
    fun volumeAfter(startFraction: Float, dy: Float, height: Int): Float {
        if (height <= 0) return startFraction.coerceIn(0f, 1f)
        return (startFraction - dy / height).coerceIn(0f, 1f)
    }

    private const val MIN_BRIGHTNESS = 0.02f
}
