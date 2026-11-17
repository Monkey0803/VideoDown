package com.videodown.douyin.data.download

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.FileHealthPolicy
import com.videodown.douyin.domain.model.StoredFileHealth
import java.io.File

/**
 * I4：已存文件的健康检查。
 *
 * 两层成本：
 *  - **低成本**（每次都可以做）：路径是否存在 + 与完成时快照比较大小与修改时间；
 *  - **结构校验**（打开失败或用户主动检查时）：复用下载完成时的媒体校验（容器、轨道、尾部）。
 *
 * 大文件不会被无条件读一遍：低成本检查只做 `stat`，结构校验也只读文件头/尾的样本，
 * 不做全量哈希或逐帧解码。
 */
class LibraryFileInspector(
    private val structureCheck: (File, DownloadRecord) -> Unit = { file, record ->
        validateDownloadedFile(file, record)
    },
) {
    /** 只做低成本检查；[record.filePath] 为空或文件不存在时返回 MISSING。 */
    fun cheapCheck(record: DownloadRecord): StoredFileHealth {
        val file = record.filePath?.let(::File)
        val exists = file?.isFile == true
        return FileHealthPolicy.cheapHealth(
            exists = exists,
            expectedSize = record.fileSizeBytes,
            expectedModifiedAt = record.fileModifiedAt,
            actualSize = if (exists) file!!.length() else null,
            actualModifiedAt = if (exists) file!!.lastModified() else null,
        )
    }

    /**
     * 深入检查：先做低成本比对，再执行结构校验。
     *
     * 记录已经处于 MISSING 时直接返回，不去打开一个不存在的文件；结构校验本身失败时报告
     * CORRUPT，不区分「容器打不开」与「截断」，因为用户要做的都是重新解析/重新下载。
     */
    fun inspect(record: DownloadRecord): StoredFileHealth {
        val cheap = cheapCheck(record)
        if (cheap == StoredFileHealth.MISSING) return cheap
        val file = record.filePath?.let(::File) ?: return StoredFileHealth.MISSING
        val passed = runCatching { structureCheck(file, record) }.isSuccess
        return FileHealthPolicy.afterStructureCheck(cheap, passed)
    }
}
