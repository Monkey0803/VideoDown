package com.videodown.douyin

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.player.PlayerScreen
import com.videodown.douyin.presentation.player.PlayerViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.anyOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real decoding and window rotation, using a synthetic clip and an isolated history store. */
@RunWith(AndroidJUnit4::class)
class PlayerLandscapeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val directory = File(context.cacheDir, "player-landscape-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = ViewModelStore()
    private val showingPlayer = mutableStateOf(true)
    private var originalOrientation = 0

    @Before fun setup() {
        val video = File(directory, "fixture.mp4")
        instrumentation.context.assets.open("landscape-fixture.mp4").use { source ->
            video.outputStream().use { source.copyTo(it) }
        }
        val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
        val record = DownloadRecord(id = "landscape", awemeId = "fixture", title = "横屏播放测试",
            fileName = video.name, filePath = video.absolutePath, status = DownloadStatus.COMPLETED)
        runBlocking { history.upsert(record) }
        compose.runOnUiThread {
            originalOrientation = compose.activity.requestedOrientation
            val model = PlayerViewModel(history)
            store.put("player", model)
            compose.activity.setContent {
                VideoDownTheme {
                    if (showingPlayer.value) {
                        PlayerScreen(record.id, onBack = { showingPlayer.value = false }, viewModel = model)
                    } else {
                        Text("已返回视频库")
                    }
                }
            }
        }
        waitForOrientation(Configuration.ORIENTATION_PORTRAIT)
        waitForPlayer { it.playbackState == Player.STATE_READY && it.videoSize.width == 320 }
    }

    @After fun cleanup() {
        compose.runOnUiThread { showingPlayer.value = false }
        compose.waitForIdle()
        compose.runOnUiThread { store.clear() }
        directory.deleteRecursively()
    }

    @Test fun landscapeVideoUsesItsOwnAspectRatioAndHasAVisibleFullscreenEntry() {
        compose.onNodeWithContentDescription("横屏播放").assertIsDisplayed()
        compose.runOnIdle {
            val view = playerView()!!
            assertEquals(16f / 9f, view.width.toFloat() / view.height, .04f)
        }
        saveScreenshot("inline-player.png")
    }

    @Test fun rotatingWhilePausedPreservesPositionAndReturnsToPortrait() {
        val originalPlayer = compose.runOnIdle { playerView()!!.player!! }
        compose.runOnUiThread {
            originalPlayer.pause()
            originalPlayer.seekTo(15_000)
        }
        waitForPlayer { !it.isPlaying && it.currentPosition >= 14_500 }
        compose.onNodeWithContentDescription("横屏播放").performClick()
        waitForOrientation(Configuration.ORIENTATION_LANDSCAPE)
        waitForSystemBars(false)
        compose.runOnIdle {
            assertSame(originalPlayer, playerView()!!.player)
            assertFalse(originalPlayer.playWhenReady)
            assertEquals(15_000L, originalPlayer.currentPosition)
            assertTrue(playerView()!!.width > playerView()!!.height)
        }
        saveScreenshot("landscape-player.png")
        compose.onNodeWithContentDescription("退出全屏").performClick()
        waitForOrientation(Configuration.ORIENTATION_PORTRAIT)
        waitForSystemBars(true)
        compose.runOnIdle {
            assertSame(originalPlayer, playerView()!!.player)
            assertFalse(originalPlayer.playWhenReady)
            assertEquals(15_000L, originalPlayer.currentPosition)
        }
        compose.onNodeWithContentDescription("横屏播放").assertIsDisplayed()
    }

    @Test fun nativeFullscreenControlAndSystemBackKeepPlaybackRunning() {
        val originalPlayer = compose.runOnIdle { playerView()!!.player!! }
        compose.runOnUiThread {
            originalPlayer.seekTo(10_000)
            playerView()!!.showController()
        }
        waitForPlayer { it.isPlaying && it.currentPosition >= 10_000 }
        Espresso.onView(allOf(anyOf(withId(androidx.media3.ui.R.id.exo_fullscreen),
            withId(androidx.media3.ui.R.id.exo_minimal_fullscreen)), isDisplayed())).perform(click())
        waitForOrientation(Configuration.ORIENTATION_LANDSCAPE)
        compose.runOnIdle {
            assertSame(originalPlayer, playerView()!!.player)
            assertTrue(originalPlayer.playWhenReady)
            assertTrue(originalPlayer.currentPosition >= 10_000)
        }
        Espresso.pressBack()
        waitForOrientation(Configuration.ORIENTATION_PORTRAIT)
        waitForSystemBars(true)
        waitForPlayer { it.isPlaying && it.currentPosition > 10_000 }
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
    }

    @Test fun leavingFullscreenRestoresWindowAndOrientationPolicy() {
        compose.onNodeWithContentDescription("横屏播放").performClick()
        waitForOrientation(Configuration.ORIENTATION_LANDSCAPE)
        waitForSystemBars(false)
        compose.runOnUiThread { showingPlayer.value = false }
        compose.onNodeWithText("已返回视频库").assertIsDisplayed()
        waitForSystemBars(true)
        compose.runOnIdle {
            assertEquals(originalOrientation, compose.activity.requestedOrientation)
            assertEquals(null, playerView())
        }
    }

    private fun waitForPlayer(predicate: (Player) -> Boolean) {
        compose.waitUntil(10_000) {
            var ready = false
            compose.runOnUiThread { ready = playerView()?.player?.let(predicate) == true }
            ready
        }
    }

    private fun waitForOrientation(orientation: Int) {
        compose.waitUntil(10_000) {
            var matches = false
            compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                matches = compose.activity.resources.configuration.orientation == orientation &&
                    (decor.width > decor.height) == (orientation == Configuration.ORIENTATION_LANDSCAPE)
            }
            matches
        }
        compose.waitForIdle()
    }

    private fun waitForSystemBars(visible: Boolean) {
        compose.waitUntil(5_000) {
            var matches = false
            compose.runOnUiThread {
                val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                matches = insets != null && insets.isVisible(WindowInsetsCompat.Type.statusBars()) == visible &&
                    insets.isVisible(WindowInsetsCompat.Type.navigationBars()) == visible
            }
            matches
        }
    }

    private fun playerView(): PlayerView? = findPlayerView(compose.activity.window.decorView)

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findPlayerView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun saveScreenshot(name: String) {
        compose.runOnUiThread {
            playerView()!!.apply {
                controllerShowTimeoutMs = 0
                showController()
            }
        }
        compose.waitUntil(5_000) {
            var visible = false
            compose.runOnUiThread { visible = playerView()?.isControllerFullyVisible == true }
            visible
        }
        val output = File(context.cacheDir, "player-ui-test-artifacts").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
