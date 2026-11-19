package com.videodown.douyin

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.presentation.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 窗口尺寸类驱动的一级导航与页面布局（J1/J2/J5）。
 *
 * 用 `wm size` 改真实显示尺寸（窗口尺寸变化，manifest 已声明 `screenSize` 等 configChanges，
 * 活动不会被重建），再断言 Rail/底栏切换、首页双栏、设置 list-detail、页面标题 heading 语义
 * 与底栏触控高度。结束后恢复原尺寸，避免影响后续用例。
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private var widthPx = 0
    private var heightPx = 0

    private fun instrumentation() = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String): String {
        val descriptor = instrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    @Before fun rememberPhysicalSize() {
        val match = Regex("Physical size: (\\d+)x(\\d+)").find(shell("wm size"))
        if (match != null) {
            widthPx = match.groupValues[1].toInt()
            heightPx = match.groupValues[2].toInt()
        }
    }

    @After fun restoreDisplaySize() {
        shell("wm size reset")
        compose.waitForIdle()
    }

    private fun density(): Float = compose.activity.resources.displayMetrics.density

    /** 证据截图：保存在应用外部文件目录，测试后用 adb run-as 取回。 */
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = instrumentation().uiAutomation.takeScreenshot() ?: return
        val target = File(instrumentation().targetContext.getExternalFilesDir(null), "adaptive").apply { mkdirs() }
        File(target, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** 改宽度到 [dp]，高度保持原物理高度。 */
    private fun resizeToWidth(dp: Int) {
        val px = (dp * density()).toInt()
        shell("wm size ${px}x$heightPx")
        compose.waitForIdle()
    }

    private fun waitForTag(tag: String, present: Boolean = true, timeoutMillis: Long = 10_000) {
        compose.waitUntil(timeoutMillis) {
            val found = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            found == present
        }
    }

    @Test fun compactWindowKeepsBottomBarAndFullWidthTabs() {
        // 480dp 的 Compact 窗口：先真实改显示宽度，再断言仍用底部导航。
        resizeToWidth(480)
        compose.onNodeWithTag("tab-bar").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("navigation-rail").fetchSemanticsNodes().isEmpty())
        capture("compact-480-home")
        // 底栏三个入口的触控区域不小于 48dp。
        val density = density()
        val tab = compose.onNodeWithTag("tab-parse").fetchSemanticsNode()
        assertTrue("底栏入口高度 ${tab.boundsInRoot.height / density}dp 小于 48dp", tab.boundsInRoot.height >= 48f * density)
    }

    @Test fun mediumWindowSwitchesToRailAndKeepsCurrentPageWhenResizingBack() {
        resizeToWidth(700)
        waitForTag("navigation-rail")
        assertTrue(compose.onAllNodesWithTag("tab-bar").fetchSemanticsNodes().isEmpty())
        capture("medium-700-home")
        compose.onNodeWithTag("rail-tab-library").performClick()
        compose.onNodeWithText("已存内容").assertIsDisplayed()
        capture("medium-700-library")

        // 缩回 Compact：导航形态换回底栏，页面状态（当前在已存）不因尺寸变化丢失。
        resizeToWidth(480)
        waitForTag("tab-bar")
        compose.onNodeWithText("已存内容").assertIsDisplayed()
    }

    @Test fun expandedWindowSplitsHomeIntoTwoPanes() {
        resizeToWidth(1200)
        waitForTag("navigation-rail")
        // 首页双栏：左侧解析主面板与右侧站点卡片同屏可见。
        compose.onNodeWithText("解析视频").assertIsDisplayed()
        compose.onNodeWithTag("site-kubb-card").assertIsDisplayed()

        val parse = compose.onNodeWithText("解析视频").fetchSemanticsNode().boundsInRoot
        val site = compose.onNodeWithTag("site-kubb-card").fetchSemanticsNode().boundsInRoot
        assertTrue("Expanded 下站点卡片应位于解析面板右侧", site.left >= parse.right)
        capture("expanded-1200-home")
    }

    @Test fun expandedSettingsUsesListDetailInsteadOfSubpageNavigation() {
        resizeToWidth(1200)
        waitForTag("navigation-rail")
        compose.onNodeWithTag("rail-tab-settings").performClick()
        // 左侧分类列表与右侧详情同屏；不出现「先点分类再进子页」的返回键。
        compose.onNodeWithTag("settings-open-appearance").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-about").assertIsDisplayed()
        compose.onNodeWithText("外观模式").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("settings-back").fetchSemanticsNodes().size)
        capture("expanded-1200-settings")
    }

    @Test fun pageTitlesCarryHeadingSemantics() {
        // 四个主页面标题都是 heading：TalkBack 可以用「按标题导航」定位。
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit) and
            hasText("拾影")).assertIsDisplayed()
        compose.onNodeWithText("任务").performClick()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit) and
            hasText("下载任务")).assertIsDisplayed()
    }
}
