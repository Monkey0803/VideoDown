package com.videodown.douyin

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.espresso.Espresso
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.core.PreferredQuality
import com.videodown.douyin.core.ServiceLocator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run in both system night modes to exercise the actual themed destinations. */
@RunWith(AndroidJUnit4::class)
class Material3NavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun appAndPlayerResourcesStayChineseAfterRecreation() {
        fun assertLanguage() {
            assertEquals("zh-CN", compose.activity.resources.configuration.locales[0].toLanguageTag())
            assertEquals("速度", compose.activity.getString(androidx.media3.ui.R.string.exo_controls_playback_speed))
            assertEquals("播放", compose.activity.getString(androidx.media3.ui.R.string.exo_controls_play_description))
        }
        assertLanguage()
        compose.activityRule.scenario.recreate()
        assertLanguage()
        compose.onNodeWithText("拾影").assertIsDisplayed()
        compose.onNodeWithText("解析视频").assertIsDisplayed()
        compose.onNodeWithText("任务").performClick()
        compose.onNodeWithText("下载任务").assertIsDisplayed()
        compose.onNodeWithText("已存").performClick()
        compose.onNodeWithText("已存视频").assertIsDisplayed()
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("我的设置").assertIsDisplayed()
        compose.onNodeWithText("外观模式").assertIsDisplayed()
    }

    @Test fun navigationFiltersAndDialogsRemainUsable() {
        compose.onNodeWithText("拾影").assertIsDisplayed()
        screenshot("home")
        compose.onNodeWithText("任务").performClick()
        compose.onNodeWithText("下载任务").assertIsDisplayed()
        compose.onNodeWithText("全部").assertIsSelected()
        compose.onNode(hasText("已完成") and hasClickAction()).performClick().assertIsSelected()
        screenshot("tasks")
        compose.onNodeWithText("已存").performClick()
        compose.onNodeWithText("已存视频").assertIsDisplayed()
        screenshot("library")
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("默认画质").assertIsDisplayed().performClick()
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        screenshot("settings")
        compose.onNodeWithText("解析").performClick()
        compose.onNodeWithText("拾影").assertIsDisplayed()
    }

    @Test fun qualityChangesAreSavedOnlyOnConfirmation() {
        val preferences = ServiceLocator.preferences
        val original = preferences.preferredQuality
        try {
            preferences.preferredQuality = PreferredQuality.HIGHEST
            compose.onNodeWithText("我的").performClick()
            compose.onNodeWithText("默认画质").performClick()
            qualityOption("最高画质").assertIsSelected()
            qualityOption("节省空间").performClick().assertIsSelected()
            assertEquals(PreferredQuality.HIGHEST, preferences.preferredQuality)
            compose.onNodeWithText("取消").performClick()

            compose.onNodeWithText("默认画质").performClick()
            qualityOption("最高画质").assertIsSelected()
            qualityOption("标准画质").performClick()
            Espresso.pressBack()
            assertEquals(PreferredQuality.HIGHEST, preferences.preferredQuality)

            compose.onNodeWithText("默认画质").performClick()
            qualityOption("最高画质").assertIsSelected()
            qualityOption("节省空间").performClick()
            screenshot("quality-dialog")
            compose.onNodeWithText("确定").performClick()
            assertEquals(PreferredQuality.SAVER, preferences.preferredQuality)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("默认画质").performClick()
            qualityOption("节省空间").assertIsSelected()
            compose.onNodeWithText("取消").performClick()
        } finally {
            preferences.preferredQuality = original
        }
    }

    private fun qualityOption(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
    )

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val mode = instrumentation.targetContext.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "material3").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$mode-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
