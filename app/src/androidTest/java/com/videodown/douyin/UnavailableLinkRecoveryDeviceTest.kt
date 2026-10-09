package com.videodown.douyin

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in actual unavailable public URL and production task-page recovery; no login or fixture server. */
@RunWith(AndroidJUnit4::class)
class UnavailableLinkRecoveryDeviceTest {
    @Test fun publicUnavailableLinkOffersOriginalPageReparse() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("public_unavailable_link") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val tag = UUID.randomUUID().toString()
        val taskTitle = "失效链接恢复验收-$tag"
        val sourcePage = "https://www.w3.org/2010/05/video/mediaevents.html"
        fun descendant(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { descendant(it, text)?.let { found -> return found } }
            return null
        }
        fun find(text: String): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.text?.toString() == text) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
                return null
            }
            return automation.rootInActiveWindow?.let(::visit)
        }
        suspend fun click(text: String, taskScoped: Boolean = false) {
            var node = withTimeout(15_000) {
                while (true) {
                    if (taskScoped) {
                        var parent = find(taskTitle)?.parent
                        while (parent != null) {
                            descendant(parent, text)?.let { return@withTimeout it }
                            parent = parent.parent
                        }
                    } else find(text)?.let { return@withTimeout it }
                    delay(50)
                }
                error("Unreachable")
            }
            while (!node.isClickable && node.parent != null) node = node.parent
            assertTrue("Actual UI action $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun capture(name: String) {
            val directory = File(context.cacheDir, "expired-link-evidence").apply { mkdirs() }
            automation.takeScreenshot().let { bitmap ->
                File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            fun render(node: AccessibilityNodeInfo): String = buildString {
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                append("<node text=\"${android.text.TextUtils.htmlEncode(node.text?.toString().orEmpty())}\" bounds=\"$bounds\">\n")
                for (i in 0 until node.childCount) node.getChild(i)?.let { append(render(it)) }
                append("</node>\n")
            }
            File(directory, "$name.xml").writeText(automation.rootInActiveWindow?.let(::render).orEmpty())
        }
        var taskId: String? = null
        try {
            context.startActivity(Intent().setClassName(context.packageName,
                "com.videodown.douyin.presentation.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(500)
            val variant = VideoVariant("https://www.w3.org/2010/05/sintel/trailer.webm", "过期链接验收")
            val video = DouyinVideo("expired-$tag", taskTitle, variants = listOf(variant),
                shareUrl = sourcePage, platform = VideoPlatform.GENERIC)
            val id = ServiceLocator.downloader.start(video, variant).also { taskId = it }
            val failed = withTimeout(90_000) { ServiceLocator.historyRepository.observeRecord(id).first {
                it?.status in setOf(DownloadStatus.FAILED, DownloadStatus.COMPLETED, DownloadStatus.PAUSED)
            }!! }
            assertEquals(failed.errorMessage, DownloadStatus.FAILED, failed.status)
            assertTrue("Expected actual expired or denied public source, got ${failed.failureKind}",
                failed.failureKind in setOf(DownloadFailureKind.LINK_EXPIRED, DownloadFailureKind.ACCESS_DENIED))
            report("UNAVAILABLE_PUBLIC: failureKind=${failed.failureKind}; original source page retained")
            click("任务")
            withTimeout(15_000) { while (find("重新解析原网址") == null) delay(50) }
            capture("expired-task")
            click("重新解析原网址", taskScoped = true)
            report("UNAVAILABLE_PUBLIC: unique task recovery button clicked")
            withTimeout(90_000) {
                while (find("粘贴链接") == null || find(sourcePage) == null ||
                    (find("网址过期或访问受限") == null && find("解析结果") == null)) delay(100)
            }
            assertNotNull("Recovery must return to real Home input", find("粘贴链接"))
            assertNotNull("Recovery must parse the exact original HTML page, not expired CDN URL", find(sourcePage))
            capture("expired-reparse-terminal")
            report("UNAVAILABLE_PUBLIC: actual recovery button navigated Home; terminal=" +
                if (find("网址过期或访问受限") != null) "ACCESS_DENIED (remote challenge); new download not covered" else "parsed results")
        } finally {
            try { capture("unavailable-final-state") }
            finally { taskId?.let { withContext(NonCancellable) { ServiceLocator.downloader.cancel(it, removeRecord = true) } } }
        }
    }
}
