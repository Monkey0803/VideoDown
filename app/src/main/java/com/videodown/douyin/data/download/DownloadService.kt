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
import com.videodown.douyin.core.formatSpeed
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    private var individualJob: Job? = null
    private var foregroundError: DownloadServiceException? = null
    private var foregroundReady = false
    private var expectedStop = false
    private var latestStartId = 0

    /** Tasks this service run has shown an individual notification for. */
    private val trackedIds = mutableSetOf<String>()

    /** Stable notification slot per task, so two tasks never share a pending intent. */
    private val notificationSlots = mutableMapOf<String, Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundCompat(buildNotification(ServiceLocator.downloader.progress.value))
            foregroundReady = true
        } catch (error: Exception) {
            // l10n-ignore: a developer log line, never shown to the user
        android.util.Log.w("DownloadService", "无法进入前台下载服务", error)  // l10n-ignore: 开发者日志行，从不展示给用户
            foregroundError = DownloadServiceException(com.videodown.douyin.core.ServiceFailure.FOREGROUND_START, error)
            requests.failPending(foregroundError!!)
            failTransfers(foregroundError!!.message.orEmpty())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        requests.acknowledge(intent?.getStringExtra(EXTRA_REQUEST), foregroundError)
        if (!foregroundReady) { stopSelf(); return START_NOT_STICKY }
        expectedStop = false
        if (progressJob == null) observeProgress()
        if (individualJob == null) observeIndividualNotifications()
        when (intent?.action) {
            ACTION_PAUSE_ALL -> serviceScope.launch { ServiceLocator.downloader.pauseAll() }
            ACTION_RESUME_ALL -> serviceScope.launch { ServiceLocator.downloader.resumeAll() }
            ACTION_PAUSE_ONE -> intent.getStringExtra(EXTRA_RECORD_ID)?.let { id ->
                serviceScope.launch { ServiceLocator.downloader.pause(id) }
            }
            ACTION_RESUME_ONE -> intent.getStringExtra(EXTRA_RECORD_ID)?.let { id ->
                serviceScope.launch { ServiceLocator.downloader.resume(id) }
            }
            ACTION_STOP -> {
                expectedStop = true
                failTransfers(com.videodown.douyin.core.ServiceFailure.BACKGROUND_STOPPED.storageKey)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // A restart without the original foreground request must not silently resurrect jobs.
        return START_NOT_STICKY
    }

    private fun observeProgress() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            ServiceLocator.downloader.progress.collectLatest { map ->
                val active = map.values.filter { it.isActive }
                if (active.isEmpty()) {
                    kotlinx.coroutines.delay(150)
                    if (ServiceLocator.downloader.progress.value.values.any { it.isActive }) return@collectLatest
                    expectedStop = true
                    if (stopSelfResult(latestStartId)) stopForegroundCompat()
                    else expectedStop = false
                    return@collectLatest
                }
                notify(buildNotification(map))
            }
        }
    }

    /**
     * Mirrors each task of this service run into its own notification, so one task can be paused
     * or resumed without opening 拾影.
     *
     * Opt-in: a busy queue would otherwise post one notification per task. A task leaves the set
     * once it completes, fails or disappears, and every individual notification is dropped with the
     * service — with nothing left to transfer the service exits by design, and a leftover control
     * for a task nothing is transferring would be misleading.
     */
    private fun observeIndividualNotifications() {
        individualJob?.cancel()
        individualJob = serviceScope.launch {
            // Hydrate history first: an empty snapshot would otherwise look like "task deleted".
            runCatching { ServiceLocator.historyRepository.snapshot() }
            combine(
                ServiceLocator.downloader.progress,
                ServiceLocator.historyRepository.observeAll(),
            ) { progress, records -> progress to records.associateBy { it.id } }
                .collect { (progress, recordsById) ->
                    if (!ServiceLocator.preferences.individualTaskNotifications) {
                        clearIndividualNotifications()
                        return@collect
                    }
                    trackedIds.addAll(progress.keys)
                    trackedIds.filter { id ->
                        val status = progress[id]?.status ?: recordsById[id]?.status
                        status == null || status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED
                    }.forEach { id ->
                        trackedIds -= id
                        cancelIndividualNotification(id)
                    }
                    trackedIds.forEach { id ->
                        recordsById[id]?.let { record -> postIndividualNotification(record, progress[id]) }
                    }
                }
        }
    }

    private fun slotFor(id: String): Int {
        notificationSlots[id]?.let { return it }
        val used = notificationSlots.values.toSet()
        var slot = 0
        while (slot in used) slot++
        notificationSlots[id] = slot
        return slot
    }

    private fun postIndividualNotification(record: DownloadRecord, progress: DownloadProgress?) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val slot = slotFor(record.id)
        val status = progress?.status ?: record.status
        val running = status == DownloadStatus.RUNNING || status == DownloadStatus.PENDING
        val downloaded = progress?.downloadedBytes ?: record.downloadedBytes
        val total = progress?.totalBytes?.takeIf { it > 0 } ?: record.totalBytes
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        val detail = buildString {
            append(if (running) "$percent%" else getString(R.string.history_status_paused))
            if (running) {
                append("  ·  ${formatSpeed(progress?.bytesPerSecond ?: 0L)}")
                if (total > 0) append("  ·  ${formatBytes(downloaded)} / ${formatBytes(total)}")
            }
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            OPEN_TASKS_REQUEST,
            Intent().setClassName(packageName, "com.videodown.douyin.presentation.MainActivity")
                .setAction(ACTION_OPEN_TASKS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val action = if (running) {
            taskAction(slot, record.id, ACTION_PAUSE_ONE, getString(R.string.action_pause), R.drawable.ic_shiying_pause, 0)
        } else {
            taskAction(slot, record.id, ACTION_RESUME_ONE, getString(R.string.action_resume), R.drawable.ic_shiying_resume_download, 1)
        }
        val notification = NotificationCompat.Builder(this, VideoDownApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shiying_notification_download)
            // A private transfer must not name itself on the lock screen.
            .setContentTitle(com.videodown.douyin.domain.model.VaultPolicy.notificationTitle(record) ?: getString(R.string.vault_locked_title))
            .setContentText(detail)
            .setProgress(100, percent, total <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(action)
            .build()
        // An extra per-task control is a convenience: failing to post it must never interrupt the
        // transfer itself, which the summary notification still reports.
        runCatching { NotificationManagerCompat.from(this).notify(INDIVIDUAL_NOTIFICATION_BASE + slot, notification) }
    }

    private fun taskAction(slot: Int, id: String, action: String, label: String, icon: Int, offset: Int): NotificationCompat.Action =
        NotificationCompat.Action(
            icon,
            label,
            PendingIntent.getService(
                this,
                INDIVIDUAL_ACTION_REQUEST_BASE + slot * 2 + offset,
                Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_RECORD_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

    private fun cancelIndividualNotification(id: String) {
        val slot = notificationSlots.remove(id) ?: return
        NotificationManagerCompat.from(this).cancel(INDIVIDUAL_NOTIFICATION_BASE + slot)
    }

    private fun clearIndividualNotifications() {
        val manager = NotificationManagerCompat.from(this)
        notificationSlots.values.forEach { slot -> manager.cancel(INDIVIDUAL_NOTIFICATION_BASE + slot) }
        notificationSlots.clear()
        trackedIds.clear()
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
            .setContentTitle(
                when {
                    active.any { it.phase == com.videodown.douyin.domain.model.TransferPhase.EXTRACTING } -> getString(R.string.history_phase_extracting)
                    active.any { it.phase == com.videodown.douyin.domain.model.TransferPhase.MERGING } -> getString(R.string.history_phase_merging)
                    count > 1 -> getString(R.string.service_downloading_many, count)
                    else -> getString(R.string.service_downloading_one)
                },
            )
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
                getString(R.string.history_pause_all),
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
        try { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
        catch (error: Exception) { failTransfers(com.videodown.douyin.core.ServiceFailure.NOTIFICATION_UPDATE.storageKey); stopSelf() }
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        failTransfers(com.videodown.douyin.core.ServiceFailure.TIME_LIMIT.storageKey)
        expectedStop = true
        stopSelf()
    }

    private fun failTransfers(message: String) {
        foregroundReady = false
        foregroundError = DownloadServiceException(com.videodown.douyin.core.ServiceFailure.UNKNOWN)
        requests.failPending(foregroundError!!)
        ServiceLocator.interruptBackgroundDownloads(message)
    }

    override fun onDestroy() {
        if (!expectedStop && foregroundError == null) failTransfers(com.videodown.douyin.core.ServiceFailure.INTERRUPTED.storageKey)
        clearIndividualNotifications()
        individualJob?.cancel()
        progressJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val INDIVIDUAL_NOTIFICATION_BASE = 1100
        private const val INDIVIDUAL_ACTION_REQUEST_BASE = 2100
        private const val OPEN_TASKS_REQUEST = 8703
        const val ACTION_START = "com.videodown.douyin.action.START_DOWNLOAD"
        const val ACTION_PAUSE_ALL = "com.videodown.douyin.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.videodown.douyin.action.RESUME_ALL"
        const val ACTION_PAUSE_ONE = "com.videodown.douyin.action.PAUSE_ONE"
        const val ACTION_RESUME_ONE = "com.videodown.douyin.action.RESUME_ONE"
        const val ACTION_OPEN_TASKS = "com.videodown.douyin.action.OPEN_TASKS"
        const val ACTION_STOP = "com.videodown.douyin.action.STOP"
        const val EXTRA_RECORD_ID = "download_record_id"

        private const val EXTRA_REQUEST = "download_service_request"
        private val requests = DownloadServiceRequests()

        /** Wait until Android has actually promoted this service to foreground. */
        suspend fun start(context: Context) = requests.awaitStart { token ->
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_REQUEST, token)
            val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            if (component == null) throw IllegalStateException("Download service is unavailable")
        }
    }
}
