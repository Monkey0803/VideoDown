package com.videodown.douyin

import android.content.Intent
import android.media.MediaExtractor
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.SubtitleLanguageSelection
import com.videodown.douyin.domain.model.SubtitleSelection
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.player.PlayerScreen
import com.videodown.douyin.presentation.player.PlayerViewModel
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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
 * A page that advertises a subtitle track offers it, and downloading with it embeds a real text
 * track into the finished file — verified by reading the container and by the player's own toggle.
 *
 * Everything is served locally, so the whole path runs offline through the bundled engine.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleDownloadDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var originalSubtitles = true
    private lateinit var server: SubtitleServer
    private val ownedIds = mutableListOf<String>()

    @Before fun setup() {
        originalSubtitles = ServiceLocator.preferences.subtitlesEnabled
        ServiceLocator.preferences.subtitlesEnabled = true
        server = SubtitleServer(instrumentation.context.assets.open("landscape-fixture.mp4").use { it.readBytes() })
        runBlocking { context.getSharedPreferences("playback_preferences", 0).edit().clear().commit() }
    }

    @After fun cleanup() = runBlocking {
        ServiceLocator.preferences.subtitlesEnabled = originalSubtitles
        ownedIds.forEach { runCatching { ServiceLocator.downloader.cancel(it, true) } }
        server.close()
        Unit
    }

    private fun evidence(name: String, text: String) {
        File(context.cacheDir, "subtitle-download-evidence").apply { mkdirs() }
            .let { File(it, name).writeText(text) }
    }

    private suspend fun parsePage(): DouyinVideo {
        val result = ServiceLocator.videoRepository.parseVideos(server.pageUrl)
        assertTrue("Parsing the page failed: $result", result is AppResult.Success)
        return (result as AppResult.Success).value.selectedVideo
    }

    @Test fun aPageWithASubtitleTrackOffersExactlyThatLanguage() = runBlocking {
        val video = parsePage()
        assertTrue("The page really declares a zh track, got ${video.subtitleLanguages}", video.subtitleLanguages.contains("zh"))
        val choice = SubtitleLanguageSelection.preferred(video.subtitleLanguages, video.autoCaptionLanguages)
        assertEquals(listOf("zh"), choice.languages)
        // The domain reports which name to use; the screen owns the wording.
        assertEquals(com.videodown.douyin.domain.model.SubtitleLanguageKey.CHINESE,
            com.videodown.douyin.domain.model.SubtitleLanguageSelection.displayKey(choice.languages.single()))
        instrumentedReport("subtitle-parse.txt", "languages=${video.subtitleLanguages} auto=${video.autoCaptionLanguages} key=${com.videodown.douyin.domain.model.SubtitleLanguageSelection.displayKey(choice.languages.single())}")
    }

    @Test fun downloadingWithSubtitlesEmbedsATextTrackThePlayerOffers() = runBlocking {
        assertTrue("Preserve any existing transfer", ServiceLocator.downloader.progress.value.values.none { it.isActive })
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("解析").fetchSemanticsNodes().isNotEmpty() }

        val video = parsePage()
        val variant = video.variants.first()
        val id = ServiceLocator.downloader.start(video, variant, SubtitleSelection(listOf("zh")))
        ownedIds += id
        val record = withTimeout(120_000) {
            val finished = ServiceLocator.historyRepository.observeRecord(id)
                .first { it?.status == DownloadStatus.COMPLETED || it?.status == DownloadStatus.FAILED }!!
            assertEquals("Task ended as ${finished.status}: ${finished.errorMessage}", DownloadStatus.COMPLETED, finished.status)
            finished
        }
        assertEquals("The intent must survive on the record", listOf("zh"), record.subtitleLanguages)

        // The container is the ground truth: embedding must have added a track that is neither
        // video nor audio. A file that merely sits next to a .vtt would have exactly two.
        val tracks = mutableListOf<String>()
        MediaExtractor().also { extractor ->
            try {
                extractor.setDataSource(record.filePath!!)
                for (index in 0 until extractor.trackCount) tracks += extractor.getTrackFormat(index).getString("mime").orEmpty()
            } finally { extractor.release() }
        }
        val text = tracks.filterNot { it.startsWith("video/") || it.startsWith("audio/") }
        assertTrue("Expected an embedded text track, got $tracks", text.isNotEmpty())

        // And the user-visible consequence: the player offers its 字幕 switch for this file.
        compose.runOnUiThread {
            val model = PlayerViewModel()
            compose.activity.setContent { VideoDownTheme { PlayerScreen(record.id, {}, model) } }
        }
        compose.waitForIdle()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("subtitles-toggle").fetchSemanticsNodes().isNotEmpty() }
        instrumentedReport("subtitle-embed.txt", buildString {
            appendLine("file=${File(record.filePath!!).name} bytes=${File(record.filePath!!).length()}")
            appendLine("tracks=$tracks")
            appendLine("textTracks=$text")
            appendLine("player toggle=shown")
        })
    }

    private fun instrumentedReport(name: String, text: String) {
        evidence(name, text)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", text.replace("\n", " | ")) })
    }

    /** Serves a page with one video and a WebVTT subtitle track it declares via `<track>`. */
    private class SubtitleServer(private val videoBytes: ByteArray) : java.io.Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val pageUrl get() = "http://127.0.0.1:${socket.localPort}/page"
        private val subtitles = """
            WEBVTT

            00:00:00.000 --> 00:00:05.000
            第一句字幕

            00:00:05.000 --> 00:00:10.000
            第二句字幕
        """.trimIndent().toByteArray()
        private val page = """<html><head><title>字幕验证</title></head><body>
            <video controls src="/sample.mp4">
              <track kind="subtitles" src="/subs.vtt" srclang="zh" label="中文" default>
            </video></body></html>""".toByteArray()
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
                            "/page" -> page to "text/html; charset=utf-8"
                            "/sample.mp4" -> videoBytes to "video/mp4"
                            "/subs.vtt" -> subtitles to "text/vtt"
                            else -> ByteArray(0) to "text/plain"
                        }
                        val output: OutputStream = connection.getOutputStream()
                        output.write(("HTTP/1.1 ${if (body.isEmpty()) "404 Not Found" else "200 OK"}\r\n" +
                            "Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
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
