package com.videodown.douyin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadNetworkPolicy
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.download.EngineRateControl
import com.videodown.douyin.data.generic.EngineMediaDownloader
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.MediaDownload
import com.videodown.douyin.domain.model.MediaSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * I5：引擎进程真的被共享带宽预算压住了。
 *
 * 用一个比令牌桶容量大得多的本地 HLS 素材（约 2 MiB）走随包 yt-dlp：
 *  - 单任务给全局限速，下载耗时必须接近「字节数 ÷ 上限」，而不是一口气拉完；
 *  - 两个任务同时下载时共享同一个全局桶，总耗时要接近「两份字节 ÷ 同一个上限」。
 * 第二条正是 I5 与之前「每个进程各自 ratelimit」的区别：各自限速时并行下载的耗时会接近单份时间。
 */
@RunWith(AndroidJUnit4::class)
class EngineBandwidthDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    // 本机 FixtureServer 的吞吐约 1 MB/s，上限取 256 KB/s 才能把「预算」和「服务器带宽」区分开。
    private val cap = 256L * 1024L

    private fun ffmpeg(directory: File, vararg args: String) {
        val base = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val process = ProcessBuilder(
            listOf(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath, "-y", "-hide_banner", "-loglevel", "error") + args,
        ).directory(directory).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = "${File(base, "ffmpeg/usr/lib")}:${File(base, "python/usr/lib")}"
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }

    /** 约 2 MiB、8 秒的测试图案视频；走 yt-dlp 直连下载，进度事件按块上报，正好被预算逐块节流。 */
    private suspend fun createLargeFixture(directory: File) = withContext(Dispatchers.IO) {
        ServiceLocator.videoEngine.inspectVersion()
        ffmpeg(
            directory,
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25", "-t", "8",
            "-c:v", "mpeg4", "-b:v", "2400k", "-g", "25", "big.mp4",
        )
    }

    private fun record(id: String, server: String) = DownloadRecord(
        id = id,
        awemeId = "bandwidth-test",
        title = "共享带宽用例",
        fileName = "engine-$id.mp4",
        sourceUrl = "$server/big.mp4",
        media = MediaDownload(
            // 直连媒体也要走引擎路径（非 DIRECT 类型让 `usesEngine` 为真，且不要求音轨），
            // 这样进度事件由 yt-dlp 逐块上报，才能验证预算是否真的在暂停进程。
            type = MediaSourceType.HLS,
            pageUrl = "$server/big.mp4",
            mediaId = "big",
            extension = "mp4",
        ),
    )

    private fun control(id: String) = EngineRateControl(
        sessionId = id,
        globalLimit = { cap },
        taskLimit = { 0L },
    )

    @Test fun aSingleEngineDownloadIsPacedByTheSharedBudget() = runBlocking {
        val directory = File(context.cacheDir, "engine-bandwidth-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            createLargeFixture(directory)
            val bytes = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
            FixtureServer(directory).use { server ->
                val downloader = EngineMediaDownloader(
                    ServiceLocator.videoEngine, ServiceLocator.browserSessions, File(directory, "sessions"),
                )
                val started = System.currentTimeMillis()
                val output = withTimeout(120_000) {
                    downloader.download(
                        record("single-${UUID.randomUUID()}", server.baseUrl), DownloadPaths(directory),
                        DownloadNetworkPolicy.Unrestricted, null, control("single"),
                    ) { _, _, _, _ -> }
                }
                val elapsedMs = System.currentTimeMillis() - started
                assertTrue("fixture must be worth pacing: $bytes bytes", bytes > 512 * 1024)
                val rate = bytes * 1000.0 / elapsedMs
                assertTrue(
                    "aggregate $rate B/s must stay near the $cap B/s cap (elapsed ${elapsedMs}ms)",
                    rate <= cap * 1.8,
                )
                assertTrue(
                    "the engine must take real time, elapsed ${elapsedMs}ms for $bytes bytes at $cap B/s",
                    elapsedMs >= bytes * 1000L / cap * 6 / 10,
                )
                assertEquals("engine output must validate", true, output.isFile && output.length() > 0)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun twoEngineDownloadsShareOneGlobalBudget() = runBlocking {
        val directory = File(context.cacheDir, "engine-bandwidth-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            createLargeFixture(directory)
            val bytes = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
            FixtureServer(directory).use { server ->
                val downloader = EngineMediaDownloader(
                    ServiceLocator.videoEngine, ServiceLocator.browserSessions, File(directory, "sessions"),
                )
                val started = System.currentTimeMillis()
                val outputs = withTimeout(180_000) {
                    listOf("first", "second").map { label ->
                        async(Dispatchers.IO) {
                            downloader.download(
                                record("$label-${UUID.randomUUID()}", server.baseUrl), DownloadPaths(directory),
                                DownloadNetworkPolicy.Unrestricted, null, control(label),
                            ) { _, _, _, _ -> }
                        }
                    }.awaitAll()
                }
                val elapsedMs = System.currentTimeMillis() - started
                val totalBytes = bytes * 2
                val rate = totalBytes * 1000.0 / elapsedMs
                assertTrue(
                    "two processes must share one bucket: aggregate $rate B/s (cap $cap B/s, elapsed ${elapsedMs}ms)",
                    rate <= cap * 1.8,
                )
                // 各自限速时两路并行耗时接近一份；共享同一个桶时必须接近两份字节的等待时间。
                assertTrue(
                    "sharing must roughly double the wall clock, elapsed ${elapsedMs}ms for $totalBytes bytes",
                    elapsedMs >= totalBytes * 1000L / cap * 6 / 10,
                )
                outputs.forEach { assertTrue("every engine output must exist", it.isFile && it.length() > 0) }
            }
        } finally { directory.deleteRecursively() }
    }
}
