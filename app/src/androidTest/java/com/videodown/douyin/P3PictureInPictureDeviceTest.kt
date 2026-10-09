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
    private var completedLoops = 0L
    private val loopListener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && oldPosition.positionMs > newPosition.positionMs) completedLoops++
        }
    }
    // Access only on the main thread; preserves progress across short fixture loop boundaries.
    private fun continuousPosition() = completedLoops * player.duration.coerceAtLeast(0) + player.currentPosition
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
        compose.runOnUiThread { player.addListener(loopListener) }
        Unit
    }
    @After fun close() {
        if (::activity.isInitialized && !activity.isDestroyed) {
            instrumentation.runOnMainSync { if (::player.isInitialized) player.removeListener(loopListener); activity.setContent { androidx.compose.material3.Text("已关闭") } }
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
        var mediaItem: androidx.media3.common.MediaItem? = null
        instrumentation.runOnMainSync { mediaItem = player.currentMediaItem }
        repeat(3) { cycle ->
            compose.onNodeWithContentDescription("倍速 1.0×").performScrollTo().performClick()
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            await { activity.isInPictureInPictureMode }
            compose.onNodeWithText("播放速度").assertDoesNotExist()
            compose.onNodeWithContentDescription("画中画").assertDoesNotExist()
            var initial = 0L
            instrumentation.runOnMainSync { initial = continuousPosition() }
            Thread.sleep(600)
            await { player.isPlaying && continuousPosition() >= initial + 250 }
            context.sendBroadcast(Intent("com.videodown.douyin.PIP_PLAY_PAUSE").setPackage(context.packageName))
            await { !player.isPlaying }
            context.sendBroadcast(Intent("com.videodown.douyin.PIP_PLAY_PAUSE").setPackage(context.packageName))
            await { player.isPlaying }
            instrumentation.runOnMainSync { initial = continuousPosition() }
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await { !activity.isInPictureInPictureMode }
            await { find(activity.window.decorView)?.player === player && player.isPlaying && player.currentMediaItem == mediaItem && continuousPosition() >= initial }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PiP Home/expand iteration ${cycle + 1}: same player, advancing progress, play/pause OK\n") })
        }
    }

    @Test fun pipScreenOffThenReturnKeepsActivityAndPlayer() {
        val secure = context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardSecure
        org.junit.Assume.assumeTrue("Secure device requires opt-in manual unlock", !secure || InstrumentationRegistry.getArguments().getString("manual_unlock") == "true")
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        var mediaItem: androidx.media3.common.MediaItem? = null
        instrumentation.runOnMainSync { mediaItem = player.currentMediaItem }
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        repeat(2) { cycle ->
            compose.onNodeWithContentDescription("画中画").performScrollTo().performClick()
            await { activity.isInPictureInPictureMode }
            compose.waitForIdle()
            var initial = 0L
            instrumentation.runOnMainSync {
                initial = continuousPosition()
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "PIP_BEFORE_LOCK: cycle=${cycle + 1}, position=${player.currentPosition}, loops=$completedLoops, continuous=$initial\n")
                })
            }
            try {
                shell("input keyevent KEYCODE_SLEEP")
                await { !context.getSystemService(android.os.PowerManager::class.java).isInteractive }
                if (secure) await { keyguard.isKeyguardLocked }
                Thread.sleep(1000)
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
                await { find(activity.window.decorView)?.player === player && player.currentMediaItem == mediaItem }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PIP_LOCKED: cycle=${cycle + 1}, interactive=false, keyguardLocked=${keyguard.isKeyguardLocked}, samePlayer/media=true\n") })
            } finally { shell("input keyevent KEYCODE_WAKEUP") }
            await { context.getSystemService(android.os.PowerManager::class.java).isInteractive }
            if (secure) {
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "MANUAL_UNLOCK_READY: PiP cycle ${cycle + 1}; unlock normally within 120 seconds\n") })
                val deadline = System.currentTimeMillis() + 120000
                while (keyguard.isKeyguardLocked && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertFalse("Manual unlock not completed within 120 seconds", keyguard.isKeyguardLocked)
            } else {
                shell("wm dismiss-keyguard")
                await { !keyguard.isKeyguardLocked }
            }
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await { !activity.isInPictureInPictureMode }
            compose.waitForIdle()
            instrumentation.runOnMainSync { instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "PIP_RETURN_STATE: cycle=${cycle + 1}, samePlayer=${find(activity.window.decorView)?.player === player}, sameMedia=${player.currentMediaItem == mediaItem}, state=${player.playbackState}, playing=${player.isPlaying}, playWhenReady=${player.playWhenReady}, position=${player.currentPosition}, loops=$completedLoops, continuous=${continuousPosition()}, beforeLock=$initial, destroyed=${activity.isDestroyed}\n")
            }) }
            // ON_STOP pauses video by design. Return preserves the player/media/position;
            // actual UI playback is resumed below instead of requiring implicit autoplay.
            await { find(activity.window.decorView)?.player === player && player.currentMediaItem == mediaItem && player.playbackState == Player.STATE_READY && continuousPosition() >= initial }
            instrumentation.runOnMainSync { find(activity.window.decorView)!!.showController() }
            val control = androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(androidx.media3.ui.R.id.exo_play_pause))
            var playing = false
            instrumentation.runOnMainSync { playing = player.isPlaying; initial = continuousPosition() }
            if (!playing) control.perform(androidx.test.espresso.action.ViewActions.click())
            await { player.isPlaying && continuousPosition() >= initial + 250 }
            control.perform(androidx.test.espresso.action.ViewActions.click())
            await { !player.isPlaying }
            control.perform(androidx.test.espresso.action.ViewActions.click())
            await { player.isPlaying }
            val evidence = File(context.cacheDir, "p1-manual-pip-evidence").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(evidence, "unlocked-cycle-${cycle + 1}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PIP_UNLOCKED: cycle=${cycle + 1}, samePlayer/media=true, looping progress advanced, expanded and actual UI play/pause OK\n") })
        }
        await { player.currentPosition > 250 }
        instrumentation.runOnMainSync { activity.finish() }
        await { !player.isPlaying && player.playbackState == Player.STATE_IDLE }
        val file = File(record.filePath!!)
        assertTrue(preferences.checkpoint(record.id, file.length(), file.lastModified()).resumePosition() > 0)
    }
    @Test fun systemPipDismissReleasesVideo() {
        compose.onNodeWithContentDescription("画中画").performScrollTo().performClick()
        await { activity.isInPictureInPictureMode }
        compose.waitForIdle()
        Thread.sleep(1000)
        instrumentation.runOnMainSync { instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "PIP_STATE: system=${activity.isInPictureInPictureMode}, compose=${activity.inPictureInPicture}, samePlayer=${find(activity.window.decorView)?.player === player}\n")
        }) }
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val oldFlags = info.flags
        try {
            info.flags = oldFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            var root: android.view.accessibility.AccessibilityNodeInfo? = null
            await { root = automation.windows.firstOrNull { it.isInPictureInPictureMode }?.root; root != null }
            val evidence = File(context.cacheDir, "p1-pip-evidence").apply { mkdirs() }
            fun render(node: android.view.accessibility.AccessibilityNodeInfo): String = buildString {
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                append("<node text=\"${android.text.TextUtils.htmlEncode(node.text?.toString().orEmpty())}\" description=\"${android.text.TextUtils.htmlEncode(node.contentDescription?.toString().orEmpty())}\" bounds=\"$bounds\" clickable=\"${node.isClickable}\">\n")
                for (i in 0 until node.childCount) node.getChild(i)?.let { append(render(it)) }
                append("</node>\n")
            }
            File(evidence, "windows.xml").writeText(automation.windows.filter { it.isInPictureInPictureMode }.mapNotNull { it.root }.joinToString("\n") { render(it) })
            automation.takeScreenshot().let { bitmap ->
                File(evidence, "pip.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            // This invokes the real system PiP dismissal path, rather than Activity.finish().
            if (!root!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS)) {
                val pipBounds = android.graphics.Rect().also(root!!::getBoundsInScreen)
                fun closeNode(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    if (node.contentDescription?.toString() in setOf("关闭", "Close") && pipBounds.contains(bounds.centerX(), bounds.centerY())) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { closeNode(it)?.let { found -> return found } }
                    return null
                }
                var close = automation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::closeNode)
                if (close == null) {
                    val bounds = android.graphics.Rect().also(root!!::getBoundsInScreen)
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
                    await { close = automation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::closeNode); close != null }
                }
                assertNotNull("Real system PiP close control must be exposed", close)
                assertTrue(close!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PIP_SYSTEM_CLOSE: clicked actual Chinese system close control\n") })
            }
            await { !player.isPlaying && player.playbackState == Player.STATE_IDLE }
            val file = File(record.filePath!!)
            assertTrue(preferences.checkpoint(record.id, file.length(), file.lastModified()).resumePosition() > 0)
        } finally {
            info.flags = oldFlags
            automation.serviceInfo = info
        }
    }
    @Test fun explicitPipEntryThenActivityClosureReleasesVideoAndSavesProgress() {
        compose.onNodeWithContentDescription("画中画").performScrollTo().performClick()
        await { compose.activity.isInPictureInPictureMode }
        Thread.sleep(1000)
        await { player.currentPosition > 1000 }
        instrumentation.runOnMainSync { compose.activity.finish() }
        await { !player.isPlaying && player.playbackState == Player.STATE_IDLE }
        val file = File(record.filePath!!)
        assertTrue(preferences.checkpoint(record.id, file.length(), file.lastModified()).resumePosition() > 1000)
    }
}
