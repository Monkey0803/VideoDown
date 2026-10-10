package com.videodown.douyin.data.download

import com.videodown.douyin.data.gallery.GalleryAutoSave
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.VaultPolicy

/**
 * Body of the 「下载完成」 notification.
 *
 * The gallery outcome is folded into the same card instead of posting a second notification: the
 * user asked for one thing (this download finished) and one card should answer both questions —
 * it finished, and where it landed.
 */
/** Where an auto-saved copy landed; the notifier words each case. */
enum class GalleryLanding { SKIPPED, SAVED, FAILED }

/**
 * The parts of the completion card.
 *
 * Reported as values rather than as a sentence: the notification is built with a `Context`, so it
 * can word this in the app language, and the title itself is content rather than chrome.
 */
data class CompletionSummary(
    /** `null` means the title must not be shown; the notification substitutes its wording. */
    val title: String?,
    val qualityLabel: String,
    val audio: Boolean,
    val landing: GalleryLanding,
    /** A stored reason key (see `core/ErrorResources.kt`), never a sentence. */
    val reasonKey: String? = null,
)

fun completionSummary(record: DownloadRecord, gallery: GalleryAutoSave): CompletionSummary {
    // A private record must not name itself on the lock screen either.
    val title = VaultPolicy.notificationTitle(record)  // null = hidden; the notifier words it
    val quality = record.resolutionLabel.ifBlank { "" }
    return CompletionSummary(
        title = title,
        qualityLabel = quality,
        audio = record.isAudio,
        landing = when (gallery) {
            GalleryAutoSave.Skipped -> GalleryLanding.SKIPPED
            GalleryAutoSave.Saved -> GalleryLanding.SAVED
            is GalleryAutoSave.Failed -> GalleryLanding.FAILED
        },
        reasonKey = (gallery as? GalleryAutoSave.Failed)?.reasonKey,
    )
}
