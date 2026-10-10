package com.videodown.douyin

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * A subtitle file dropped next to a downloaded video becomes a real text track with a toggle.
 *
 * The track itself is provided by Media3; what is asserted here is that 拾影 finds the sidecar,
 * offers the toggle only when a text track exists, and remembers the choice.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleSidecarDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var originalSubtitles = true
    private lateinit var source: File
    private lateinit var record: DownloadRecord

    private fun seedVideo(withSubtitles: Boolean) {
        val base = "subtitle-${UUID.randomUUID()}"
        source = File(ServiceLocator.downloadPaths.downloadDir, "$base.mp4")
        instrumentation.context.assets.open("landscape-fixture.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        if (withSubtitles) {
            File(source.parentFile, "$base.srt").writeText(
                "1\n00:00:00,000 --> 00:00:05,000\n测试字幕\n\n2\n00:00:05,000 --> 00:00:10,000\n第二句\n",
            )
        }
        record = DownloadRecord(source.name, "video", "字幕验证", fileName = source.name, filePath = source.path,
            status = DownloadStatus.COMPLETED, resolutionLabel = "320P")
        runBlocking { ServiceLocator.historyRepository.upsert(record) }
        compose.runOnUiThread {
            val model = PlayerViewModel()
            compose.activity.setContent { VideoDownTheme { PlayerScreen(record.id, {}, model) } }
        }
        compose.waitForIdle()
    }

    @Before fun setup() {
        originalSubtitles = ServiceLocator.preferences.subtitlesEnabled
        ServiceLocator.preferences.subtitlesEnabled = true
    }

    @After fun cleanup() {
        ServiceLocator.preferences.subtitlesEnabled = originalSubtitles
        if (::record.isInitialized) runBlocking { ServiceLocator.historyRepository.delete(record.id) }
        if (::source.isInitialized) {
            File(source.parentFile, source.name.substringBeforeLast('.') + ".srt").delete()
            source.delete()
        }
    }

    @Test fun aSidecarSubtitleBecomesATrackWithAToggleThatPersists() {
        seedVideo(withSubtitles = true)
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("subtitles-toggle").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("字幕：开启").assertExists()

        compose.onNodeWithTag("subtitles-toggle").performClick()
        compose.waitForIdle()
        assertEquals("The choice must be stored", false, ServiceLocator.preferences.subtitlesEnabled)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("字幕：关闭").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("字幕 关").assertExists()

        compose.onNodeWithTag("subtitles-toggle").performClick()
        compose.waitForIdle()
        assertTrue(ServiceLocator.preferences.subtitlesEnabled)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("字幕：开启").fetchSemanticsNodes().isNotEmpty() }

        File(context.cacheDir, "subtitle-evidence").apply { mkdirs() }.let { evidence ->
            File(evidence, "summary.txt").writeText(
                "sidecar=${source.name.substringBeforeLast('.')}.srt\ntoggle shown=true\n" +
                    "off persisted=false, on persisted=true\n",
            )
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "SUBTITLES: sidecar discovered, toggle shown and persisted both ways\n")
        })
    }

    @Test fun aVideoWithoutSubtitlesOffersNoToggle() {
        seedVideo(withSubtitles = false)
        // Give Media3 time to report its tracks; the toggle must still never appear.
        compose.waitForIdle()
        Thread.sleep(2_000)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("subtitles-toggle").fetchSemanticsNodes().size)
    }
}
