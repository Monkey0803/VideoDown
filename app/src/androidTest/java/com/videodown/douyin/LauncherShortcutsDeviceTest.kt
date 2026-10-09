package com.videodown.douyin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadService
import com.videodown.douyin.presentation.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The published launcher shortcuts and the screens their intents actually open. */
@RunWith(AndroidJUnit4::class)
class LauncherShortcutsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @After fun clearClipboard() {
        ServiceLocator.preferences.clipboardDetect = true
        ServiceLocator.preferences.lastClipboardOffer = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching { context.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
        }
    }

    @Test fun theThreeEntryShortcutsArePublishedForThisInstalledPackage() {
        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        val ids = shortcuts.map { it.id }
        assertTrue("Expected the three entry shortcuts, got $ids",
            ids.containsAll(listOf("quick_parse", "batch_import", "open_tasks")))
        shortcuts.forEach { shortcut ->
            assertEquals("A shortcut must target the installed package, including debug builds",
                context.packageName, shortcut.intent.component?.packageName)
        }
    }

    @Test fun theTasksShortcutOpensTheTaskPage() {
        ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).setAction(DownloadService.ACTION_OPEN_TASKS),
        ).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("下载任务").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("下载任务").assertIsDisplayed()
        }
    }

    @Test fun theBatchImportShortcutOpensTheImportDialog() {
        ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).setAction(LauncherShortcuts.ACTION_BATCH_IMPORT),
        ).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("批量链接导入").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("批量链接导入").assertIsDisplayed()
        }
    }

    @Test fun quickParseReadsTheCopiedLinkDirectlyWithoutTheConfirmationBanner() {
        val slug = UUID.randomUUID().toString().take(8)
        ServiceLocator.preferences.clipboardDetect = true
        ServiceLocator.preferences.lastClipboardOffer = null
        instrumentation.runOnMainSync {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("copied", "看看这个 https://v.douyin.com/$slug/ 复制此链接"))
        }
        ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).setAction(LauncherShortcuts.ACTION_QUICK_PARSE),
        ).use {
            // The shortcut is an explicit request, so it parses straight away.
            compose.waitUntil(15_000) { compose.onAllNodesWithText(slug, substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("An explicit shortcut must not also raise the passive banner",
                compose.onAllNodesWithTag("clipboard-offer").fetchSemanticsNodes().isEmpty())
        }
    }
}
