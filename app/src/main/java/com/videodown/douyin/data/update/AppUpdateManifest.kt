package com.videodown.douyin.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Version manifest fetched from a user-configured endpoint for the in-app update check.
 *
 * The schema is intentionally tolerant: a publisher may supply either [versionCode] or
 * [versionName] (or both), and may offer a direct APK ([downloadUrl]) and/or a release page
 * ([releasePageUrl]). Unknown fields are ignored so the manifest can grow without breaking
 * already-shipped clients.
 */
@Serializable
data class AppUpdateManifest(
    @SerialName("versionCode") val versionCode: Int? = null,
    @SerialName("versionName") val versionName: String? = null,
    @SerialName("downloadUrl") val downloadUrl: String? = null,
    @SerialName("releasePageUrl") val releasePageUrl: String? = null,
    @SerialName("notes") val notes: String? = null,
    /** 同一份更新说明的英文版；弹窗按界面语言选取（见 [notesFor]）。 */
    @SerialName("notesEn") val notesEn: String? = null,
    /** Accepted aliases so simple hand-written manifests keep working. */
    @SerialName("url") val plainUrl: String? = null,
    @SerialName("changelog") val changelog: String? = null,
) {
    /** Direct APK link, falling back to the generic `url` alias. */
    val apkUrl: String? get() = downloadUrl?.takeIf(String::isNotBlank) ?: plainUrl?.takeIf(String::isNotBlank)

    val pageUrl: String? get() = releasePageUrl?.takeIf(String::isNotBlank)

    /** Release notes, falling back to the `changelog` alias. */
    val releaseNotes: String? get() = notes?.takeIf(String::isNotBlank) ?: changelog?.takeIf(String::isNotBlank)

    /**
     * The notes for the interface language: the matching field first, the other language as a
     * fallback, so a manifest that ships only one of them still shows something.
     */
    fun notesFor(english: Boolean): String? {
        val preferred = if (english) notesEn else releaseNotes
        val fallback = if (english) releaseNotes else notesEn
        return preferred?.takeIf(String::isNotBlank) ?: fallback?.takeIf(String::isNotBlank)
    }
}

/** The locally installed version, read once from `PackageManager`. */
data class AppVersion(val versionCode: Int, val versionName: String)

/**
 * Compares dotted numeric version names such as `1.0.2` and `1.10.0`.
 *
 * Returns a negative value when [current] is older than [candidate], zero when they are equal,
 * and a positive value when [current] is newer. Pre-release suffixes (`-beta`) are ignored and
 * missing segments count as zero, which matches how the app versions its releases.
 */
fun compareVersionNames(current: String, candidate: String): Int {
    val left = current.substringBefore('-').split('.').map { it.trim().toIntOrNull() ?: 0 }
    val right = candidate.substringBefore('-').split('.').map { it.trim().toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(left.size, right.size)) {
        val difference = (left.getOrNull(index) ?: 0) - (right.getOrNull(index) ?: 0)
        if (difference != 0) return difference
    }
    return 0
}
