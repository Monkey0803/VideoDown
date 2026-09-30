package com.videodown.douyin

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.*
import com.videodown.douyin.data.download.*
import com.videodown.douyin.data.generic.*
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class GenericVideoDeviceTest {
    @Test fun optionalPublicPageParse() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("generic_live_url")
        org.junit.Assume.assumeTrue("Pass generic_live_url to enable a public site check", !url.isNullOrBlank())
        val result = ServiceLocator.videoRepository.parseVideos(url!!)
        assertTrue("Public parse failed: $result", result is AppResult.Success)
        val parsed = (result as AppResult.Success).value
        report("Public page: ${parsed.videos.size} video(s), ${parsed.selectedVideo.variants.size} formats, title=${parsed.selectedVideo.title.take(60)}")
        if (InstrumentationRegistry.getArguments().getString("generic_live_download") == "true") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.cacheDir, "public-test-${UUID.randomUUID()}").apply { mkdirs() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
                val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
                    DownloadPaths(directory), scope, "", "VideoDown-Test", engineDownloader = EngineMediaDownloader(
                        ServiceLocator.videoEngine, ServiceLocator.browserSessions, File(directory, "sessions")))
                val video = parsed.selectedVideo
                val variant = video.variants.minWith(compareBy<VideoVariant> { it.qualityRank }.thenBy { it.sizeBytes ?: Long.MAX_VALUE })
                report("Public download: ${variant.label}")
                val id = engine.start(video, variant)
                val completed = withTimeout(180_000) { history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!! }
                assertEquals(completed.errorMessage, DownloadStatus.COMPLETED, completed.status)
                assertMedia(File(completed.filePath!!))
                report("Public complete: ${completed.totalBytes} bytes, ${File(completed.filePath).extension}")
            } finally { scope.coroutineContext.job.cancelAndJoin(); directory.deleteRecursively() }
        }
    }

    /** Two instrumentation runs with a real force-stop between them; opt-in because the seed is intentionally killed. */
    @Test fun processExitResumeCheckpoint() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("process_exit_phase")
        org.junit.Assume.assumeTrue("Pass process_exit_phase=seed/resume", phase in setOf("seed", "resume"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = File(context.cacheDir, "generic-process-exit-task.txt")
        if (phase == "seed") {
            check(!marker.exists()) { "A process-exit fixture already exists; resume it first" }
            val url = requireNotNull(arguments.getString("generic_live_url"))
            val parsed = ServiceLocator.videoRepository.parseVideos(url) as AppResult.Success
            val video = parsed.value.selectedVideo
            val variant = video.variants.filter { it.media?.usesEngine == true }
                .minWith(compareBy<VideoVariant> { it.qualityRank }.thenBy { it.sizeBytes ?: Long.MAX_VALUE })
            val id = ServiceLocator.downloader.start(video.copy(title = "进程退出恢复测试"), variant)
            marker.writeText(id)
            withTimeout(60_000) { ServiceLocator.downloader.progress.first { (it[id]?.downloadedBytes ?: 0) > 0 } }
            report("PROCESS_EXIT_READY")
            awaitCancellation() // The external harness must force-stop the target package here.
        } else {
            val id = marker.readText().trim()
            try {
                val paused = withTimeout(10_000) {
                    ServiceLocator.historyRepository.observeRecord(id).first { it?.status == DownloadStatus.PAUSED }!!
                }
                assertTrue(paused.media!!.usesEngine)
                assertTrue(ServiceLocator.downloadPaths.engineDirectory(id).listFiles().orEmpty().isNotEmpty())
                ServiceLocator.downloader.resume(id)
                val completed = withTimeout(240_000) {
                    ServiceLocator.historyRepository.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
                }
                assertEquals(completed.errorMessage, DownloadStatus.COMPLETED, completed.status)
                assertMedia(File(completed.filePath!!))
                report("PROCESS_EXIT_RESUMED bytes=${completed.totalBytes}")
            } finally {
                ServiceLocator.downloader.cancel(id, true)
                marker.delete()
            }
        }
    }

    @Test fun runtimeAndDirectHlsDashDownloadsProduceVideoAndAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ServiceLocator.videoEngine.inspectVersion()
        assertTrue(ServiceLocator.videoEngine.version.value, ServiceLocator.videoEngine.version.value.contains("2026.08.19"))
        val directory = File(context.cacheDir, "generic-test-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            createMediaFixtures(context, directory)
            FixtureServer(directory).use { server ->
                val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
                val paths = DownloadPaths(File(directory, "downloads"))
                val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient), paths,
                    scope, "", "VideoDown-Test", engineDownloader = EngineMediaDownloader(ServiceLocator.videoEngine,
                        ServiceLocator.browserSessions, File(directory, "sessions")))
                for (path in listOf("/sample.mp4", "/opaque", "/page", "/sample.m3u8", "/master.m3u8", "/sample.mpd")) {
                    withTimeout(90_000) {
                        report("Parsing $path")
                        val parsed = ServiceLocator.genericRepository.parseVideos(server.baseUrl + path)
                        assertTrue("$path: $parsed", parsed is AppResult.Success)
                        val video = (parsed as AppResult.Success).value.selectedVideo
                        if (path == "/master.m3u8") assertEquals(2, video.variants.size)
                        val variant = video.defaultVariant!!
                        if (path.endsWith("m3u8") || path.endsWith("mpd")) assertTrue(variant.media!!.usesEngine)
                        val id = engine.start(video, variant)
                        val result = history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.PAUSED) }!!
                        assertEquals("$path: ${result.errorMessage}", DownloadStatus.COMPLETED, result.status)
                        assertMedia(File(result.filePath!!))
                        report("Completed $path: ${result.totalBytes} bytes, ${File(result.filePath).extension}")
                    }
                }
            }
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun cancellationStopsEngineAndPolicyBlocksBeforeNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "generic-cancel-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            FixtureServer(directory, slow = true).use { server ->
                var groupId = 0
                val job = launch {
                    ServiceLocator.videoEngine.execute(buildJsonObject { put("mode", "parse"); put("url", server.baseUrl + "/slow") }) {
                        if (it["event"]?.jsonPrimitive?.content == "pid") groupId = it["pid"]!!.jsonPrimitive.int
                    }
                }
                withTimeout(15_000) { while (!server.requested.get()) delay(30) }
                withTimeout(5_000) { job.cancelAndJoin() }
                assertTrue(groupId > 0)
                withTimeout(3_000) {
                    while (runCatching { android.system.Os.kill(-groupId, 0) }.isSuccess) delay(30)
                }
                val denied = object : DownloadNetworkPolicy {
                    override val allowed = kotlinx.coroutines.flow.flowOf(false)
                    override fun isAllowed() = false
                }
                try {
                    ServiceLocator.videoEngine.execute(buildJsonObject { put("mode", "parse"); put("url", server.baseUrl) }, denied) {}
                    fail("Wi-Fi policy must block engine start")
                } catch (_: WifiRequiredException) { }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun segmentedTaskPausesOnWifiLossAndResumesWithoutDuplicateCompletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "segment-resume-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val policy = object : DownloadNetworkPolicy {
            override val allowed = kotlinx.coroutines.flow.MutableStateFlow(true)
            override fun isAllowed() = allowed.value
        }
        try {
            createMediaFixtures(context, directory)
            FixtureServer(directory, throttleSegments = true).use { server ->
                val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
                val paths = DownloadPaths(File(directory, "downloads"))
                var completions = 0
                val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient), paths,
                    scope, "", "fixture", networkPolicy = policy, onCompleted = { completions++ },
                    engineDownloader = EngineMediaDownloader(ServiceLocator.videoEngine, ServiceLocator.browserSessions, File(directory, "sessions")))
                val parsed = ServiceLocator.genericRepository.parseVideos(server.baseUrl + "/sample.m3u8") as AppResult.Success
                val video = parsed.value.selectedVideo
                val id = engine.start(video, video.defaultVariant!!)
                withTimeout(30_000) { engine.progress.first { (it[id]?.downloadedBytes ?: 0) > 0 } }
                policy.allowed.value = false
                val paused = withTimeout(5_000) { history.observeRecord(id).first { it?.status == DownloadStatus.PAUSED }!! }
                assertEquals(WIFI_PAUSE_REASON, paused.errorMessage)
                assertEquals(0, completions)
                assertTrue(paths.engineDirectory(id).listFiles().orEmpty().isNotEmpty())
                engine.pause(id) // joins the task before resuming
                policy.allowed.value = true
                engine.resume(id)
                val complete = withTimeout(60_000) { history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!! }
                assertEquals(complete.errorMessage, DownloadStatus.COMPLETED, complete.status)
                withTimeout(2_000) { while (completions != 1) delay(10) }
                assertMedia(File(complete.filePath!!))
                assertFalse(paths.engineDirectory(id).exists())
            }
        } finally { scope.coroutineContext.job.cancelAndJoin(); directory.deleteRecursively() }
    }

    @Test fun protectedFileIsRejectedBeforeTaskCanComplete() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "protected-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            createMediaFixtures(context, directory)
            try {
                validateVideo(File(directory, "protected.mp4"))
                fail("Encrypted media must not count as a successful playable download")
            } catch (error: VideoEngineException) { assertEquals("DRM", error.kind) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellingAtMergeStopsProcessGroupAndCanResume() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "merge-cancel-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            createMediaFixtures(context, directory)
            FixtureServer(directory).use { server ->
                val parsed = ServiceLocator.genericRepository.parseVideos(server.baseUrl + "/sample.mpd") as AppResult.Success
                val variant = parsed.value.selectedVideo.defaultVariant!!
                val output = File(directory, "merging").apply { mkdirs() }
                val request = buildJsonObject {
                    put("mode", "download"); put("url", variant.media!!.pageUrl)
                    put("media_id", variant.media!!.mediaId); put("format", variant.media!!.formatSelector)
                    put("output", File(output, "media.%(ext)s").absolutePath)
                }
                var groupId = 0
                var merged = false
                var completed = false
                val job = launch {
                    ServiceLocator.videoEngine.execute(request) { event ->
                        when (event["event"]?.jsonPrimitive?.content) {
                            "pid" -> groupId = event["pid"]!!.jsonPrimitive.int
                            "phase" -> { merged = true; currentCoroutineContext().cancel() }
                            "complete" -> completed = true
                        }
                    }
                }
                withTimeout(45_000) { job.join() }
                assertTrue("Must reach the merge phase", merged)
                assertFalse(completed)
                assertTrue(groupId > 0)
                withTimeout(3_000) { while (runCatching { android.system.Os.kill(-groupId, 0) }.isSuccess) delay(30) }
                assertTrue(File(output, ".selection").isFile)
                withTimeout(45_000) {
                    ServiceLocator.videoEngine.execute(request) { if (it["event"]?.jsonPrimitive?.content == "complete") completed = true }
                }
                assertTrue(completed)
                assertMedia(output.listFiles()!!.single { it.name == "media.mp4" })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun browserCookieRedirectsNeverLeakAcrossHosts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "cookie-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            createMediaFixtures(context, directory)
            FixtureServer(directory).use { server ->
                withContext(Dispatchers.Main) {
                    android.webkit.CookieManager.getInstance().setCookie(server.baseUrl, "session=private; Path=/")
                    android.webkit.CookieManager.getInstance().flush()
                }
                val result = ServiceLocator.genericRepository.parse(server.baseUrl + "/redirect", true)
                assertTrue(result.toString(), result is AppResult.Success)
                assertFalse("Cookie must not reach localhost after 127.0.0.1 redirect", server.redirectCookie.get())
                ServiceLocator.browserSessions.clear()
                assertNull(ServiceLocator.browserSessions.cookie(server.baseUrl))
            }
        } finally { directory.deleteRecursively() }
    }

    private fun assertMedia(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            assertTrue("Video track: $types", types.any { it.startsWith("video/") })
            assertTrue("Audio track: $types", types.any { it.startsWith("audio/") })
        } finally { extractor.release() }
    }
}

