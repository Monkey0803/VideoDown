package com.videodown.douyin.data.remote

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import com.videodown.douyin.domain.model.DouyinAuthor
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.LivePhotoMotion
import com.videodown.douyin.domain.model.MediaSignature
import com.videodown.douyin.domain.model.MediaSourceType
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
                parseGifVideos(post, requestedId) +
                parsePictures(post, requestedId)
            if (videos.isNotEmpty()) return ParsedVideos(videos)
        }
        return null
    }

    /**
     * 微博正文里的静态图片（九宫格/多图）。
     *
     * GIF 图片带 `video` 字段，由 [parseGifVideos] 以 mp4 形式产出，这里跳过以免重复；
     * 位置编号沿用原帖的附件顺序，动图被跳过时图片序号仍是它在原帖里的位置。
     */
    private fun parsePictures(post: JsonObject, requestedId: String): List<DouyinVideo> {
        val pictures = post.obj("pic_infos") ?: return emptyList()
        val pictureIds = (post["pic_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: pictures.keys.toList()
        val id = post.text("mblogid") ?: post.text("idstr") ?: post.text("id") ?: requestedId
        val user = post.obj("user")
        val page = "https://m.weibo.cn/status/$id"
        val title = (post.text("text_raw") ?: post.text("text") ?: AppText.get(R.string.fallback_title_weibo_image))
            .replace(Regex("<[^>]*>"), "").replace("&amp;", "&").trim().take(160)
            .ifBlank { AppText.get(R.string.fallback_title_weibo_image) }
        return pictureIds.distinct().mapIndexedNotNull { index, pictureId ->
            val picture = pictures.obj(pictureId) ?: return@mapIndexedNotNull null
            // `video` 在载荷里是字符串（mp4 地址），不是对象；动图交给 parseGifVideos。
            // Live Photo 例外：它的 `video` 是 .mov 动作片段，静态帧本身仍是可保存的图片，
            // 不能因为带了 video 字段就整张丢掉——否则微博的 livephoto 帖子会解析成空。
            val isLivePhoto = picture.text("type") == "livephoto"
            if (!isLivePhoto && picture.text("video") != null) return@mapIndexedNotNull null
            // 静态图原图优先，其后才是站方压过的大图；Live Photo 的 `original` 常被压到 1080，
            // 而 `largest` 才是它能提供的最大静帧，所以两者顺序不同。URL 缺失就换下一个尺寸。
            val sizeKeys = if (isLivePhoto) LIVE_PHOTO_SIZE_KEYS else PIC_SIZE_KEYS
            val size = sizeKeys.mapNotNull { picture.obj(it) }.firstOrNull { it.text("url") != null }
                ?: return@mapIndexedNotNull null
            val url = pictureUrl(size.text("url")) ?: return@mapIndexedNotNull null
            val width = size.number("width")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            val height = size.number("height")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            val extension = MediaSignature.extensionFromUrl(url) ?: "jpg"
            val entryId = "${id}_img${index + 1}"
            val motion = if (isLivePhoto) {
                mediaUrl(picture.text("video"))?.let { motionUrl ->
                    LivePhotoMotion(
                        sourceUrl = motionUrl,
                        referer = VideoPlatform.WEIBO.referer,
                        extension = "mov",
                    )
                }
            } else null
            // Live Photo 的语义必须完整：载荷缺少动态片段时不退化成普通静态图。
            if (isLivePhoto && motion == null) return@mapIndexedNotNull null
            DouyinVideo(
                awemeId = entryId,
                title = title,
                coverUrl = url,
                author = DouyinAuthor(
                    uid = user?.text("idstr") ?: user?.text("id"),
                    nickname = user?.text("screen_name").orEmpty(),
                    avatarUrl = mediaUrl(user?.text("avatar_hd") ?: user?.text("profile_image_url")),
                    platform = VideoPlatform.WEIBO,
                ),
                variants = listOf(
                    VideoVariant(
                        url = url,
                        label = AppText.get(R.string.quality_original),
                        width = width,
                        height = height,
                        qualityRank = 0,
                        referer = VideoPlatform.WEIBO.referer,
                        kind = MediaKind.IMAGE,
                        livePhotoMotion = motion,
                        media = MediaDownload(
                            type = MediaSourceType.DIRECT,
                            pageUrl = page,
                            mediaId = entryId,
                            formatId = extension,
                            extension = extension,
                            extractor = "image",
                        ),
                    ),
                ),
                shareUrl = page,
                platform = VideoPlatform.WEIBO,
                mediaLabel = AppText.get(
                    if (isLivePhoto) R.string.media_label_live_photo else R.string.media_label_image,
                    index + 1,
                ),
            )
        }
    }

    /** 图片地址规范化：补全 `//` 前缀并把新浪图床的 http 改成 https。 */
    private fun pictureUrl(raw: String?): String? = mediaUrl(raw)?.let(::forceHttpsSina)

    private fun forceHttpsSina(url: String): String {
        val parsed = url.toHttpUrl() ?: return url
        return if (parsed.scheme == "http" &&
            (parsed.host == "sinaimg.cn" || parsed.host.endsWith(".sinaimg.cn"))) {
            parsed.newBuilder().scheme("https").build().toString()
        } else url
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
            // Live Photo 的 video 是 .mov 动作片段，不是 GIF；静态帧由 parsePictures 产出。
            if (picture.text("type") == "livephoto") return@mapNotNull null
            val source = mp4Url(picture.text("video"))?.toHttpUrl() ?: return@mapNotNull null
            // Weibo still advertises HTTP for GIF renditions; its image CDN also serves HTTPS.
            val url = forceHttpsSina(source.toString())
            val sizes = listOf("largest", "original", "large", "bmiddle", "thumbnail")
                .mapNotNull { picture.obj(it) }
            val dimensions = sizes.firstOrNull { (it.number("width") ?: 0) > 0 && (it.number("height") ?: 0) > 0 }
            val width = dimensions?.number("width")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            val height = dimensions?.number("height")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            val cover = mediaUrl(picture.obj("largecover")?.text("url"))
                ?: sizes.firstNotNullOfOrNull { mediaUrl(it.text("url")) }
            val page = "https://m.weibo.cn/status/$id"
            val variants = mutableListOf(
                VideoVariant(
                    url,
                    qualityLabel(width, height),
                    width,
                    height,
                    qualityRank = minOf(width ?: 0, height ?: 0),
                    referer = VideoPlatform.WEIBO.referer,
                ),
            )
            // 微博同时保留 GIF 原图和便于播放的 MP4 转码。两者是同一媒体的下载格式，
            // 放进同一个条目的变体列表，结果页可选择而不会把一张动图重复成两条内容。
            listOf("original", "largest", "large", "bmiddle", "thumbnail")
                .mapNotNull { picture.obj(it) }
                .firstNotNullOfOrNull { size ->
                    pictureUrl(size.text("url"))?.takeIf { MediaSignature.extensionFromUrl(it) == "gif" }
            }?.let { gifUrl ->
                variants += VideoVariant(
                    url = gifUrl,
                    label = "GIF",
                    width = width,
                    height = height,
                    qualityRank = minOf(width ?: 0, height ?: 0),
                    referer = VideoPlatform.WEIBO.referer,
                    media = MediaDownload(
                        type = MediaSourceType.DIRECT,
                        pageUrl = page,
                        mediaId = "${id}_$pictureId",
                        formatId = "gif",
                        extension = "gif",
                        extractor = "image",
                    ),
                    kind = MediaKind.IMAGE,
                )
            }
            DouyinVideo(
                awemeId = "${id}_$pictureId", title = title, coverUrl = cover,
                author = DouyinAuthor(uid = user?.text("idstr") ?: user?.text("id"),
                    nickname = user?.text("screen_name").orEmpty(),
                    avatarUrl = mediaUrl(user?.text("avatar_hd") ?: user?.text("profile_image_url")),
                    platform = VideoPlatform.WEIBO),
                variants = variants,
                shareUrl = page, platform = VideoPlatform.WEIBO,
            )
        }.mapIndexed { index, video -> video.copy(mediaLabel = AppText.get(R.string.media_label_animated, index + 1)) }
    }

    private companion object {
        /** 微博图片的尺寸档位；`original` 才是真原图，`largest` 往往是压缩过的最大可用图。 */
        val PIC_SIZE_KEYS = listOf("original", "largest", "large", "bmiddle", "thumbnail")
        /** Live Photo 的静帧：`largest` 通常比被压到 1080 的 `original` 更大。 */
        val LIVE_PHOTO_SIZE_KEYS = listOf("largest", "original", "large", "bmiddle", "thumbnail")
    }
}
