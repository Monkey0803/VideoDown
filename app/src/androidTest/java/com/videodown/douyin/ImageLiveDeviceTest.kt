package com.videodown.douyin

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadEngine
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.download.ResumableDownloader
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.ArchiveRequest
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.archiveEntry
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * 图片能力的在线验收（opt-in）。
 *
 * 直链默认用 httpbin 的 PNG；平台图文链接必须由调用方给，因为真实作品链接会过期，
 * 把一条可能失效的链接写进仓库会让这组用例变成"永远失败"而不是"没测"：
 *
 * ```
 * -Pandroid.testInstrumentationRunnerArguments.live_images=true \
 * -Pandroid.testInstrumentationRunnerArguments.image_live_douyin=<note link> \
 * -Pandroid.testInstrumentationRunnerArguments.image_live_weibo=<multi image link> \
 * -Pandroid.testInstrumentationRunnerArguments.image_live_kuaishou=<atlas link> \
 * -Pandroid.testInstrumentationRunnerArguments.image_live_x=<photo link>
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ImageLiveDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val directory = File(instrumentation.targetContext.cacheDir, "image-live-${UUID.randomUUID()}").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
    private val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
        DownloadPaths(directory), scope, "", "VideoDown-Test")

    @After fun cleanup() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        directory.deleteRecursively()
    }

    @Test fun aDirectImageUrlParsesAndDownloadsOverTheRealNetwork() = runBlocking {
        assumeTrue("Pass -e live_images true", arguments.getString("live_images") == "true")
        val url = arguments.getString("image_live_url") ?: "https://httpbin.org/image/png"
        val parsed = ServiceLocator.videoRepository.parseVideos(url)
        val videos = (parsed as? AppResult.Success)?.value?.videos
        assertNotNull("parse failed for $url: $parsed", videos)
        assertTrue("expected an image entry, got ${videos!!.map { it.awemeId }}", videos.single().isImagePost)
        val video = videos.single()
        val id = engine.start(video, video.variants.single())
        val completed = withTimeout(180_000) {
            history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
        }
        assertEquals(completed.errorMessage, DownloadStatus.COMPLETED, completed.status)
        val file = File(completed.filePath!!)
        assertTrue("saved file is not empty", file.length() > 0L)
        report("direct image: ${file.name} ${file.length()} bytes")
    }

    @Test fun realPlatformImagePostsParseAndPackage() = runBlocking {
        assumeTrue("Pass -e live_images true", arguments.getString("live_images") == "true")
        val links = listOfNotNull(
            arguments.getString("image_live_douyin")?.let { "douyin" to it },
            arguments.getString("image_live_weibo")?.let { "weibo" to it },
            arguments.getString("image_live_kuaishou")?.let { "kuaishou" to it },
            arguments.getString("image_live_x")?.let { "x" to it },
        )
        assumeTrue("Pass image_live_douyin / image_live_weibo / image_live_kuaishou / image_live_x", links.isNotEmpty())
        links.forEach { (name, link) ->
            val parsed = ServiceLocator.videoRepository.parseVideos(link)
            val videos = (parsed as? AppResult.Success)?.value?.videos
                ?: throw AssertionError("$name parse failed: $parsed")
            val images = videos.filter { it.isImagePost }
            assertTrue("$name -> no image entries in ${videos.map { it.awemeId }}", images.isNotEmpty())
            val anchor = images.first()
            val request = ArchiveRequest(
                title = anchor.title,
                postId = anchor.awemeId.substringBefore("_img"),
                platform = anchor.platform,
                postPageUrl = anchor.videoWebUrl,
                entries = images.mapNotNull { it.archiveEntry() },
                coverUrl = anchor.coverUrl,
            )
            val id = engine.startArchive(request)
            val completed = withTimeout(180_000) {
                history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
            }
            assertEquals("$name: ${completed.errorMessage}", DownloadStatus.COMPLETED, completed.status)
            ZipFile(File(completed.filePath!!)).use { zip ->
                val entries = zip.entries().toList()
                val expected = request.entries.sumOf { 1 + if (it.livePhotoMotion != null) 1 else 0 }
                assertEquals("$name: entry count", expected, entries.size)
                entries.filter { it.name.startsWith("images/") }.forEach { entry ->
                    val head = zip.getInputStream(entry).use { input -> ByteArray(64).let { bytes ->
                        val count = input.read(bytes); if (count <= 0) ByteArray(0) else bytes.copyOf(count)
                    } }
                    assertNotNull("$name: ${entry.name} is not an image", com.videodown.douyin.domain.model.MediaSignature.imageExtension(head))
                }
            }
            report("$name images: ${images.size} entries -> ${completed.fileName}")
        }
    }

    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
