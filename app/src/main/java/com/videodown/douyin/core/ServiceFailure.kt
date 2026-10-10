package com.videodown.douyin.core

import com.videodown.douyin.R

/**
 * Why the background download service gave up.
 *
 * Like the engine failures, only the key is stored on a task record: the record outlives the
 * language it was written in.
 */
enum class ServiceFailure {
    FOREGROUND_START, BACKGROUND_STOPPED, NOTIFICATION_UPDATE, TIME_LIMIT, INTERRUPTED, UNKNOWN;

    val storageKey: String get() = SERVICE_ERROR_PREFIX + name.lowercase()
}

const val SERVICE_ERROR_PREFIX = "service:"

fun serviceFailureOf(stored: String?): ServiceFailure? =
    stored?.takeIf { it.startsWith(SERVICE_ERROR_PREFIX) }
        ?.removePrefix(SERVICE_ERROR_PREFIX)
        ?.let { value -> ServiceFailure.entries.firstOrNull { it.name.lowercase() == value } }

fun serviceFailureResFor(failure: ServiceFailure): Int = when (failure) {
    ServiceFailure.FOREGROUND_START -> R.string.service_foreground_failed
    ServiceFailure.BACKGROUND_STOPPED -> R.string.service_background_stopped
    ServiceFailure.NOTIFICATION_UPDATE -> R.string.service_notification_failed
    ServiceFailure.TIME_LIMIT -> R.string.service_time_limit
    ServiceFailure.INTERRUPTED -> R.string.service_interrupted
    ServiceFailure.UNKNOWN -> R.string.service_interrupted
}
