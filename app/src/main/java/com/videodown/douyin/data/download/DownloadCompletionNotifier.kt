package com.videodown.douyin.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.videodown.douyin.core.AppPreferences
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.presentation.MainActivity

class DownloadCompletionNotifier(private val context: Context, private val preferences: AppPreferences) {
    fun notifyCompleted(record: DownloadRecord) {
        if (!preferences.notifyOnCompletion || !areNotificationsEnabled(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_COMPLETED)
            .setData(Uri.Builder().scheme("shiying").authority("completed").appendPath(record.id).build())
            .putExtra(EXTRA_RECORD_ID, record.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.videodown.douyin.R.drawable.ic_shiying_notification_complete)
            .setContentTitle("下载完成")
            .setContentText(record.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${record.title}\n${record.resolutionLabel} · 点击查看"))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        // A tag per record avoids collisions with the foreground progress notification.
        try {
            NotificationManagerCompat.from(context).notify(record.id, 1002, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and notify; the download still succeeds.
        }
    }

    companion object {
        const val CHANNEL_ID = "download_completions"
        const val ACTION_OPEN_LIBRARY = "com.videodown.douyin.action.OPEN_LIBRARY"
        const val ACTION_OPEN_COMPLETED = "com.videodown.douyin.action.OPEN_COMPLETED"
        const val EXTRA_RECORD_ID = "record_id"

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "下载完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "视频下载完成后的提醒"
                },
            )
        }

        fun areNotificationsEnabled(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
