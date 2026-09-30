package com.videodown.douyin.domain.model

/** Keeps local extraction distinct from remote audio download and other source renditions. */
fun DownloadRecord.audioExtractionSelection(): Pair<DouyinVideo, VideoVariant> {
    require(status == DownloadStatus.COMPLETED && !isAudio) { "只可从已存视频提取音频" }
    val variant = VideoVariant(sourcePageUrl.ifBlank { sourceUrl }, "本地提取音频 · M4A", sizeBytes = totalBytes.takeIf { it > 0 },
        media = MediaDownload(type = MediaSourceType.SEPARATE, mediaId = id, formatId = "local-audio",
            extension = "m4a", extractor = "local", audioOnly = true, localRecordId = id))
    return DouyinVideo(awemeId, title, coverUrl = coverUrl,
        author = DouyinAuthor(secUid = authorSecUid, nickname = authorNickname, avatarUrl = authorAvatarUrl, platform = platform),
        shareUrl = sourcePageUrl, platform = platform, audioVariants = listOf(variant)) to variant
}
