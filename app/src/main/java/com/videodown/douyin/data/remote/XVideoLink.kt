package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.VideoPlatform
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class XVideoLink(val postId: String, val mediaIndex: Int? = null) {
    companion object {
        fun parse(raw: String): XVideoLink? {
            val url = raw.toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
                VideoLinkExtractor.platformForHost(url.host) != VideoPlatform.X || url.host == "t.co") return null
            val match = Regex("^/(?:[A-Za-z0-9_]+/status|i/web/status|statuses)/(\\d{5,22})(?:/(?:video|photo)/([1-9]\\d?))?/?$")
                .matchEntire(url.encodedPath) ?: return null
            return XVideoLink(match.groupValues[1], match.groupValues[2].toIntOrNull())
        }
    }
}
