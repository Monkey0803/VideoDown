package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal sealed interface XParseResult {
    data class Success(val value: ParsedVideos) : XParseResult
    /**
     * [failure] is the typed reason; [httpStatus] is set when the request itself failed, which is
     * reported as an HTTP error rather than as a parse failure.
     */
    data class Failure(val failure: XParseFailure, val allowFallback: Boolean = true, val httpStatus: Int? = null) : XParseResult
}

internal class XVideoJsonParser {
    fun graphql(body: String, link: XVideoLink): XParseResult {
        val root = json(body) ?: return invalid()
        val result = root.obj("data")?.obj("tweetResult")?.obj("result") ?: return invalid()
        blocked(result)?.let { return it }
        val tweet = unwrap(result)
        blocked(tweet)?.let { return it }
        if (postId(tweet) != link.postId) return invalid()
        val original = tweet.obj("legacy")?.obj("retweeted_status_result")?.obj("result")
        original?.let { blocked(it)?.let { failure -> return failure } }
        val post = original?.let(::unwrap) ?: tweet
        blocked(post)?.let { return it }
        val quoted = post.obj("quoted_status_result")?.obj("result")?.takeIf { blocked(it) == null }?.let(::unwrap)
        return collect(link, post, quoted, embedded = false)
    }

    fun syndication(body: String, link: XVideoLink): XParseResult {
        val post = json(body) ?: return invalid()
        blocked(post)?.let { return it }
        if (post.text("id_str") != link.postId) return invalid()
        return collect(link, post, post.obj("quoted_tweet")?.takeIf { blocked(it) == null }, embedded = true)
    }

    private fun collect(link: XVideoLink, post: JsonObject, quote: JsonObject?, embedded: Boolean): XParseResult {
        fun media(p: JsonObject) = if (embedded) p.array("mediaDetails")
            else p.obj("legacy")?.obj("extended_entities")?.array("media") ?: JsonArray(emptyList())
        val videos = mutableListOf<DouyinVideo>()
        var hasVideo = false
        var selectedId: String? = null
        listOfNotNull(post, quote).forEachIndexed { sourceIndex, source ->
            val id = if (embedded) source.text("id_str") else postId(source)
            if (id == null || !id.matches(Regex("[0-9]{5,22}"))) return@forEachIndexed
            val legacy = if (embedded) source else source.obj("legacy") ?: return@forEachIndexed
            val userNode = if (embedded) source.obj("user") else source.obj("core")?.obj("user_results")?.obj("result")
            val user = if (embedded) userNode else userNode?.obj("legacy")
            val username = (user?.text("screen_name") ?: userNode?.obj("core")?.text("screen_name"))
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,50}")) }
            val author = DouyinAuthor(uid = userNode?.text("rest_id") ?: user?.text("id_str"), secUid = username,
                nickname = user?.text("name") ?: userNode?.obj("core")?.text("name") ?: username ?: "X 用户",  // l10n-ignore: 持久化内容（记录标题/文件名/目录名/画质标签）：随语言改变会让同一内容落到不同名字
                avatarUrl = mediaUrl(user?.text("profile_image_url_https") ?: userNode?.obj("avatar")?.text("image_url")),
                platform = VideoPlatform.X)
            media(source).forEachIndexed mediaLoop@{ index, element ->
                val item = element as? JsonObject ?: return@mediaLoop
                if (item.text("type") !in listOf("video", "animated_gif")) return@mediaLoop
                hasVideo = true
                val info = item.obj("video_info") ?: return@mediaLoop
                val variants = info.array("variants").mapNotNull { raw ->
                    val variant = raw as? JsonObject ?: return@mapNotNull null
                    if (variant.text("content_type") != "video/mp4") return@mapNotNull null
                    val url = mp4Url(variant.text("url"))?.toHttpUrlOrNull()
                        ?.takeIf { it.isHttps && it.host == "video.twimg.com" } ?: return@mapNotNull null
                    val size = Regex("/(\\d{2,5})x(\\d{2,5})/").find(url.encodedPath)
                    val width = size?.groupValues?.get(1)?.toIntOrNull()
                    val height = size?.groupValues?.get(2)?.toIntOrNull()
                    VideoVariant(url.toString(), qualityLabel(width, height), width, height,
                        bitrate = (variant.number("bitrate") ?: variant.number("bit_rate"))?.toInt(),
                        qualityRank = minOf(width ?: 0, height ?: 0), referer = VideoPlatform.X.referer)
                }.sortedByDescending { it.bitrate ?: 0 }.distinctBy { it.url }
                if (variants.isEmpty()) return@mediaLoop
                val mediaId = item.text("id_str")?.takeIf { it.all(Char::isDigit) }
                    ?: Regex("_video/(\\d+)/").find(variants.first().url)?.groupValues?.get(1) ?: (index + 1).toString()
                val key = "${id}_$mediaId"
                if (sourceIndex == 0 && link.mediaIndex == index + 1) selectedId = key
                val fullText = source.obj("note_tweet")?.obj("note_tweet_results")?.obj("result")?.text("text")
                    ?: legacy.text("full_text") ?: legacy.text("text") ?: "X 视频"  // l10n-ignore: 持久化内容（记录标题/文件名/目录名/画质标签）：随语言改变会让同一内容落到不同名字
                videos.add(DouyinVideo(key, fullText.replace(Regex("https://t\\.co/\\w+"), "")
                    .replace("&amp;", "&").trim().take(200).ifBlank { "X 视频" },  // l10n-ignore: 持久化内容（记录标题/文件名/目录名/画质标签）：随语言改变会让同一内容落到不同名字
                    coverUrl = mediaUrl(item.text("media_url_https") ?: item.text("media_url")),
                    durationMs = info.number("duration_millis") ?: 0L, author = author, variants = variants,
                    shareUrl = "https://x.com/${username ?: "i"}/status/$id/video/${index + 1}", platform = VideoPlatform.X,
                    mediaLabel = "${if (sourceIndex == 1) "引用 · " else ""}视频 ${index + 1}"))  // l10n-ignore: 持久化内容（记录标题/文件名/目录名/画质标签）：随语言改变会让同一内容落到不同名字
            }
        }
        if (link.mediaIndex != null && selectedId == null) {
            return XParseResult.Failure(XParseFailure.NOT_FOUND)
        }
        if (videos.isEmpty()) return XParseResult.Failure(if (hasVideo) XParseFailure.UNSUPPORTED_FORMAT else XParseFailure.NO_VIDEO)
        val unique = videos.distinctBy { it.awemeId }
        return XParseResult.Success(ParsedVideos(unique, selectedId?.let { key -> unique.indexOfFirst { it.awemeId == key } } ?: 0))
    }

    private fun blocked(post: JsonObject): XParseResult.Failure? = when {
        post.text("__typename") == "TweetUnavailable" -> XParseResult.Failure(
            if (post.text("reason") in listOf("Protected", "NsfwLoggedOut", "NsfwViewerHasNoStatedAge")) XParseFailure.LOGIN_REQUIRED
            else XParseFailure.UNAVAILABLE, false)
        post.text("__typename") == "TweetTombstone" || post.containsKey("tombstone") ->
            XParseResult.Failure(XParseFailure.RESTRICTED, false)
        else -> null
    }
    private fun unwrap(post: JsonObject): JsonObject = if (post.text("__typename") == "TweetWithVisibilityResults") post.obj("tweet") ?: post else post
    private fun postId(post: JsonObject) = post.text("rest_id") ?: post.obj("legacy")?.text("id_str")
    private fun json(body: String) = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    private fun invalid() = XParseResult.Failure(XParseFailure.INVALID)
}
