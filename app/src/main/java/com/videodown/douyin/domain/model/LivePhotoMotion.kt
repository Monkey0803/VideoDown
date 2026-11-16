package com.videodown.douyin.domain.model

import kotlinx.serialization.Serializable

/**
 * Live Photo 的动态伴随资源。
 *
 * 静帧仍由 [VideoVariant] / [DownloadRecord.filePath] 表示；这个对象只保存与它成对的 MOV。
 * 远端字段在解析时写入，本地字段在创建任务和完成下载时补齐。所有新增字段都有默认值，旧历史
 * 可以继续反序列化。
 */
@Serializable
data class LivePhotoMotion(
    val sourceUrl: String,
    val referer: String? = null,
    val extension: String = "mov",
    val expectedBytes: Long? = null,
    val fileName: String = "",
    val filePath: String? = null,
)
