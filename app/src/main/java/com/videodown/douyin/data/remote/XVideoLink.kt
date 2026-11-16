package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.VideoPlatform
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class XVideoLink(
    val postId: String,
    /** 路径里的媒体序号（`/video/2`、`/photo/3` 的 2/3）；没有指定时为 `null`。 */
    val mediaIndex: Int? = null,
    /** 序号属于视频还是图片；`/photo/N` 选的是第 N 张图，不是第 N 个媒体。 */
    val mediaType: MediaType = MediaType.VIDEO,
) {
    enum class MediaType { VIDEO, PHOTO }

    companion object {
        fun parse(raw: String): XVideoLink? {
            val url = raw.toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
                VideoLinkExtractor.platformForHost(url.host) != VideoPlatform.X || url.host == "t.co") return null
            val match = Regex("^/(?:[A-Za-z0-9_]+/status|i/web/status|statuses)/(\\d{5,22})(?:/(video|photo)/([1-9]\\d?))?/?$")
                .matchEntire(url.encodedPath) ?: return null
            return XVideoLink(
                postId = match.groupValues[1],
                mediaIndex = match.groupValues[3].toIntOrNull(),
                mediaType = if (match.groupValues[2] == "photo") MediaType.PHOTO else MediaType.VIDEO,
            )
        }
    }
}
