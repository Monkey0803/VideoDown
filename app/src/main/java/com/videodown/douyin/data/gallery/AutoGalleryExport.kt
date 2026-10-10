package com.videodown.douyin.data.gallery

import com.videodown.douyin.domain.model.DownloadRecord

/** Outcome of the optional 「下载后自动存入相册」 step. */
sealed interface GalleryAutoSave {
    /** The preference is off, so nothing was attempted. */
    data object Skipped : GalleryAutoSave

    data object Saved : GalleryAutoSave

    /** The reason is typed; the wording comes from resources, like a manual save. */
    data class Failed(val reasonKey: String) : GalleryAutoSave
}

/**
 * Copies each finished download into the system gallery when the user asked for it.
 *
 * Kept apart from [GalleryExporter] so the decision logic is plain Kotlin: whether to run at all,
 * and what to tell the user when the copy is not possible. A failure here never affects the
 * download itself — the file is already complete in the app directory and the record stays
 * COMPLETED.
 */
class AutoGalleryExport(
    private val enabled: () -> Boolean,
    private val canWrite: () -> Boolean,
    private val export: suspend (DownloadRecord) -> Unit,
) {
    suspend fun save(record: DownloadRecord): GalleryAutoSave {
        // Copying a private file into the shared gallery would undo the point of marking it private.
        com.videodown.douyin.domain.model.VaultPolicy.autoGalleryReasonKey(record)?.let {
            return GalleryAutoSave.Failed(it)
        }
        if (!enabled()) return GalleryAutoSave.Skipped
        // Android 9 and below write through MediaStore, which needs the legacy storage permission.
        // A background download cannot ask for it, so the reason says how to save manually.
        if (!canWrite()) return GalleryAutoSave.Failed(GalleryExportFailure.PERMISSION.storageKey)
        return runCatching { export(record) }.fold(
            onSuccess = { GalleryAutoSave.Saved },
            onFailure = { error ->
                // The exception's own message may contain OS text, so only its type is used.
                GalleryAutoSave.Failed(
                    (error as? GalleryExportException)?.failure?.storageKey ?: GalleryExportFailure.WRITE_FAILED.storageKey,
                )
            },
        )
    }
}
