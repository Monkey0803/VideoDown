package com.videodown.douyin.data.generic

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import com.videodown.douyin.domain.model.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun webUrl(value: String?): String? = value?.toHttpUrlOrNull()?.takeIf {
    it.username.isEmpty() && it.password.isEmpty()
}?.toString()

/**
 * Subtitle language names.
 *
 * The bridge sends a curated list of names, but the raw extractor shape (`{language: [formats]}`)
 * is also accepted so the two ends cannot drift apart unnoticed.
 */
internal fun languages(value: JsonElement?): List<String> = when (value) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonObject -> value.keys.toList()
    else -> emptyList()
}.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

class GenericMetadataParser {
    fun parse(info: JsonObject, pageUrl: String, browserSession: Boolean = false, userAgent: String? = null): ParsedVideos {
        val entries = info["entries"] as? JsonArray
        val videos = (entries?.mapNotNull { it as? JsonObject } ?: listOf(info)).mapNotNull { item ->
            if (item.flag("is_live") || item.text("live_status") in setOf("is_live", "is_upcoming") || item.flag("has_drm")) return@mapNotNull null
            val page = webUrl(item.text("webpage_url")) ?: pageUrl
            val id = item.text("id") ?: page
            val formats = (item["formats"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(item)
            val usable = formats.filter { webUrl(it.text("url")) != null && !it.flag("has_drm") }
            val audio = usable.filter { it.text("vcodec") == "none" && it.text("acodec") != "none" }
                .maxByOrNull { (if (it.text("ext") == "m4a") 10000 else 0) + (it.number("tbr") ?: 0.0).toInt() }
            val variants = usable.mapNotNull format@ { f ->
                if (f.text("vcodec") == "none") return@format null
                val url = webUrl(f.text("url")) ?: return@format null
                val separate = f.text("acodec") == "none" && audio != null
                val protocol = f.text("protocol").orEmpty()
                val type = when {
                    separate -> MediaSourceType.SEPARATE
                    protocol.contains("m3u8") || url.substringBefore('?').endsWith(".m3u8", true) -> MediaSourceType.HLS
                    protocol.contains("dash") || url.substringBefore('?').endsWith(".mpd", true) -> MediaSourceType.DASH
                    protocol in setOf("http", "https", "") -> MediaSourceType.DIRECT
                    else -> return@format null
                }
                val formatId = f.text("format_id").orEmpty()
                val headers = (f["http_headers"] ?: item["http_headers"]) as? JsonObject
                val height = f.number("height")?.toInt()
                val width = f.number("width")?.toInt()
                val rank = listOfNotNull(width, height).minOrNull() ?: 0
                val declaredExt = f.text("ext")?.lowercase()?.takeIf { it.isNotBlank() }
                // A declared OGG/OGV (or another unsupported container) is not MP4.
                // Preserve opaque URLs without extension metadata, but never relabel known formats.
                if (declaredExt != null && declaredExt !in setOf("mp4", "webm", "mkv", "mov", "m4v", "ts")) return@format null
                val ext = declaredExt ?: "mp4"
                VideoVariant(url, buildString {
                    append(if (rank > 0) "${rank}P" else AppText.get(R.string.quality_original))
                    append(" · ${ext.uppercase()}")
                    if (f.text("acodec") == "none" && !separate) append(AppText.get(R.string.media_label_no_audio))
                    if (formatId.isNotEmpty()) append(" · $formatId")
                }, width, height, f.number("tbr")?.times(1000)?.toInt(),
                    if (separate) null else (f.number("filesize") ?: f.number("filesize_approx"))?.toLong(),
                    qualityRank = rank, referer = webUrl(headers?.text("Referer")) ?: page,
                    media = MediaDownload(type, page, id, formatId,
                        if (separate) audio?.text("format_id") else null,
                        if (separate) audio?.text("url") else null,
                        ext, userAgent ?: headers?.text("User-Agent"), browserSession, item.text("extractor_key").orEmpty()))
            }
            val audioVariants = usable.filter { it.text("vcodec") == "none" &&
                !it.text("acodec").isNullOrBlank() && it.text("acodec") != "none" && !it.text("format_id").isNullOrBlank() &&
                (it.text("protocol").orEmpty() in setOf("http", "https", "") || it.text("protocol").orEmpty().contains("m3u8") || it.text("protocol").orEmpty().contains("dash")) }
                .sortedByDescending { it.number("tbr") ?: 0.0 }.map { f ->
                    val headers = (f["http_headers"] ?: item["http_headers"]) as? JsonObject
                    val formatId = f.text("format_id")!!
                    VideoVariant(webUrl(f.text("url"))!!,
                        "${(f.number("tbr") ?: 0.0).toInt().takeIf { it > 0 }?.let { "$it kbps" } ?: "原音轨"} · M4A · $formatId",
                        referer = webUrl(headers?.text("Referer")) ?: page,
                        media = MediaDownload(type = when {
                            f.text("protocol").orEmpty().contains("m3u8") -> MediaSourceType.HLS
                            f.text("protocol").orEmpty().contains("dash") -> MediaSourceType.DASH
                            else -> MediaSourceType.DIRECT
                        }, pageUrl = page, mediaId = id,
                            formatId = formatId, extension = "m4a", userAgent = userAgent ?: headers?.text("User-Agent"),
                            useBrowserSession = browserSession, extractor = item.text("extractor_key").orEmpty(), audioOnly = true))
                }.distinctBy { it.media?.formatId }
            if (variants.isEmpty()) null else DouyinVideo(id, item.text("title") ?: AppText.get(R.string.fallback_title_web_video),
                webUrl(item.text("thumbnail")), ((item.number("duration") ?: 0.0) * 1000).toLong(),
                DouyinAuthor(nickname = item.text("uploader").orEmpty(), platform = VideoPlatform.GENERIC),
                variants, page, VideoPlatform.GENERIC, audioVariants = audioVariants,
                subtitleLanguages = languages(item["subtitles"]), autoCaptionLanguages = languages(item["automatic_captions"]))
        }.distinctBy { it.awemeId to it.shareUrl }
        require(videos.isNotEmpty()) { AppText.get(R.string.generic_parse_no_video) }
        return ParsedVideos(videos)
    }
}
