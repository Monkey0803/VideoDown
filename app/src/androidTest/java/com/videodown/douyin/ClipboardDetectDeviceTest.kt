package com.videodown.douyin

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.presentation.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Actual clipboard contents, the real focus path 拾影 uses to read them, and the confirmation
 * banner. Live parsing is deliberately not required: the copied text reaching the input field is
 * what these cases assert, so a network failure cannot be mistaken for a clipboard failure.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardDetectDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @After fun restore() {
        ServiceLocator.preferences.clipboardDetect = true
        ServiceLocator.preferences.lastClipboardOffer = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching { clipboard.clearPrimaryClip() }
        }
    }

    private fun copy(text: String) {
        instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("copied", text)) }
    }

    private fun bannerNodes() = compose.onAllNodesWithTag("clipboard-offer").fetchSemanticsNodes()

    /** Loses and regains window focus, which is the only moment the app may read the clipboard. */
    private fun refocus(scenario: ActivityScenario<MainActivity>) {
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test fun copiedLinkIsOfferedAndReachesTheInputOnlyAfterConfirmation() {
        val slug = UUID.randomUUID().toString().take(8)
        ServiceLocator.preferences.clipboardDetect = true
        ServiceLocator.preferences.lastClipboardOffer = null
        copy("7.32 复制打开抖音，看看【某某的作品】 https://v.douyin.com/$slug/ 复制此链接")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { bannerNodes().isNotEmpty() }
            compose.onNodeWithTag("clipboard-offer-parse").assertIsDisplayed()
            // Nothing is parsed until the banner is confirmed.
            assertEquals(0, compose.onAllNodesWithText(slug, substring = true).fetchSemanticsNodes().size)
            compose.onNodeWithTag("clipboard-offer-parse").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(slug, substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, bannerNodes().size)
            // Confirming consumes the offer; only 忽略 records a dismissal.
            assertNull(ServiceLocator.preferences.lastClipboardOffer)
            refocus(scenario)
            assertTrue("A confirmed offer must not come back", bannerNodes().isEmpty())
        }
    }

    @Test fun dismissedLinkIsNeverOfferedAgain() {
        val slug = UUID.randomUUID().toString().take(8)
        val url = "https://m.weibo.cn/status/$slug"
        ServiceLocator.preferences.clipboardDetect = true
        ServiceLocator.preferences.lastClipboardOffer = null
        copy("分享一个微博视频 $url")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { bannerNodes().isNotEmpty() }
            compose.onNodeWithTag("clipboard-offer-dismiss").performClick()
            compose.waitUntil(5_000) { bannerNodes().isEmpty() }
            assertEquals(url, ServiceLocator.preferences.lastClipboardOffer)
            refocus(scenario)
            refocus(scenario)
            assertTrue("A dismissed link must stay dismissed across focus changes", bannerNodes().isEmpty())
        }
    }

    @Test fun aPlainWebLinkIsNeverOffered() {
        ServiceLocator.preferences.clipboardDetect = true
        ServiceLocator.preferences.lastClipboardOffer = null
        copy("https://example.com/${UUID.randomUUID()}.mp4")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            refocus(scenario)
            assertTrue("A generic web link must not raise an unrequested prompt", bannerNodes().isEmpty())
        }
    }

    @Test fun turningThePreferenceOffStopsReadingTheClipboard() {
        val slug = UUID.randomUUID().toString().take(8)
        ServiceLocator.preferences.clipboardDetect = false
        ServiceLocator.preferences.lastClipboardOffer = null
        copy("https://v.kuaishou.com/$slug")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            refocus(scenario)
            assertTrue("The clipboard must not be read while the preference is off", bannerNodes().isEmpty())
            // Turning it back on proves the gate, rather than a broken reader.
            instrumentation.runOnMainSync { ServiceLocator.preferences.clipboardDetect = true }
            refocus(scenario)
            compose.waitUntil(10_000) { bannerNodes().isNotEmpty() }
        }
    }
}
