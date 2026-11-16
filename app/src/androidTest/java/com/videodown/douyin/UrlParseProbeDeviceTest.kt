package com.videodown.douyin

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppError
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadEngine
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.download.ResumableDownloader
import com.videodown.douyin.data.download.validateDownloadedFile
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.remote.VideoSizeProbe
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.ArchiveRequest
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.DouyinVideo
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * 手动探针：把任意链接交给真实 repository，打印解析结果；图片帖再做一次打包 + 结果页/看图器截图。
 *
 * ```
 * adb shell "am instrument -w \
 *   -e class com.videodown.douyin.UrlParseProbeDeviceTest \
 *   -e probe_url 'https://weibo.com/<uid>/<mblogid>' \
 *   com.videodown.douyin.debug.test/com.videodown.douyin.LocaleTestRunner"
 * ```
 *
 * 产物在应用私有 `files/probe-evidence/`（summary.txt、zip、截图），用 `adb exec-out run-as` 取回。
 */
@RunWith(AndroidJUnit4::class)
class UrlParseProbeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val evidence = File(context.filesDir, "probe-evidence").apply { mkdirs() }
    private val directory = File(context.cacheDir, "probe-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val engine = DownloadEngine(
        history,
        ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
        DownloadPaths(directory), scope, "", "VideoDown-Probe",
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
        java.io.FileOutputStream(File(evidence, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        report("screenshot: probe-evidence/$name")
    }

    @Test fun probe() = runBlocking {
        val url = arguments.getString("probe_url")
        assumeTrue("Pass -e probe_url=<url>", !url.isNullOrBlank())
        val summary = StringBuilder("url: $url\n")
        fun log(line: String) {
            summary.append(line).append('\n')
            report(line)
        }

        val started = System.currentTimeMillis()
        val videos = when (val result = ServiceLocator.videoRepository.parseVideos(url!!)) {
            is AppResult.Success -> {
                log("elapsed: ${System.currentTimeMillis() - started} ms")
                result.value.videos
            }
            is AppResult.Failure -> {
                val error = result.error
                log("elapsed: ${System.currentTimeMillis() - started} ms")
                log("failure: ${error::class.simpleName} message=${error.message}")
                when (error) {
                    is AppError.Parse -> log("  reasonKey=${error.reasonKey} platform=${error.platform} detail=${error.detail}")
                    is AppError.Http -> log("  http=${error.code} platform=${error.platform}")
                    is AppError.Network -> log("  platform=${error.platform} reasonKey=${error.reasonKey} cause=${error.cause}")
                    else -> log("  detail=$error")
                }
                File(evidence, "summary.txt").writeText(summary.toString())
                throw AssertionError("parse failed: ${error.message}")
            }
        }
        log("entries: ${videos.size}")
        videos.forEachIndexed { index, video ->
            log("[$index] id=${video.awemeId} platform=${video.platform} image=${video.isImagePost} kind=${video.variants.map { it.kind }.distinct()} title=${video.title.take(60)}")
            video.variants.take(3).forEach { v ->
                log("    variant label=${v.label} ext=${v.extensionOrDefault} size=${v.sizeBytes} url=${v.url.take(100)}")
            }
        }

        val images = videos.filter { it.isImagePost }
        if (images.isEmpty()) {
            File(evidence, "summary.txt").writeText(summary.toString())
            return@runBlocking
        }

        // 结果页截图：把真实解析结果灌进 HomeScreen。
        showResult(videos, url)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("查看全部").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.waitUntil(20_000) {
            runCatching { compose.onAllNodesWithText("获取大小中…").fetchSemanticsNodes().isEmpty() }.getOrDefault(false)
        }
        Thread.sleep(800)
        capture("result.png")

        // 打包 + 校验，再下第一张给看图器截图。
        val anchor = images.first()
        val request = ArchiveRequest(
            title = anchor.title,
            postId = anchor.awemeId.substringBefore("_img"),
            platform = anchor.platform,
            postPageUrl = anchor.videoWebUrl,
            entries = images.mapNotNull { it.archiveEntry() },
            coverUrl = anchor.coverUrl,
        )
        log("archive: ${request.entries.size} images")
        val archiveId = engine.startArchive(request)
        val archived = withTimeout(180_000) {
            history.observeRecord(archiveId).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
        }
        assertEquals(archived.errorMessage, DownloadStatus.COMPLETED, archived.status)
        val zip = File(archived.filePath!!)
        ZipFile(zip).use { file ->
            val entries = file.entries().toList()
            log("zip: ${zip.length()} bytes, ${entries.size} entries, first=${entries.first().name}")
            assertEquals(request.entries.size, entries.size)
        }
        zip.copyTo(File(evidence, "images.zip"), overwrite = true)

        val first = images.first()
        val singleId = engine.start(first, first.variants.single())
        val single = withTimeout(120_000) {
            history.observeRecord(singleId).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
        }
        assertEquals(single.errorMessage, DownloadStatus.COMPLETED, single.status)
        log("first image: ${File(single.filePath!!).name} ${File(single.filePath).length()} bytes")
        compose.runOnUiThread {
            compose.activity.setContent { VideoDownTheme { ImageViewerScreen(single, onBack = {}) } }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("image-viewer").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        Thread.sleep(1500)
        capture("viewer.png")

        File(evidence, "summary.txt").writeText(summary.toString())
    }

    private fun showResult(videos: List<DouyinVideo>, url: String) {
        val repository = object : VideoRepository {
            override suspend fun parseShareText(shareText: String) = AppResult.Success(ParsedVideos(videos).selectedVideo)
            override suspend fun parseVideos(shareText: String) = AppResult.Success(ParsedVideos(videos))
        }
        val probe = VideoSizeProbe(ServiceLocator.douyinApiClient.okHttpClient)
        compose.runOnUiThread {
            val vm = HomeViewModel(repository, engine, { it.defaultVariant }, fetchSize = { probe.sizeBytes(it) })
            store.put("probe", vm)
            compose.activity.setContent { VideoDownTheme { HomeScreen(null, {}, {}, {}, {}, emptyList(), vm) } }
            vm.parseSharedText(url)
        }
    }
}
