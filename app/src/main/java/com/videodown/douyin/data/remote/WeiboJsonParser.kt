package com.videodown.douyin.data.remote

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import com.videodown.douyin.domain.model.DouyinAuthor
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class WeiboJsonParser {
    fun parse(body: String, requestedId: String): DouyinVideo? = parseVideos(body, requestedId)?.selectedVideo

    fun parseVideos(body: String, requestedId: String): ParsedVideos? {
        val response = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val root = response.obj("data")?.takeIf { it.containsKey("id") || it.containsKey("idstr") } ?: response
        for (post in listOfNotNull(root, root.obj("retweeted_status"))) {
            val videos = listOfNotNull(parseVideo(post, requestedId)?.takeIf { it.variants.isNotEmpty() }) +
                parseGifVideos(post, requestedId)
            if (videos.isNotEmpty()) return ParsedVideos(videos)
        }
        return null
    }

    private fun parseVideo(post: JsonObject, requestedId: String): DouyinVideo? {
        val page = post.obj("page_info")
        val media = page?.obj("media_info") ?: post.obj("mix_media_info")?.array("items")
            ?.mapNotNull { (it as? JsonObject)?.obj("data")?.obj("media_info") }?.firstOrNull() ?: return null
        val variants = media.array("playback_list").mapNotNull { element ->
            val play = (element as? JsonObject)?.obj("play_info") ?: return@mapNotNull null
            // The list also contains JPEG scrubber sheets; never expose them as videos.
            if (play.text("mime")?.startsWith("image/") == true || play.number("type") == 3L) return@mapNotNull null
            val url = mp4Url(play.text("url")) ?: return@mapNotNull null
            val width = play.number("width")?.toInt()
            val height = play.number("height")?.toInt()
            VideoVariant(url, qualityLabel(width, height, play.text("quality_desc") ?: AppText.get(R.string.quality_original)),
                width, height, bitrate = play.number("bitrate")?.toInt(), sizeBytes = play.number("size")?.takeIf { it > 0 },
                qualityRank = minOf(width ?: 0, height ?: 0), referer = VideoPlatform.WEIBO.referer)
        }.toMutableList()
        if (variants.isEmpty()) {
            // Only the rank is decided here; the visible label comes from qualityLabel, so no
            // display string is ever compared (which would break the moment it is translated).
            listOf("mp4_720p_mp4" to 720, "mp4_hd_url" to 540, "stream_url_hd" to 540,
                "mp4_sd_url" to 360, "stream_url" to 360).forEach { (key, rank) ->
                mp4Url(media.text(key))?.let {
                    variants.add(VideoVariant(it, qualityLabel(rank, rank),
                        qualityRank = rank, referer = VideoPlatform.WEIBO.referer))
                }
            }
        }
        val user = post.obj("user")
        val id = post.text("mblogid") ?: post.text("idstr") ?: requestedId
        val title = (media.text("video_title") ?: media.text("kol_title") ?: media.text("name")
            ?: post.text("text_raw") ?: post.text("text") ?: page?.text("page_title") ?: AppText.get(R.string.fallback_title_weibo_video))
            .replace(Regex("<[^>]*>"), "").replace("&amp;", "&").trim().take(160)
        return DouyinVideo(
            awemeId = id, title = title.ifBlank { AppText.get(R.string.fallback_title_weibo_video) },
            coverUrl = mediaUrl(page?.text("page_pic") ?: page?.obj("page_pic")?.text("url")),
            durationMs = ((media.text("duration")?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
            author = DouyinAuthor(uid = user?.text("idstr") ?: user?.text("id"),
                nickname = user?.text("screen_name") ?: media.text("author_name").orEmpty(),
                avatarUrl = mediaUrl(user?.text("avatar_hd") ?: user?.text("profile_image_url")), platform = VideoPlatform.WEIBO),
            variants = variants.distinctBy { it.url }, shareUrl = "https://m.weibo.cn/status/$id", platform = VideoPlatform.WEIBO,
        )
    }

    private fun parseGifVideos(post: JsonObject, requestedId: String): List<DouyinVideo> {
        val pictures = post.obj("pic_infos") ?: return emptyList()
        // Use the post's attachment order. Separate GIFs are media items, not quality variants.
        val pictureIds = (post["pic_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: pictures.keys.toList()
        val id = post.text("mblogid") ?: post.text("idstr") ?: post.text("id") ?: requestedId
        val user = post.obj("user")
        val title = (post.text("text_raw") ?: post.text("text") ?: AppText.get(R.string.fallback_title_weibo_gif))
            .replace(Regex("<[^>]*>"), "").replace("&amp;", "&").trim().take(160).ifBlank { AppText.get(R.string.fallback_title_weibo_gif) }
        return pictureIds.distinct().mapNotNull { pictureId ->
            val picture = pictures.obj(pictureId) ?: return@mapNotNull null
            val source = mp4Url(picture.text("video"))?.toHttpUrl() ?: return@mapNotNull null
            // Weibo still advertises HTTP for GIF renditions; its image CDN also serves HTTPS.
            val url = if (source.scheme == "http" &&
                (source.host == "sinaimg.cn" || source.host.endsWith(".sinaimg.cn"))) {
                source.newBuilder().scheme("https").build().toString()
            } else source.toString()
            val sizes = listOf("largest", "original", "large", "bmiddle", "thumbnail")
                .mapNotNull { picture.obj(it) }
            val dimensions = sizes.firstOrNull { (it.number("width") ?: 0) > 0 && (it.number("height") ?: 0) > 0 }
            val width = dimensions?.number("width")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            val height = dimensions?.number("height")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            val cover = mediaUrl(picture.obj("largecover")?.text("url"))
                ?: sizes.firstNotNullOfOrNull { mediaUrl(it.text("url")) }
            DouyinVideo(
                awemeId = "${id}_$pictureId", title = title, coverUrl = cover,
                author = DouyinAuthor(uid = user?.text("idstr") ?: user?.text("id"),
                    nickname = user?.text("screen_name").orEmpty(),
                    avatarUrl = mediaUrl(user?.text("avatar_hd") ?: user?.text("profile_image_url")),
                    platform = VideoPlatform.WEIBO),
                variants = listOf(VideoVariant(url, qualityLabel(width, height), width, height,
                    qualityRank = minOf(width ?: 0, height ?: 0), referer = VideoPlatform.WEIBO.referer)),
                shareUrl = "https://m.weibo.cn/status/$id", platform = VideoPlatform.WEIBO,
            )
        }.mapIndexed { index, video -> video.copy(mediaLabel = AppText.get(R.string.media_label_animated, index + 1)) }
    }
}
