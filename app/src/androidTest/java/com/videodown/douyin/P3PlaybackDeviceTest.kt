package com.videodown.douyin

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.playback.PlaybackPreferences
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.player.*
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class P3PlaybackDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var directory: File
    private lateinit var history: DownloadHistoryRepositoryImpl
    private lateinit var preferences: PlaybackPreferences
    private lateinit var preferenceName: String
    private val models = ViewModelStore()
    @Before fun setup() {
        directory = File(context.cacheDir, "p3-playback-${UUID.randomUUID()}").apply { mkdirs() }
        history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
        preferenceName = directory.name
        preferences = PlaybackPreferences(context, preferenceName)
    }
    @After fun close() {
        compose.runOnUiThread { compose.activity.setContent { Text("已关闭") }; models.clear() }
        compose.waitForIdle()
        context.getSharedPreferences(preferenceName, 0).edit().clear().commit()
        directory.deleteRecursively()
    }
    private fun record(audio: Boolean = false): DownloadRecord = runBlocking {
        val asset = if (audio) "audio-fixture.m4a" else "landscape-fixture.mp4"
        val file = File(directory, asset)
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        DownloadRecord(if (audio) "audio" else "video", "media", "P3 播放验证", filePath = file.path, fileName = file.name,
            status = DownloadStatus.COMPLETED, media = if (audio) MediaDownload(audioOnly = true) else null).also { history.upsert(it) }
    }
    private fun show(record: DownloadRecord, retainedModel: PlayerViewModel? = null): PlayerViewModel {
        val model = retainedModel ?: PlayerViewModel(history, PlaybackPreferences(context, preferenceName))
        compose.runOnUiThread {
            models.put("player-${UUID.randomUUID()}", model)
            compose.activity.setContent { VideoDownTheme { PlayerScreen(record.id, {}, model) } }
        }
        waitForPlayer { it.playbackState == Player.STATE_READY }
        return model
    }
    private fun find(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun player() = checkNotNull(find(compose.activity.window.decorView)?.player)
    private fun waitForPlayer(predicate: (Player) -> Boolean) {
        compose.waitUntil(10000) {
            var ready = false
            compose.runOnUiThread { find(compose.activity.window.decorView)?.player?.let { ready = predicate(it) } }
            ready
        }
    }
    private fun leave() {
        compose.runOnUiThread { compose.activity.setContent { Text("已关闭播放器") } }
        compose.waitForIdle()
    }
    @Test fun leavingAndNewViewModelResumeRealDecodedPosition() {
        val record = record(); show(record)
        compose.runOnUiThread { player().pause(); player().seekTo(8000) }
        waitForPlayer { it.currentPosition >= 8000 }
        leave()
        val file = File(record.filePath!!)
        assertEquals(8000L, PlaybackPreferences(context, preferenceName).checkpoint(record.id, file.length(), file.lastModified()).resumePosition())
        show(record)
        waitForPlayer { it.currentPosition in 8000..9500 }
    }
    @Test fun retainedViewModelSurvivesActualActivityRecreationWithCurrentPosition() {
        val record = record(); val model = show(record)
        compose.runOnUiThread { player().pause(); player().seekTo(12000) }
        waitForPlayer { it.currentPosition >= 12000 }
        compose.activityRule.scenario.recreate()
        show(record, model)
        waitForPlayer { it.currentPosition in 12000..13500 }
    }
    @Test fun actualSpeedAndLoopControlsApplyAndKeepAudioVideoPreferencesSeparate() {
        val video = record(); show(video)
        compose.onNodeWithText("倍速 1.0×").performScrollTo().performClick()
        compose.onNodeWithText("1.5×", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("循环：关闭").performScrollTo().performClick()
        waitForPlayer { it.playbackParameters.speed == 1.5f && it.repeatMode == Player.REPEAT_MODE_ONE }
        val reopened = PlaybackPreferences(context, preferenceName)
        assertEquals(PlaybackOptions(1.5f, true), reopened.options(false))
        assertEquals(PlaybackOptions(), reopened.options(true))
        leave(); val audio = record(true); show(audio)
        compose.onNodeWithText("倍速 1.0×").performScrollTo().performClick()
        compose.onNodeWithText("0.75×", substring = false).performClick()
        compose.onNodeWithText("循环：关闭").performScrollTo().performClick()
        waitForPlayer { it.playbackParameters.speed == 0.75f && it.repeatMode == Player.REPEAT_MODE_ONE }
        compose.runOnUiThread { player().seekTo(player().duration - 100); player().play() }
        waitForPlayer { it.currentPosition in 0..1000 && it.isPlaying }
        assertEquals(PlaybackOptions(1.5f, true), reopened.options(false))
        assertEquals(PlaybackOptions(0.75f, true), reopened.options(true))
    }
    @Test fun landscapeSpeedDialogCanScrollToMaximumSpeed() {
        val video = record(); show(video)
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        compose.onNodeWithText("倍速 1.0×").performScrollTo().performClick()
        compose.onNodeWithText("4.0×", substring = false).performScrollTo().performClick()
        waitForPlayer { it.playbackParameters.speed == 4f }
    }
    @Test fun portraitSpeedOptionsAndPlayPauseIconsFollowActualPlayer() {
        val video = record(); show(video)
        waitForPlayer { it.isPlaying }
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
        compose.onNodeWithText("暂停", substring = false).performClick()
        waitForPlayer { !it.isPlaying }
        compose.onNodeWithContentDescription("播放").assertIsDisplayed()
        compose.onNodeWithText("播放", substring = false).performClick()
        waitForPlayer { it.isPlaying }
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
        compose.onNodeWithText("倍速 1.0×").performScrollTo().performClick()
        compose.onNodeWithText("2.0×", substring = false).assertIsDisplayed().performClick()
        waitForPlayer { it.playbackParameters.speed == 2f }
        compose.onNodeWithText("倍速 2.0×").performClick()
        compose.onNodeWithText("4.0×", substring = false).assertIsDisplayed().performClick()
        waitForPlayer { it.playbackParameters.speed == 4f }
        assertEquals(4f, PlaybackPreferences(context, preferenceName).options(false).speed)
    }
    @Test fun completedPlaybackReopensAtBeginning() {
        val record = record(); show(record)
        compose.runOnUiThread { player().seekTo(player().duration - 100); player().play() }
        waitForPlayer { it.playbackState == Player.STATE_ENDED }
        leave()
        val file = File(record.filePath!!)
        assertEquals(0L, preferences.checkpoint(record.id, file.length(), file.lastModified()).resumePosition())
        show(record); waitForPlayer { it.currentPosition in 0..1000 }
    }
    @Test fun replacementFileInvalidatesOldCheckpointWithoutChangingOptions() {
        val record = record(); val file = File(record.filePath!!)
        preferences.saveCheckpoint(record.id, file.length(), file.lastModified(), PlaybackCheckpoint(8000, 60000))
        preferences.setOptions(false, PlaybackOptions(2f, true))
        file.appendBytes(byteArrayOf(0))
        val reopened = PlaybackPreferences(context, preferenceName)
        assertEquals(0L, reopened.checkpoint(record.id, file.length(), file.lastModified()).resumePosition())
        assertEquals(PlaybackOptions(2f, true), reopened.options(false))
    }
}
