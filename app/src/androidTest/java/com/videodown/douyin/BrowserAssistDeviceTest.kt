package com.videodown.douyin

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.browser.BROWSER_PANEL_TOGGLE_TAG
import com.videodown.douyin.presentation.browser.BROWSER_STATUS_TAG
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserAssistDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dynamicPageRequiresPlayThenDetectSelectAndDownload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "browser-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        var sourcePage = ""
        try {
            runBlocking { createMediaFixtures(context, directory) }
            FixtureServer(directory).use { server ->
                sourcePage = server.baseUrl + "/dynamic"
                compose.onNode(hasSetTextAction()).performTextReplacement(sourcePage)
                compose.onNodeWithText("打开网页查找视频").performScrollTo().performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithTag(BROWSER_PANEL_TOGGLE_TAG).fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(20_000) {
                    var loaded = false
                    onView(isAssignableFrom(WebView::class.java)).check { view, _ -> loaded = (view as WebView).title == "Dynamic fixture" && view.progress == 100 }
                    loaded
                }
                screenshot("browser-before-play.png")
                // A native Espresso pointer click on the WebView's full-page play button.
                onView(isAssignableFrom(WebView::class.java)).perform(click())
                screenshot("browser-after-play.png")
                compose.onNodeWithContentDescription("查找视频").performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithText("MP4 ·", substring = true).fetchSemanticsNodes().isNotEmpty() }
                screenshot("browser-detected.png")
                compose.onNodeWithText("MP4 ·", substring = true).performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithText("解析结果").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("保存到本地").performClick()
                compose.waitUntil(20_000) {
                    runBlocking { com.videodown.douyin.core.ServiceLocator.historyRepository.snapshot().any {
                        it.sourcePageUrl == sourcePage && it.status == com.videodown.douyin.domain.model.DownloadStatus.COMPLETED
                    } }
                }
                screenshot("browser-downloaded.png")
                // 下载完成后结果页主按钮变成「查看已保存内容」，点它进入播放器。
                // 旧用例点的是任务页任务卡上的「播放」图标，结果页从来没有那个控件，属于既有失效断言。
                compose.waitUntil(20_000) { compose.onAllNodesWithText("查看已保存内容").fetchSemanticsNodes().isNotEmpty() }
                // 「已加入 N 个下载任务」的 Snackbar 会盖住主按钮，等它自己消失再点，避免触摸被它吃掉。
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithText("已加入", substring = true).assertDoesNotExist() }.isSuccess
                }
                compose.onNodeWithText("查看已保存内容").performClick()
                compose.waitUntil(15_000) {
                    var playing = false
                    compose.runOnUiThread {
                        fun find(view: android.view.View): androidx.media3.ui.PlayerView? {
                            if (view is androidx.media3.ui.PlayerView) return view
                            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                            return null
                        }
                        val player = find(compose.activity.window.decorView)?.player
                        playing = player?.isPlaying == true && player.currentPosition > 100 &&
                            player.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO } &&
                            player.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                    }
                    playing
                }
                compose.waitForIdle()
                screenshot("browser-player.png")
            }
        } finally {
            runBlocking {
                com.videodown.douyin.core.ServiceLocator.historyRepository.snapshot().filter { sourcePage.isNotEmpty() && it.sourcePageUrl == sourcePage }
                    .forEach { com.videodown.douyin.core.ServiceLocator.downloader.cancel(it.id, true) }
            }
            directory.deleteRecursively()
        }
    }

    /**
     * 没有媒体的页面必须"说话"：先给扫描结论，再在解析失败时给出真正的原因。
     *
     * 回归：面板曾经把 `AppError.message` 直接当文案，而引擎失败带的是 `reasonKey`、message 是空串，
     * 于是失败过一次之后状态行一片空白——用户在网页解析里看不到任何解释。
     */
    @Test fun aPageWithoutMediaExplainsItselfInsteadOfStayingSilent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "browser-plain-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            FixtureServer(directory).use { server ->
                val page = server.baseUrl + "/plain"
                compose.onNode(hasSetTextAction()).performTextReplacement(page)
                compose.onNodeWithText("打开网页查找视频").performScrollTo().performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithTag(BROWSER_PANEL_TOGGLE_TAG).fetchSemanticsNodes().isNotEmpty() }
                awaitWebView("Plain fixture")

                // 1) 扫完一个候选都没有：必须给出结论，而不是留白。
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText(context.getString(R.string.browser_nothing_found)).fetchSemanticsNodes().isNotEmpty()
                }
                screenshot("browser-nothing-found.png")

                // 2) 用网页登录态重新解析：失败原因要被解析成给人看的话，而不是空串或原始 key。
                expandPanel()
                compose.onNodeWithText("使用网页登录态重新解析").performClick()
                val resolved = listOf(R.string.engine_unsupported, R.string.generic_no_video).map(context::getString)
                compose.waitUntil(30_000) {
                    resolved.any { copy -> compose.onAllNodesWithText(copy, substring = true).fetchSemanticsNodes().isNotEmpty() }
                }
                val text = compose.onNodeWithTag(BROWSER_STATUS_TAG, useUnmergedTree = true).fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")
                assertTrue("失败后状态行不能是空白，实际为「$text」", text.isNotBlank())
                screenshot("browser-parse-failure-text.png")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * 同一张图的三个 URL（PNG / JPG / 带格式参数的 JPG）只显示一行，格式作为可选项。
     *
     * 对应线上 X 帖子里的四行「Image · PNG/WEBP/JPG/JPG」——按 URL 平铺时用户根本分不清。
     */
    @Test fun oneImageWithSeveralFormatUrlsIsListedOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "browser-variants-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            writeNoiseImage(File(directory, "photo.png"), Bitmap.CompressFormat.PNG)
            writeNoiseImage(File(directory, "photo.jpg"), Bitmap.CompressFormat.JPEG)
            // 装饰图：路径命中头像目录，即使尺寸够大也不该出现在候选里。
            writeNoiseImage(File(directory, "avatar/avatar.png").apply { parentFile?.mkdirs() }, Bitmap.CompressFormat.PNG)
            FixtureServer(directory).use { server ->
                val page = server.baseUrl + "/variants"
                compose.onNode(hasSetTextAction()).performTextReplacement(page)
                compose.onNodeWithText("打开网页查找视频").performScrollTo().performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithTag(BROWSER_PANEL_TOGGLE_TAG).fetchSemanticsNodes().isNotEmpty() }
                awaitWebView("Variants fixture")

                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText("图片 · 200 × 200", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                // 三个 URL、两种格式 → 只有一行。
                compose.onAllNodesWithText("图片 · 200 × 200", substring = true).assertCountEquals(1)
                screenshot("browser-image-dedupe.png")

                // 计数行报的是聚合后的条数；格式菜单里能看到另一份编码。
                compose.onNodeWithText("图片 1").assertExists()
                compose.onNodeWithContentDescription("选择格式").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("JPG", substring = true).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(compose.onAllNodesWithText("PNG", substring = true).fetchSemanticsNodes().isNotEmpty())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** 网页自己的「See this post in the app」弹窗要被隐藏，但不能连带弄坏页面内容。 */
    @Test fun pageOwnAppPromptDialogIsHidden() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "browser-modal-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            FixtureServer(directory).use { server ->
                val page = server.baseUrl + "/app-modal"
                compose.onNode(hasSetTextAction()).performTextReplacement(page)
                compose.onNodeWithText("打开网页查找视频").performScrollTo().performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithTag(BROWSER_PANEL_TOGGLE_TAG).fetchSemanticsNodes().isNotEmpty() }
                awaitWebView("App modal fixture")

                compose.waitUntil(10_000) {
                    evaluate("getComputedStyle(document.querySelector('[role=\"dialog\"]')).display").contains("none")
                }
                assertTrue("弹窗隐藏不能连带删掉页面内容", evaluate("document.getElementById('under').innerText").contains("under"))
                screenshot("browser-app-modal-hidden.png")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** 「桌面版网页」要真的换 UA 并重载；关掉后恢复系统默认 UA。 */
    @Test fun desktopSiteTogglesUserAgent() {
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "browser-desktop-${UUID.randomUUID()}",
        ).apply { mkdirs() }
        try {
            FixtureServer(directory).use { server ->
                val page = server.baseUrl + "/plain"
                compose.onNode(hasSetTextAction()).performTextReplacement(page)
                compose.onNodeWithText("打开网页查找视频").performScrollTo().performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithTag(BROWSER_PANEL_TOGGLE_TAG).fetchSemanticsNodes().isNotEmpty() }
                awaitWebView("Plain fixture")

                val timeOriginBefore = pageTimeOrigin()
                expandPanel()
                openMoreMenu()
                compose.onNodeWithText("桌面版网页").performClick()
                compose.waitUntil(15_000) { currentUserAgent().contains("Macintosh") }
                // 切 UA 会触发重载；等页面真的换了一代再操作，否则面板状态会被重载重置。
                compose.waitUntil(20_000) { pageTimeOrigin() != timeOriginBefore }
                awaitWebView("Plain fixture")

                // 重载会重置面板；重新展开并打开「更多」留证，再把开关关掉。
                expandPanel()
                openMoreMenu()
                screenshot("browser-desktop-site.png")
                compose.onNodeWithText("桌面版网页").performClick()
                compose.waitUntil(15_000) { !currentUserAgent().contains("Macintosh") }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** 顶部栏要显示网页标题，而不是只有域名。 */
    @Test fun topBarShowsPageTitle() {
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "browser-title-${UUID.randomUUID()}",
        ).apply { mkdirs() }
        try {
            FixtureServer(directory).use { server ->
                compose.onNode(hasSetTextAction()).performTextReplacement(server.baseUrl + "/plain")
                compose.onNodeWithText("打开网页查找视频").performScrollTo().performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithTag(BROWSER_PANEL_TOGGLE_TAG).fetchSemanticsNodes().isNotEmpty() }
                awaitWebView("Plain fixture")
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Plain fixture").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** 页面每重载一次就换一个 timeOrigin，用来区分「还在旧页面」和「重载已完成」。 */
    private fun pageTimeOrigin(): String = evaluate("String(performance.timeOrigin)")

    private fun openMoreMenu() {
        compose.onNodeWithText("更多").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("桌面版网页").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun expandPanel() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(BROWSER_PANEL_TOGGLE_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(BROWSER_PANEL_TOGGLE_TAG).performClick()
        compose.waitForIdle()
    }

    private fun awaitWebView(title: String) {
        compose.waitUntil(20_000) {
            var loaded = false
            onView(isAssignableFrom(WebView::class.java)).check { view, _ ->
                loaded = (view as WebView).title == title && view.progress == 100
            }
            loaded
        }
    }

    private fun webView(): WebView {
        var view: WebView? = null
        onView(isAssignableFrom(WebView::class.java)).check { found, _ -> view = found as WebView }
        return checkNotNull(view) { "WebView 尚未挂上" }
    }

    private fun evaluate(script: String): String {
        val view = webView()
        val latch = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) { value ->
                result = value ?: ""
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    private fun currentUserAgent(): String {
        val view = webView()
        var userAgent = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync { userAgent = view.settings.userAgentString.orEmpty() }
        return userAgent
    }

    /** 噪点图：纯色 PNG 会压到 1 KiB 以下、被候选的噪音过滤直接丢掉。 */
    private fun writeNoiseImage(file: File, format: Bitmap.CompressFormat) {
        val side = 200
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val random = Random(7)
        for (x in 0 until side) for (y in 0 until side) {
            bitmap.setPixel(x, y, 0xFF000000.toInt() or random.nextInt(0xFFFFFF))
        }
        file.outputStream().use { bitmap.compress(format, 95, it) }
        bitmap.recycle()
        assertTrue("${file.name} 太小，会被当作噪音过滤", file.length() > 10L * 1024)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "generic-ui-artifacts").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
