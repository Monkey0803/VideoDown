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
import com.videodown.douyin.data.gallery.GalleryAutoSave
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.VaultPolicy
import com.videodown.douyin.presentation.MainActivity

class DownloadCompletionNotifier(private val context: Context, private val preferences: AppPreferences) {
    fun notifyCompleted(record: DownloadRecord, gallery: GalleryAutoSave = GalleryAutoSave.Skipped) {
        if (!preferences.notifyOnCompletion || !areNotificationsEnabled(context)) return
        // A private record is never deep-linked: tapping its card opens the app, where the vault
        // gate still stands between the tap and the video.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                if (VaultPolicy.deepLinkable(record)) {
                    setAction(ACTION_OPEN_COMPLETED)
                    setData(Uri.Builder().scheme("shiying").authority("completed").appendPath(record.id).build())
                    putExtra(EXTRA_RECORD_ID, record.id)
                }
            }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.videodown.douyin.R.drawable.ic_shiying_notification_complete)
            .setContentTitle(context.getString(com.videodown.douyin.R.string.notification_download_complete))
            .setContentText(VaultPolicy.notificationTitle(record) ?: context.getString(com.videodown.douyin.R.string.vault_locked_title))
            .setStyle(NotificationCompat.BigTextStyle().bigText(completionSummaryText(context, completionSummary(record, gallery))))
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
                NotificationChannel(CHANNEL_ID, context.getString(com.videodown.douyin.R.string.notification_download_complete), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(com.videodown.douyin.R.string.notification_channel_description)
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

/**
 * The completion card's body.
 *
 * Written here rather than in `CompletionSummary`: the wording belongs to the notification, which
 * has a `Context`, and a stored key must not be shown as raw text.
 */
private fun completionSummaryText(context: android.content.Context, summary: CompletionSummary): String {
    val label = summary.qualityLabel.ifBlank {
        context.getString(if (summary.audio) com.videodown.douyin.R.string.notification_kind_audio else com.videodown.douyin.R.string.notification_kind_video)
    }
    val landing = when (summary.landing) {
        GalleryLanding.SKIPPED -> context.getString(com.videodown.douyin.R.string.notification_landing_open)
        GalleryLanding.SAVED -> context.getString(
            if (summary.audio) com.videodown.douyin.R.string.notification_landing_music
            else com.videodown.douyin.R.string.notification_landing_gallery,
        )
        GalleryLanding.FAILED -> context.getString(
            com.videodown.douyin.R.string.notification_landing_failed,
            context.getString(galleryReasonRes(summary.reasonKey)),
        )
    }
    val title = summary.title ?: context.getString(com.videodown.douyin.R.string.vault_locked_title)
    return "$title\n$label · $landing"
}

/** The wording for a stored gallery reason key, including the private-vault case. */
private fun galleryReasonRes(reasonKey: String?): Int {
    com.videodown.douyin.data.gallery.galleryFailureOf(reasonKey)?.let { return com.videodown.douyin.core.galleryFailureRes(it) }
    if (reasonKey == com.videodown.douyin.domain.model.VaultPolicy.VAULT_GALLERY_KEY) return com.videodown.douyin.R.string.notification_vault_no_gallery
    return com.videodown.douyin.R.string.gallery_write_failed
}
