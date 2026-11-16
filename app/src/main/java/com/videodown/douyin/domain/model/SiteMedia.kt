package com.videodown.douyin.domain.model

import kotlinx.serialization.Serializable
import java.net.URI

/** 可扩展站点专区中的一个频道；[path] 永远是站点内的绝对路径。 */
data class SiteChannel(
    val id: String,
    val path: String,
)

/** 站点适配器明确声明的能力；未声明的能力一律不向界面开放。 */
data class SiteCapabilities(
    val onlinePlayback: Boolean,
    val download: Boolean,
    val anonymousOnly: Boolean = true,
    val live: Boolean = false,
)

/** 站点适配器向界面公开的稳定能力，不包含 Android 或网络实现。 */
data class SiteSource(
    val id: String,
    val homeUrl: String,
    val channels: List<SiteChannel>,
    val navigationHosts: Set<String>,
    val capabilities: SiteCapabilities,
    val blockedPathPrefixes: Set<String> = emptySet(),
    val mediaHostSuffixes: Set<String> = emptySet(),
) {
    fun channelUrl(channel: SiteChannel): String = homeUrl.trimEnd('/') + channel.path

    fun allowsNavigation(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        if (navigationHosts.none { host == it || host.endsWith(".$it") }) return false
        val path = uri.path.orEmpty().lowercase()
        return blockedPathPrefixes.none { path == it || path.startsWith("$it/") }
    }

    fun acceptsMediaUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = uri.host?.lowercase() ?: return false
        return mediaHostSuffixes.isEmpty() || mediaHostSuffixes.any { host == it || host.endsWith(".$it") }
    }
}

/**
 * 一次在线播放所需的临时数据。
 *
 * CDN 地址和请求头只留在内存里；持久化历史只保存可重新解析的页面地址。
 */
data class OnlinePlaybackSession(
    val id: String,
    val siteId: String,
    val pageUrl: String,
    val video: DouyinVideo,
    val variant: VideoVariant,
    val referer: String,
    val userAgent: String,
    val resumePositionMs: Long = 0L,
)

/** 下载记录必须保存可重新解析的站点页面，而不是短期有效的媒体地址。 */
fun OnlinePlaybackSession.toDownloadVideo(): DouyinVideo = video.copy(shareUrl = pageUrl)

/** 站点页面提供的展示元数据；不参与媒体地址或授权判断。 */
@Serializable
data class SitePageMetadata(
    val title: String? = null,
    val coverUrl: String? = null,
    val episodeLabel: String? = null,
    val pageUrl: String? = null,
) {
    val displayTitle: String?
        get() {
            val base = title.cleanSiteText()?.takeUnless(::isGenericSiteMediaTitle) ?: return null
            val episode = episodeLabel.cleanSiteText()?.takeUnless { base.contains(it, ignoreCase = true) }
            return listOfNotNull(base, episode).joinToString(" · ")
        }

    val safeCoverUrl: String?
        get() {
            val value = coverUrl.cleanSiteText() ?: return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
            return uri.toString()
        }

    val safePageUrl: String?
        get() {
            val value = pageUrl.cleanSiteText() ?: return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
            return uri.toString()
        }
}

fun DouyinVideo.withSitePageMetadata(metadata: SitePageMetadata?): DouyinVideo {
    if (metadata == null) return this
    return copy(
        title = metadata.displayTitle ?: title,
        coverUrl = metadata.safeCoverUrl ?: coverUrl,
    )
}

fun isGenericSiteMediaTitle(value: String): Boolean {
    val normalized = value.trim().substringAfterLast('/').substringBefore('?').lowercase()
    return normalized.matches(Regex("^(chunklist|master|playlist|index)([_-].*)?(\\.[a-z0-9]+)?$"))
}

private fun String?.cleanSiteText(): String? = this?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotEmpty)

@Serializable
data class SiteWatchRecord(
    val id: String,
    val siteId: String,
    val pageUrl: String,
    val title: String,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val resumePositionMs: Long
        get() = if (durationMs > 0L && positionMs >= durationMs - 5_000L) 0L else positionMs.coerceAtLeast(0L)
}

/** 去掉片段但保留 `?id=` 等选集参数，保证同一集能命中同一条观看记录。 */
fun canonicalSitePageUrl(raw: String): String {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return raw.trim().substringBefore('#')
    val scheme = uri.scheme?.lowercase()
    if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return raw.trim().substringBefore('#')
    val host = uri.host.lowercase()
    val port = when {
        uri.port < 0 -> ""
        scheme == "https" && uri.port == 443 -> ""
        scheme == "http" && uri.port == 80 -> ""
        else -> ":${uri.port}"
    }
    val path = uri.rawPath?.ifBlank { "/" } ?: "/"
    return "$scheme://$host$port$path" + (uri.rawQuery?.let { "?$it" } ?: "")
}

fun siteWatchId(siteId: String, pageUrl: String): String = "$siteId|${canonicalSitePageUrl(pageUrl)}"
