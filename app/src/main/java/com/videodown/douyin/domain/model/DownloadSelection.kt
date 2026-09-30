package com.videodown.douyin.domain.model

/** Identity excludes expiring CDN URLs, but retains the actual selected engine format. */
fun DownloadRecord.matchesSelection(video: DouyinVideo, variant: VideoVariant): Boolean {
    if (platform != video.platform || awemeId != video.awemeId) return false
    if (!resolutionLabel.trim().equals(variant.label.trim(), ignoreCase = true)) return false
    val previous = media
    val next = variant.media
    return if (previous != null && next != null) previous.sameMedia(next)
        else previous == null && next == null
}

fun DouyinVideo.selectionKey(): String = "${platform.name}:$awemeId"
