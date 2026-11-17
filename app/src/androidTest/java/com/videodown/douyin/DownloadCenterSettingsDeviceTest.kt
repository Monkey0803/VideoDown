package com.videodown.douyin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
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
            ServiceLocator.historyRepository.upsert(
                DownloadRecord(
                    id = id,
                    awemeId = id,
                    title = "全局下载条设备用例",
                    status = DownloadStatus.PAUSED,
                    totalBytes = 100,
                    downloadedBytes = 40,
                ),
            )

            compose.waitUntil(10_000) { compose.onAllNodesWithTag("download-center").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("download-center").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("task-$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("task-$id").assertIsDisplayed()
        }
    }

    @Test fun settingsHubRoutesToAllFourFocusedPages() {
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-open-appearance").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-download").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-accounts").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-storage").assertIsDisplayed()

        val destinations = listOf(
            "settings-open-appearance" to R.string.settings_theme_title,
            "settings-open-download" to R.string.settings_quality_title,
            "settings-open-accounts" to R.string.settings_x_login_row,
            "settings-open-storage" to R.string.settings_backup_settings,
        )
        destinations.forEach { (tag, titleRes) ->
            compose.onNodeWithTag(tag).performClick()
            compose.onNodeWithText(context.getString(titleRes)).assertIsDisplayed()
            compose.onNodeWithTag("settings-back").performClick()
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }
}
