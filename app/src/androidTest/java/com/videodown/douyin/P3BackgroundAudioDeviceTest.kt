package com.videodown.douyin

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaSession
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.playback.AudioPlaybackService
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.player.PlayerScreen
import com.videodown.douyin.presentation.player.PlayerViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class P3BackgroundAudioDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var source: File
    private lateinit var record: DownloadRecord
    private lateinit var oldOptions: PlaybackOptions
    private var launchIntent: Intent? = null
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    @Before fun setup() = runBlocking {
        launchIntent = Intent(compose.activity.intent)
        oldOptions = ServiceLocator.playbackRepository.options(true)
        ServiceLocator.playbackRepository.setOptions(true, PlaybackOptions())
        source = File(ServiceLocator.downloadPaths.downloadDir, "p3-background-${UUID.randomUUID()}.m4a")
        instrumentation.context.assets.open("background-audio-fixture.m4a").use { input -> source.outputStream().use { input.copyTo(it) } }
        record = DownloadRecord(source.name, "audio", "P3 后台音频验证", fileName = source.name, filePath = source.path,
            status = DownloadStatus.COMPLETED, media = MediaDownload(audioOnly = true))
        ServiceLocator.historyRepository.upsert(record)
        compose.runOnUiThread {
            val model = PlayerViewModel()
            compose.activity.setContent { VideoDownTheme { PlayerScreen(record.id, {}, model) } }
        }
        connect()
        compose.waitForIdle()
        await { controller!!.isPlaying && controller!!.playbackState == Player.STATE_READY && controller!!.currentPosition > 100 }
        Unit
    }
    @After fun close() = runBlocking {
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        instrumentation.runOnMainSync {
            // ActivityScenario identifies lifecycle events by its original launch Intent.
            // MainActivity intentionally replaces its Intent after the real notification opens.
            launchIntent?.let { compose.activity.intent = it }
            controller?.pause(); controller?.clearMediaItems()
            future?.let(MediaController::releaseFuture); future = null; controller = null
            context.stopService(Intent(context, AudioPlaybackService::class.java))
        }
        // Re-deliver a matching lifecycle transition after onNewIntent changed the launch action.
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(800)
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
        instrumentation.waitForIdleSync()
        if (::record.isInitialized) ServiceLocator.historyRepository.delete(record.id)
        if (::source.isInitialized) source.delete()
        if (::oldOptions.isInitialized) ServiceLocator.playbackRepository.setOptions(true, oldOptions)
        Unit
    }
    private fun connect() {
        instrumentation.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))).buildAsync() }
        controller = future!!.get(10, TimeUnit.SECONDS)
    }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) return
            Thread.sleep(50)
        }
        var diagnostic = "disconnected"
        instrumentation.runOnMainSync { controller?.let { diagnostic = "id=${it.currentMediaItem?.mediaId}, state=${it.playbackState}, ready=${it.playWhenReady}, playing=${it.isPlaying}, position=${it.currentPosition}, error=${it.playerError}" } }
        fail("Audio service state did not reach expected condition: $diagnostic")
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
    }
    private fun notification(): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        var found: Notification? = null
        await { found = manager.activeNotifications.firstOrNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == record.title }?.notification; found != null }
        assertEquals(R.drawable.ic_shiying_notification_audio, found!!.smallIcon.resId)
        return found!!
    }
    private fun find(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }
    @Test fun detachedUiKeepsForegroundAudioAndNotificationReturnsWithoutResettingPausedPosition() {
        val notification = notification()
        @Suppress("DEPRECATION")
        val token = notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)!!
        val system = android.media.session.MediaController(context, token)
        var initial = 0L
        instrumentation.runOnMainSync { initial = controller!!.currentPosition }
        // Restore the real activity app root while disposing the custom audio composition.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        instrumentation.runOnMainSync { MediaController.releaseFuture(future!!); future = null; controller = null }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(2500)
        connect()
        await { controller!!.isPlaying && controller!!.currentPosition >= initial + 2000 }
        system.transportControls.pause()
        await { !controller!!.playWhenReady }
        system.transportControls.seekTo(12000)
        await { controller!!.currentPosition == 12000L }
        system.sessionActivity!!.send()
        compose.waitUntil(10000) { find(compose.activity.window.decorView)?.player != null }
        await { find(compose.activity.window.decorView)?.player?.let { it.currentPosition == 12000L && !it.playWhenReady } == true }
        assertTrue(source.isFile)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("我的").performClick()
        compose.runOnUiThread {
            assertFalse(compose.activity.intent.hasExtra(AudioPlaybackService.EXTRA_RECORD_ID))
            // Restore the runner's launch identity after checking the real destination was consumed.
            compose.activity.intent = launchIntent
        }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(800)
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
        instrumentation.waitForIdleSync()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10000) { runCatching { compose.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithText("我的").assertIsSelected()
        compose.runOnIdle { assertNull(find(compose.activity.window.decorView)) }
    }
    @Test fun noisyEventPausesActualServicePlayer() {
        // The emulator has no wired-headphone HW module. Deliver the system event to
        // the actual Media3 receiver, retaining its real enabled check and listener.
        instrumentation.runOnMainSync {
            val service = AudioPlaybackService::class.java.getDeclaredField("active").apply { isAccessible = true }.get(null)!!
            val player = service.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(service)!!
            val manager = player.javaClass.getDeclaredField("audioBecomingNoisyManager").apply { isAccessible = true }.get(player)!!
            val receiver = manager.javaClass.getDeclaredField("receiver").apply { isAccessible = true }.get(manager) as android.content.BroadcastReceiver
            receiver.onReceive(context, Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        await { !controller!!.playWhenReady }
    }
    @Test fun temporaryAudioFocusLossPausesAndRegainResumes() {
        val manager = context.getSystemService(android.media.AudioManager::class.java)
        val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }.build()
        try {
            assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(focus))
            await { !controller!!.isPlaying }
        } finally { manager.abandonAudioFocusRequest(focus) }
        await { controller!!.isPlaying }
    }
    @Test fun lockScreenKeepsAudioAndSystemMediaButtonsPauseAndResume() {
        notification()
        var initial = 0L
        instrumentation.runOnMainSync { initial = controller!!.currentPosition }
        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(1500)
        await { controller!!.isPlaying && controller!!.currentPosition >= initial + 1000 }
        shell("input keyevent KEYCODE_MEDIA_PAUSE")
        await { !controller!!.playWhenReady }
        shell("input keyevent KEYCODE_MEDIA_PLAY")
        await { controller!!.isPlaying }
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        shell("input keyevent KEYCODE_HEADSETHOOK")
        await { !controller!!.playWhenReady }
        shell("input keyevent KEYCODE_HEADSETHOOK")
        await { controller!!.isPlaying }
        assertTrue(source.isFile)
    }
}
