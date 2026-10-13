package com.videodown.douyin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadCompletionNotifier
import com.videodown.douyin.data.download.DownloadService
import com.videodown.douyin.presentation.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 快捷方式 intent 打开的页面，以及"发布"这件事本身。
 *
 * 注意：三个快捷方式**当前暂时不发布**（见 [LauncherShortcuts.publish]）；下面这些 intent 路径的用例仍然有效——
 * 处理逻辑与恢复方法都原样保留，重新发布时不用重写测试。
 */
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

    /**
     * 三个快捷方式**暂时不发布**（用户要求先注释掉）。
     *
     * 这条盯住"确实清干净了"：升级安装后长按菜单里不该再留着历史发布过的条目。
     * 恢复快捷方式时，这条要连同 [LauncherShortcuts.publish] 一起改回"三个都在"。
     */
    @Test fun theEntryShortcutsAreTemporarilyNotPublished() {
        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertTrue("快捷方式应暂时不发布，实际有 ${shortcuts.map { it.id }}", shortcuts.isEmpty())
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

    /**
     * 快捷方式图标必须在**浅色启动器背景**上看得见。
     *
     * 启动器不会给图标套主题色，也不给它背景：只描白边的线条图标画在浅色菜单卡片上等于什么都不显示
     * （真机长按菜单里就是"没有图标"）。这条把每个图标真的画到白底上，量非白像素的占比。
     */
    @Test fun shortcutIconsAreVisibleOnALightLauncherBackground() {
        // 平台侧返回的 icon 对资源型图标恒为 null，所以对着「要发布的那份定义」量。
        val shortcuts = LauncherShortcuts.shortcuts(context)
        assertTrue("需要先有快捷方式", shortcuts.isNotEmpty())
        shortcuts.forEach { shortcut ->
            val label = shortcut.shortLabel?.toString() ?: shortcut.id
            val icon = shortcut.icon ?: error("$label 没有图标")
            val drawable = icon.loadDrawable(context) ?: error("$label 的图标加载不出来")
            val size = 96
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            var visible = 0
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) visible++
                }
            }
            bitmap.recycle()
            val ratio = visible.toDouble() / (size * size)
            assertTrue(
                "$label 的图标在浅色背景上几乎不可见（非白像素占比 ${"%.4f".format(ratio)}）——" +
                    "线条图标必须自带填充背景，否则启动器渲染出来就是空的",
                ratio > 0.05,
            )
        }
    }



    /** 按启动器的方式投递快捷方式 intent（新建任务 + 显式组件）。 */
    private fun launchWithAction(action: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        compose.waitForIdle()
    }

    /**
     * 快捷方式必须在**应用停在别的 tab** 时也生效。
     *
     * 这正是真机上暴露的缺陷：「批量导入」的弹窗只存在于解析页，而快捷方式此前没有把界面带过去，
     * 于是停在已存/我的页时点了什么都不发生（标志还会挂到下次进解析页才突然生效）。
     */
    @Test fun theBatchImportShortcutOpensTheDialogFromAnotherTab() {
        launchWithAction(DownloadCompletionNotifier.ACTION_OPEN_LIBRARY)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("已存视频").fetchSemanticsNodes().isNotEmpty() }

        launchWithAction(LauncherShortcuts.ACTION_BATCH_IMPORT)

        compose.waitUntil(15_000) { compose.onAllNodesWithText("批量链接导入").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("批量链接导入").assertIsDisplayed()
        // 收拾干净，别把弹窗留给下一条用例。
        runCatching { compose.onNodeWithText("关闭").performClick() }
    }

    /**
     * 剪贴板里没有可解析的链接时，「粘贴解析」也必须落到解析页。
     *
     * 此前它只在真正读到链接时才跳转，读不到就静默留在原地——用户看到的就是"打开的页面不对"。
     */
    @Test fun theQuickParseShortcutLandsOnTheParsePageWithoutAClipboardLink() {
        instrumentation.runOnMainSync {
            runCatching { context.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
        }
        launchWithAction(DownloadCompletionNotifier.ACTION_OPEN_LIBRARY)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("已存视频").fetchSemanticsNodes().isNotEmpty() }

        launchWithAction(LauncherShortcuts.ACTION_QUICK_PARSE)

        compose.waitUntil(15_000) { compose.onAllNodesWithText("解析视频").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("解析视频").assertIsDisplayed()
    }

    /**
     * 显式按下「粘贴解析」时，**普通网页链接也必须填进输入框**。
     *
     * 被动剪贴板提示只认已知平台是合理的（别拿浏览器地址乱弹窗），但显式按下就是用户明确要求解析。
     * 此前两条路径共用同一策略，普通链接被静默丢掉——真机上就是"页面对了但输入框是空的"。
     */
    @Test fun theQuickParseShortcutFillsTheInputForAPlainWebLink() {
        val slug = UUID.randomUUID().toString().take(8)
        val link = "https://example.com/watch/$slug"
        ServiceLocator.preferences.clipboardDetect = true
        ServiceLocator.preferences.lastClipboardOffer = null
        instrumentation.runOnMainSync {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("copied", link))
        }

        launchWithAction(LauncherShortcuts.ACTION_QUICK_PARSE)

        compose.waitUntil(15_000) { compose.onAllNodesWithText(slug, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * 「批量导入」应当把剪贴板里的链接**预填**进导入框，而不是让用户再粘一次。
     *
     * 走**冷启动**：快捷方式把应用拉到前台时还没有输入焦点，Android 10+ 这时读剪贴板只能拿到 null
     * （真机实测过：投递前后从同一进程读，前者是 null）。所以这条用例覆盖的是"等拿到焦点再读"。
     */
    @Test fun theBatchImportShortcutCarriesTheClipboardLinksOnAColdStart() {
        val slug = UUID.randomUUID().toString().take(6)
        val links = "两条链接 https://example.com/one-$slug https://example.com/two-$slug"
        ServiceLocator.preferences.clipboardDetect = true
        ServiceLocator.preferences.lastClipboardOffer = null
        instrumentation.runOnMainSync {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("copied", links))
        }

        ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java)
                .setAction(LauncherShortcuts.ACTION_BATCH_IMPORT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("批量链接导入").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("one-$slug", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("two-$slug", substring = true).assertIsDisplayed()
            runCatching { compose.onNodeWithText("关闭").performClick() }
        }
    }
}
