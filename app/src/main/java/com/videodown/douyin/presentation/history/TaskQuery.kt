package com.videodown.douyin.presentation.history

import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus

enum class TaskSort(val label: String) {
    STATUS("按状态"),
    RECENT("最新任务"),
    OLDEST("最早任务"),
    LARGEST("文件最大"),
}

/** Search text and ordering for the task list. */
data class TaskQuery(val text: String = "", val sort: TaskSort = TaskSort.STATUS)

/**
 * Filters and orders the task list.
 *
 * [TaskSort.STATUS] keeps the long-standing default: whatever is transferring comes first in queue
 * order, then everything else newest-first — that ordering is what makes a running queue readable.
 * The other choices let the user override it when hunting for one task in a long history.
 */
object TaskListOrder {
    fun apply(
        records: List<DownloadRecord>,
        text: String,
        sort: TaskSort,
        statusOf: (DownloadRecord) -> DownloadStatus,
        queuePosition: (String) -> Int?,
    ): List<DownloadRecord> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val matching = if (words.isEmpty()) records else records.filter { record ->
            words.all { word ->
                record.title.contains(word, ignoreCase = true) ||
                    record.authorNickname.contains(word, ignoreCase = true)
            }
        }
        return when (sort) {
            TaskSort.STATUS -> matching.sortedWith(
                compareBy<DownloadRecord> {
                    when (statusOf(it)) {
                        DownloadStatus.RUNNING -> 0
                        DownloadStatus.PENDING -> 1
                        else -> 2
                    }
                }.thenBy { queuePosition(it.id) ?: Int.MAX_VALUE }.thenByDescending { it.createdAt },
            )
            TaskSort.RECENT -> matching.sortedWith(compareByDescending<DownloadRecord> { it.updatedAt }.thenBy { it.id })
            TaskSort.OLDEST -> matching.sortedWith(compareBy<DownloadRecord> { it.updatedAt }.thenBy { it.id })
            TaskSort.LARGEST -> matching.sortedWith(
                compareByDescending<DownloadRecord> { maxOf(it.totalBytes, it.downloadedBytes) }
                    .thenByDescending { it.updatedAt }.thenBy { it.id },
            )
        }
    }
}
