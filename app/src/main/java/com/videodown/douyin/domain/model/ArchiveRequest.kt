package com.videodown.douyin.domain.model

/**
 * 一次「打包下载」请求：把同一帖子的多张图片打成一个 ZIP。
 *
 * 做成**一个任务**而不是「先下载 N 个任务再手动打包」，是因为用户点的是一次「打包下载」：
 * 一个进度、一个产物、一个失败可重试的对象。任务复用既有队列、断点续传、限速与空间预留。
 */
data class ArchiveRequest(
    val title: String,
    val postId: String,
    val platform: VideoPlatform,
    val postPageUrl: String,
    val entries: List<ArchiveEntry>,
    val coverUrl: String? = null,
    val authorNickname: String = "",
    val authorAvatarUrl: String? = null,
    val authorSecUid: String? = null,
) {
    init {
        require(entries.isNotEmpty()) { "archive needs at least one entry" }
    }
}

/** 把一条图片条目转成归档清单里的一项；没有可下载变体时返回 `null`。 */
fun DouyinVideo.archiveEntry(): ArchiveEntry? {
    val variant = sortedVariants.firstOrNull() ?: return null
    return ArchiveEntry(
        url = variant.url,
        extension = variant.extensionOrDefault,
        referer = variant.referer,
        sizeBytes = variant.sizeBytes,
        livePhotoMotion = variant.livePhotoMotion,
    )
}
