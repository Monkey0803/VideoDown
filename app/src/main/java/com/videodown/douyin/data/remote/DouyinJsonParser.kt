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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Turns Douyin payloads into [DouyinVideo].
 *
 * Douyin reshuffles where the aweme object lives (`_ROUTER_DATA.loaderData["video_(id)/page"]
 * .videoInfoRes.item_list[0]`, `aweme_detail`, `item_list[0]`, ...), so instead of hard-coding
 * a path this parser walks the JSON tree and picks the first object that actually looks like an
 * aweme. That keeps it working across the key renames Douyin ships several times a year.
 */
class DouyinJsonParser {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /** Extracts and parses the `_ROUTER_DATA` blob embedded in a share page. */
    fun parseSharePage(html: String): DouyinVideo? = parseSharePageVideos(html).firstOrNull()

    /** 图文作品会解析成多条目；只要第一条时用上面的单值入口。 */
    fun parseSharePageVideos(html: String): List<DouyinVideo> {
        val raw = extractRouterData(html) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        return parseTreeVideos(root)
    }

    /** Parses a JSON API response body. */
    fun parseJson(body: String): DouyinVideo? = parseJsonVideos(body).firstOrNull()

    fun parseJsonVideos(body: String): List<DouyinVideo> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        return parseTreeVideos(root)
    }

    /**
     * Grabs the `window._ROUTER_DATA = {...}` assignment out of the share page.
     * Uses brace counting rather than a greedy regex so nested objects survive intact.
     */
    fun extractRouterData(html: String): String? {
        val marker = html.indexOf("_ROUTER_DATA")
        if (marker < 0) return null

        val start = html.indexOf('{', marker)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until html.length) {
            val c = html[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Walks the tree and converts the first aweme-shaped object it finds. */
    fun parseTree(root: JsonElement): DouyinVideo? = parseTreeVideos(root).firstOrNull()

    /** 图文作品在这里展开成「每张图一个条目」，其余作品仍是一条视频。 */
    fun parseTreeVideos(root: JsonElement): List<DouyinVideo> {
        val aweme = findAweme(root) ?: return emptyList()
        return toVideos(aweme)
    }

    /**
     * Breadth-first search for an object carrying both `aweme_id` and a `video` object.
     * Breadth-first is deliberate: the top-level aweme is always shallower than any related
     * or recommended item nested further down, so this picks the requested video.
     */
    private fun findAweme(root: JsonElement): JsonObject? {
        val queue = ArrayDeque<JsonElement>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val current = queue.removeFirst()
            visited++

            when (current) {
                is JsonObject -> {
                    if (looksLikeAweme(current)) return current
                    current.values.forEach { queue.add(it) }
                }

                is JsonArray -> current.forEach { queue.add(it) }
                else -> Unit
            }
        }
        return null
    }

    private fun looksLikeAweme(obj: JsonObject): Boolean {
        val id = obj.string("aweme_id") ?: return false
        if (id.isBlank()) return false
        // 图文作品没有可播放的 video，但有 images：先认图片，否则 BFS 会继续往下找到
        // 推荐位里的另一个视频，下错东西。
        if (imageUrls(obj).isNotEmpty()) return true
        val video = obj["video"] as? JsonObject ?: return false
        // An aweme without any playable address is not useful to us.
        return video["play_addr"] is JsonObject ||
            video["play_addr_h264"] is JsonObject ||
            video["download_addr"] is JsonObject
    }

    private fun toVideos(aweme: JsonObject): List<DouyinVideo> {
        val images = imageUrls(aweme)
        if (images.isNotEmpty()) {
            return images.mapIndexed { index, image -> imageEntry(aweme, index, image) }
        }
        return listOf(toVideo(aweme))
    }

    /** 一张图文作品里的图片；字段名跨版本不稳，取值顺序见 [imageUrls]。 */
    private data class ImageRef(val url: String, val width: Int?, val height: Int?)

    /**
     * 图文作品的图片地址，按作品里的展示顺序去重。
     *
     * Douyin 的新旧载荷分别把地址放在 `images[].url_list`、`images[].display_image.url_list`
     * 与 `image_post_info.images[].display_image.url_list`，这里都试一遍；`images` 为空时
     * 才回退到 `image_post_info`，避免同一张图因为两处都有而出现两次。
     */
    private fun imageUrls(aweme: JsonObject): List<ImageRef> {
        val result = LinkedHashMap<String, ImageRef>()
        fun add(node: JsonObject?) {
            if (node == null) return
            val url = node.imageUrl() ?: return
            result.putIfAbsent(url, ImageRef(url, node.int("width"), node.int("height")))
        }
        val images = aweme["images"] as? JsonArray
        if (images != null && images.isNotEmpty()) {
            images.forEach { element ->
                val image = element as? JsonObject ?: return@forEach
                add(image)
                add(image["display_image"] as? JsonObject)
            }
            return result.values.toList()
        }
        // 直接按键取值：`obj()` 有类内与包级两份同名扩展，这里不让解析结果依赖重载选择。
        val postImages = (aweme["image_post_info"] as? JsonObject)?.get("images") as? JsonArray
        postImages?.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            add(item["display_image"] as? JsonObject)
            add(item["thumbnail"] as? JsonObject)
        }
        return result.values.toList()
    }

    private fun JsonObject.imageUrl(): String? {
        val list = (this["url_list"] ?: this["download_url_list"]) as? JsonArray ?: return null
        return list.asSequence()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun imageEntry(aweme: JsonObject, index: Int, image: ImageRef): DouyinVideo {
        val awemeId = aweme.string("aweme_id").orEmpty()
        val id = "${awemeId}_img${index + 1}"
        val author = aweme["author"] as? JsonObject
        val extension = MediaSignature.extensionFromUrl(image.url) ?: "jpg"
        val pageUrl = aweme.string("share_url")?.takeIf { it.isNotBlank() }
            ?: "https://www.douyin.com/note/$awemeId"
        val variant = VideoVariant(
            url = image.url,
            label = AppText.get(R.string.quality_original),
            width = image.width,
            height = image.height,
            qualityRank = 0,
            referer = VideoPlatform.DOUYIN.referer,
            kind = MediaKind.IMAGE,
            media = MediaDownload(
                type = MediaSourceType.DIRECT,
                pageUrl = pageUrl,
                mediaId = id,
                formatId = extension,
                extension = extension,
                extractor = "image",
            ),
        )
        return DouyinVideo(
            awemeId = id,
            title = aweme.string("desc").orEmpty().ifBlank { AppText.get(R.string.fallback_title_douyin_image) },
            coverUrl = image.url,
            author = DouyinAuthor(
                uid = author?.string("uid"),
                secUid = author?.string("sec_uid"),
                nickname = author?.string("nickname").orEmpty(),
                avatarUrl = author?.obj("avatar_thumb")?.firstUrl()
                    ?: author?.obj("avatar_medium")?.firstUrl(),
                platform = VideoPlatform.DOUYIN,
            ),
            variants = listOf(variant),
            shareUrl = pageUrl,
            platform = VideoPlatform.DOUYIN,
            mediaLabel = AppText.get(R.string.media_label_image, index + 1),
        )
    }

    private fun toVideo(aweme: JsonObject): DouyinVideo {
        val video = aweme["video"] as? JsonObject
        val author = aweme["author"] as? JsonObject

        return DouyinVideo(
            awemeId = aweme.string("aweme_id").orEmpty(),
            title = aweme.string("desc").orEmpty().ifBlank { AppText.get(R.string.fallback_title_douyin_video) },
            coverUrl = video?.obj("cover")?.firstUrl()
                ?: video?.obj("origin_cover")?.firstUrl()
                ?: video?.obj("dynamic_cover")?.firstUrl(),
            durationMs = aweme.long("duration") ?: video?.long("duration") ?: 0L,
            author = DouyinAuthor(
                uid = author?.string("uid"),
                secUid = author?.string("sec_uid"),
                nickname = author?.string("nickname").orEmpty(),
                avatarUrl = author?.obj("avatar_thumb")?.firstUrl()
                    ?: author?.obj("avatar_medium")?.firstUrl()
                    ?: author?.obj("avatar_larger")?.firstUrl(),
                signature = author?.string("signature"),
            ),
            variants = buildVariants(video),
            shareUrl = aweme.string("share_url")
                ?: "https://www.iesdouyin.com/share/video/${aweme.string("aweme_id").orEmpty()}/",
        )
    }

    private fun buildVariants(video: JsonObject?): List<VideoVariant> {
        if (video == null) return emptyList()

        val baseRatio = video.string("ratio")
        val candidates = mutableListOf<VideoVariant>()

        // 1. Explicit per-bitrate renditions — the authoritative resolution list.
        (video["bit_rate"] as? JsonArray)?.forEach { element ->
            val entry = element as? JsonObject ?: return@forEach
            val addr = entry.obj("play_addr") ?: return@forEach
            val url = addr.firstUrl() ?: return@forEach
            val width = addr.int("width") ?: entry.int("width")
            val height = addr.int("height") ?: entry.int("height")
            val bitrate = entry.int("bit_rate")
            val label = resolutionLabel(width, height, baseRatio)

            candidates += VideoVariant(
                url = url,
                label = label,
                width = width,
                height = height,
                bitrate = bitrate,
                sizeBytes = addr.long("data_size"),
                isWatermarked = false,
                qualityRank = rankOf(width, height, bitrate),
            )
        }

        // 2. The default (usually highest quality, watermark-free) play address.
        video.obj("play_addr")?.let { addr ->
            addr.firstUrl()?.let { url ->
                val height = addr.int("height")
                candidates += VideoVariant(
                    url = url,
                    label = resolutionLabel(addr.int("width"), height, baseRatio),
                    width = addr.int("width"),
                    height = height,
                    bitrate = null,
                    sizeBytes = addr.long("data_size"),
                    isWatermarked = url.contains("watermark=1") || url.contains("playwm"),
                    qualityRank = rankOf(addr.int("width"), height, null),
                )
            }
        }

        // 3. H.264 transcode — best compatibility when the default is H.265.
        video.obj("play_addr_h264")?.let { addr ->
            addr.firstUrl()?.let { url ->
                val height = addr.int("height")
                candidates += VideoVariant(
                    url = url,
                    label = "${resolutionLabel(addr.int("width"), height, baseRatio)} H.264",
                    width = addr.int("width"),
                    height = height,
                    sizeBytes = addr.long("data_size"),
                    isWatermarked = false,
                    qualityRank = rankOf(addr.int("width"), height, null) - 1,
                )
            }
        }

        // 4. Watermarked download address — always available, but ranked last.
        video.obj("download_addr")?.let { addr ->
            addr.firstUrl()?.let { url ->
                val height = addr.int("height")
                candidates += VideoVariant(
                    url = url,
                    label = AppText.get(R.string.douyin_label_watermarked, resolutionLabel(addr.int("width"), height, baseRatio)),
                    width = addr.int("width"),
                    height = height,
                    sizeBytes = addr.long("data_size"),
                    isWatermarked = true,
                    qualityRank = rankOf(addr.int("width"), height, null) - 100,
                )
            }
        }

        // 5. Ratio-override renditions. Douyin's CDN honours the `ratio` query parameter, so
        //    when the API only advertises one bitrate we can still offer a resolution choice.
        val genuineLabels = candidates.map { it.label }.toSet()
        deriveRatioVariants(video, baseRatio)
            .filterNot { it.label in genuineLabels }
            .forEach { candidates += it }

        return candidates
    }

    /**
     * Builds extra renditions by rewriting `ratio=<x>` on the primary play URL.
     * Only the first URL of each list is used, which keeps the option list clean.
     */
    private fun deriveRatioVariants(video: JsonObject, baseRatio: String?): List<VideoVariant> {
        val baseUrl = video.obj("play_addr")?.firstUrl() ?: return emptyList()
        val ratioParamRegex = Regex("""([?&]ratio=)([^&]+)""")
        val match = ratioParamRegex.find(baseUrl) ?: return emptyList()

        return RATIO_LADDER
            .filter { it != baseRatio?.lowercase() }
            .mapNotNull { ratio ->
                val height = ratio.removeSuffix("p").toIntOrNull() ?: return@mapNotNull null
                val url = baseUrl.replaceRange(match.range, "${match.groupValues[1]}$ratio")
                VideoVariant(
                    url = url,
                    label = "${height}P",
                    height = height,
                    isWatermarked = false,
                    qualityRank = rankOf(height, height, null) - 10,
                )
            }
    }

    /**
     * Standard resolution naming uses the *short* side: a 1080x1920 portrait video is "1080P",
     * exactly as Douyin's own `ratio` field ("1080p") reports it. Using the raw height would
     * mislabel every portrait video as "1920P".
     */
    private fun shortSide(width: Int?, height: Int?): Int? = when {
        width != null && height != null -> minOf(width, height)
        height != null -> height
        width != null -> width
        else -> null
    }

    private fun resolutionLabel(width: Int?, height: Int?, fallbackRatio: String?): String = when {
        shortSide(width, height) != null -> "${shortSide(width, height)}P"
        !fallbackRatio.isNullOrBlank() -> fallbackRatio.uppercase()
        else -> AppText.get(R.string.quality_original)
    }

    /** Higher is better; the short side dominates and bitrate breaks ties. */
    private fun rankOf(width: Int?, height: Int?, bitrate: Int?): Int =
        (shortSide(width, height) ?: 0) * 1_000 + (bitrate ?: 0) / 1_000

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()?.toLongOrNull()

    /** Recovers the first non-blank entry from a Douyin `url_list`. */
    private fun JsonObject.firstUrl(): String? {
        val list = this["url_list"] as? JsonArray ?: return null
        return list.asSequence()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
            .map { deWatermark(it) }
            .firstOrNull { it.isNotBlank() }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

    private fun deWatermark(url: String): String = url.replace("/playwm/", "/play/")

    private companion object {
        const val MAX_NODES = 20_000

        /** Resolutions Douyin's CDN commonly accepts via the `ratio` parameter. */
        val RATIO_LADDER = listOf("1080p", "720p", "540p", "360p")
    }
}
