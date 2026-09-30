package com.videodown.douyin

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.domain.model.MediaSourceType
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.home.HomeScreen
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Mixed short/wrapped labels reproduce the reported result-screen layout. */
@RunWith(AndroidJUnit4::class)
class QualityCardsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mixedQualityCardsHaveEqualBoundsAndSelectionStillWorks() {
        val labels = listOf("720P", "720P · MP4 · hls-755", "480P", "480P · MP4 · hls-377", "320P", "320P · MP4 · hls-203")
        val sizes = listOf(1024L * 1024 * 3 / 2, null, 1024L * 1024 * 1024 * 3 / 2, null, 205L * 1024, null)
        val variants = labels.mapIndexed { index, label ->
            VideoVariant("https://example.com/$index.mp4", label, sizeBytes = sizes[index], qualityRank = 6 - index,
                media = if (index % 2 == 1) MediaDownload(type = MediaSourceType.HLS) else null)
        }
        val video = DouyinVideo("quality-fixture", "画质选项布局验证", variants = variants)
        val repository = object : VideoRepository {
            override suspend fun parseShareText(shareText: String) = AppResult.Success(video)
        }
        val models = ViewModelStore()
        lateinit var model: HomeViewModel
        try {
            compose.runOnUiThread {
                model = HomeViewModel(repository, selectDefaultVariant = { it.defaultVariant }, fetchSize = { null })
                models.put("quality", model)
                compose.activity.setContent {
                    VideoDownTheme {
                        HomeScreen("fixture", {}, {}, {}, {}, emptyList(), model)
                    }
                }
            }
            compose.onNodeWithText("选择画质").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("1.5 M").assertIsDisplayed()
            compose.onNodeWithText("1.50 G").assertIsDisplayed()
            val cards = labels.map { compose.onNodeWithContentDescription("画质 $it") }
            val bounds = cards.map { it.fetchSemanticsNode().boundsInRoot }
            bounds.forEach {
                assertEquals(bounds.first().width, it.width, 1f)
                assertEquals(bounds.first().height, it.height, 1f)
            }
            cards[1].performClick()
            compose.runOnIdle { assertEquals(variants[1], model.state.value.selectedVariant) }
        } finally {
            compose.runOnUiThread { models.clear() }
        }
    }
}
