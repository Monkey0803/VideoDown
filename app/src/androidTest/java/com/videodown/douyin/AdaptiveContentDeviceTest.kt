package com.videodown.douyin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.presentation.components.AdaptivePage
import com.videodown.douyin.presentation.theme.VideoDownTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeWindowKeepsPrimaryContentAtReadableWidth() {
        compose.setContent {
            VideoDownTheme {
                Box(Modifier.requiredSize(1200.dp, 800.dp)) {
                    AdaptivePage {
                        Box(Modifier.fillMaxSize().testTag("adaptive-content"))
                    }
                }
            }
        }
        val width = compose.onNodeWithTag("adaptive-content").fetchSemanticsNode().boundsInRoot.width
        val maxWidthPx = 840f * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("大屏内容宽度应限制在 840dp，实际 ${width}px", width <= maxWidthPx + 1f)
    }
}
