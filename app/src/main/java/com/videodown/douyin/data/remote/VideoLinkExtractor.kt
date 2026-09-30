package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.VideoPlatform
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class VideoLink(val url: String, val platform: VideoPlatform)

object VideoLinkExtractor {
    private val links = Regex("https?://[^\\s<>\\\"“”‘’，。！？、）)\\]【】]+", RegexOption.IGNORE_CASE)

    fun extract(text: String): VideoLink? = extractAll(text).firstOrNull()

    fun extractAll(text: String): List<VideoLink> = links.findAll(text).mapNotNull { match ->
        val url = match.value.trimEnd('.', ',', ';', '!', '?', '。', '；').toHttpUrlOrNull() ?: return@mapNotNull null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return@mapNotNull null
        val platform = platformForHost(url.host) ?: VideoPlatform.GENERIC
        VideoLink(url.toString(), platform)
    }.distinctBy { it.url }.toList()

    fun platformForHost(host: String): VideoPlatform? = when (host.lowercase()) {
        "douyin.com", "www.douyin.com", "v.douyin.com", "iesdouyin.com", "www.iesdouyin.com" -> VideoPlatform.DOUYIN
        "weibo.com", "www.weibo.com", "m.weibo.cn", "weibo.cn", "video.weibo.com", "t.cn" -> VideoPlatform.WEIBO
        "kuaishou.com", "www.kuaishou.com", "v.kuaishou.com", "v.m.chenzhongtech.com", "v.m.gifshow.com", "m.gifshow.com" -> VideoPlatform.KUAISHOU
        "x.com", "www.x.com", "m.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "m.twitter.com", "mobile.twitter.com", "t.co" -> VideoPlatform.X
        else -> null
    }

    fun weiboId(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (platformForHost(parsed.host) != VideoPlatform.WEIBO) return null
        val path = parsed.pathSegments
        val id = when {
            parsed.host in listOf("weibo.com", "www.weibo.com") && path.size >= 2 && path[0].all(Char::isDigit) -> path[1]
            parsed.host in listOf("m.weibo.cn", "weibo.cn") && path.size >= 2 && path[0] in listOf("status", "detail") -> path[1]
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9]{5,32}")) }
    }

    fun kuaishouId(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val path = parsed.pathSegments
        val id = parsed.queryParameter("photoId") ?: when {
            path.size >= 2 && path[0] == "short-video" -> path[1]
            path.size >= 3 && path[0] == "fw" && path[1] == "photo" -> path[2]
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{5,64}")) }
    }
}
