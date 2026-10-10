package com.videodown.douyin.domain.model

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
/** Keeps local extraction distinct from remote audio download and other source renditions. */
fun DownloadRecord.audioExtractionSelection(): Pair<DouyinVideo, VideoVariant> {
    require(status == DownloadStatus.COMPLETED && !isAudio) { AppText.get(R.string.audio_extract_only_saved) }
    val variant = VideoVariant(sourcePageUrl.ifBlank { sourceUrl }, AppText.get(R.string.media_label_local_audio_m4a), sizeBytes = totalBytes.takeIf { it > 0 },
        media = MediaDownload(type = MediaSourceType.SEPARATE, mediaId = id, formatId = "local-audio",
            extension = "m4a", extractor = "local", audioOnly = true, localRecordId = id))
    return DouyinVideo(awemeId, title, coverUrl = coverUrl,
        author = DouyinAuthor(secUid = authorSecUid, nickname = authorNickname, avatarUrl = authorAvatarUrl, platform = platform),
        shareUrl = sourcePageUrl, platform = platform, audioVariants = listOf(variant)) to variant
}
