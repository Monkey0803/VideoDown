package com.videodown.douyin

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.presentation.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The copy really follows the language of the app, not the language of the source code.
 *
 * Only strings that have already moved into the resource files are asserted; the migration is in
 * flight, so "no Chinese anywhere on this screen" is deliberately not claimed yet.
 *
 * One test covers every migrated screen with a single locale switch: each switch makes the system
 * recreate the activity, so asserting all screens in one pass keeps that recreation to a minimum.
 *
 * Note on the hangs seen while this suite was written: they were **not** caused by switching the
 * locale. An interrupted run had left a stale `com.videodown.douyin.debug.test` instrumentation
 * process on the device, and every later run then wedged behind it. `am force-stop` on the app and
 * test packages before re-running cleared it, after which the suite finishes in seconds.
 */
@RunWith(AndroidJUnit4::class)
class LocalizationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @After fun restoreChinese() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.getSystemService(LocaleManager::class.java)
                    ?.setApplicationLocales(LocaleList.forLanguageTags("zh-CN"))
            }
        }
    }

    /** Per-app locales arrived with Android 13; the older device follows the system language. */
    private fun switchTo(tag: String) {
        assumeTrue("Per-app locales need Android 13", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        // Setting the app locale makes the system recreate the activity on its own; the compose
        // rule does not allow driving `scenario.recreate()` itself.
        context.getSystemService(LocaleManager::class.java)
            ?.setApplicationLocales(LocaleList.forLanguageTags(tag))
        // Language-independent: the tab copy changes with the app locale.
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("tab-bar").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun openSettings() {
        compose.onNodeWithTag("tab-settings").performClick()
        compose.waitForIdle()
    }

    private fun openUpdateDialog() {
        openSettings()
        val alreadyOpen = countOf("当前版本：") + countOf("Installed: ")
        if (alreadyOpen > 0) return
        // The settings row itself is migrated, so its label depends on the current language.
        val label = if (compose.onAllNodesWithText("Check for updates").fetchSemanticsNodes().isNotEmpty()) "Check for updates" else "检查更新"
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun countOf(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test fun everyMigratedScreenFollowsTheAppLanguage() {
        switchTo("en-US")

        // Parse screen
        compose.waitUntil(20_000) { countOf("Parse a video") > 0 }
        assertTrue("the parse screen must not still be Chinese", countOf("解析视频") == 0)
        compose.onNodeWithText("Paste a link").assertExists()
        compose.onNodeWithText("Recently saved").assertExists()

        // My settings
        openSettings()
        compose.waitUntil(20_000) { countOf("My settings") > 0 }
        assertTrue(countOf("我的设置") == 0)
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithText("Wi-Fi only").assertExists()
        compose.onNodeWithText("Download speed limit").assertExists()

        // Update dialog: a string with a format argument follows the language too
        openUpdateDialog()
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithText("Installed: ", substring = true).assertExists()
        compose.onAllNodesWithText("完成").assertCountEquals(0)
        compose.onNodeWithText("Update source").assertExists()

        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "LOCALIZATION: parse screen, settings and update dialog all rendered English\n")
        })

        switchTo("zh-CN")
        openSettings()
        compose.waitUntil(20_000) { countOf("我的设置") > 0 }
        assertTrue("switching back must restore Chinese", countOf("Parse a video") == 0)
        assertTrue(countOf("My settings") == 0)
    }

    @Test fun bothLanguagesAreDeclaredSoTheSystemCanOfferThem() {
        val config = context.resources.getXml(com.videodown.douyin.R.xml.locales_config)
        val names = mutableListOf<String>()
        var event = config.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && config.name == "locale") {
                // The attribute is namespaced: android:name, not a bare name.
                config.getAttributeValue("http://schemas.android.com/apk/res/android", "name")?.let(names::add)
            }
            event = config.next()
        }
        assertTrue(names.toString(), names.contains("zh-CN"))
        assertTrue(names.toString(), names.contains("en"))
    }
}
