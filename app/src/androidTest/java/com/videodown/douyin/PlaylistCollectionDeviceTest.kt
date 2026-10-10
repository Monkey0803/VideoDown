package com.videodown.douyin

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

/**
 * A collection page really becomes several selectable videos and can be downloaded entry by entry.
 *
 * The page is served locally, so the whole path runs offline through the bundled engine: page →
 * yt-dlp entries → one video per entry → one download per entry.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistCollectionDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ownedIds = mutableListOf<String>()
    private var originalPlaylist = false
    private lateinit var server: CollectionServer

    @Before fun setup() {
        originalPlaylist = ServiceLocator.preferences.playlistParsing
        // Two real, valid videos of different sizes: the app validates every finished file, so a
        // synthetic body would fail verification rather than prove per-entry addressing.
        val one = readAsset("landscape-fixture.mp4")
        val two = readAsset("aac-video-fixture.mp4")
        server = CollectionServer(one, two)
    }

    /** Same on-device evidence convention as the other suites: readable from `run-as`, never logged. */
    private fun evidence(name: String, text: String) {
        val directory = File(context.cacheDir, "playlist-evidence").apply { mkdirs() }
        File(directory, name).writeText(text)
    }

    private fun readAsset(name: String): ByteArray =
        instrumentation.context.assets.open(name).use { it.readBytes() }

    @After fun cleanup() = runBlocking {
        ServiceLocator.preferences.playlistParsing = originalPlaylist
        ownedIds.forEach { runCatching { ServiceLocator.downloader.cancel(it, true) } }
        server.close()
        Unit
    }

    private suspend fun parseCollection(): List<DouyinVideo> {
        val result = ServiceLocator.videoRepository.parseVideos(server.collectionUrl)
        assertTrue("Parsing the collection failed: $result", result is AppResult.Success)
        val videos = (result as AppResult.Success).value.videos
        assertTrue("A collection must yield at least one video", videos.isNotEmpty())
        return videos
    }

    @Test fun aCollectionPageBecomesSeveralSelectableVideos() = runBlocking {
        ServiceLocator.preferences.playlistParsing = true
        val videos = parseCollection()
        assertEquals("Both entries of the page must be offered", 2, videos.size)
        val urls = videos.flatMap { it.variants }.map { it.url }
        assertTrue(urls.toString(), urls.any { it.endsWith("/one.mp4") })
        assertTrue(urls.toString(), urls.any { it.endsWith("/two.mp4") })
        // Each entry keeps its own identity, so per-item selection cannot collapse onto one video.
        assertEquals(2, videos.map { it.awemeId }.distinct().size)
        evidence("collection-parse.txt", buildString {
            appendLine("page=${server.collectionUrl}")
            appendLine("videos=${videos.size}")
            videos.forEach { appendLine("  id=${it.awemeId} title=${it.title} variants=${it.variants.map { v -> v.url.substringAfterLast('/') }}") }
        })
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "COLLECTION_PARSE: 2 videos from one page, ids distinct\n")
        })
    }

    @Test fun everyEntryOfACollectionCanBeDownloaded() = runBlocking {
        assertTrue("Preserve any existing transfer", ServiceLocator.downloader.progress.value.values.none { it.isActive })
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("解析").fetchSemanticsNodes().isNotEmpty() }
        ServiceLocator.preferences.playlistParsing = true

        val videos = parseCollection()
        val expected = mapOf("/one.mp4" to server.oneBytes.size.toLong(), "/two.mp4" to server.twoBytes.size.toLong())
        assertTrue("The two entries must differ in size for the assertion to mean anything",
            expected["/one.mp4"] != expected["/two.mp4"])
        val started = videos.associateWith { video ->
            val variant = video.variants.first()
            ServiceLocator.downloader.start(video, variant).also(ownedIds::add)
        }
        val completed = withTimeout(90_000) {
            started.mapValues { (_, id) ->
                val finished = ServiceLocator.historyRepository.observeRecord(id)
                    .first { it?.status == DownloadStatus.COMPLETED || it?.status == DownloadStatus.FAILED }!!
                assertEquals("Task $id ended as ${finished.status}: ${finished.errorMessage}", DownloadStatus.COMPLETED, finished.status)
                finished
            }
        }
        assertEquals(2, completed.size)
        completed.forEach { (video, record) ->
            val variantUrl = video.variants.first().url
            val size = expected.entries.first { variantUrl.endsWith(it.key) }.value
            val file = File(record.filePath!!)
            assertEquals("Entry ${video.title} must download its own body", size, file.length())
            assertTrue("Entry ${video.title} must keep its own source url, got ${record.sourceUrl}",
                record.sourceUrl.endsWith(expected.entries.first { it.value == size }.key))
        }
        evidence("collection-download.txt", buildString {
            appendLine("entries=${videos.size}")
            completed.forEach { (video, record) ->
                appendLine("  ${video.variants.first().url.substringAfterLast('/')} bytes=${File(record.filePath!!).length()} source=${record.sourceUrl}")
            }
        })
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "COLLECTION_DOWNLOAD: both entries downloaded with their own byte counts\n")
        })
    }

    /** Serves a page with two videos plus their bodies; no Range support is needed for a 200 reply. */
    private class CollectionServer(val oneBytes: ByteArray, val twoBytes: ByteArray) : java.io.Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val collectionUrl get() = "http://127.0.0.1:${socket.localPort}/collection"
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                runCatching {
                    client.use { connection ->
                        val reader = connection.getInputStream().bufferedReader()
                        val line = reader.readLine() ?: return@use
                        while (true) { val header = reader.readLine() ?: break; if (header.isEmpty()) break }
                        val target = line.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
                        val (body, type) = when (target) {
                            "/collection" -> """<html><head><title>合集</title></head><body>
                                <video controls src="/one.mp4"></video>
                                <video controls src="/two.mp4"></video>
                                </body></html>""".toByteArray() to "text/html; charset=utf-8"
                            "/one.mp4" -> oneBytes to "video/mp4"
                            "/two.mp4" -> twoBytes to "video/mp4"
                            else -> ByteArray(0) to "text/plain"
                        }
                        val output: OutputStream = connection.getOutputStream()
                        val head = "HTTP/1.1 ${if (body.isEmpty()) "404 Not Found" else "200 OK"}\r\n" +
                            "Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        output.write(head.toByteArray())
                        output.write(body)
                        output.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            runCatching { socket.close() }
            thread.interrupt()
        }
    }
}
