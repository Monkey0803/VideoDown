package com.videodown.douyin.core

import com.videodown.douyin.domain.model.DownloadStatus

/** 尚未被用户查看的下载终态；只保存任务 id，不复制标题、网址或文件路径。 */
data class DownloadResultInbox(
    val failedIds: Set<String> = emptySet(),
    val pausedIds: Set<String> = emptySet(),
    val completedIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = failedIds.isEmpty() && pausedIds.isEmpty() && completedIds.isEmpty()
    val count: Int get() = failedIds.size + pausedIds.size + completedIds.size

    /** 只记录已经观察到的状态变化；previous 为 null 的磁盘历史不会变成新结果。 */
    fun recordTransition(id: String, previous: DownloadStatus?, current: DownloadStatus): DownloadResultInbox {
        if (previous == null || previous == current) return this
        val failed = failedIds - id
        val paused = pausedIds - id
        val completed = completedIds - id
        return when (current) {
            DownloadStatus.FAILED -> copy(failedIds = failed + id, pausedIds = paused, completedIds = completed)
            DownloadStatus.PAUSED -> copy(failedIds = failed, pausedIds = paused + id, completedIds = completed)
            DownloadStatus.COMPLETED -> copy(failedIds = failed, pausedIds = paused, completedIds = completed + id)
            DownloadStatus.PENDING, DownloadStatus.RUNNING -> copy(
                failedIds = failed,
                pausedIds = paused,
                completedIds = completed,
            )
        }
    }

    /** 删除不存在或已离开对应终态的条目，避免恢复旧 id 后显示失真的结果。 */
    fun reconciled(statuses: Map<String, DownloadStatus>): DownloadResultInbox = copy(
        failedIds = failedIds.filterTo(mutableSetOf()) { statuses[it] == DownloadStatus.FAILED },
        pausedIds = pausedIds.filterTo(mutableSetOf()) { statuses[it] == DownloadStatus.PAUSED },
        completedIds = completedIds.filterTo(mutableSetOf()) { statuses[it] == DownloadStatus.COMPLETED },
    )

    fun clearCompleted(): DownloadResultInbox = copy(completedIds = emptySet())
}
