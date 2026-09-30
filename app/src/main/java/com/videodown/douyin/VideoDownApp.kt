package com.videodown.douyin

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadCompletionNotifier

class VideoDownApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        ServiceLocator.bootstrap()
        createNotificationChannels()
        DownloadCompletionNotifier.createChannel(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "downloads"
    }
}
