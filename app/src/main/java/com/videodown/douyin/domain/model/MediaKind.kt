package com.videodown.douyin.domain.model

import kotlinx.serialization.Serializable

/**
 * 一次下载产物的媒体类型。
 *
 * 图片与压缩包不再是「某个视频的变体」，而是和视频、音频并列的一等类型：下载校验、相册/下载目录
 * 落地、播放或查看的分流、通知文案都按它分派，不再靠扩展名或字段拼凑判断。
 *
 * 旧版本写下的 `download_history.json` 没有这个字段，反序列化后一律按 [VIDEO] 处理，因此升级
 * 不会改变既有记录的含义。
 */
@Serializable
enum class MediaKind(val defaultExtension: String) {
    VIDEO("mp4"),
    AUDIO("m4a"),
    IMAGE("jpg"),

    /** 一次「打包下载」产出的 ZIP；[DownloadRecord.archiveEntries] 记录里面有哪些图片。 */
    ARCHIVE("zip"),
}
