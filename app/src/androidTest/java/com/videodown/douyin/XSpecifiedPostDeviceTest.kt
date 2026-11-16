package com.videodown.douyin

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadEngine
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.download.ResumableDownloader
import com.videodown.douyin.data.download.validateDownloadedFile
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.ArchiveRequest
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.model.archiveEntry
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.home.HomeScreen
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import com.videodown.douyin.presentation.viewer.ImageViewerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * 指定 X 推文的真机/模拟器验收：解析 → 图片打包 → 看图器截图。
 *
 * ```
 * adb shell am instrument -w \
 *   -e class com.videodown.douyin.XSpecifiedPostDeviceTest \
 *   -e x_post_url 'https://x.com/user/status/<id>' \
 *   com.videodown.douyin.debug.test/com.videodown.douyin.LocaleTestRunner
 * ```
 *
 * 产物落在应用私有目录 `files/x-evidence/`，用 `adb exec-out run-as <pkg> cat …` 取回。
 */
@RunWith(AndroidJUnit4::class)
class XSpecifiedPostDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val evidence = File(context.filesDir, "x-evidence").apply { mkdirs() }
    private val directory = File(context.cacheDir, "x-post-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val engine = DownloadEngine(
        history,
        ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
        DownloadPaths(directory), scope, "", "VideoDown-Test",
        validateFile = { file, record -> validateDownloadedFile(file, record) },
    )

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        directory.deleteRecursively()
    }

    private fun report(line: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$line\n") })

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.FileOutputStream(File(evidence, name)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        report("screenshot: x-evidence/$name")
    }

    @Test fun verifySpecifiedXPost() = runBlocking {
        val url = arguments.getString("x_post_url")
        assumeTrue("Pass -e x_post_url=<url>", !url.isNullOrBlank())
        val summary = StringBuilder("url: $url\n")
        fun log(line: String) {
            summary.append(line).append('\n')
            report(line)
        }

        val parsed = ServiceLocator.videoRepository.parseVideos(url!!)
        val videos = when (parsed) {
            is AppResult.Success -> parsed.value.videos
            is AppResult.Failure -> {
                log("parse failure: ${parsed.error}")
                File(evidence, "x-post-summary.txt").writeText(summary.toString())
                throw AssertionError("parse failed: ${parsed.error}")
            }
        }
        log("entries: ${videos.size}")
        videos.forEachIndexed { index, video ->
            log("[$index] id=${video.awemeId} image=${video.isImagePost} title=${video.title.take(60)}")
            video.variants.forEach { variant ->
                log("    variant label=${variant.label} ext=${variant.extensionOrDefault} size=${variant.sizeBytes} kind=${variant.kind}")
            }
        }

        // 结果页截图：把解析结果灌进 HomeScreen，看真实界面。
        showResult(videos, url)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("查看全部").fetchSemanticsNodes().isNotEmpty() }
        // 让 Coil 把封面真正解码出来再截图，否则会拍到空封面。
        compose.waitForIdle()
        // 等图片大小探测结束再截图，避免拍到「获取大小中…」。
        compose.waitUntil(20_000) {
            runCatching { compose.onAllNodesWithText("获取大小中…").fetchSemanticsNodes().isEmpty() }.getOrDefault(false)
        }
        Thread.sleep(800)
        capture("x-post-result.png")

        val images = videos.filter { it.isImagePost }
        if (images.isNotEmpty()) {
            val anchor = images.first()
            val request = ArchiveRequest(
                title = anchor.title,
                postId = anchor.awemeId.substringBefore("_img"),
                platform = anchor.platform,
                postPageUrl = anchor.videoWebUrl,
                entries = images.mapNotNull { it.archiveEntry() },
                coverUrl = anchor.coverUrl,
            )
            log("archive: ${request.entries.size} images -> one zip")
            val id = engine.startArchive(request)
            val completed = withTimeout(180_000) {
                history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
            }
            assertEquals(completed.errorMessage, DownloadStatus.COMPLETED, completed.status)
            val zip = File(completed.filePath!!)
            ZipFile(zip).use { archive ->
                val entries = archive.entries().toList()
                log("zip: ${zip.length()} bytes, ${entries.size} entries, first=${entries.first().name}")
                assertEquals(request.entries.size, entries.size)
            }
            zip.copyTo(File(evidence, "x-post-images.zip"), overwrite = true)

            // 下载第一张图片并在看图器里截图（真实图片，不是占位）。
            val first = images.first()
            val single = engine.start(first, first.variants.single())
            val singleDone = withTimeout(120_000) {
                history.observeRecord(single).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
            }
            assertEquals(singleDone.errorMessage, DownloadStatus.COMPLETED, singleDone.status)
            log("first image: ${File(singleDone.filePath!!).name} ${File(singleDone.filePath).length()} bytes")
            compose.runOnUiThread {
                compose.activity.setContent {
                    VideoDownTheme { ImageViewerScreen(singleDone, onBack = {}) }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("image-viewer").fetchSemanticsNodes().isNotEmpty()
            }
            // 节点存在不代表 Coil 已经画出像素；等一帧稳定后再截图。
            compose.waitForIdle()
            Thread.sleep(1500)
            capture("x-post-image.png")
        } else {
            val variant = videos.firstOrNull()?.variants?.minByOrNull { it.sizeBytes ?: Long.MAX_VALUE }
            assertNotNull("no variant to download", variant)
            val single = engine.start(videos.first(), variant!!)
            val done = withTimeout(180_000) {
                history.observeRecord(single).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
            }
            assertEquals(done.errorMessage, DownloadStatus.COMPLETED, done.status)
            log("video: ${File(done.filePath!!).name} ${File(done.filePath).length()} bytes")
        }

        File(evidence, "x-post-summary.txt").writeText(summary.toString())
        assertTrue("expected at least one entry", videos.isNotEmpty())
    }

    private fun showResult(videos: List<com.videodown.douyin.domain.model.DouyinVideo>, url: String) {
        val repository = object : VideoRepository {
            override suspend fun parseShareText(shareText: String) = AppResult.Success(ParsedVideos(videos).selectedVideo)
            override suspend fun parseVideos(shareText: String) = AppResult.Success(ParsedVideos(videos))
        }
        compose.runOnUiThread {
            val probe = com.videodown.douyin.data.remote.VideoSizeProbe(ServiceLocator.douyinApiClient.okHttpClient)
            val vm = HomeViewModel(repository, engine, { it.defaultVariant }, fetchSize = { probe.sizeBytes(it) })
            store.put("x-post", vm)
            compose.activity.setContent {
                VideoDownTheme { HomeScreen(null, {}, {}, {}, {}, emptyList(), vm) }
            }
            vm.parseSharedText(url)
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("打包下载", substring = true).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("保存到本地").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("保存当前视频").fetchSemanticsNodes().isNotEmpty() }
    }
}
