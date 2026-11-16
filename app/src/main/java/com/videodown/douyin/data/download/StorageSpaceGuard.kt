package com.videodown.douyin.data.download

import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.core.AppError

internal const val STORAGE_RESERVE_BYTES = 32L * 1024 * 1024

/**
 * 图片任务的工作余量。
 *
 * 一张图不需要视频合并那样的 32 MiB 再写余量；沿用同一常数会让 9 张图各占 32 MiB，
 * 在一台只剩几百 MB 的手机上被误判成「空间不足」。
 */
internal const val IMAGE_RESERVE_BYTES = 2L * 1024 * 1024

/** Unknown source sizes still require a small working reserve; merged sources need two copies. */
internal fun requiredStorageBytes(
    expected: Long?,
    existing: Long = 0,
    merging: Boolean = false,
    reserveBytes: Long = STORAGE_RESERVE_BYTES,
): Long {
    val reserve = reserveBytes.coerceAtLeast(0L)
    val remaining = ((expected ?: 0L).coerceAtLeast(0L) - existing.coerceAtLeast(0L)).coerceAtLeast(0L)
    val multiplier = if (merging) 2L else 1L
    return if (remaining > (Long.MAX_VALUE - reserve) / multiplier) Long.MAX_VALUE
        else remaining * multiplier + reserve
}
internal fun spaceFailure(available: Long, required: Long): AppError.Storage? =
    if (available < required) AppError.Storage("", failureKind = DownloadFailureKind.STORAGE_FULL, reasonKey = "download:out_of_space") else null

internal class InsufficientStorageException(val error: AppError.Storage) : Exception(error.message)

/** The engine owns this task directory; do not follow unexpected symbolic links. */
internal fun taskDirectoryBytes(directory: java.io.File): Long = directory.walkTopDown()
    .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
    .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
    .fold(0L) { bytes, file -> saturatingStorageAdd(bytes, file.length().coerceAtLeast(0)) }
