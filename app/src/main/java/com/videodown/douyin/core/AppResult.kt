package com.videodown.douyin.core

import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.legacyFailureKind

/**
 * Application level error taxonomy. Kept framework-free so every layer can use it.
 */
sealed interface AppError {
    val message: String
    val failureKind: DownloadFailureKind? get() = null

    data class Network(
        override val message: String,
        val cause: Throwable? = null,
        override val failureKind: DownloadFailureKind? = null,
        /** Set when the failure belongs to one platform, so the UI can name it. */
        val platform: com.videodown.douyin.domain.model.VideoPlatform? = null,
        val reasonKey: String? = null,
    ) : AppError

    /**
     * A non-2xx HTTP response.
     *
     * Carries the status as a number because retry and refresh decisions depend on it; the message
     * stays technical on purpose, and the UI words the status from resources.
     */
    data class Http(
        val code: Int,
        val cause: Throwable? = null,
        override val failureKind: DownloadFailureKind? = null,
        val platform: com.videodown.douyin.domain.model.VideoPlatform? = null,
    ) : AppError {
        override val message: String get() = "HTTP $code"
    }
    data class Parse(
        override val message: String,
        override val failureKind: DownloadFailureKind? = null,
        /**
         * Set when a parser reported a typed reason (see `core/ErrorResources.kt`), so the UI can
         * word it from resources instead of relying on [message].
         */
        val reasonKey: String? = null,
        /** Set when the failure belongs to one platform, so the UI can name it. */
        val platform: com.videodown.douyin.domain.model.VideoPlatform? = null,
        /** A data value that belongs in the sentence (an id, a count). */
        val detail: String? = null,
    ) : AppError
    data class InvalidLink(
        override val message: String,
        override val failureKind: DownloadFailureKind? = null,
        val reasonKey: String? = null,
    ) : AppError
    data class Storage(
        override val message: String,
        val cause: Throwable? = null,
        override val failureKind: DownloadFailureKind? = null,
        val reasonKey: String? = null,
    ) : AppError
    data class Unknown(
        override val message: String,
        val cause: Throwable? = null,
        override val failureKind: DownloadFailureKind? = null,
        val reasonKey: String? = null,
    ) : AppError
}

fun Throwable.toAppError(): AppError = when (this) {
    // A key rather than a sentence: the UI words it, and this type is used from every layer.
    is java.io.IOException -> AppError.Network("", this, reasonKey = "generic:network")
    else -> AppError.Unknown("", this, reasonKey = "generic:unknown")
}

/**
 * Minimal, allocation-light result type used across the app.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value

fun <T> AppResult<T>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error

/**
 * The reason a gallery save failed.
 *
 * Reads the typed failure when there is one; a `SecurityException` means the permission is missing,
 * and anything else gets the generic wording. External exceptions may carry untranslated OS text or
 * file paths, so their own message is never shown.
 */
fun galleryFailureKindOf(error: Throwable): com.videodown.douyin.data.gallery.GalleryExportFailure =
    (error as? com.videodown.douyin.data.gallery.GalleryExportException)?.failure
        ?: if (error is SecurityException) com.videodown.douyin.data.gallery.GalleryExportFailure.PERMISSION
        else com.videodown.douyin.data.gallery.GalleryExportFailure.WRITE_FAILED

fun AppError.downloadFailureKind(): DownloadFailureKind = failureKind
    ?: (this as? AppError.Http)?.let { http ->
        when (http.code) {
            401 -> DownloadFailureKind.LOGIN_REQUIRED
            403 -> DownloadFailureKind.ACCESS_DENIED
            404, 410 -> DownloadFailureKind.LINK_EXPIRED
            429 -> DownloadFailureKind.RATE_LIMITED
            else -> DownloadFailureKind.NETWORK
        }
    }
    ?: legacyFailureKind(message)
    ?: DownloadFailureKind.UNKNOWN
