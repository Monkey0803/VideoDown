package com.videodown.douyin

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
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
import com.videodown.douyin.data.generic.ImageMedia
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.home.HomeScreen
import com.videodown.douyin.presentation.home.HomeViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.util.UUID

/**
 * 直链图片端到端：解析 → 结果页「保存这张图片」→ 落盘为图片文件。
 *
 * 走真实的 `probeDirect` 与 `DownloadEngine`（只把历史与目录指向测试缓存），所以扩展名、
 * 魔数与校验失败路径都是真跑出来的。
 */
@RunWith(AndroidJUnit4::class)
class ImageDownloadDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val directory = File(instrumentation.targetContext.cacheDir, "image-download-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
        DownloadPaths(directory), scope, "", "VideoDown-Test")
    private lateinit var vm: HomeViewModel

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        compose.waitForIdle()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        directory.deleteRecursively()
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF3949AB.toInt())
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun show(repository: VideoRepository) {
        compose.runOnUiThread {
            vm = HomeViewModel(repository, engine, { it.defaultVariant }, fetchSize = { null })
            store.put("home", vm)
            compose.activity.setContent {
                VideoDownTheme {
                    HomeScreen(null, {}, {}, {}, {}, emptyList(), vm)
                }
            }
        }
    }

    @Test fun aDirectImageUrlIsParsedSavedAndStoredAsAnImage() {
        ImageFixtureServer(png()).use { server ->
            val url = server.baseUrl + "/photo.png"
            show(ServiceLocator.videoRepository)
            compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(url)
            compose.onNodeWithText("立即解析").performClick()
            compose.waitUntil(30_000) { vm.state.value.video != null || (!vm.state.value.isParsing && vm.state.value.error != null) }
            val video = vm.state.value.video
            assertNotNull(vm.state.value.error?.toString(), video)
            assertTrue("expected an image entry, got $video", video!!.isImagePost)
            assertTrue("expected the served image url, got ${video.variants.single().url}",
                video.variants.single().url.contains("/photo.png"))
            compose.onNodeWithText("保存这张图片").performClick()

            compose.waitUntil(60_000) {
                vm.state.value.activeRecord?.status in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)
            }
            val record = vm.state.value.activeRecord!!
            assertEquals(record.errorMessage, DownloadStatus.COMPLETED, record.status)
            val file = File(record.filePath!!)
            val head = file.inputStream().use { input -> ByteArray(8).also { input.read(it) } }
            assertEquals("the saved file is a PNG", 0x89.toByte(), head[0])
            assertEquals("png", file.extension)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "image download: ${record.fileName} ${file.length()} bytes\n")
            })
        }
    }

    @Test fun anImagePostShowsTheArchiveButtonAndHidesTheQualityPicker() {
        val images = (1..3).map { index ->
            val base = ImageMedia.entry(
                url = "https://cdn.example/$index.jpg",
                pageUrl = "https://page.example/post",
                extension = "jpg",
                sizeBytes = 1024L,
                platform = VideoPlatform.WEIBO,
            )
            val id = "post_img$index"
            base.copy(
                awemeId = id,
                variants = base.variants.map { variant -> variant.copy(media = variant.media?.copy(mediaId = id)) },
            )
        }
        val repository = object : VideoRepository {
            override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> = AppResult.Success(images.first())
            override suspend fun parseVideos(shareText: String): AppResult<ParsedVideos> = AppResult.Success(ParsedVideos(images))
        }
        show(repository)
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("https://page.example/post")
        compose.onNodeWithText("立即解析").performClick()
        compose.waitUntil(10_000) { vm.state.value.video != null }
        compose.onNodeWithTag("result-more-options").performClick()
        // 底部按钮不在结果页的滚动容器里，只能断言存在；它们固定贴在底部按钮区。
        compose.onNodeWithText("打包下载（3 张）").assertExists()
        compose.onNodeWithText("选择画质").assertDoesNotExist()
        compose.onNodeWithText("保存这张图片").assertExists()
    }

    @Test fun aPageUrlWithNoVideoStillFailsInsteadOfDownloadingHtml() {
        show(ServiceLocator.videoRepository)
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("https://example.invalid/no-media")
        compose.onNodeWithText("立即解析").performClick()
        compose.waitUntil(60_000) { !vm.state.value.isParsing }
        // 解析失败是允许的结果；这里只确认没有把网页当成图片落盘。
        assertTrue(vm.state.value.video == null)
    }
}

/** 为本地图片服务 HTTP；HEAD 只回头，GET 回完整 PNG（忽略 Range 也能被下载器正确重写）。 */
internal class ImageFixtureServer(private val body: ByteArray) : Closeable {
    private val socket = ServerSocket(0)
    val port: Int get() = socket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    private val thread = Thread({
        while (!socket.isClosed) {
            runCatching {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) line = reader.readLine()
                    val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: image/png\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n\r\n"
                    client.getOutputStream().apply {
                        write(head.toByteArray())
                        if (!requestLine.startsWith("HEAD")) write(body)
                        flush()
                    }
                }
            }
        }
    }, "image-fixture-server").apply { isDaemon = true; start() }

    override fun close() {
        runCatching { socket.close() }
        thread.interrupt()
    }
}
