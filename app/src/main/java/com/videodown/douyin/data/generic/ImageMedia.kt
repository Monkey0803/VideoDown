package com.videodown.douyin.data.generic

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.MediaSignature
import com.videodown.douyin.domain.model.MediaSourceType
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/**
 * 图片直链 / 网页候选 → 一个可下载条目。
 *
 * 直链解析与网页辅助共用这一段，保证两边产出的记录形状一致：一条 `variants`、稳定的
 * `mediaId`（否则同帖多图无法区分重复与重新解析）、以及从帖子页重新解析所需的 `pageUrl`。
 */
internal object ImageMedia {

    /** 网页候选的噪音阈值：图标、头像、追踪像素不值得列进候选列表。 */
    const val MIN_SIDE_PX = 100
    const val MIN_BYTES = 10L * 1024

    /** 扩展名优先取真实内容，其次 URL，最后兜底 jpg。 */
    fun extensionOf(url: String, contentType: String? = null): String =
        MediaSignature.extensionForContentType(contentType)
            ?: MediaSignature.extensionFromUrl(url)
            ?: "jpg"

    /**
     * 网页候选是否值得展示。
     *
     * 宽高已知时按尺寸过滤；只知道大小时按字节数过滤（仍可能漏掉小图，但不会把 1×1 追踪像素
     * 列出来）；两者都不知道时放行，由用户自己判断。
     */
    fun isNoise(width: Int?, height: Int?, sizeBytes: Long?): Boolean = when {
        width != null && height != null -> width < MIN_SIDE_PX || height < MIN_SIDE_PX
        sizeBytes != null && sizeBytes > 0L -> sizeBytes < MIN_BYTES
        else -> false
    }

    fun entry(
        url: String,
        pageUrl: String,
        extension: String,
        sizeBytes: Long? = null,
        width: Int? = null,
        height: Int? = null,
        referer: String? = null,
        userAgent: String? = null,
        browserSession: Boolean = false,
        platform: VideoPlatform = VideoPlatform.GENERIC,
        title: String? = null,
    ): DouyinVideo {
        val id = idOf(url)
        val variant = VideoVariant(
            url = url,
            label = AppText.get(R.string.quality_original),
            width = width,
            height = height,
            sizeBytes = sizeBytes,
            qualityRank = 0,
            // 图片 CDN 常有防盗链；来源页比平台首页更准。
            referer = referer ?: pageUrl,
            kind = MediaKind.IMAGE,
            media = MediaDownload(
                type = MediaSourceType.DIRECT,
                pageUrl = pageUrl,
                mediaId = id,
                formatId = extension,
                extension = extension,
                userAgent = userAgent,
                useBrowserSession = browserSession,
                extractor = "image",
            ),
        )
        return DouyinVideo(
            awemeId = id,
            title = title ?: titleFor(url),
            coverUrl = url,
            author = com.videodown.douyin.domain.model.DouyinAuthor(platform = platform),
            variants = listOf(variant),
            shareUrl = pageUrl,
            platform = platform,
        )
    }

    /** 与 `probeDirect` 的既有做法一致：URL 的 SHA-256 前 24 位就是稳定 id。 */
    private fun idOf(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private fun titleFor(url: String): String {
        val name = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
        return name ?: AppText.get(R.string.fallback_title_web_image)
    }
}
