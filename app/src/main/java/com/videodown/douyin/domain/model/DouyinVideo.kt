package com.videodown.douyin.domain.model

/**
 * Author (Douyin account) attached to a video.
 *
 * [secUid] is the stable, shareable account id used to build the profile deep link;
 * [uid] is the numeric id that the public web API exposes.
 */
data class DouyinAuthor(
    val uid: String? = null,
    val secUid: String? = null,
    val nickname: String = "",
    val avatarUrl: String? = null,
    val signature: String? = null,
    val platform: VideoPlatform = VideoPlatform.DOUYIN,
) {
    /**
     * HTTPS profile link that works in a browser and is captured by the Douyin app's
     * App Links when it is installed.
     */
    val profileUrl: String?
        get() = when (platform) {
            VideoPlatform.GENERIC -> null
            VideoPlatform.DOUYIN -> secUid?.takeIf(String::isNotBlank)?.let { "https://www.douyin.com/user/$it" }
            VideoPlatform.WEIBO -> uid?.takeIf(String::isNotBlank)?.let { "https://weibo.com/u/$it" }
            VideoPlatform.KUAISHOU -> (secUid ?: uid)?.takeIf(String::isNotBlank)?.let { "https://www.kuaishou.com/profile/$it" }
            VideoPlatform.X -> secUid?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,50}")) }?.let { "https://x.com/$it" }
        }

    /**
     * Native deep link that opens the profile directly inside the Douyin app.
     * Falls back to [profileUrl] when no sec_uid is known.
     */
    val profileDeepLink: String?
        get() = if (platform != VideoPlatform.DOUYIN) profileUrl else secUid?.takeIf { it.isNotBlank() }
            ?.let { "snssdk1128://user/profile/$it" }
            ?: profileUrl
}

/**
 * One selectable rendition of a video.
 *
 * Douyin exposes several playable URLs per video (different `ratio` values and
 * watermarked / non-watermarked hosts). Each becomes one [VideoVariant] so the user
 * can pick a resolution before downloading.
 */
data class VideoVariant(
    val url: String,
    val label: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val sizeBytes: Long? = null,
    val isWatermarked: Boolean = false,
    /** Higher is better; used for sorting and for choosing a default. */
    val qualityRank: Int = 0,
    /** CDN requests and size probes must use the source platform's Referer. */
    val referer: String? = null,
    val media: MediaDownload? = null,
    /**
     * 这个变体最终落地的是什么媒体。
     *
     * 放在最后并带默认值：既有构造点全部是位置参数 + 命名参数混用，插在中间会静默移位。
     */
    val kind: MediaKind = MediaKind.VIDEO,
) {
    /** 下载时的扩展名：来源给出的真实扩展名优先，其次按类型兜底。 */
    val extensionOrDefault: String
        get() = media?.extension?.takeIf { it.isNotBlank() } ?: kind.defaultExtension

    /** 记录与任务分流用的类型：音频以 `audioOnly` 为准，其余看变体类型。 */
    val mediaKind: MediaKind
        get() = when {
            media?.audioOnly == true -> MediaKind.AUDIO
            kind == MediaKind.IMAGE -> MediaKind.IMAGE
            else -> MediaKind.VIDEO
        }

    val resolutionText: String
        get() = when {
            width != null && height != null -> "${width}x${height}"
            height != null -> "${height}P"
            else -> label
        }
}

/**
 * Parsed video metadata. The legacy type/id names remain compatible with existing callers.
 */
data class DouyinVideo(
    val awemeId: String,
    val title: String,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    val author: DouyinAuthor = DouyinAuthor(),
    val variants: List<VideoVariant> = emptyList(),
    val shareUrl: String = "",
    val platform: VideoPlatform = VideoPlatform.DOUYIN,
    val mediaLabel: String? = null,
    val audioVariants: List<VideoVariant> = emptyList(),
    /** Languages the source offers as real subtitle tracks; empty when it offers none. */
    val subtitleLanguages: List<String> = emptyList(),
    /** Languages available only as machine captions; used when no real subtitle exists. */
    val autoCaptionLanguages: List<String> = emptyList(),
) {
    /**
     * Variants ordered from best to worst, collapsed to one entry per resolution label.
     *
     * Douyin advertises the same rendition through `bit_rate`, `play_addr` and `play_addr_h264`
     * with different URLs but identical dimensions, so de-duplicating by label (rather than by
     * URL) is what keeps the resolution picker free of duplicate chips. Distinct labels such as
     * `1080P H.264` and `1080P 带水印` intentionally survive.
     */
    val sortedVariants: List<VideoVariant>
        get() = variants
            .sortedWith(
                compareByDescending<VideoVariant> { it.qualityRank }
                    .thenByDescending { it.sizeBytes ?: 0L },
            )
            .distinctBy { it.label.trim().lowercase() }

    val defaultVariant: VideoVariant?
        get() = sortedVariants.firstOrNull()

    /**
     * 这个条目描述的是图片（图文/图集作品里的某一张）。
     *
     * 结果页据此隐藏画质选择与字幕开关、把「在平台中预览」换成「查看原帖」，已存页据此把点击
     * 分流到看图器而不是播放器。
     */
    val isImagePost: Boolean
        get() = variants.isNotEmpty() && variants.all { it.kind == MediaKind.IMAGE }

    /** Native deep link that opens this exact video in the Douyin app. */
    val videoDeepLink: String
        get() = if (platform == VideoPlatform.DOUYIN) "snssdk1128://aweme/detail/$awemeId" else videoWebUrl

    val videoWebUrl: String
        get() = shareUrl.takeIf(String::isNotBlank) ?: when (platform) {
            VideoPlatform.GENERIC -> shareUrl
            VideoPlatform.DOUYIN -> "https://www.douyin.com/video/$awemeId"
            VideoPlatform.WEIBO -> "https://m.weibo.cn/status/$awemeId"
            VideoPlatform.KUAISHOU -> "https://www.kuaishou.com/short-video/$awemeId"
            VideoPlatform.X -> "https://x.com/i/status/${awemeId.substringBefore('_')}"
        }
}
