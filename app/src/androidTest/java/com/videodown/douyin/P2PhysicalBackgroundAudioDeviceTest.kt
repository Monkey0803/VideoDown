package com.videodown.douyin

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.playback.AudioPlaybackService
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.presentation.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** System notification UI controls and secure screen-off; never bypasses keyguard. */
class P2PhysicalBackgroundAudioDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun report(value: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$value\n") })
    private var ownTitle: String? = null
    private fun capture(name: String) {
        val dir = File(context.cacheDir, "p2-audio-evidence").apply { mkdirs() }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val systemUi = root?.packageName?.toString() == "com.android.systemui"
        val card = if (systemUi) ownTitle?.let(::mediaCard) else root
        if (systemUi && card == null) { report("CAPTURE_SKIPPED: own media card unavailable; no unrelated notification screenshot saved"); return }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            val bounds = android.graphics.Rect().also { card?.getBoundsInScreen(it) }
            bounds.intersect(0, 0, bitmap.width, bitmap.height)
            val captured = if (systemUi && bounds.width() > 0 && bounds.height() > 0)
                android.graphics.Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height()) else bitmap
            File(dir, "$name.png").outputStream().use { captured.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            if (captured !== bitmap) captured.recycle(); bitmap.recycle()
        }
        fun render(node: AccessibilityNodeInfo): String = buildString {
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            append("<node class=\"${node.className}\" id=\"${node.viewIdResourceName}\" text=\"${android.text.TextUtils.htmlEncode(node.text?.toString().orEmpty())}\" desc=\"${android.text.TextUtils.htmlEncode(node.contentDescription?.toString().orEmpty())}\" bounds=\"$bounds\">\n")
            for (i in 0 until node.childCount) node.getChild(i)?.let { append(render(it)) }; append("</node>\n")
        }
        File(dir, "$name.xml").writeText(card?.let(::render).orEmpty())
    }
    private suspend fun await(check: () -> Boolean) = withTimeout(15_000) { while (!check()) delay(50) }
    private fun findAction(node: AccessibilityNodeInfo, playing: Boolean): AccessibilityNodeInfo? {
        val description = node.contentDescription?.toString().orEmpty()
        val matches = if (playing) description.contains("暂停") || description.equals("Pause", true)
            else description.contains("播放") || description.equals("Play", true)
        if (matches) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findAction(it, playing)?.let { found -> return found } }
        return null
    }
    private fun findTitle(node: AccessibilityNodeInfo, title: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == title) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findTitle(it, title)?.let { found -> return found } }
        return null
    }
    private fun mediaCard(title: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow?.takeIf { it.packageName?.toString() == "com.android.systemui" } ?: return null
        var node = findTitle(root, title) ?: return null
        while (node.parent != null) {
            // The real MIUI hierarchy exposes the media tile as qs_media_controls.
            // Stop at a card boundary; never search a carousel/list/shade ancestor.
            if (node.viewIdResourceName in setOf("com.android.systemui:id/qs_media_controls", "android:id/status_bar_latest_event_content")) return node
            if (node.viewIdResourceName in setOf("com.android.systemui:id/media_carousel", "com.android.systemui:id/notification_stack_scroller")) return null
            node = node.parent
        }
        return null
    }
    private fun scopedAction(title: String, playing: Boolean): AccessibilityNodeInfo? = mediaCard(title)?.let { findAction(it, playing) }
    private suspend fun clickSystemControl(title: String, playing: Boolean) {
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
        await { scopedAction(title, playing) != null }
        capture(if (playing) "notification-before-pause" else "notification-before-play")
        var clickable = scopedAction(title, playing) ?: throw AssertionError("Own audio action disappeared")
        while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
        if (!clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val action = scopedAction(title, playing) ?: throw AssertionError("Own audio action disappeared before touch")
            val bounds = android.graphics.Rect().also(action::getBoundsInScreen)
            report("AUDIO_CARD_TOUCH_FALLBACK: observed own action bounds=$bounds")
            val now = android.os.SystemClock.uptimeMillis()
            for (type in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, now + if (type == android.view.MotionEvent.ACTION_UP) 80 else 0, type, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
            }
        }
        capture(if (playing) "notification-after-pause" else "notification-after-play")
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
    }
    @Test fun actualSystemAudioControlsAndSecureScreenOffReturnPreservePlayback() = runBlocking {
        assertEquals("Explicit human-unlock opt-in required", "true", InstrumentationRegistry.getArguments().getString("manual_unlock"))
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        assertTrue(keyguard.isKeyguardSecure); assertFalse(keyguard.isKeyguardLocked)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.areNotificationsEnabled())
        assertFalse("Do not replace existing user audio", manager.activeNotifications.any { it.notification.extras.getParcelable<android.media.session.MediaSession.Token>(android.app.Notification.EXTRA_MEDIA_SESSION) != null })
        val originalOptions = ServiceLocator.playbackRepository.options(true)
        val launchIntent = Intent(compose.activity.intent)
        val id = "p2-audio-${UUID.randomUUID()}"
        val source = File(ServiceLocator.downloadPaths.downloadDir, "$id.m4a")
        val record = DownloadRecord(id, id, "后台音频-${UUID.randomUUID().toString().take(8)}", fileName = source.name, filePath = source.path,
            status = DownloadStatus.COMPLETED, media = MediaDownload(audioOnly = true))
        ownTitle = record.title
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        var loops = 0L
        var slept = false
        fun snapshot(): Triple<Boolean, Long, String?> {
            var result = Triple(false, 0L, null as String?)
            instrumentation.runOnMainSync { controller?.let { result = Triple(it.isPlaying, loops + it.currentPosition, it.currentMediaItem?.mediaId) } }
            return result
        }
        try {
            instrumentation.context.assets.open("background-audio-fixture.m4a").use { input -> source.outputStream().use { input.copyTo(it) } }
            ServiceLocator.playbackRepository.setOptions(true, PlaybackOptions(loop = true))
            ServiceLocator.historyRepository.upsert(record)
            compose.onNodeWithText("已存").performClick()
            compose.onNodeWithText(record.title).performClick()
            compose.waitForIdle()
            instrumentation.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))).buildAsync() }
            controller = future!!.get(10, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { controller!!.addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) loops += controller!!.duration.coerceAtLeast(0)
                }
            }) }
            await { snapshot().first && snapshot().third == id }
            capture("audio-player-before-home")
            if (InstrumentationRegistry.getArguments().getString("audio_inspect_only") == "true") {
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                await { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.systemui" }
                delay(500); capture("notification-card-diagnostic")
                report("DIAGNOSTIC_ONLY: system notification hierarchy collected, acceptance not executed")
                return@runBlocking
            }
            val beforeHome = snapshot().second
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            await { snapshot().first && snapshot().second > beforeHome + 1000 }
            report("AUDIO_HOME: playing=true, same media=$id, continuousPosition=$beforeHome->${snapshot().second}")
            clickSystemControl(record.title, true); await { !snapshot().first }
            val paused = snapshot().second; delay(300); assertTrue(kotlin.math.abs(snapshot().second - paused) < 100)
            report("ACTUAL_SHADE_PAUSE: paused position=$paused")
            clickSystemControl(record.title, false); await { snapshot().first && snapshot().second > paused + 250 }
            report("ACTUAL_SHADE_PLAY: playback advanced after actual UI action")
            assertEquals(id, snapshot().third)
            instrumentation.runOnMainSync { assertEquals(Player.STATE_READY, controller!!.playbackState) }
            if (InstrumentationRegistry.getArguments().getString("audio_return_only") != "true") {
            val beforeLock = snapshot().second
            shell("input keyevent 223"); slept = true
            await { !power.isInteractive && keyguard.isKeyguardLocked }
            await { snapshot().first && snapshot().second > beforeLock + 1000 }
            assertFalse(power.isInteractive); assertTrue(keyguard.isKeyguardLocked)
            assertEquals(id, snapshot().third)
            instrumentation.runOnMainSync { assertEquals(Player.STATE_READY, controller!!.playbackState) }
            report("AUDIO_LOCKED: secureLocked=true, interactive=false, sameMedia=${snapshot().third}, continuousPosition=$beforeLock->${snapshot().second}")
            shell("input keyevent 224"); slept = false
            report("MANUAL_UNLOCK_READY: background AUDIO; normal human unlock required within 120 seconds")
            withTimeout(120_000) { while (keyguard.isKeyguardLocked) delay(100) }
            } else report("UI_RETURN_ONLY: no secure lock attempted; secure acceptance is the separate completed runner")
            assertTrue(snapshot().first); assertEquals(id, snapshot().third)
            instrumentation.runOnMainSync { assertEquals(Player.STATE_READY, controller!!.playbackState) }
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
            compose.waitForIdle()
            withTimeout(10_000) { while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) delay(100) }
            compose.waitUntil(10000) { compose.onAllNodesWithText(record.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("分享").assertIsDisplayed()
            capture("audio-after-normal-unlock")
            report("AUDIO_UNLOCKED: same media and playing retained; position=${snapshot().second}")
        } finally {
            withContext(NonCancellable) {
                if (slept) shell("input keyevent 224")
                instrumentation.runOnMainSync { controller?.pause(); controller?.clearMediaItems(); future?.let(MediaController::releaseFuture); context.stopService(Intent(context, AudioPlaybackService::class.java)); compose.activity.intent = launchIntent }
                ServiceLocator.historyRepository.delete(id); source.delete()
                ServiceLocator.playbackRepository.setOptions(true, originalOptions)
                // Secure devices remain locked on timeout: automation never dismisses the keyguard.
                if (!keyguard.isKeyguardLocked) {
                    instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); delay(500)
                    context.startActivity(Intent(launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); compose.waitForIdle()
                }
                report("AUDIO_CLEANUP: own record/file removed, audio options restored, keyguardLocked=${keyguard.isKeyguardLocked}")
            }
        }
    }
}
