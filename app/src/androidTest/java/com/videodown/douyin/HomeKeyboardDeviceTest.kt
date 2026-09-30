package com.videodown.douyin

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.presentation.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the actual activity and software keyboard; input contains no downloadable link. */
@RunWith(AndroidJUnit4::class)
class HomeKeyboardDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = "键盘避让测试\n多行分享文案\n不包含下载链接"

    @Test fun keyboardAvoidanceKeepsInputAndSubmitVisibleAndDoneClearsFocus() {
        openKeyboard()
        compose.waitUntil(5_000) {
            val input = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInWindow
            val submit = compose.onNodeWithText("立即解析").fetchSemanticsNode().boundsInWindow
            val visible = visibleContentBounds()
            input.top >= visible.first && submit.bottom <= visible.second
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithText("立即解析").assertIsDisplayed()
        compose.onNodeWithText("已存").assertDoesNotExist()
        saveScreenshot("keyboard-visible.png")

        compose.onNode(hasSetTextAction()).performImeAction()
        waitForKeyboard(false)
        compose.onNode(hasSetTextAction()).assertIsNotFocused().assertTextContains(fixture)
        compose.onNodeWithText("已存").assertIsDisplayed()
        saveScreenshot("keyboard-dismissed.png")
    }

    @Test fun parseButtonDismissesKeyboardEvenWhenTheInputCannotBeParsed() {
        openKeyboard()
        compose.onNodeWithText("立即解析").performTouchInput { click() }
        waitForKeyboard(false)
        compose.onNode(hasSetTextAction()).assertIsNotFocused().assertTextContains(fixture)
        compose.onNodeWithText("已存").assertIsDisplayed()
    }

    @Test fun tappingNonInteractiveContentDismissesKeyboardWithoutClearingInput() {
        openKeyboard()
        compose.onNodeWithText("解析视频").performTouchInput { click() }
        waitForKeyboard(false)
        compose.onNode(hasSetTextAction()).assertIsNotFocused().assertTextContains(fixture)
    }

    @Test fun leavingHomeAndReturningDoesNotRestoreTheKeyboardOrFocus() {
        openKeyboard()
        compose.onNode(hasSetTextAction()).performImeAction()
        waitForKeyboard(false)
        compose.onNodeWithText("我的").performClick()
        waitForKeyboard(false)
        compose.onNodeWithText("下载偏好").assertIsDisplayed()
        // NavigationBar merges and animates its label semantics while the IME closes.
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("解析", substring = false, useUnmergedTree = true)
                .assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithText("解析", substring = false, useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).assertIsNotFocused().assertTextContains(fixture)
        waitForKeyboard(false)
    }

    @Test fun systemBackDismissesKeyboardAndTheInputCanBeFocusedAgain() {
        openKeyboard()
        Espresso.pressBack()
        waitForKeyboard(false)
        compose.onNode(hasSetTextAction()).assertIsNotFocused().assertTextContains(fixture)
        compose.onNode(hasSetTextAction()).performTouchInput { click() }
        waitForKeyboard(true)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNode(hasSetTextAction()).performImeAction()
        waitForKeyboard(false)
    }

    private fun openKeyboard() {
        compose.onNode(hasSetTextAction()).performScrollTo().performTouchInput { click() }
        compose.onNode(hasSetTextAction()).performTextReplacement(fixture)
        waitForKeyboard(true)
        compose.waitForIdle()
    }

    private fun waitForKeyboard(visible: Boolean) {
        compose.waitUntil(5_000) {
            var matches = false
            compose.runOnUiThread {
                val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                matches = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                    if (visible) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
                    else insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0
            }
            matches
        }
        compose.waitForIdle()
    }

    private fun visibleContentBounds(): Pair<Float, Float> {
        var bounds = 0f to 0f
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
            bounds = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top.toFloat() to
                (decor.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom).toFloat()
        }
        return bounds
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.cacheDir, "keyboard-ui-test-artifacts").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
