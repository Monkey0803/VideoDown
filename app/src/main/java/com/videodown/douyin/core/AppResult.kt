package com.videodown.douyin.core

import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.legacyFailureKind

/**
 * Application level error taxonomy. Kept framework-free so every layer can use it.
 */
sealed interface AppError {
    val message: String
    val failureKind: DownloadFailureKind? get() = null

    data class Network(override val message: String, val cause: Throwable? = null, override val failureKind: DownloadFailureKind? = null) : AppError
    data class Parse(override val message: String, override val failureKind: DownloadFailureKind? = null) : AppError
    data class InvalidLink(override val message: String, override val failureKind: DownloadFailureKind? = null) : AppError
    data class Storage(override val message: String, val cause: Throwable? = null, override val failureKind: DownloadFailureKind? = null) : AppError
    data class Unknown(override val message: String, val cause: Throwable? = null, override val failureKind: DownloadFailureKind? = null) : AppError
}

fun Throwable.toAppError(): AppError = when (this) {
    is java.io.IOException -> AppError.Network("网络连接失败，请检查网络后重试", this)
    else -> AppError.Unknown("操作失败，请稍后重试", this)
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

/** External exceptions may contain untranslated OS messages or file paths. */
fun Throwable.galleryErrorMessage(): String = when (message) {
    "视频尚未下载完成", "本地视频文件不存在", "此容器暂不支持保存到相册，请使用分享文件",
    "无法创建相册视频", "无法写入相册视频", "无法完成相册保存" -> message!!
    else -> when (this) {
        is SecurityException -> "没有相册访问权限，请检查系统权限设置"
        else -> "请检查存储空间和相册权限后重试"
    }
}

fun AppError.downloadFailureKind(): DownloadFailureKind = failureKind ?: legacyFailureKind(message) ?: DownloadFailureKind.UNKNOWN
