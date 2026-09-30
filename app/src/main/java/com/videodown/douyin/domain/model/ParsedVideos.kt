package com.videodown.douyin.domain.model

/** Distinct media items in a post; each item owns its own resolution variants. */
data class ParsedVideos(val videos: List<DouyinVideo>, val selectedIndex: Int = 0) {
    init { require(videos.isNotEmpty() && selectedIndex in videos.indices) }
    val selectedVideo: DouyinVideo get() = videos[selectedIndex]
}
