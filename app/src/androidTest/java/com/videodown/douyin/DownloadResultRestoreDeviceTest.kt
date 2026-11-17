package com.videodown.douyin

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.videodown.douyin.core.DownloadResultInbox
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.VideoDownApp
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DownloadResultRestoreDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val id = "restore-${UUID.randomUUID()}"
    private lateinit var previousInbox: DownloadResultInbox

    @Before fun prepare() = runBlocking {
        previousInbox = ServiceLocator.preferences.downloadResultInbox
        ServiceLocator.historyRepository.upsert(
            DownloadRecord(
                id = id,
                awemeId = id,
                title = "跨进程完成结果",
                status = DownloadStatus.COMPLETED,
                totalBytes = 100,
                downloadedBytes = 100,
            ),
        )
        ServiceLocator.preferences.downloadResultInbox = DownloadResultInbox(completedIds = setOf(id))
        compose.runOnUiThread {
            compose.activity.setContent {
                VideoDownTheme {
                    VideoDownApp(null, {}, openLibrary = false, onLibraryOpened = {})
                }
            }
        }
    }

    @After fun cleanup() = runBlocking {
        ServiceLocator.historyRepository.delete(id)
        ServiceLocator.preferences.downloadResultInbox = previousInbox
    }

    @Test fun persistedCompletionAppearsOnceAndClearsOnItsDestination() {
        compose.onNodeWithText(compose.activity.getString(R.string.download_center_completed, 1)).assertIsDisplayed()
        compose.onNodeWithTag("tab-library").performClick()
        compose.waitUntil(5_000) { ServiceLocator.preferences.downloadResultInbox.completedIds.isEmpty() }
    }
}
