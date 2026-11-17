package com.videodown.douyin.domain.model

/**
 * 已存文件的健康状态。
 *
 * 只描述事实，不携带文案：界面按状态给出一句原因和一个主操作。
 */
enum class StoredFileHealth {
    /** 完成时没有留下大小/修改时间快照（旧记录），只能确认文件还在。 */
    UNKNOWN,

    /** 文件在，且与完成时的快照一致。 */
    HEALTHY,

    /** 文件已经不在了；用户可能在文件管理器或系统清理里删掉了它。 */
    MISSING,

    /** 文件还在，但大小或修改时间与完成时不同：可能是被替换或写了一半。 */
    CHANGED,

    /** 结构校验失败：容器打不开、轨道缺失或被截断。 */
    CORRUPT,
}

/**
 * I4：已存文件的健康判定策略。
 *
 * 纯 Kotlin：文件系统与媒体解码都在数据层，这里只回答「快照与现状怎么比、什么时候要深入检查」。
 * 设计原则是「宁可提示，不要误删」：快照缺失只算 UNKNOWN，绝不因为旧记录缺字段就报告损坏。
 */
object FileHealthPolicy {
    /**
     * 低成本判定：只比较「存在与否 + 大小 + 修改时间」。
     *
     * [expectedSize]/[expectedModifiedAt] 来自记录上完成时的快照；[actualSize]/[actualModifiedAt]
     * 来自当前磁盘。任一侧缺失都不升级为 CHANGED——旧记录没有快照，不能拿它当损坏证据。
     */
    fun cheapHealth(
        exists: Boolean,
        expectedSize: Long?,
        expectedModifiedAt: Long?,
        actualSize: Long?,
        actualModifiedAt: Long?,
    ): StoredFileHealth {
        if (!exists) return StoredFileHealth.MISSING
        if (expectedSize == null && expectedModifiedAt == null) return StoredFileHealth.UNKNOWN
        if (actualSize == null) return StoredFileHealth.UNKNOWN
        if (expectedSize != null && expectedSize != actualSize) return StoredFileHealth.CHANGED
        if (expectedModifiedAt != null && actualModifiedAt != null && expectedModifiedAt != actualModifiedAt) {
            return StoredFileHealth.CHANGED
        }
        return StoredFileHealth.HEALTHY
    }

    /** 深入校验的结论：结构校验通过时沿用低成本结果，失败一律是 CORRUPT。 */
    fun afterStructureCheck(cheap: StoredFileHealth, structurePassed: Boolean): StoredFileHealth = when {
        !structurePassed -> StoredFileHealth.CORRUPT
        else -> cheap
    }
}
