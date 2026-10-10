package com.videodown.douyin.data.generic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The request contract between the app and the bundled Python bridge.
 *
 * Extracted so the contract itself is a plain function that can be asserted in a JVM test: the
 * bridge is only reachable on a device, and a field that silently stops being sent would be a bug
 * nothing else would catch.
 */
object EngineRequest {
    /** Entries parsed from one collection URL. Kept in sync with the settings copy. */
    const val PLAYLIST_LIMIT = 20

    /**
     * A download of one selected item.
     *
     * [subtitles] is empty for the common case; when set, the bridge fetches exactly those
     * languages and embeds them, so the finished file carries its own text track.
     */
    fun download(
        url: String,
        output: String,
        format: String?,
        audioOnly: Boolean,
        mediaId: String?,
        referer: String?,
        userAgent: String?,
        browserSession: Boolean,
        cookieFile: String?,
        subtitles: List<String> = emptyList(),
        automaticCaptions: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("mode", "download")
        put("url", url)
        put("output", output)
        format?.let { put("format", it) }
        if (audioOnly) put("audio_only", true)
        mediaId?.let { put("media_id", it) }
        putJsonObject("headers") {
            referer?.let { put("Referer", it) }
            userAgent?.let { put("User-Agent", it) }
        }
        if (browserSession) cookieFile?.let { put("cookies", it) }
        // Nothing to embed into an extracted audio track, so the pair never travels together.
        if (subtitles.isNotEmpty() && !audioOnly) {
            put("subtitles", true)
            putJsonArray("subtitle_langs") { subtitles.forEach { add(JsonPrimitive(it)) } }
            if (automaticCaptions) put("auto_captions", true)
        }
    }

    fun parse(
        url: String,
        browserSession: Boolean,
        cookieFile: String?,
        referer: String?,
        userAgent: String?,
        playlist: Boolean,
    ): JsonObject = buildJsonObject {
        put("mode", "parse")
        put("url", url)
        putJsonObject("headers") {
            referer?.let { put("Referer", it) }
            userAgent?.let { put("User-Agent", it) }
        }
        if (browserSession) cookieFile?.let { put("cookies", it) }
        // Absent means "only the linked video"; the bridge also keeps its own per-site default.
        if (playlist) {
            put("playlist", true)
            put("playlist_limit", PLAYLIST_LIMIT)
        }
    }
}
