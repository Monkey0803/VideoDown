package com.videodown.douyin.core

import com.videodown.douyin.R

/**
 * Resource ids for errors, shared by `data` and `presentation`.
 *
 * The id mapping lives in `core` because a data-layer class may need to *store* the key of an
 * error (a task record outlives the screen that showed it), while only the UI can resolve it.
 */
internal fun engineKindRes(kind: String): Int = when (kind) {
    "NO_AUDIO" -> R.string.engine_no_audio
    "DRM" -> R.string.engine_drm
    "LIVE" -> R.string.engine_live
    "CHANGED" -> R.string.engine_changed
    "ACCESS" -> R.string.engine_access
    "EXPIRED" -> R.string.engine_expired
    "RATE" -> R.string.engine_rate
    "AUTH" -> R.string.engine_auth
    "SPACE" -> R.string.engine_space
    "STORAGE_ACCESS" -> R.string.engine_storage_access
    "NETWORK" -> R.string.engine_network
    "TIMEOUT" -> R.string.engine_timeout
    "RUNTIME" -> R.string.engine_runtime
    else -> R.string.engine_unsupported
}

/**
 * Engine failures are stored as a key, not as text.
 *
 * A task record is written once and read later, possibly after the user changed the app language, so
 * storing a sentence would freeze the language it was written in. Anything without the prefix is an
 * older record's message and is shown as it is.
 */
const val ENGINE_ERROR_PREFIX = "engine:"

fun engineErrorKey(kind: String): String = ENGINE_ERROR_PREFIX + kind

/** The engine kind inside a stored value, or `null` when it is not an engine key. */
fun engineKindOf(stored: String?): String? =
    stored?.takeIf { it.startsWith(ENGINE_ERROR_PREFIX) }?.removePrefix(ENGINE_ERROR_PREFIX)?.takeIf { it.isNotBlank() }

/** The stored key for a typed parser failure. */
fun reasonKeyOf(failure: com.videodown.douyin.data.remote.XParseFailure): String = failure.storageKey

/** Keys whose wording starts with the platform name, so the UI must pass it as `%1$s`. */
private val PLATFORM_PREFIXED_KEYS = setOf("platform:network", "platform:no_data", "platform:no_mp4")

/** Keys whose wording contains a data value, so the UI must pass it as `%1$s`. */
private val DETAIL_KEYS = setOf("douyin:id_unresolved")

/** True when the wording for [key] needs a data value substituted into it. */
fun reasonKeyNeedsDetail(key: String?): Boolean = key in DETAIL_KEYS

/** True when the wording for [key] needs the platform name substituted into it. */
fun reasonKeyNeedsPlatform(key: String?): Boolean = key in PLATFORM_PREFIXED_KEYS

/** The resource for a stored reason key from any parser, or `null` when it names no known reason. */
fun reasonKeyRes(key: String?): Int? = when {
    key == null -> null
    engineKindOf(key) != null -> engineKindRes(engineKindOf(key)!!)
    key == "download:wifi_paused" -> R.string.download_wifi_paused
    key == "download:resume_mismatch" -> R.string.download_resume_mismatch
    key == "download:empty_body" -> R.string.download_empty_body
    key == "download:out_of_space" -> R.string.download_out_of_space
    key == "download:storage_write" -> R.string.download_storage_write
    key == "download:network" -> R.string.download_network_failed
    key == "generic:network" -> R.string.error_network_failed
    key == "generic:unknown" -> R.string.error_unknown
    key == "input:x_link" -> R.string.input_x_link
    key == "x:timeout" -> R.string.x_timeout
    key == "input:generic_link" -> R.string.input_generic_link
    key == "generic:no_video" -> R.string.generic_no_video
    key == "weibo:no_mp4" -> R.string.weibo_no_mp4
    key == "input:needs_link" -> R.string.input_needs_link
    key == "input:douyin_link" -> R.string.input_douyin_link
    key == "input:douyin_unrecognised" -> R.string.input_douyin_unrecognised
    key == "douyin:id_unresolved" -> R.string.douyin_id_unresolved
    key == "douyin:no_video" -> R.string.douyin_no_video
    key == "input:weibo_link" -> R.string.input_weibo_link
    key == "input:generic_only" -> R.string.input_generic_only
    key == "platform:network" -> R.string.platform_network_failed
    key == "platform:no_data" -> R.string.platform_no_data
    key == "platform:no_mp4" -> R.string.platform_no_mp4
    else -> com.videodown.douyin.data.remote.xFailureOf(key)?.let { xFailureRes(it) }
        ?: com.videodown.douyin.core.serviceFailureOf(key)?.let { serviceFailureResFor(it) }
        ?: com.videodown.douyin.data.gallery.galleryFailureOf(key)?.let { galleryFailureRes(it) }
        ?: if (key == com.videodown.douyin.domain.model.VaultPolicy.VAULT_GALLERY_KEY) R.string.notification_vault_no_gallery else null
}

