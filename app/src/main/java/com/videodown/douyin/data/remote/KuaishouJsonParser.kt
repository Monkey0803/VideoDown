package com.videodown.douyin.data.remote

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import com.videodown.douyin.domain.model.DouyinAuthor
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.MediaSignature
import com.videodown.douyin.domain.model.MediaSourceType
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.serialization.json.*

class KuaishouJsonParser {

    /** 只要第一条；多条目（图集）用 [parseVideos]。 */
    fun parse(html: String, pageUrl: String): DouyinVideo? = parseVideos(html, pageUrl).firstOrNull()

    /**
     * 解析分享页内嵌 JSON。
     *
     * 图集作品在 `photo.atlas` 里给出图片相对路径、CDN 主机和每张图的尺寸（实测载荷：
     * `{cdnList:[{cdn}], list:["/ufile/atlas/xxx_0.jpg", …], size:[{w,h}, …]}`），
     * 这里展开成「每张图一个条目」；视频作品仍是一条视频。两种作品都用同一个作品 id，
     * 顺序按 atlas 列表。
     */
    fun parseVideos(html: String, pageUrl: String): List<DouyinVideo> {
        val root = embeddedJson(html, "INIT_STATE") ?: embeddedJson(html, "__APOLLO_STATE__") ?: return emptyList()
        val expectedId = VideoLinkExtractor.kuaishouId(pageUrl)
        val candidates = objects(root).filter {
            it.containsKey("mainMvUrls") || it.containsKey("photoUrl") || it.containsKey("videoResource") || it.containsKey("atlas")
        }
        val photo = candidates.firstOrNull { expectedId == null || expectedId in ids(it) } ?: return emptyList()
        val id = expectedId ?: ids(photo).firstOrNull() ?: return emptyList()
        val user = photo.obj("user") ?: objects(root).firstOrNull { it.text("id") == photo.text("userId") && it.containsKey("name") }
        val author = DouyinAuthor(
            uid = photo.text("userId") ?: user?.text("id"),
            secUid = photo.text("userEid"),
            nickname = photo.text("userName") ?: user?.text("name").orEmpty(),
            avatarUrl = mediaUrl(photo.text("headUrl") ?: user?.text("headerUrl")),
            platform = VideoPlatform.KUAISHOU,
        )

        val images = atlasImages(photo)
        if (images.isNotEmpty()) {
            return images.mapIndexed { index, image ->
                val entryId = "${id}_img${index + 1}"
                val extension = MediaSignature.extensionFromUrl(image.url) ?: "jpg"
                val page = "https://www.kuaishou.com/short-video/$id"
                DouyinVideo(
                    awemeId = entryId,
                    title = photo.text("caption")?.take(160) ?: AppText.get(R.string.fallback_title_kuaishou_image),
                    coverUrl = image.url,
                    author = author,
                    variants = listOf(
                        VideoVariant(
                            url = image.url,
                            label = AppText.get(R.string.quality_original),
                            width = image.width,
                            height = image.height,
                            qualityRank = 0,
                            referer = VideoPlatform.KUAISHOU.referer,
                            kind = MediaKind.IMAGE,
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
                    platform = VideoPlatform.KUAISHOU,
                    mediaLabel = AppText.get(R.string.media_label_image, index + 1),
                )
            }
        }

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
        return listOf(
            DouyinVideo(
                awemeId = id, title = photo.text("caption")?.take(160) ?: AppText.get(R.string.fallback_title_kuaishou_video),
                coverUrl = mediaUrl(photo.array("coverUrls").firstOrNull()?.let { (it as? JsonObject)?.text("url") }
                    ?: photo.text("coverUrl")), durationMs = photo.number("duration") ?: 0L,
                author = author,
                variants = variants.distinctBy { it.url },
                shareUrl = "https://www.kuaishou.com/short-video/$id", platform = VideoPlatform.KUAISHOU,
            ),
        )
    }

    private data class AtlasImage(val url: String, val width: Int?, val height: Int?)

    private fun atlasImages(photo: JsonObject): List<AtlasImage> {
        val atlas = photo.obj("atlas") ?: return emptyList()
        // 路径是相对 CDN 主机的；主机列表在 cdnList（对象）或 cdn（字符串）里各版本给一个。
        val host = atlas.array("cdnList").firstNotNullOfOrNull { (it as? JsonObject)?.text("cdn") }
            ?: atlas.array("cdn").firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: return emptyList()
        val sizes = atlas.array("size")
        return atlas.array("list").mapIndexedNotNull { index, element ->
            val path = (element as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
            val size = sizes.getOrNull(index) as? JsonObject
            AtlasImage(
                url = if (path.startsWith("http")) path else "https://$host$path",
                width = size?.number("w")?.takeIf { it > 0 }?.toInt(),
                height = size?.number("h")?.takeIf { it > 0 }?.toInt(),
            )
        }
    }

    private fun ids(photo: JsonObject): List<String> = listOfNotNull(
        photo.text("share_info")?.split('&')?.firstOrNull { it.startsWith("photoId=") }?.substringAfter('='),
        photo.text("id"), photo.text("photoId"),
    ).filter { it.matches(Regex("[A-Za-z0-9_-]{5,64}")) }
}
