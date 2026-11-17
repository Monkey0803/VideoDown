package com.videodown.douyin.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 归一化后的 B 站视频页：`https://www.bilibili.com/video/BV…`（可带 `?p=N`）。 */
data class BilibiliVideoLink(val url: String, val videoId: String, val page: Int? = null)

/** B 站链接的三种结果，界面与仓库按它分流。 */
sealed interface BilibiliLinkResult {
    data class Page(val link: BilibiliVideoLink) : BilibiliLinkResult

    /** `b23.tv` 短链：必须联网展开，展开后的目标还要过同一套校验。 */
    data class ShortLink(val url: String) : BilibiliLinkResult

    /** 番剧、直播、课程、音频、空间、频道列表等第一版不支持的内容。 */
    data object Unsupported : BilibiliLinkResult
}

/**
 * E1：B 站链接识别与归一化（纯 Kotlin，可穷举）。
 *
 * 只认单个视频页：`/video/BV…`、`/video/av…`，允许显式 `?p=N`。番剧、直播、课程、音频、
 * 空间和频道列表一律返回 [BilibiliLinkResult.Unsupported]——宁可说清楚不支持，也不要把
 * 「解析不到」伪装成网络失败。
 *
 * 短链只接受 `b23.tv`，且展开后的跳转目标必须仍是可信的 B 站视频页；其它主机的跳转一律拒绝，
 * 避免一个开放重定向把解析带到任意站点。
 */
object BilibiliLink {
    // BV 号区分大小写（`BV1GJ411x7h7` 与全小写不是同一个 ID），av 号只接受十进制。
    private val videoIdPattern = Regex("(?:BV[0-9A-Za-z]{10}|[aA][vV][0-9]{1,12})")
    private val pageHosts = setOf("bilibili.com", "www.bilibili.com", "m.bilibili.com")
    private val shortHosts = setOf("b23.tv", "www.b23.tv")
    /** 明确不支持的内容子域：识别成 B 站，但直接回复「不支持」，不交给引擎。 */
    private val unsupportedHosts = setOf("live.bilibili.com", "space.bilibili.com", "audio.bilibili.com")

    fun parse(raw: String): BilibiliLinkResult {
        val url = raw.toHttpUrlOrNull() ?: return BilibiliLinkResult.Unsupported
        val host = url.host.lowercase()
        if (host in shortHosts) return BilibiliLinkResult.ShortLink(url.toString())
        if (host in unsupportedHosts) return BilibiliLinkResult.Unsupported
        if (host !in pageHosts) return BilibiliLinkResult.Unsupported
        val link = videoLink(url.toString()) ?: return BilibiliLinkResult.Unsupported
        return BilibiliLinkResult.Page(link)
    }

    /** 展开后的跳转目标是否仍是可信视频页；不是就返回 null。 */
    fun trustedTarget(raw: String): BilibiliVideoLink? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.host.lowercase() !in pageHosts) return null
        return videoLink(url.toString())
    }

    /** 展开短链时允许经过的主机；跳去别处就停止，不跟到底。 */
    fun isTrustedRedirectHost(host: String): Boolean =
        host.lowercase() in pageHosts || host.lowercase() in shortHosts

    private fun videoLink(raw: String): BilibiliVideoLink? {
        val url = raw.toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments.filter(String::isNotBlank)
        if (segments.size < 2 || segments[0] != "video") return null
        val id = segments[1].takeIf { videoIdPattern.matches(it) } ?: return null
        // BV 号保持原样；av 前缀统一成小写（`AV123` 与 `av123` 是同一个视频）。
        val canonicalId = if (id.startsWith("av", ignoreCase = true)) "av" + id.substring(2) else id
        val page = url.queryParameter("p")?.toIntOrNull()?.takeIf { it > 0 }
        val canonical = buildString {
            append("https://www.bilibili.com/video/").append(canonicalId)
            if (page != null && page > 1) append("?p=").append(page)
        }
        return BilibiliVideoLink(canonical, canonicalId, page)
    }
}
