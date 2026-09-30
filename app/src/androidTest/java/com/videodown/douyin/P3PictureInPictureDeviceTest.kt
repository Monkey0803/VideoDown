package com.videodown.douyin

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.playback.PlaybackPreferences
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.player.PlayerScreen
import com.videodown.douyin.presentation.player.PlayerViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class P3PictureInPictureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var directory: File
    private lateinit var record: DownloadRecord
    private lateinit var activity: MainActivity
    private lateinit var player: Player
    private lateinit var preferences: PlaybackPreferences
    @Before fun setup() = runBlocking {
        directory = File(context.cacheDir, "p3-pip-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, "video.mp4")
        instrumentation.context.assets.open("landscape-fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
        record = DownloadRecord("pip", "media", "P3 画中画验证", filePath = file.path, fileName = file.name, status = DownloadStatus.COMPLETED)
        history.upsert(record)
        preferences = PlaybackPreferences(context, directory.name)
        compose.runOnUiThread {
            activity = compose.activity
            val model = PlayerViewModel(history, preferences)
            compose.activity.setContent { VideoDownTheme { PlayerScreen(record.id, {}, model) } }
        }
        compose.waitUntil(10000) {
            var ready = false
            compose.runOnUiThread { find(compose.activity.window.decorView)?.player?.let { player = it; ready = it.isPlaying && it.currentPosition > 100 } }
            ready
        }
        Unit
    }
    @After fun close() {
        if (::activity.isInitialized && !activity.isDestroyed) {
            instrumentation.runOnMainSync { activity.setContent { androidx.compose.material3.Text("已关闭") } }
            instrumentation.waitForIdleSync()
        }
        if (::directory.isInitialized) {
            context.getSharedPreferences(directory.name, 0).edit().clear().commit()
            directory.deleteRecursively()
        }
    }
    private fun find(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun await(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = check() }
            if (ready) return
            Thread.sleep(50)
        }
        fail("PiP state did not reach expected condition")
    }
    @Test fun homeEntersRealPipHidesSpeedDialogAndReturnsWithoutResettingVideo() {
        compose.onNodeWithText("倍速 1.0×").performScrollTo().performClick()
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        await { compose.activity.isInPictureInPictureMode }
        compose.onNodeWithText("播放速度").assertDoesNotExist()
        compose.onNodeWithText("画中画").assertDoesNotExist()
        var initial = 0L
        instrumentation.runOnMainSync { initial = player.currentPosition }
        Thread.sleep(1000)
        await { player.isPlaying && player.currentPosition >= initial + 500 }
        context.sendBroadcast(Intent("com.videodown.douyin.PIP_PLAY_PAUSE").setPackage(context.packageName))
        await { !player.isPlaying }
        context.sendBroadcast(Intent("com.videodown.douyin.PIP_PLAY_PAUSE").setPackage(context.packageName))
        await { player.isPlaying }
        instrumentation.runOnMainSync { initial = player.currentPosition }
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { !compose.activity.isInPictureInPictureMode }
        await { find(activity.window.decorView)?.player === player && player.isPlaying && player.currentPosition >= initial }
    }
    @Test fun pipScreenOffThenReturnKeepsActivityAndPlayer() {
        compose.onNodeWithText("画中画", substring = false).performScrollTo().performClick()
        await { activity.isInPictureInPictureMode }
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        try {
            shell("input keyevent KEYCODE_SLEEP")
            Thread.sleep(1000)
            assertFalse(activity.isFinishing)
            assertFalse(activity.isDestroyed)
        } finally { shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard") }
        await { context.getSystemService(android.os.PowerManager::class.java).isInteractive }
        shell("wm dismiss-keyguard")
        shell("input keyevent KEYCODE_MENU")
        await { !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked }
        Thread.sleep(500)
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { !activity.isInPictureInPictureMode }
        await { find(activity.window.decorView)?.player === player && player.playbackState == Player.STATE_READY }
    }
    @Test fun systemPipDismissReleasesVideo() {
        compose.onNodeWithText("画中画", substring = false).performScrollTo().performClick()
        await { activity.isInPictureInPictureMode }
        Thread.sleep(1000)
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val oldFlags = info.flags
        try {
            info.flags = oldFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            var root: android.view.accessibility.AccessibilityNodeInfo? = null
            await { root = automation.windows.firstOrNull { it.isInPictureInPictureMode }?.root; root != null }
            // This invokes the real system PiP dismissal path, rather than Activity.finish().
            assertTrue(root!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS))
            await { !player.isPlaying && player.playbackState == Player.STATE_IDLE }
            val file = File(record.filePath!!)
            assertTrue(preferences.checkpoint(record.id, file.length(), file.lastModified()).resumePosition() > 0)
        } finally {
            info.flags = oldFlags
            automation.serviceInfo = info
        }
    }
    @Test fun explicitPipEntryThenActivityClosureReleasesVideoAndSavesProgress() {
        compose.onNodeWithText("画中画", substring = false).performScrollTo().performClick()
        await { compose.activity.isInPictureInPictureMode }
        Thread.sleep(1000)
        await { player.currentPosition > 1000 }
        instrumentation.runOnMainSync { compose.activity.finish() }
        await { !player.isPlaying && player.playbackState == Player.STATE_IDLE }
        val file = File(record.filePath!!)
        assertTrue(preferences.checkpoint(record.id, file.length(), file.lastModified()).resumePosition() > 1000)
    }
}