/** The resource for an application error, or `null` when it still carries its own text. */
internal fun errorTextRes(error: AppError): Int? = when (error) {
    is AppError.Http -> R.string.error_http_status
    is AppError.Parse -> reasonKeyRes(error.reasonKey)
    is AppError.InvalidLink -> reasonKeyRes(error.reasonKey)
    is AppError.Network -> reasonKeyRes(error.reasonKey)
    is AppError.Unknown -> reasonKeyRes(error.reasonKey)
    is AppError.Storage -> reasonKeyRes(error.reasonKey)
    else -> null
}

/** The resource for a gallery failure; also used to word a batch result's stored key. */
fun galleryFailureRes(failure: com.videodown.douyin.data.gallery.GalleryExportFailure): Int = when (failure) {
    com.videodown.douyin.data.gallery.GalleryExportFailure.NOT_COMPLETED -> R.string.gallery_not_completed
    com.videodown.douyin.data.gallery.GalleryExportFailure.FILE_MISSING -> R.string.gallery_file_missing
    com.videodown.douyin.data.gallery.GalleryExportFailure.UNSUPPORTED_CONTAINER -> R.string.gallery_unsupported
    com.videodown.douyin.data.gallery.GalleryExportFailure.CREATE_FAILED -> R.string.gallery_create_failed
    com.videodown.douyin.data.gallery.GalleryExportFailure.WRITE_FAILED -> R.string.gallery_write_failed
    com.videodown.douyin.data.gallery.GalleryExportFailure.FINISH_FAILED -> R.string.gallery_finish_failed
    com.videodown.douyin.data.gallery.GalleryExportFailure.PERMISSION -> R.string.gallery_permission
}

/** The resource for an X parse failure. */
fun xFailureRes(failure: com.videodown.douyin.data.remote.XParseFailure): Int = when (failure) {
    com.videodown.douyin.data.remote.XParseFailure.NOT_FOUND -> R.string.x_not_found
    com.videodown.douyin.data.remote.XParseFailure.UNSUPPORTED_FORMAT -> R.string.x_unsupported_format
    com.videodown.douyin.data.remote.XParseFailure.NO_VIDEO -> R.string.x_no_video
    com.videodown.douyin.data.remote.XParseFailure.LOGIN_REQUIRED -> R.string.x_login_required
    com.videodown.douyin.data.remote.XParseFailure.UNAVAILABLE -> R.string.x_unavailable
    com.videodown.douyin.data.remote.XParseFailure.RESTRICTED -> R.string.x_restricted
    com.videodown.douyin.data.remote.XParseFailure.INVALID -> R.string.x_invalid
    com.videodown.douyin.data.remote.XParseFailure.RATE_LIMITED -> R.string.x_rate_limited
    com.videodown.douyin.data.remote.XParseFailure.NETWORK -> R.string.x_network
}

/** The resource for an update-check failure. */
fun updateReasonRes(reasonKey: String): Int = when (reasonKey) {
    "update:bad_url" -> R.string.update_reason_bad_url
    "update:http_error" -> R.string.update_reason_http
    "update:bad_format" -> R.string.update_reason_bad_format
    "update:network" -> R.string.update_reason_network
    "update:no_version" -> R.string.update_reason_no_version
    "update:not_configured" -> R.string.update_reason_not_configured
    else -> R.string.update_reason_failed
}
