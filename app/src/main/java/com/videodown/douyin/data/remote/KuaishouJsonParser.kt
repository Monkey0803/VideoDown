package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.DouyinAuthor
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.serialization.json.*

class KuaishouJsonParser {
    fun parse(html: String, pageUrl: String): DouyinVideo? {
        val root = embeddedJson(html, "INIT_STATE") ?: embeddedJson(html, "__APOLLO_STATE__") ?: return null
        val expectedId = VideoLinkExtractor.kuaishouId(pageUrl)
        val candidates = objects(root).filter { it.containsKey("mainMvUrls") || it.containsKey("photoUrl") || it.containsKey("videoResource") }
        val photo = candidates.firstOrNull { expectedId == null || expectedId in ids(it) } ?: return null
        val id = expectedId ?: ids(photo).firstOrNull() ?: return null
        val width = photo.number("width")?.toInt()
        val height = photo.number("height")?.toInt()
        val variants = mutableListOf<VideoVariant>()
        fun add(raw: String?, w: Int? = width, h: Int? = height, bitrate: Int? = null) {
            val url = mp4Url(raw) ?: return
            variants.add(VideoVariant(url, qualityLabel(w, h), w, h, bitrate = bitrate,
                qualityRank = minOf(w ?: 0, h ?: 0), referer = VideoPlatform.KUAISHOU.referer))
        }
        // mainMvUrls contains progressive MP4; manifest often only contains HLS playlists.
        photo.array("mainMvUrls").mapNotNull { (it as? JsonObject)?.text("url") }.forEach { add(it) }
        add(photo.text("photoUrl"))
        val manifest = photo.obj("manifest") ?: photo.text("manifest")?.let {
            runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }
        listOfNotNull(manifest, photo.obj("videoResource")).forEach { resource ->
            objects(resource).filter { it.containsKey("url") && it.containsKey("width") }.forEach { rendition ->
                add(rendition.text("url"), rendition.number("width")?.toInt(), rendition.number("height")?.toInt(),
                    rendition.number("avgBitrate")?.toInt()?.times(1000))
            }
        }
        val user = photo.obj("user") ?: objects(root).firstOrNull { it.text("id") == photo.text("userId") && it.containsKey("name") }
        return DouyinVideo(
            awemeId = id, title = photo.text("caption")?.take(160) ?: "快手视频",  // l10n-ignore: 持久化内容（记录标题/文件名/目录名/画质标签）：随语言改变会让同一内容落到不同名字
            coverUrl = mediaUrl(photo.array("coverUrls").firstOrNull()?.let { (it as? JsonObject)?.text("url") }
                ?: photo.text("coverUrl")), durationMs = photo.number("duration") ?: 0L,
            author = DouyinAuthor(uid = photo.text("userId") ?: user?.text("id"), secUid = photo.text("userEid"),
                nickname = photo.text("userName") ?: user?.text("name").orEmpty(),
                avatarUrl = mediaUrl(photo.text("headUrl") ?: user?.text("headerUrl")), platform = VideoPlatform.KUAISHOU),
            variants = variants.distinctBy { it.url },
            shareUrl = "https://www.kuaishou.com/short-video/$id", platform = VideoPlatform.KUAISHOU,
        )
    }

    private fun ids(photo: JsonObject): List<String> = listOfNotNull(
        photo.text("share_info")?.split('&')?.firstOrNull { it.startsWith("photoId=") }?.substringAfter('='),
        photo.text("id"), photo.text("photoId"),
    ).filter { it.matches(Regex("[A-Za-z0-9_-]{5,64}")) }
}
