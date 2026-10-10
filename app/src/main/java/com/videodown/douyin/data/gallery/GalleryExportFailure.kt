package com.videodown.douyin.data.gallery

/**
 * Why saving a finished download into the gallery failed.
 *
 * Typed rather than textual: the caller used to match on the message to tell these apart, which
 * silently broke as soon as any wording changed. The wording itself lives in resources.
 */
enum class GalleryExportFailure {
    NOT_COMPLETED, FILE_MISSING, UNSUPPORTED_CONTAINER, CREATE_FAILED, WRITE_FAILED, FINISH_FAILED, PERMISSION;

    /** A key that survives being handed to another layer (batch results, notifications). */
    val storageKey: String get() = GALLERY_FAILURE_PREFIX + name.lowercase()
}

const val GALLERY_FAILURE_PREFIX = "gallery:"

fun galleryFailureOf(key: String?): GalleryExportFailure? =
    key?.takeIf { it.startsWith(GALLERY_FAILURE_PREFIX) }
        ?.removePrefix(GALLERY_FAILURE_PREFIX)
        ?.let { value -> GalleryExportFailure.entries.firstOrNull { it.name.lowercase() == value } }

class GalleryExportException(val failure: GalleryExportFailure, cause: Throwable? = null) : Exception(failure.storageKey, cause)
