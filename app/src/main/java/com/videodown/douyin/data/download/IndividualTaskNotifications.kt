package com.videodown.douyin.data.download

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * 每任务通知卡的身份与清理。
 *
 * 身份是「固定 id + 记录 id 当 tag」：这样服务之外的路径（设置页把开关关掉、任务被删除）也能只凭
 * 记录 id 撤掉某张卡，不必知道服务内部的通知槽位——槽位现在只用来给 PendingIntent 造互不相同的
 * request code，不再当通知身份。
 */
object IndividualTaskNotifications {

    /** 所有独立任务卡共用的通知 id；具体是哪一条记录由 tag 区分。 */
    const val ID = 1100

    /** 撤掉本应用当前所有独立任务卡：服务启动时清上次的遗留、开关关掉时立即收回，都用它。 */
    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.activeNotifications }.getOrDefault(emptyList())
            .filter { it.id == ID }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /** 撤掉某条记录的卡（记录已删除、或该任务已完成/失败）。 */
    fun cancel(context: Context, recordId: String) {
        NotificationManagerCompat.from(context).cancel(recordId, ID)
    }
}
