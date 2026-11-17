package com.videodown.douyin.data.gallery

import kotlinx.serialization.Serializable
import java.io.File

/**
 * 「这条记录已经导出到哪里」的回执。
 *
 * 与下载记录分开保存：导出目标是系统相册 / 公共下载目录里的条目，记录本身随时可能被删除或重新
 * 下载，回执只回答一个问题——同一个源文件是不是已经写过去了，写到了哪个 URI。
 *
 * 判重不靠文件名：记录 id 区分不同任务，[fingerprint] 区分同一任务后来换了内容，[targets]
 * 里的 URI 才是真正被写入的目标。用户从系统相册或下载目录删掉目标后，
 * [ExportReceipts.isValid] 会因为目标不存在而失效，下一次导出重新写入。
 */
@Serializable
data class ExportReceipt(
    val recordId: String,
    /** 源文件指纹：每个文件的「名称 + 大小 + 修改时间」，按传入顺序拼接。 */
    val fingerprint: String,
    /** 已写入的目标：MediaStore `content://` URI，或 Android 9 及以下公共目录里的文件路径。 */
    val targets: List<String>,
    /** 写入时间，用于清理长期不用的回执。 */
    val savedAt: Long,
)

/** 回执的判定与指纹计算；纯 Kotlin，不碰时钟、ContentResolver 或磁盘以外的设备能力。 */
object ExportReceipts {
    /**
     * 这条回执还能不能代表「已经保存过」。
     *
     * 四个条件缺一不可：同一条记录、同一份源文件内容、至少一个目标、以及每个目标现在仍然存在。
     * 目标全部还在但有一个被删掉时整条回执作废（Live Photo 的静帧/MOV 是一对，不能只留一半）。
     */
    fun isValid(
        receipt: ExportReceipt?,
        recordId: String,
        fingerprint: String,
        targetExists: (String) -> Boolean,
    ): Boolean = receipt != null &&
        receipt.recordId == recordId &&
        receipt.targets.isNotEmpty() &&
        receipt.fingerprint == fingerprint &&
        receipt.targets.all(targetExists)

    /**
     * 只读元数据的指纹。
     *
     * 不做全文件哈希：几 GB 的视频不该因为一次「保存到相册」就在前台读一遍；名称 + 大小 +
     * 修改时间已经能区分「同名不同内容」的替换。
     */
    fun fingerprint(files: List<File>): String = files.joinToString("|") { file ->
        "${file.name}:${file.length()}:${file.lastModified()}"
    }
}
