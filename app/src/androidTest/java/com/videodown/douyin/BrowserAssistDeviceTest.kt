package com.videodown.douyin

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrowserAssistDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dynamicPageRequiresPlayThenDetectSelectAndDownload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "browser-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        var sourcePage = ""
        try {
            runBlocking { createMediaFixtures(context, directory) }
            FixtureServer(directory).use { server ->
                sourcePage = server.baseUrl + "/dynamic"
                compose.onNode(hasSetTextAction()).performTextReplacement(sourcePage)
                compose.onNodeWithText("打开网页查找视频").performScrollTo().performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithText("使用网页登录态重新解析").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(20_000) {
                    var loaded = false
                    onView(isAssignableFrom(WebView::class.java)).check { view, _ -> loaded = (view as WebView).title == "Dynamic fixture" && view.progress == 100 }
                    loaded
                }
                screenshot("browser-before-play.png")
                // A native Espresso pointer click on the WebView's full-page play button.
                onView(isAssignableFrom(WebView::class.java)).perform(click())
                screenshot("browser-after-play.png")
                compose.onNodeWithContentDescription("查找视频").performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithText("MP4 ·", substring = true).fetchSemanticsNodes().isNotEmpty() }
                screenshot("browser-detected.png")
                compose.onNodeWithText("MP4 ·", substring = true).performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithText("解析结果").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("保存到本地").performClick()
                compose.waitUntil(20_000) {
                    runBlocking { com.videodown.douyin.core.ServiceLocator.historyRepository.snapshot().any {
                        it.sourcePageUrl == sourcePage && it.status == com.videodown.douyin.domain.model.DownloadStatus.COMPLETED
                    } }
                }
                screenshot("browser-downloaded.png")
                compose.onAllNodesWithContentDescription("播放").onFirst().performClick()
                compose.waitUntil(15_000) {
                    var playing = false
                    compose.runOnUiThread {
                        fun find(view: android.view.View): androidx.media3.ui.PlayerView? {
                            if (view is androidx.media3.ui.PlayerView) return view
                            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                            return null
                        }
                        val player = find(compose.activity.window.decorView)?.player
                        playing = player?.isPlaying == true && player.currentPosition > 100 &&
                            player.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO } &&
                            player.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                    }
                    playing
                }
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("下载任务").assertDoesNotExist() }.isSuccess
                }
                compose.waitForIdle()
                screenshot("browser-player.png")
            }
        } finally {
            runBlocking {
                com.videodown.douyin.core.ServiceLocator.historyRepository.snapshot().filter { sourcePage.isNotEmpty() && it.sourcePageUrl == sourcePage }
                    .forEach { com.videodown.douyin.core.ServiceLocator.downloader.cancel(it.id, true) }
            }
            directory.deleteRecursively()
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "generic-ui-artifacts").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
