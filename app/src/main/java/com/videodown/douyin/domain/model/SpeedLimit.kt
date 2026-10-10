package com.videodown.douyin.domain.model

/**
 * Offered limits in KB/s; `0` always means unlimited.
 *
 * Kept in the domain because the global setting and a single task offer the same list, and both
 * need to recognise a stored value. The wording of each option lives in the presentation layer.
 */
val speedLimitOptions = listOf(0, 512, 1024, 2048, 5120)

/** Bytes per second for a KB/s setting; `0` means unlimited. */
fun speedLimitBytesPerSecond(kbps: Int): Long = kbps.coerceAtLeast(0).toLong() * 1024L
