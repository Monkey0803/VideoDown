package com.videodown.douyin

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * A global cap really paces a transfer, and a per-task cap can be set from the task card.
 *
 * The pacing *maths* is covered deterministically in `BandwidthLimiterTest`; this case exists to
 * prove the wiring — a real HTTP download is slower with a limit than without one.
 */
@RunWith(AndroidJUnit4::class)
class SpeedLimitDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ownedIds = mutableListOf<String>()

    @After fun cleanup() = runBlocking {
        ServiceLocator.preferences.globalSpeedLimitKbps = 0
        ownedIds.forEach { runCatching { ServiceLocator.downloader.cancel(it, true) } }
        Unit
    }

    private fun foregroundApp() {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("解析").fetchSemanticsNodes().isNotEmpty() }
    }

    private suspend fun download(server: FixtureServer, bytes: Long, title: String): Long {
        val variant = VideoVariant("${server.baseUrl}/sample.mp4", "720P", sizeBytes = bytes)
        val video = DouyinVideo(UUID.randomUUID().toString(), title, platform = VideoPlatform.GENERIC, variants = listOf(variant))
        val startedAt = System.currentTimeMillis()
        val id = ServiceLocator.downloader.start(video, variant).also(ownedIds::add)
        withTimeout(60_000) {
            ServiceLocator.historyRepository.observeRecord(id).first {
                it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.PAUSED)
            }!!
        }
        return System.currentTimeMillis() - startedAt
    }

    @Test fun aGlobalLimitPacesARealTransferWithoutCorruptingIt() = runBlocking {
        assertTrue("Preserve any existing transfer", ServiceLocator.downloader.progress.value.values.none { it.isActive })
        foregroundApp()
        val directory = File(context.cacheDir, "speed-limit-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            instrumentation.context.assets.open("landscape-fixture.mp4").use { input ->
                File(directory, "sample.mp4").outputStream().use { input.copyTo(it) }
            }
            val expected = File(directory, "sample.mp4").readBytes()
            FixtureServer(directory).use { server ->
                ServiceLocator.preferences.globalSpeedLimitKbps = 0
                val unlimited = download(server, expected.size.toLong(), "不限速-${UUID.randomUUID().toString().take(6)}")
                val limitedId = ownedIds.last()
                val limitedFile = ServiceLocator.historyRepository.getById(limitedId)!!.filePath!!.let(::File)
                assertArrayEquals("An unlimited transfer must still be byte-perfect", expected, limitedFile.readBytes())

                // 8 KB/s with a 10 KB file: the one-second credit does not cover it, so the
                // remainder is paced instead of arriving at localhost speed.
                ServiceLocator.preferences.globalSpeedLimitKbps = 8
                val limited = download(server, expected.size.toLong(), "限速-${UUID.randomUUID().toString().take(6)}")
                val pacedFile = ServiceLocator.historyRepository.getById(ownedIds.last())!!.filePath!!.let(::File)
                assertArrayEquals("A paced transfer must be byte-perfect too", expected, pacedFile.readBytes())

                assertTrue("A limited transfer must be slower: unlimited=${unlimited}ms limited=${limited}ms",
                    limited > unlimited + 150)
                File(context.cacheDir, "speed-limit-evidence").apply { mkdirs() }.let { evidence ->
                    File(evidence, "summary.txt").writeText(
                        "bytes=${expected.size}\nunlimited=${unlimited}ms\nglobalLimit=8 KB/s\nlimited=${limited}ms\n" +
                            "both files byte-perfect=true\n",
                    )
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "SPEED_LIMIT: unlimited=${unlimited}ms, limited=${limited}ms, both byte-perfect\n")
                })
            }
        } finally {
            ServiceLocator.preferences.globalSpeedLimitKbps = 0
            directory.deleteRecursively()
        }
    }

    /**
     * A per-task cap set while the task is still queued must pace it once a slot frees up.
     *
     * 10 KB at an 8 KB/s cap cannot finish in under ~287 ms, so the assertion is a guarantee of the
     * configured rate rather than a measurement of machine speed.
     */
    @Test fun aPerTaskLimitIsAppliedWhenAQueuedTaskStarts() = runBlocking {
        assertTrue("Preserve any existing transfer", ServiceLocator.downloader.progress.value.values.none { it.isActive })
        foregroundApp()
        val directory = File(context.cacheDir, "speed-limit-queued-${UUID.randomUUID()}").apply { mkdirs() }
        val originalConcurrency = ServiceLocator.preferences.maxConcurrentDownloads
        try {
            instrumentation.context.assets.open("landscape-fixture.mp4").use { input ->
                File(directory, "sample.mp4").outputStream().use { input.copyTo(it) }
            }
            val expected = File(directory, "sample.mp4").readBytes()
            ServiceLocator.preferences.globalSpeedLimitKbps = 0
            ServiceLocator.preferences.maxConcurrentDownloads = 1
            FixtureServer(directory, slow = true).use { slowServer ->
                FixtureServer(directory).use { fastServer ->
                    fun start(server: FixtureServer, title: String): String {
                        val variant = VideoVariant("${server.baseUrl}/sample.mp4", "720P", sizeBytes = expected.size.toLong())
                        val video = DouyinVideo(UUID.randomUUID().toString(), title, platform = VideoPlatform.GENERIC, variants = listOf(variant))
                        return runBlocking { ServiceLocator.downloader.start(video, variant) }.also(ownedIds::add)
                    }
                    val blockerId = start(slowServer, "占位-${UUID.randomUUID().toString().take(6)}")
                    withTimeout(20_000) { ServiceLocator.downloader.progress.first { it[blockerId] != null } }
                    val pacedId = start(fastServer, "排队限速-${UUID.randomUUID().toString().take(6)}")
                    // Still queued behind the one slot, so the cap is stored before the transfer starts.
                    withTimeout(20_000) { ServiceLocator.downloader.progress.first { it[pacedId] != null } }
                    ServiceLocator.downloader.setSpeedLimit(pacedId, 8)
                    assertEquals(8, ServiceLocator.historyRepository.getById(pacedId)?.speedLimitKbps)

                    ServiceLocator.downloader.pause(blockerId)
                    val startedAt = System.currentTimeMillis()
                    withTimeout(60_000) {
                        ServiceLocator.historyRepository.observeRecord(pacedId).first { it?.status == DownloadStatus.COMPLETED }
                    }
                    val elapsed = System.currentTimeMillis() - startedAt
                    val paced = ServiceLocator.historyRepository.getById(pacedId)!!.filePath!!.let(::File)
                    assertArrayEquals("A paced task must still be byte-perfect", expected, paced.readBytes())
                    assertTrue("10 KB at 8 KB/s cannot take only ${elapsed}ms", elapsed >= 200)
                    File(context.cacheDir, "speed-limit-evidence").apply { mkdirs() }.let { evidence ->
                        File(evidence, "queued-task.txt").writeText(
                            "bytes=${expected.size}\nperTaskLimit=8 KB/s\nelapsed=${elapsed}ms\nbyte-perfect=true\n",
                        )
                    }
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "SPEED_LIMIT_QUEUED: per-task 8 KB/s paced the queued task to ${elapsed}ms\n")
                    })
                }
            }
        } finally {
            ServiceLocator.preferences.globalSpeedLimitKbps = 0
            ServiceLocator.preferences.maxConcurrentDownloads = originalConcurrency
            ownedIds.forEach { runCatching { ServiceLocator.downloader.cancel(it, true) } }
            directory.deleteRecursively()
        }
    }

    @Test fun aPerTaskLimitIsSetFromTheTaskCardAndShowsOnTheCard() = runBlocking {
        assertTrue("Preserve any existing transfer", ServiceLocator.downloader.progress.value.values.none { it.isActive })
        foregroundApp()
        val directory = File(context.cacheDir, "speed-limit-task-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            instrumentation.context.assets.open("landscape-fixture.mp4").use { input ->
                File(directory, "sample.mp4").outputStream().use { input.copyTo(it) }
            }
            // A slow server keeps the task in a state where the card offers its controls.
            FixtureServer(directory, slow = true).use { server ->
                val title = "单任务限速-${UUID.randomUUID().toString().take(6)}"
                val variant = VideoVariant("${server.baseUrl}/sample.mp4", "720P", sizeBytes = File(directory, "sample.mp4").length())
                val video = DouyinVideo(UUID.randomUUID().toString(), title, platform = VideoPlatform.GENERIC, variants = listOf(variant))
                val id = ServiceLocator.downloader.start(video, variant).also(ownedIds::add)
                withTimeout(15_000) { ServiceLocator.downloader.progress.first { it.containsKey(id) } }
                ServiceLocator.downloader.pause(id)
                withTimeout(15_000) {
                    ServiceLocator.historyRepository.observeRecord(id).first { it?.status == DownloadStatus.PAUSED }
                }

                compose.onNodeWithText("任务").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("设置限速").performClick()
                compose.waitForIdle()
                compose.onNodeWithText("单任务限速").assertExists()
                compose.onNodeWithText("5 MB/s").performClick()
                // waitUntil takes a plain (non-suspending) predicate, so the record is polled here.
                withTimeout(10_000) {
                    while (ServiceLocator.historyRepository.getById(id)?.speedLimitKbps != 5120) delay(100)
                }
                assertEquals(5120, ServiceLocator.historyRepository.getById(id)?.speedLimitKbps)

                // The cap is visible on the card and the action now reports the active limit.
                compose.waitUntil(10_000) { compose.onAllNodesWithText("限速 5 MB/s", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("限速 5 MB/s").fetchSemanticsNodes().isNotEmpty() }

                // Choosing 跟随全局 clears it again.
                compose.onNodeWithContentDescription("限速 5 MB/s").performClick()
                compose.waitForIdle()
                compose.onNodeWithText("跟随全局").performClick()
                withTimeout(10_000) {
                    while (ServiceLocator.historyRepository.getById(id)?.speedLimitKbps != 0) delay(100)
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "SPEED_LIMIT_CARD: set 5 MB/s from the card, then back to 跟随全局\n")
                })
            }
        } finally {
            ownedIds.forEach { runCatching { ServiceLocator.downloader.cancel(it, true) } }
            delay(200)
            directory.deleteRecursively()
        }
    }
}
