package com.videodown.douyin.domain.model

/**
 * What the private vault hides, and how locked content is described.
 *
 * Everything here is a plain decision so it can be asserted in the JVM suite: the vault is only as
 * good as its least guarded surface, and the surfaces are spread across the library, the task list,
 * notifications, the link export and the automatic gallery copy.
 */
object VaultPolicy {
    /** Shown instead of a private title wherever the vault is locked. */

    /** The vault is a separate view: the normal library never mixes private items into it. */
    fun belongsToVault(record: DownloadRecord): Boolean = record.isPrivate

    /** A locked vault shows nothing at all, not even an empty-looking private item. */
    fun visibleInVault(record: DownloadRecord, unlocked: Boolean): Boolean = record.isPrivate && unlocked

    /**
     * The title to render: a private record never shows its real title while the vault is locked.
     *
     * The task list uses this with `unlocked = false`, so an in-progress private transfer is not
     * readable from the list either.
     */
    fun title(record: DownloadRecord, unlocked: Boolean): String? =
        // `null` means "the title is hidden"; the UI substitutes its own wording, so this file
        // stays free of user-visible copy.
        if (record.isPrivate && !unlocked) null else record.title

    /** Notification body lines; private records never name themselves. */
    fun notificationTitle(record: DownloadRecord): String? = title(record, unlocked = false)

    /**
     * A private record is never copied into the shared gallery automatically.
     *
     * Exporting it there would undo the reason it was marked private; the user can still do it by
     * hand from inside the unlocked vault.
     */
    /**
     * Why a record is never auto-saved, as a key.
     *
     * A key rather than a sentence: the notification words it in the app language, and this file is
     * framework-free.
     */
    fun autoGalleryReasonKey(record: DownloadRecord): String? =
        if (record.isPrivate) VAULT_GALLERY_KEY else null

    /** The gallery-copy key for a private record. */
    const val VAULT_GALLERY_KEY = "vault:not_auto_saved"

    /**
     * Whether a completion notification may jump straight into the video.
     *
     * A private record must not: the tap would bypass the gate that everything else respects.
     */
    fun deepLinkable(record: DownloadRecord): Boolean = !record.isPrivate

    /** Links exported for backup skip private records entirely. */
    fun linkExportable(record: DownloadRecord): Boolean = !record.isPrivate
}
