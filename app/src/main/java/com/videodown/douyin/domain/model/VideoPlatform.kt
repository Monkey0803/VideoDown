package com.videodown.douyin.domain.model

import kotlinx.serialization.Serializable

/**
 * A source platform.
 *
 * The display name lives in resources (`presentation/Labels.kt`); only values with behaviour —
 * the referer a request must carry — are kept here.
 */
@Serializable
enum class VideoPlatform(val referer: String) {
    DOUYIN("https://www.douyin.com/"),
    WEIBO("https://weibo.com/"),
    KUAISHOU("https://www.kuaishou.com/"),
    X("https://x.com/"),
    GENERIC(""),
}
