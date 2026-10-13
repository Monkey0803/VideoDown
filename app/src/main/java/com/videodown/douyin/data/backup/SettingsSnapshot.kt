package com.videodown.douyin.data.backup

import com.videodown.douyin.core.LibraryLayout
import com.videodown.douyin.core.LibrarySort
import com.videodown.douyin.core.PreferredQuality
import com.videodown.douyin.core.ThemeMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The user's settings, as they travel between devices.
 *
 * Every field is nullable so a backup written by an older or newer build still applies: a missing
 * or unreadable value leaves the current setting alone instead of silently resetting it to a
 * default. The SAF export directory is deliberately absent — a persisted tree permission belongs to
 * one device and would be a dangling grant after a restore.
 */
@Serializable
data class SettingsSnapshot(
    val version: Int = SettingsBackupCodec.CURRENT_VERSION,
    val themeMode: String? = null,
    val dynamicColor: Boolean? = null,
    val preferredQuality: String? = null,
    val libraryLayout: String? = null,
    val librarySort: String? = null,
    val wifiOnly: Boolean? = null,
    val autoResume: Boolean? = null,
    val maxConcurrentDownloads: Int? = null,
    val notifyOnCompletion: Boolean? = null,
    val individualTaskNotifications: Boolean? = null,
    val clipboardDetect: Boolean? = null,
    val autoSaveToGallery: Boolean? = null,
    val globalSpeedLimitKbps: Int? = null,
    val subtitlesEnabled: Boolean? = null,
    val playlistParsing: Boolean? = null,
    val updateManifestUrl: String? = null,
) {
    /**
     * The same snapshot with anything unusable removed.
     *
     * A hand-edited or foreign file must never be able to put the app into a state its own UI
     * cannot express, so unknown enum names become `null` (keep current) and numbers are clamped to
     * the ranges the settings screens offer.
     */
    fun sanitized(): SettingsSnapshot = copy(
        themeMode = themeMode?.takeIf { name -> ThemeMode.entries.any { it.name == name } },
        preferredQuality = preferredQuality?.takeIf { name -> PreferredQuality.entries.any { it.name == name } },
        libraryLayout = libraryLayout?.takeIf { name -> LibraryLayout.entries.any { it.name == name } },
        librarySort = librarySort?.takeIf { name -> LibrarySort.entries.any { it.name == name } },
        maxConcurrentDownloads = maxConcurrentDownloads?.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT),
        globalSpeedLimitKbps = globalSpeedLimitKbps?.takeIf { kbps -> kbps >= 0 && kbps <= MAX_SPEED_LIMIT_KBPS },
        // A blank or non-http value means "leave the current source alone" rather than "clear it".
        updateManifestUrl = updateManifestUrl?.trim()?.takeIf { it.startsWith("http") },
    )

    companion object {
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 5

        /** Nothing above this is offered; a larger value is treated as unusable rather than clamped. */
        const val MAX_SPEED_LIMIT_KBPS = 64 * 1024
    }
}

object SettingsBackupCodec {
    const val CURRENT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun encode(snapshot: SettingsSnapshot): String = json.encodeToString(SettingsSnapshot.serializer(), snapshot)

    /** The snapshot in [text], or `null` when the text is not a settings backup at all. */
    fun decode(text: String?): SettingsSnapshot? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return runCatching { json.decodeFromString(SettingsSnapshot.serializer(), trimmed) }.getOrNull()
    }
}
