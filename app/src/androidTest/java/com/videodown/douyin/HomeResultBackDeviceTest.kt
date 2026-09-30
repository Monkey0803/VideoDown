package com.videodown.douyin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.home.HomeViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeResultBackDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val input = "保留原始分享文案"

    @Test fun systemBackReturnsToInputWithoutFinishingActivity() {
        showResult()
        Espresso.pressBack()
        assertInputRestored()
    }

    @Test fun toolbarBackReturnsToTheSameInput() {
        showResult()
        compose.onNodeWithContentDescription("返回").performClick()
        assertInputRestored()
    }

    @Test fun simplifiedQualityLabelsKeepDistinctSourcesSelectable() {
        val direct = VideoVariant(url = "https://example.invalid/direct.mp4", label = "720P",
            sizeBytes = 1024, qualityRank = 720)
        val hls = VideoVariant(url = "https://example.invalid/playlist.m3u8",
            label = "720P · MP4 · hls-852", qualityRank = 720)
        showResult(listOf(direct, hls))
        compose.onNodeWithText(hls.label).assertDoesNotExist()
        compose.onAllNodesWithText("720P").assertCountEquals(2)
        compose.onNode(hasText("大小未知") and hasClickAction()).performClick()
        compose.runOnUiThread {
            assertEquals(hls, ViewModelProvider(compose.activity)[HomeViewModel::class.java].state.value.selectedVariant)
        }
    }

    private fun showResult(variants: List<VideoVariant> = listOf(VideoVariant(
        url = "https://example.invalid/video.mp4", label = "720P", sizeBytes = 1024,
    ))) {
        // Seed metadata through the normal result entry point without relying on a public service.
        compose.runOnUiThread {
            val viewModel = ViewModelProvider(compose.activity)[HomeViewModel::class.java]
            viewModel.onInputChange(input)
            viewModel.acceptBrowserResult(ParsedVideos(listOf(DouyinVideo(
                awemeId = "back-navigation-fixture",
                title = "返回行为测试",
                coverUrl = null,
                durationMs = 8_000,
                variants = variants,
                shareUrl = "https://example.invalid/video",
            ))))
        }
        compose.onNodeWithText("解析结果").assertIsDisplayed()
        compose.onNodeWithText("保存到本地").assertIsDisplayed()
    }

    private fun assertInputRestored() {
        compose.onNodeWithText("解析结果").assertDoesNotExist()
        compose.onNodeWithText("拾影").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains(input)
        compose.runOnUiThread { assertFalse(compose.activity.isFinishing) }
    }
}
