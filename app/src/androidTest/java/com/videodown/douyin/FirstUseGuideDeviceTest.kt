package com.videodown.douyin

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.home.HomeScreen
import com.videodown.douyin.presentation.theme.VideoDownTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FirstUseGuideDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private var previousDismissed = false
    private var previousClipboardDetect = false

    @Before fun prepare() {
        previousDismissed = ServiceLocator.preferences.firstUseGuideDismissed
        previousClipboardDetect = ServiceLocator.preferences.clipboardDetect
        ServiceLocator.preferences.firstUseGuideDismissed = false
        ServiceLocator.preferences.clipboardDetect = false
        showHome()
    }

    @After fun restore() {
        ServiceLocator.preferences.firstUseGuideDismissed = previousDismissed
        ServiceLocator.preferences.clipboardDetect = previousClipboardDetect
    }

    @Test fun dismissingTheGuidePersistsAcrossRecomposition() {
        compose.onNodeWithTag("first-use-guide").assertIsDisplayed()
        compose.onNodeWithTag("site-kubb-card").assertIsDisplayed()
        compose.onAllNodesWithText("KUBB.TV").assertCountEquals(0)
        compose.onNodeWithTag("first-use-guide-dismiss").assertIsDisplayed().performClick()
        compose.onNodeWithTag("first-use-guide").assertDoesNotExist()
        compose.onNodeWithText("KUBB.TV").assertIsDisplayed()

        showHome()
        compose.onNodeWithTag("first-use-guide").assertDoesNotExist()
    }

    private fun showHome() {
        compose.runOnUiThread {
            compose.activity.setContent {
                VideoDownTheme {
                    HomeScreen(null, {}, {}, {}, {}, emptyList())
                }
            }
        }
    }
}
