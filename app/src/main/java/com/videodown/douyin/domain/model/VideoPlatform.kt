package com.videodown.douyin.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class VideoPlatform(val label: String, val referer: String) {
    DOUYIN("抖音", "https://www.douyin.com/"),
    WEIBO("微博", "https://weibo.com/"),
    KUAISHOU("快手", "https://www.kuaishou.com/"),
    X("X", "https://x.com/"),
    GENERIC("网页", ""),
}
