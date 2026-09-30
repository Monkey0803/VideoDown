package com.videodown.douyin.data.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.videodown.douyin.R
import com.videodown.douyin.VideoDownApp
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps downloads alive while the app is in the background and mirrors their progress into a
 * notification.
 *
 * The transfer itself lives in [DownloadEngine]'s application scope; this service exists so that
 * Android keeps the process alive (and so the user can see and cancel the transfer).
 */
class DownloadService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private var progressJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(buildNotification(emptyMap()))
        observeProgress()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> serviceScope.launch { ServiceLocator.downloader.pauseAll() }
            ACTION_RESUME_ALL -> serviceScope.launch { ServiceLocator.downloader.resumeAll() }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun observeProgress() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            ServiceLocator.downloader.progress.collectLatest { map ->
                val active = map.values.filter { it.isActive }
                if (active.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                    return@collectLatest
                }
                notify(buildNotification(map))
            }
        }
    }

    private fun buildNotification(progress: Map<String, DownloadProgress>): Notification {
        val active = progress.values.filter { it.isActive }
        val count = active.size
        val totalBytes = active.sumOf { if (it.totalBytes > 0) it.totalBytes else 0L }
        val doneBytes = active.sumOf { it.downloadedBytes }
        val percent = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt() else 0
        val speed = active.sumOf { it.bytesPerSecond }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(packageName, "com.videodown.douyin.presentation.MainActivity"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, VideoDownApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(com.videodown.douyin.R.drawable.ic_shiying_notification_download)
            .setContentTitle(if (active.any { it.phase == com.videodown.douyin.domain.model.TransferPhase.EXTRACTING }) "正在提取音频" else if (active.any { it.phase == com.videodown.douyin.domain.model.TransferPhase.MERGING }) "正在合并音视频" else if (count > 1) "正在下载 $count 个视频" else "正在下载视频")
            .setContentText(
                buildString {
                    append(formatBytes(doneBytes))
                    if (totalBytes > 0) append(" / ${formatBytes(totalBytes)}")
                    if (speed > 0) append("  ·  ${formatBytes(speed)}/s")
                },
            )
            .setProgress(100, percent, totalBytes <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                "全部暂停",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        return builder.build()
    }

    private fun notify(notification: Notification) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        progressJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.videodown.douyin.action.START_DOWNLOAD"
        const val ACTION_PAUSE_ALL = "com.videodown.douyin.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.videodown.douyin.action.RESUME_ALL"
        const val ACTION_STOP = "com.videodown.douyin.action.STOP"

        /** Starts (or nudges) the foreground service. Safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