internal suspend fun createMediaFixtures(context: Context, directory: File) = withContext(Dispatchers.IO) {
    // Initializes the pinned native binaries before fixture generation.
    ServiceLocator.videoEngine.inspectVersion()
    val source = File(directory, "source.mp4")
    InstrumentationRegistry.getInstrumentation().context.assets.open("landscape-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
    fun ffmpeg(vararg args: String) {
        val base = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val process = ProcessBuilder(listOf(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath, "-y", "-hide_banner", "-loglevel", "error") + args)
            .directory(directory).redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = "${File(base, "ffmpeg/usr/lib")}:${File(base, "python/usr/lib")}"
            }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }
    ffmpeg("-i", source.absolutePath, "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=44100", "-t", "3", "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "sample.mp4")
    ffmpeg("-i", "sample.mp4", "-c", "copy", "-encryption_scheme", "cenc-aes-ctr",
        "-encryption_key", "00000000000000000000000000000000", "-encryption_kid", "11111111111111111111111111111111", "protected.mp4")
    ffmpeg("-i", "sample.mp4", "-c", "copy", "-hls_time", "1", "-hls_list_size", "0", "sample.m3u8")
    ffmpeg("-i", "sample.mp4", "-map", "0", "-c", "copy", "-f", "dash", "sample.mpd")
    File(directory, "master.m3u8").writeText("""#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=160000,RESOLUTION=640x360
sample.m3u8?quality=360
#EXT-X-STREAM-INF:BANDWIDTH=320000,RESOLUTION=1280x720
sample.m3u8?quality=720
""")
}

internal class FixtureServer(private val directory: File, private val slow: Boolean = false, private val throttleSegments: Boolean = false) : java.io.Closeable {
    private val socket = ServerSocket(0)
    private val pool = Executors.newCachedThreadPool()
    val requested = AtomicBoolean(false)
    val redirectCookie = AtomicBoolean(false)
    val baseUrl get() = "http://127.0.0.1:${socket.localPort}"
    init {
        pool.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                pool.execute {
                    client.use { connection ->
                        runCatching {
                            val input = connection.getInputStream().bufferedReader()
                            val line = input.readLine() ?: return@runCatching
                            val target = line.split(' ')[1].substringBefore('?')
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val header = input.readLine() ?: break
                                if (header.isEmpty()) break
                                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                            }
                            requested.set(true)
                            if (slow) { Thread.sleep(30_000); return@runCatching }
                            val output = connection.getOutputStream()
                            if (target == "/redirect") {
                                output.write("HTTP/1.1 302 Found\r\nLocation: http://localhost:${socket.localPort}/opaque\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                return@runCatching
                            }
                            if (headers["host"]?.startsWith("localhost") == true && headers["cookie"] != null) redirectCookie.set(true)
                            val html = when (target) {
                                "/page" -> "<html><head><title>Fixture video</title></head><body><video controls src='/sample.mp4'></video></body></html>"
                                "/dynamic" -> """<html><head><title>Dynamic fixture</title><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0"><button style="position:absolute;inset:0;width:100%;height:100%" onclick="this.remove();let v=document.createElement('video');v.controls=true;v.src='/opaque';v.style.width='100%';document.body.append(v);v.play()">播放测试视频</button></body></html>"""
                                else -> null
                            }
                            val file = File(directory, if (target == "/opaque") "sample.mp4" else target.removePrefix("/"))
                            val body = html?.toByteArray() ?: file.takeIf { it.isFile && it.canonicalPath.startsWith(directory.canonicalPath + "/") }?.readBytes() ?: byteArrayOf()
                            val range = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                            val requestedEnd = headers["range"]?.substringAfter('-')?.toIntOrNull()
                            val end = (requestedEnd ?: (body.size - 1)).coerceAtMost(body.size - 1)
                            val partial = headers.containsKey("range") && body.isNotEmpty() && range <= end
                            val bytes = if (partial) body.copyOfRange(range, end + 1) else body
                            val mime = when { html != null -> "text/html"; target.endsWith("m3u8") -> "application/vnd.apple.mpegurl"; target.endsWith("mpd") -> "application/dash+xml"; else -> "application/octet-stream" }
                            val status = if (partial) "206 Partial Content" else if (body.isEmpty()) "404 Not Found" else "200 OK"
                            output.write(buildString {
                                append("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nAccept-Ranges: bytes\r\n")
                                if (partial) append("Content-Range: bytes $range-$end/${body.size}\r\n")
                                append("Connection: close\r\n\r\n")
                            }.toByteArray())
                            if (!line.startsWith("HEAD ")) {
                                if (throttleSegments && (target.endsWith(".ts") || target.endsWith(".m4s"))) {
                                    for (offset in bytes.indices step 1024) {
                                        output.write(bytes, offset, minOf(1024, bytes.size - offset)); output.flush(); Thread.sleep(80)
                                    }
                                } else output.write(bytes)
                            }
                            output.flush()
                        }
                    }
                }
            }
        }
    }
    override fun close() { socket.close(); pool.shutdownNow() }
}

internal fun report(message: String) {
    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
