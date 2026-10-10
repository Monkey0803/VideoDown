package com.videodown.douyin

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.playback.AudioPlaybackService
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.domain.model.PlaybackOptions
import com.videodown.douyin.domain.model.SleepTimer
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.player.PlayerScreen
import com.videodown.douyin.presentation.player.PlayerViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The audio sleep timer: the dialog writes a real deadline, and the media service really pauses
 * playback once that deadline passes — no waiting for the 15-minute choice to elapse.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var source: File
    private lateinit var record: DownloadRecord
    private lateinit var oldOptions: PlaybackOptions
    private var oldDeadline = SleepTimer.OFF
    private var launchIntent: Intent? = null
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val evidence get() = File(context.cacheDir, "sleep-timer-evidence").apply { mkdirs() }

    @Before fun setup() = runBlocking {
        launchIntent = Intent(compose.activity.intent)
        oldOptions = ServiceLocator.playbackRepository.options(true)
        oldDeadline = ServiceLocator.playbackRepository.sleepTimerDeadline()
        ServiceLocator.playbackRepository.setOptions(true, PlaybackOptions())
        ServiceLocator.playbackRepository.setSleepTimerDeadline(SleepTimer.OFF)
        source = File(ServiceLocator.downloadPaths.downloadDir, "sleep-timer-${UUID.randomUUID()}.m4a")
        instrumentation.context.assets.open("background-audio-fixture.m4a").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        record = DownloadRecord(source.name, "audio", "睡眠定时验证", fileName = source.name, filePath = source.path,
            status = DownloadStatus.COMPLETED, media = MediaDownload(audioOnly = true))
        ServiceLocator.historyRepository.upsert(record)
        compose.runOnUiThread {
            val model = PlayerViewModel()
            compose.activity.setContent { VideoDownTheme { PlayerScreen(record.id, {}, model) } }
        }
        connect()
        compose.waitForIdle()
        await { controller!!.isPlaying && controller!!.playbackState == Player.STATE_READY }
        Unit
    }

    @After fun close() = runBlocking {
        instrumentation.runOnMainSync {
            launchIntent?.let { compose.activity.intent = it }
            controller?.pause(); controller?.clearMediaItems()
            future?.let(MediaController::releaseFuture); future = null; controller = null
            context.stopService(Intent(context, AudioPlaybackService::class.java))
        }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(800)
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
        instrumentation.waitForIdleSync()
        if (::record.isInitialized) ServiceLocator.historyRepository.delete(record.id)
        if (::source.isInitialized) source.delete()
        if (::oldOptions.isInitialized) ServiceLocator.playbackRepository.setOptions(true, oldOptions)
        ServiceLocator.playbackRepository.setSleepTimerDeadline(oldDeadline)
        Unit
    }

    private fun connect() {
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))).buildAsync()
        }
        controller = future!!.get(10, TimeUnit.SECONDS)
    }

    private fun await(timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) return
            Thread.sleep(50)
        }
        var diagnostic = "disconnected"
        instrumentation.runOnMainSync {
            controller?.let { diagnostic = "playing=${it.isPlaying}, state=${it.playbackState}, position=${it.currentPosition}" }
        }
        throw AssertionError("Condition not reached: $diagnostic")
    }

    @Test fun theDialogStartsAndClearsARealDeadline() {
        val before = System.currentTimeMillis()
        compose.onNodeWithTag("sleep-timer").performClick()
        compose.onNodeWithText("15 分钟后暂停").performClick()
        compose.waitForIdle()

        val deadline = ServiceLocator.playbackRepository.sleepTimerDeadline()
        assertTrue("Expected roughly 15 minutes ahead, got ${deadline - before} ms",
            deadline - before in 14 * 60_000L..16 * 60_000L)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("约剩", substring = true).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("sleep-timer").performClick()
        compose.onNodeWithText("关闭定时").performClick()
        compose.waitForIdle()
        assertEquals(SleepTimer.OFF, ServiceLocator.playbackRepository.sleepTimerDeadline())
        compose.waitUntil(5_000) { compose.onAllNodesWithText("约剩", substring = true).fetchSemanticsNodes().isEmpty() }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "SLEEP_TIMER_UI: deadline set ${deadline - before} ms ahead, then cleared from the dialog\n")
        })
    }

    @Test fun anElapsedDeadlinePausesPlaybackAndClearsItself() {
        var playing = false
        instrumentation.runOnMainSync { playing = controller!!.isPlaying }
        assertTrue("Playback must be running before the timer can stop it", playing)

        // A real, soon-to-expire deadline written through the same repository the dialog uses.
        ServiceLocator.playbackRepository.setSleepTimerDeadline(System.currentTimeMillis() + 3_000L)
        await(20_000) { !controller!!.isPlaying }
        assertEquals("The deadline must not be able to fire twice", SleepTimer.OFF,
            ServiceLocator.playbackRepository.sleepTimerDeadline())

        val finalState = StringBuilder()
        instrumentation.runOnMainSync {
            finalState.append("isPlaying=${controller!!.isPlaying}, position=${controller!!.currentPosition}, ")
        }
        finalState.append("deadline=${ServiceLocator.playbackRepository.sleepTimerDeadline()}")
        File(evidence, "summary.txt").writeText(
            "record=${record.id}\nsource=${source.name}\ndeadline=+3s\n$finalState\n",
        )
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "SLEEP_TIMER: real audio paused after its deadline and the deadline was cleared ($finalState)\n")
        })
    }
}
