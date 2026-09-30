package com.videodown.douyin.data.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import com.videodown.douyin.R
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.PlaybackCheckpoint
import com.videodown.douyin.domain.model.PlaybackOptions
import com.videodown.douyin.presentation.MainActivity
import java.io.File

/** The audio Player belongs to the service, never to the activity or its composition. */
class AudioPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { save(); handler.postDelayed(this, 2000) }
    }
    private fun launchIntent(id: String) = PendingIntent.getActivity(this, 8701,
        Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_PLAYER).putExtra(EXTRA_RECORD_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            // Keep media playback separate from the download service's notification (1001).
            .setNotificationId(8701)
            .build().apply { setSmallIcon(R.drawable.ic_shiying_notification_audio) })
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        session = MediaSession.Builder(this, player).setSessionActivity(launchIntent("")).build()
        active = this
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) launchIntent(player.currentMediaItem?.mediaId.orEmpty())
                if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED) || events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) {
                    ServiceLocator.playbackRepository.setOptions(true, PlaybackOptions(player.playbackParameters.speed, player.repeatMode == Player.REPEAT_MODE_ONE))
                }
                save()
            }
        })
        handler.post(ticker)
    }
    private fun save() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        if (uri.scheme != "file") return
        val file = uri.path?.let(::File) ?: return
        if (!file.isFile) return
        ServiceLocator.playbackRepository.saveCheckpoint(item.mediaId, file.length(), file.lastModified(),
            PlaybackCheckpoint(player.currentPosition, player.duration.coerceAtLeast(0), player.playbackState == Player.STATE_ENDED))
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) session else null
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        save()
        if (active === this) active = null
        session?.release(); session = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
    companion object {
        const val ACTION_OPEN_PLAYER = "com.videodown.douyin.OPEN_PLAYER"
        const val EXTRA_RECORD_ID = "record_id"
        private var active: AudioPlaybackService? = null
        /** Opening a video pauses audio owned by this process to avoid two simultaneous players. */
        fun pauseActive() { active?.player?.pause() }
    }
}
