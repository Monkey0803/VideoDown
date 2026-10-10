package com.videodown.douyin

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppPreferences
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.ThemeMode
import com.videodown.douyin.presentation.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppearanceModeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun appearanceSwitchesImmediatelyPersistsAndCanFollowSystem() {
        val preferences = ServiceLocator.preferences
        val originalMode = preferences.themeMode
        val originalSystemMode = shell("cmd uimode night").substringAfter(": ").trim()
        try {
            preferences.themeMode = ThemeMode.SYSTEM
            shell("cmd uimode night no")
            compose.onNodeWithText("我的").performClick()
            assertAppearance(dark = false)

            openModeDialog()
            option(ThemeMode.SYSTEM).assertIsSelected()
            option(ThemeMode.DARK).performClick()
            compose.onNodeWithText("取消").performClick()
            assertEquals(ThemeMode.SYSTEM, preferences.themeMode)
            assertAppearance(dark = false)

            selectMode(ThemeMode.DARK)
            assertAppearance(dark = true, screenshotName = "appearance-dark")
            selectMode(ThemeMode.LIGHT)
            assertAppearance(dark = false, screenshotName = "appearance-light")
            assertEquals(ThemeMode.LIGHT, AppPreferences(instrumentation.targetContext).themeMode)

            shell("cmd uimode night yes")
            assertAppearance(dark = false)
            compose.activityRule.scenario.recreate()
            assertAppearance(dark = false)
            openModeDialog()
            option(ThemeMode.LIGHT).assertIsSelected()
            compose.onNodeWithText("取消").performClick()

            selectMode(ThemeMode.SYSTEM)
            assertAppearance(dark = true)
            shell("cmd uimode night no")
            assertAppearance(dark = false)
        } finally {
            preferences.themeMode = originalMode
            shell("cmd uimode night $originalSystemMode")
        }
    }

    private fun openModeDialog() {
        compose.onNode(hasText("外观模式") and hasClickAction()).performScrollTo().performClick()
    }

    private fun option(mode: ThemeMode) = compose.onNode(
        hasText(instrumentation.targetContext.getString(com.videodown.douyin.presentation.themeModeLabelRes(mode))) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
    )

    private fun selectMode(mode: ThemeMode) {
        openModeDialog()
        option(mode).performClick()
        compose.onNodeWithText("确定").performClick()
        compose.waitForIdle()
        assertEquals(mode, ServiceLocator.preferences.themeMode)
    }

    private fun assertAppearance(dark: Boolean, screenshotName: String? = null) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val bars = WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
            assertEquals(!dark, bars.isAppearanceLightStatusBars)
            assertEquals(!dark, bars.isAppearanceLightNavigationBars)
        }
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            val luminance = Color(bitmap.getPixel(2, bitmap.height / 2)).luminance()
            assertTrue("Expected dark=$dark, background luminance=$luminance",
                if (dark) luminance < 0.1f else luminance > 0.8f)
            if (screenshotName != null) {
                val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "material3").apply { mkdirs() }
                File(directory, "$screenshotName.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
