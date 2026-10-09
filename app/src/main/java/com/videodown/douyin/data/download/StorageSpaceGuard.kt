package com.videodown.douyin.data.download

import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.core.AppError

internal const val STORAGE_RESERVE_BYTES = 32L * 1024 * 1024

/** Unknown source sizes still require a small working reserve; merged sources need two copies. */
internal fun requiredStorageBytes(expected: Long?, existing: Long = 0, merging: Boolean = false): Long {
    val remaining = ((expected ?: 0L).coerceAtLeast(0L) - existing.coerceAtLeast(0L)).coerceAtLeast(0L)
    val multiplier = if (merging) 2L else 1L
    return if (remaining > (Long.MAX_VALUE - STORAGE_RESERVE_BYTES) / multiplier) Long.MAX_VALUE
        else remaining * multiplier + STORAGE_RESERVE_BYTES
}
internal fun spaceFailure(available: Long, required: Long): AppError.Storage? =
    if (available < required) AppError.Storage("存储空间不足，请清理空间后继续", failureKind = DownloadFailureKind.STORAGE_FULL) else null

internal class InsufficientStorageException(val error: AppError.Storage) : Exception(error.message)

/** The engine owns this task directory; do not follow unexpected symbolic links. */
internal fun taskDirectoryBytes(directory: java.io.File): Long = directory.walkTopDown()
    .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
    .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
    .fold(0L) { bytes, file -> saturatingStorageAdd(bytes, file.length().coerceAtLeast(0)) }
