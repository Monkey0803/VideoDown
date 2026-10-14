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

    /**
     * 是否是视频号分享链接。
     *
     * 为什么单独把它认出来：分享短链 `weixin.qq.com/sph/…` 会跳到 `channels.weixin.qq.com`，而那个页面
     * 只给标题、作者和播放数据——**播放地址是微信客户端内动态签发的，网页里没有可直接下载的地址**，
     * 内置引擎也没有视频号的解析器（实测：打包的 yt-dlp 里没有任何 weixin/wechat 提取器）。
     * 于是解析必然失败，这时要如实说清楚，而不是把通用兜底那句"打开网页登录或播放后查找"抛给用户——
     * 那条路对这个网站是死的。它只决定"失败时说哪句话"，不是拒绝解析：万一页面将来真给了直链，
     * 解析照样会成功。
     *
     * 只认视频号：`mp.weixin.qq.com` 的公众号文章不在此列，那种页面往往内嵌普通视频，能走通用路径。
     */
    fun isWeChatChannelsLink(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return when (parsed.host.lowercase()) {
            "channels.weixin.qq.com" -> true
            "weixin.qq.com", "www.weixin.qq.com" -> parsed.pathSegments.firstOrNull() == "sph"
            else -> false
        }
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
