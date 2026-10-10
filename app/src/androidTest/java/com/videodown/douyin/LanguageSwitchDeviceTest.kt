package com.videodown.douyin

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.presentation.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「我的 → 外观 → 语言」在两种系统上各自该有的样子：
 *
 * - Android 13 及以上：真的可以在应用内切换，选择写进系统的「按应用语言」，界面语言当场跟着变；
 * - Android 12 及以下：系统没有这个能力，弹窗只解释并给出去系统设置的入口，不给可选项。
 *
 * 两条路径写在一个用例里按 `SDK_INT` 分支，而不是用 `assumeTrue` 跳过另一条：这里两条路径各自
 * 都有真实断言，报告的 tests/failures 不会被「假定跳过」搅浑——本工具链会把
 * `AssumptionViolatedException` 写进 XML 的 failure 计数里（任务本身仍算通过，见
 * docs/validation/lint-cleanup-2026-10-10.md 的复核）。
 *
 * 用真实的 MainActivity 而不是替身界面：切换语言会让系统重建 Activity，替身内容会被冲掉。
 */
@RunWith(AndroidJUnit4::class)
class LanguageSwitchDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun restoreChinese() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.getSystemService(LocaleManager::class.java)
                    ?.setApplicationLocales(LocaleList.forLanguageTags("zh-CN"))
            }
        }
    }

    /** 从底部标签进设置页，等界面空闲。 */
    private fun openSettings() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("tab-bar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-settings").performClick()
        compose.waitForIdle()
    }

    /** 系统里保存的按应用语言；Android 12 及以下没有这个概念。 */
    private fun appLocaleTag(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return context.getSystemService(LocaleManager::class.java)?.applicationLocales
            ?.takeIf { !it.isEmpty }?.toLanguageTags()
    }

    @Test fun theLanguageRowOffersWhatThisAndroidVersionSupports() {
        openSettings()
        compose.onNodeWithText("语言").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            compose.onNodeWithText("English").performClick()
            compose.onNodeWithText("确定").performClick()
            // 切换语言会让系统重建 Activity；等底部标签回来后重新进设置页，断言整屏是英文。
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("tab-bar").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("tab-settings").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Language").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Appearance mode").assertIsDisplayed()
            compose.onNodeWithText("English").assertIsDisplayed()
            assertTrue("英文界面下不应再有中文文案", compose.onAllNodesWithText("外观模式").fetchSemanticsNodes().isEmpty())
            assertEquals("en", appLocaleTag())
        } else {
            compose.onNodeWithText(context.getString(R.string.settings_language_unsupported)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.action_open_settings)).assertIsDisplayed()
            assertTrue("没有按应用语言就不该给出可选项", compose.onAllNodesWithText("简体中文").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
            compose.waitForIdle()
            assertTrue(
                "取消后弹窗应关闭",
                compose.onAllNodesWithText(context.getString(R.string.settings_language_unsupported)).fetchSemanticsNodes().isEmpty(),
            )
            // 行本身（标题 + 值两个文本在同一个可点击行里合并成一个节点）。
            compose.onNode(
                hasText(context.getString(R.string.settings_language_title)) and
                    hasText(context.getString(R.string.settings_language_system)),
            ).assertIsDisplayed()
            assertEquals(null, appLocaleTag())
        }
    }
}
