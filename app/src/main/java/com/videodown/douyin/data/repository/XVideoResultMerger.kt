package com.videodown.douyin.data.repository

import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.data.remote.XVideoLink

/** Combine media by X's media ID, never by title or position in a playlist. */
internal object XVideoResultMerger {
    fun merge(url: String, native: ParsedVideos?, extracted: ParsedVideos?): ParsedVideos? {
        if (native == null && extracted == null) return null
        if (extracted == null) return native
        val post = XVideoLink.parse(url) ?: return native ?: extracted
        val videos = native?.videos?.toMutableList() ?: mutableListOf()
        for (video in extracted.videos) {
            val matches = videos.indices.filter { videos[it].awemeId.substringAfterLast('_') == video.awemeId }
            if (matches.size == 1) {
                val position = matches.single()
                val original = videos[position]
                val knownUrls = original.variants.map(VideoVariant::url).toSet()
                videos[position] = original.copy(variants = original.variants + video.variants.filter { it.url !in knownUrls },
                    audioVariants = (original.audioVariants + video.audioVariants).distinctBy { it.media?.formatId })
            } else if (matches.isEmpty()) {
                videos.add(video.copy(awemeId = "${post.postId}_${video.awemeId}",
                    platform = VideoPlatform.X, shareUrl = url,
                    mediaLabel = "视频 ${videos.size + 1}"))
            }
            // A duplicate/ambiguous media ID is not a safe basis for another candidate.
        }
        if (videos.isEmpty()) return null
        val selected = native?.selectedVideo?.awemeId?.let { id -> videos.indexOfFirst { it.awemeId == id } }
            ?.takeIf { it >= 0 }
            ?: extracted.selectedVideo.awemeId.let { id ->
                videos.indexOfFirst { it.awemeId == "${post.postId}_$id" }.takeIf { it >= 0 }
            } ?: 0
        return ParsedVideos(videos, selected)
    }
}
