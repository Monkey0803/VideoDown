package com.videodown.douyin

import android.media.AudioManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.onAllNodesWithText
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.player.PlayerScreen
import com.videodown.douyin.presentation.player.PlayerViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Real touches on a real video surface.
 *
 * The gestures only own the screen while the player's own controls are hidden, so the controller is
 * hidden explicitly; a swipe is verified by the system volume and window brightness it really
 * changed, not only by the on-screen label.
 */
@RunWith(AndroidJUnit4::class)
class PlayerGestureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var source: File
    private lateinit var record: DownloadRecord
    private var originalVolume = 0
    private var originalBrightness = -1f
    private val evidence get() = File(context.cacheDir, "player-gesture-evidence").apply { mkdirs() }

    @Before fun setup() = runBlocking {
        val audioManager = context.getSystemService(AudioManager::class.java)
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        originalBrightness = compose.activity.window.attributes.screenBrightness
        source = File(ServiceLocator.downloadPaths.downloadDir, "gesture-${UUID.randomUUID()}.mp4")
        instrumentation.context.assets.open("landscape-fixture.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        record = DownloadRecord(source.name, "video", "手势验证", fileName = source.name, filePath = source.path,
            status = DownloadStatus.COMPLETED, resolutionLabel = "320P")
        ServiceLocator.historyRepository.upsert(record)
        compose.runOnUiThread {
            val model = PlayerViewModel()
            compose.activity.setContent { VideoDownTheme { PlayerScreen(record.id, {}, model) } }
        }
        compose.waitForIdle()
        // The gesture overlay only exists while PlayerView's own controls are hidden.
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            if (compose.runOnUiThread { findPlayerView(compose.activity.window.decorView) } != null) break
            Thread.sleep(100)
        }
        hidePlayerControls()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("player-surface").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun close() {
        instrumentation.runOnMainSync {
            context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            compose.activity.window.attributes = compose.activity.window.attributes.apply { screenBrightness = originalBrightness }
        }
        if (::record.isInitialized) runBlocking { ServiceLocator.historyRepository.delete(record.id) }
        if (::source.isInitialized) source.delete()
    }

    /** Hides PlayerView's own controls so the gesture layer owns the surface. */
    private fun hidePlayerControls() {
        val playerView = compose.runOnUiThread { findPlayerView(compose.activity.window.decorView) }
        assertTrue("The player surface must contain a PlayerView", playerView != null)
        // Auto-show would bring the controls back on the next playback event and take the surface
        // away from the gesture layer mid-test.
        instrumentation.runOnMainSync {
            playerView!!.controllerAutoShow = false
            playerView!!.hideController()
        }
        compose.waitForIdle()
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPlayerView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun feedbackShown(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun anyFeedbackShown() =
        compose.onAllNodesWithTag("gesture-feedback").fetchSemanticsNodes().isNotEmpty()

    private fun awaitFeedback(expected: String, timeoutMs: Long = 3_000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (feedbackShown(expected)) return
            Thread.sleep(50)
        }
        throw AssertionError("'$expected' was never shown")
    }

    @Test fun doubleTapSeeksSwipeChangesVolumeAndBrightnessAndLongPressBoosts() {
        hidePlayerControls()

        // Right half first: forward, then back from there.
        compose.onNodeWithTag("player-surface").performTouchInput { doubleClick(Offset(width * 0.75f, height * 0.5f)) }
        awaitFeedback("+10 秒")
        compose.onNodeWithTag("player-surface").performTouchInput { doubleClick(Offset(width * 0.25f, height * 0.5f)) }
        awaitFeedback("-10 秒")

        // Volume: a swipe up on the right half must raise the real system stream volume.
        val audioManager = context.getSystemService(AudioManager::class.java)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        instrumentation.runOnMainSync { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max / 5, 0) }
        val volumeBefore = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        compose.onNodeWithTag("player-surface").performTouchInput {
            down(Offset(width * 0.8f, height * 0.8f))
            moveTo(Offset(width * 0.8f, height * 0.7f)); advanceEventTime(16)
            moveTo(Offset(width * 0.8f, height * 0.55f)); advanceEventTime(16)
            moveTo(Offset(width * 0.8f, height * 0.4f)); advanceEventTime(16)
            moveTo(Offset(width * 0.8f, height * 0.25f)); advanceEventTime(16)
            up()
        }
        val volumeAfter = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        assertTrue("A swipe up must raise the volume: $volumeBefore -> $volumeAfter (feedback shown=${anyFeedbackShown()})",
            volumeAfter > volumeBefore)

        // Brightness: a swipe up on the left half must raise the window brightness.
        val brightnessBefore = compose.activity.window.attributes.screenBrightness
        compose.onNodeWithTag("player-surface").performTouchInput {
            down(Offset(width * 0.2f, height * 0.8f))
            moveTo(Offset(width * 0.2f, height * 0.7f)); advanceEventTime(16)
            moveTo(Offset(width * 0.2f, height * 0.55f)); advanceEventTime(16)
            moveTo(Offset(width * 0.2f, height * 0.4f)); advanceEventTime(16)
            moveTo(Offset(width * 0.2f, height * 0.25f)); advanceEventTime(16)
            up()
        }
        val brightnessAfter = compose.activity.window.attributes.screenBrightness
        assertTrue("A swipe up must raise brightness: $brightnessBefore -> $brightnessAfter",
            brightnessAfter in 0f..1f && brightnessAfter > 0f && brightnessAfter != brightnessBefore)

        // Long press: the boost indicator stays while the finger is held and clears on release.
        // The long-press threshold is a virtual-time timeout, so the main clock is driven by hand.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("player-surface").performTouchInput { down(Offset(width * 0.5f, height * 0.5f)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.advanceTimeByFrame()
        assertTrue("The boost indicator must stay up while the finger is held", feedbackShown("2× 快进中"))
        compose.onNodeWithTag("player-surface").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { !anyFeedbackShown() }

        File(evidence, "summary.txt").writeText(
            "seek=+10s/-10s confirmed by the on-screen label\n" +
                "volume=$volumeBefore -> $volumeAfter (max=$max)\n" +
                "brightness=$brightnessBefore -> $brightnessAfter\n" +
                "longPressBoost=shown while held, cleared on release\n",
        )
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "PLAYER_GESTURES: seek labels, volume $volumeBefore->$volumeAfter, brightness $brightnessBefore->$brightnessAfter, 2x boost\n")
        })
    }
}
