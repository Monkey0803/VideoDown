package com.videodown.douyin

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.domain.model.StorageBudget
import com.videodown.douyin.domain.model.StorageBudgetEntry
import com.videodown.douyin.domain.model.StorageCleanupResult
import com.videodown.douyin.domain.model.StorageUsage
import com.videodown.douyin.domain.repository.StorageRepository
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.settings.StorageDialog
import com.videodown.douyin.presentation.settings.StorageViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the storage dialog against fixed ledgers so the space-budget bar and the shortfall reason
 * are verified on a real device. The engine's own path is covered by P1FeaturesDeviceTest.
 */
@RunWith(AndroidJUnit4::class)
class SpaceBudgetDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val models = ViewModelStore()

    private class FakeStorage(private val usage: StorageUsage) : StorageRepository {
        override suspend fun inspect() = usage
        override suspend fun clean(plan: StorageUsage) = StorageCleanupResult(0, 0, 0, 0, 0)
    }

    @After fun cleanup() { compose.runOnUiThread { models.clear() } }

    @Test fun overCommittedBudgetShowsTheShortfallAndAsksToFreeSpace() {
        show(StorageUsage(availableBytes = 1_000_000,
            budget = StorageBudget(4_000_000, listOf(StorageBudgetEntry("预算任务", 4_000_000)))))
        compose.waitUntil(5000) { compose.onAllNodes(hasText("空间不足", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("运行任务尚需 3.8 M · 1 个任务").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("预算占用 80.0%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("空间不足：仍缺 2.9 M，请清理残留或删除已存视频后继续。").performScrollTo().assertIsDisplayed()
        capture("budget-shortfall")
    }

    @Test fun emptyLedgerExplainsThereIsNoRunningBudget() {
        show(StorageUsage(availableBytes = 1_000_000))
        compose.waitUntil(5000) { compose.onAllNodes(hasText("运行任务空间预算：无")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("当前没有运行中的下载任务占用空间预算。").performScrollTo().assertIsDisplayed()
        capture("budget-empty-dialog")
    }

    private fun show(usage: StorageUsage) {
        compose.runOnUiThread {
            val model = StorageViewModel(FakeStorage(usage)); models.put("storage", model)
            compose.activity.setContent { VideoDownTheme { StorageDialog(model) {} } }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "space-budget").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
