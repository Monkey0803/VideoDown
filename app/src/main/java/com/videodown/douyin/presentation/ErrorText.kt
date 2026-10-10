package com.videodown.douyin.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.core.AppError
import com.videodown.douyin.core.engineKindOf
import com.videodown.douyin.core.engineKindRes
import com.videodown.douyin.core.errorTextRes
import com.videodown.douyin.core.galleryFailureRes

/**
 * The wording for an error.
 *
 * Kept as the single entry point so every screen shows the same sentence for the same failure, and
 * so a new error variant only has to be worded once. Variants that still carry their own text keep
 * using it (the remaining work is recorded in the P6 acceptance notes).
 */
@Composable
fun appErrorText(error: AppError): String {
    val res = errorTextRes(error)
    val platform = (error as? com.videodown.douyin.core.AppError.Parse)?.platform
        ?: (error as? com.videodown.douyin.core.AppError.Network)?.platform
        ?: (error as? com.videodown.douyin.core.AppError.Http)?.platform
    return when {
        res == null -> error.message
        // An HTTP failure on a known platform names it; the status is always shown.
        error is com.videodown.douyin.core.AppError.Http && platform != null ->
            stringResource(R.string.platform_http_error, stringResource(videoPlatformLabelRes(platform)), error.code)
        error is com.videodown.douyin.core.AppError.Http -> stringResource(res, error.code)
        platform != null && com.videodown.douyin.core.reasonKeyNeedsPlatform(reasonKeyOf(error))
            -> stringResource(res, stringResource(videoPlatformLabelRes(platform)))
        detailOf(error) != null && com.videodown.douyin.core.reasonKeyNeedsDetail(reasonKeyOf(error))
            -> stringResource(res, detailOf(error)!!)
        else -> stringResource(res)
    }
}

fun appErrorText(context: Context, error: AppError): String {
    val res = errorTextRes(error)
    val platform = (error as? com.videodown.douyin.core.AppError.Parse)?.platform
        ?: (error as? com.videodown.douyin.core.AppError.Network)?.platform
        ?: (error as? com.videodown.douyin.core.AppError.Http)?.platform
    return when {
        res == null -> error.message
        error is com.videodown.douyin.core.AppError.Http && platform != null ->
            context.getString(R.string.platform_http_error, platformName(context, platform), error.code)
        error is com.videodown.douyin.core.AppError.Http -> context.getString(res, error.code)
        platform != null && com.videodown.douyin.core.reasonKeyNeedsPlatform(reasonKeyOf(error))
            -> context.getString(res, platformName(context, platform))
        detailOf(error) != null && com.videodown.douyin.core.reasonKeyNeedsDetail(reasonKeyOf(error))
            -> context.getString(res, detailOf(error)!!)
        else -> context.getString(res)
    }
}

/**
 * The wording for a failure stored on a task record.
 *
 * Engine failures are stored as `engine:<kind>`; anything else is an older record's own message and
 * is shown unchanged.
 */
@Composable
fun storedErrorMessage(stored: String?): String? {
    // One mapper covers every key family, so no stored key can reach the screen as raw text.
    val res = com.videodown.douyin.core.reasonKeyRes(stored)
    return if (res == null) stored else stringResource(res)
}

fun storedErrorMessage(context: Context, stored: String?): String? {
    val res = com.videodown.douyin.core.reasonKeyRes(stored)
    return if (res == null) stored else context.getString(res)
}

/**
 * The wording for a failed gallery save.
 *
 * `LibraryActions`, the task screen and the player all show this, so it is worded once. The
 * exception's own message is never displayed: it may contain OS text or a file path.
 */
@Composable
fun galleryErrorMessage(error: Throwable): String =
    stringResource(galleryFailureRes(com.videodown.douyin.core.galleryFailureKindOf(error)))

fun galleryErrorMessage(context: Context, error: Throwable): String =
    context.getString(galleryFailureRes(com.videodown.douyin.core.galleryFailureKindOf(error)))

/** The data value an error wants in its sentence, if any. */
private fun detailOf(error: AppError): String? = (error as? AppError.Parse)?.detail

/** The stored reason key of an error, whichever variant carries one. */
private fun reasonKeyOf(error: AppError): String? = when (error) {
    is AppError.Parse -> error.reasonKey
    is AppError.Network -> error.reasonKey
    is AppError.InvalidLink -> error.reasonKey
    is AppError.Unknown -> error.reasonKey
    else -> null
}
