package com.videodown.douyin

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.VideoDownApp
import com.videodown.douyin.presentation.adaptive.LocalWindowWidth
import com.videodown.douyin.presentation.adaptive.WindowWidth
import com.videodown.douyin.presentation.settings.SettingsScreen
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DownloadCenterSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownedIds = mutableListOf<String>()

    @After fun cleanup() = runBlocking {
        ownedIds.forEach { ServiceLocator.historyRepository.delete(it) }
    }

    @Test fun pausedTaskAppearsGloballyAndOpensTheTaskPage() {
        runBlocking {
            val id = UUID.randomUUID().toString()
            ownedIds += id
            val record = DownloadRecord(
                    id = id,
                    awemeId = id,
                    title = "全局下载条设备用例",
                    status = DownloadStatus.PENDING,
                    totalBytes = 100,
                    downloadedBytes = 40,
                )
            ServiceLocator.historyRepository.upsert(record)

            compose.waitUntil(10_000) { compose.onAllNodesWithTag("download-center").fetchSemanticsNodes().isNotEmpty() }
            ServiceLocator.historyRepository.upsert(record.copy(status = DownloadStatus.PAUSED))
            compose.onNodeWithTag("download-center").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("task-$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("task-$id").assertIsDisplayed()
            // 设备上可能已有其他任务：只要当前卡片带「更多任务操作」即可，不要求全局只有一个。
            assertTrue(
                compose.onAllNodesWithContentDescription(context.getString(R.string.history_more_actions))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            compose.onNodeWithTag("task-more-$id").assertIsDisplayed().performClick()
            compose.onNodeWithText(context.getString(R.string.history_set_limit)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.action_delete)).assertIsDisplayed()
        }
    }

    @Test fun completedResultOpensTheSavedPage() {
        runBlocking {
            val id = UUID.randomUUID().toString()
            ownedIds += id
            val record = DownloadRecord(
                id = id,
                awemeId = id,
                title = "完成结果设备用例",
                status = DownloadStatus.PENDING,
                totalBytes = 100,
                downloadedBytes = 40,
            )
            ServiceLocator.historyRepository.upsert(record)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("download-center").fetchSemanticsNodes().isNotEmpty() }
            ServiceLocator.historyRepository.upsert(record.copy(status = DownloadStatus.COMPLETED, downloadedBytes = 100))
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(context.getString(R.string.download_center_completed, 1)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("download-center-dismiss").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.download_center_completed, 1)).performClick()
            compose.onNodeWithText(context.getString(R.string.library_title)).assertIsDisplayed()
        }
    }

    @Test fun settingsHubRoutesToAllFiveFocusedPages() {
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-open-appearance").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-download").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-accounts").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-storage").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-about").performScrollTo().assertIsDisplayed()

        val destinations = listOf(
            "settings-open-appearance" to R.string.settings_theme_title,
            "settings-open-download" to R.string.settings_quality_title,
            "settings-open-accounts" to R.string.settings_x_login_row,
            "settings-open-storage" to R.string.settings_backup_settings,
            "settings-open-about" to R.string.settings_update_row,
        )
        destinations.forEach { (tag, titleRes) ->
            compose.onNodeWithTag(tag).performClick()
            compose.onNodeWithText(context.getString(titleRes)).assertIsDisplayed()
            compose.onNodeWithTag("settings-back").performClick()
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test fun settingsHubRemainsReachableAtTwoHundredPercentFont() {
        val configuration = Configuration(compose.activity.resources.configuration).apply { fontScale = 2f }
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(compose.activity.resources.displayMetrics.density, fontScale = 2f),
                ) {
                    VideoDownTheme { SettingsScreen() }
                }
            }
        }
        listOf("settings-open-appearance", "settings-open-download", "settings-open-accounts", "settings-open-storage", "settings-open-about")
            .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
    }

    @Test fun clearingWebSessionsRequiresConfirmation() {
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-open-accounts").performClick()
        compose.onNodeWithText(context.getString(R.string.settings_clear_session)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.settings_clear_session_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
    }

    @Test fun twelveHundredDpWindowUsesNavigationRail() {
        // 导航形态改由官方窗口尺寸类决定：直接渲染 VideoDownApp 时注入同一个 CompositionLocal，
        // 与 MainActivity 的 calculateWindowSizeClass 路径等价。
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalWindowWidth provides WindowWidth.EXPANDED) {
                    VideoDownTheme {
                        VideoDownApp(
                            sharedText = null,
                            onSharedTextConsumed = {},
                            openLibrary = false,
                            onLibraryOpened = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("tab-bar").assertDoesNotExist()
    }
}
