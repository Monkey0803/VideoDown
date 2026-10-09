package com.videodown.douyin

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.generic.validateVideo
import com.videodown.douyin.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Opt-in historical MDN source removal; replacement is discovered by the real anonymous WebView. */
@RunWith(AndroidJUnit4::class)
class ExpiredPublicMediaRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val originalPage = "https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/video"
    private val oldSource = "https://interactive-examples.mdn.mozilla.net/media/examples/flower.mp4"

    private fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) find(root.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun text(value: String) = find(automation.rootInActiveWindow) { it.text?.toString() == value }
    private suspend fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo = withTimeout(60_000) {
        while (true) {
            find(automation.rootInActiveWindow, predicate)?.let { return@withTimeout it }
            delay(100)
        }
        error("Unreachable")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue("Actual UI click", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private suspend fun clickText(value: String) = click(awaitNode { it.text?.toString() == value })
    private fun capture(name: String) {
        val directory = File(context.cacheDir, "expired-public-media-evidence").apply { mkdirs() }
        automation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun render(node: AccessibilityNodeInfo): String = buildString {
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            append("<node text=\"${android.text.TextUtils.htmlEncode(node.text?.toString().orEmpty())}\" bounds=\"$bounds\">\n")
            for (index in 0 until node.childCount) node.getChild(index)?.let { append(render(it)) }
            append("</node>\n")
        }
        File(directory, "$name.xml").writeText(automation.rootInActiveWindow?.let(::render).orEmpty())
    }

    @Test fun realRemovedMdnSourceRecoversThroughOriginalPageAndAnonymousDiscovery() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("public_mdn_recovery") == "true")
        assumeTrue("Preserve existing active downloads", ServiceLocator.downloader.progress.value.values.none { it.isActive })
        val previousIds = ServiceLocator.historyRepository.snapshot().map { it.id }.toSet()
        val tag = UUID.randomUUID().toString()
        val taskTitle = "MDN失效视频验收-$tag"
        val ownedIds = mutableSetOf<String>()
        var saveRequested = false
        try {
            // Re-enter the real input screen without clearing app data or browser cookies.
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(originalPage))
                .setClassName(context.packageName, "com.videodown.douyin.presentation.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitNode { it.text?.toString() == originalPage }
            val variant = VideoVariant(oldSource, "MDN历史失效源")
            val video = DouyinVideo("expired-mdn-$tag", taskTitle, variants = listOf(variant),
                shareUrl = originalPage, platform = VideoPlatform.GENERIC)
            val oldId = ServiceLocator.downloader.start(video, variant).also { ownedIds.add(it) }
            val failed = withTimeout(120_000) { ServiceLocator.historyRepository.observeRecord(oldId).first {
                it?.status in setOf(DownloadStatus.FAILED, DownloadStatus.COMPLETED, DownloadStatus.PAUSED)
            }!! }
            assertEquals(failed.errorMessage, DownloadStatus.FAILED, failed.status)
            assertEquals(DownloadFailureKind.LINK_EXPIRED, failed.failureKind)
            assertTrue("Actual old URL must return 404", failed.errorMessage.orEmpty().contains("404"))
            assertEquals(originalPage, failed.sourcePageUrl)
            report("MDN_RECOVERY: production old source HTTP404, LINK_EXPIRED")
            clickText("任务")
            val title = awaitNode { it.text?.toString() == taskTitle }
            var container = title.parent
            var recovery: AccessibilityNodeInfo? = null
            while (container != null && recovery == null) {
                recovery = find(container) { it.text?.toString() == "重新解析原网址" }
                container = container.parent
            }
            assertNotNull("Recovery must belong to the unique own task", recovery)
            capture("old-source-failed")
            click(recovery!!)
            withTimeout(120_000) {
                while (text(originalPage) == null || text("当前内容暂不支持") == null || text("粘贴链接") == null) delay(100)
            }
            capture("original-page-auto-unsupported")
            report("MDN_RECOVERY: task recovery opened exact original HTML; automatic parser UNSUPPORTED")
            clickText("打开网页查找视频")
            // The real page's embedded demo loads the resource normally. No injected script or supplied new media URL.
            val candidate = awaitNode {
                val label = it.text?.toString().orEmpty()
                label.startsWith("WebM · ") && label.contains(".mdnplay.dev · 选择画质")
            }
            capture("natural-flower-candidate")
            click(candidate)
            awaitNode { it.text?.toString() == "flower.webm" }
            capture("flower-parsed")
            saveRequested = true
            clickText("保存到本地")
            val fresh = withTimeout(60_000) { ServiceLocator.historyRepository.observeAll().first { records ->
                records.any { it.id !in previousIds && it.id != oldId && it.sourcePageUrl == originalPage && it.title == "flower.webm" }
            }.single { it.id !in previousIds && it.id != oldId && it.sourcePageUrl == originalPage && it.title == "flower.webm" } }
            ownedIds.add(fresh.id)
            val source = fresh.sourceUrl.toHttpUrl()
            assertTrue(source.host.endsWith(".mdnplay.dev"))
            assertTrue("Select the flower resource, not unrelated archive.org examples", source.encodedPath.endsWith("/flower.webm"))
            assertEquals(originalPage, fresh.sourcePageUrl)
            val completed = withTimeout(120_000) { ServiceLocator.historyRepository.observeRecord(fresh.id).first {
                it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.PAUSED)
            }!! }
            assertEquals(completed.errorMessage, DownloadStatus.COMPLETED, completed.status)
            val file = File(completed.filePath!!)
            val bytes = file.readBytes()
            assertTrue(bytes.isNotEmpty())
            assertEquals(bytes.size.toLong(), completed.totalBytes)
            validateVideo(file)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertTrue((0 until extractor.trackCount).any {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
                })
            } finally { extractor.release() }
            val retriever = MediaMetadataRetriever()
            val duration = try {
                retriever.setDataSource(file.absolutePath)
                assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            } finally { retriever.release() }
            assertTrue(duration > 0)
            // Independently compare complete server bytes, using only the URL discovered by the production browser.
            val request = Request.Builder().url(completed.sourceUrl).header("Referer", originalPage).build()
            withContext(Dispatchers.IO) {
                ServiceLocator.douyinApiClient.okHttpClient.newCall(request).execute().use { response ->
                    assertTrue(response.isSuccessful)
                    assertArrayEquals(response.body!!.bytes(), bytes)
                }
            }
            capture("fresh-flower-completed")
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            report("MDN_RECOVERY: completed ${bytes.size} bytes, video duration=$duration, sha256=$sha; original MDN source retained")
        } finally {
            withContext(NonCancellable) {
                // Capture a record created immediately before cancellation even if the UI wait timed out.
                if (saveRequested) ServiceLocator.historyRepository.snapshot().filter {
                    it.id !in previousIds && it.sourcePageUrl == originalPage && it.title == "flower.webm"
                }.forEach { ownedIds.add(it.id) }
                try { capture("final-state") }
                finally { ownedIds.forEach { ServiceLocator.downloader.cancel(it, removeRecord = true) } }
            }
        }
    }
}
