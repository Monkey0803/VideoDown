package com.videodown.douyin

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppPreferences
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.*
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Requires a Range-capable slow HTTP server reached over real Wi-Fi, never adb reverse. */
@RunWith(AndroidJUnit4::class)
class PhysicalNetworkRecoveryDeviceTest {
    /** Run seed, force-stop only the app after READY, then run resume in the new process. */
    @Test fun forceStopRestoresProductionTasksAndSavedQueue() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("physical_process_phase")
        assumeTrue("Opt-in two-phase force-stop run", phase in setOf("seed", "resume"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = File(context.cacheDir, "physical-process-fixture.txt")
        val engine = ServiceLocator.downloader
        if (phase == "seed") {
            assumeTrue("Preserve pre-existing active downloads", engine.progress.value.values.none { it.isActive })
            check(!marker.exists()) { "Resume the existing process fixture first" }
            val url = requireNotNull(arguments.getString("network_test_url"))
            require(java.net.URI(url).host !in setOf("localhost", "127.0.0.1", "::1"))
            val variant = VideoVariant(url, "force-stop fixture")
            val tag = UUID.randomUUID().toString()
            val ids = mutableListOf<String>()
            engine.setConcurrency(1)
            try {
                repeat(3) { index ->
                    ids.add(engine.start(DouyinVideo("$tag-$index", "进程恢复验证 ${index + 1}",
                        platform = VideoPlatform.GENERIC, variants = listOf(variant)), variant))
                    marker.writeText(ids.joinToString("\n"))
                }
                engine.moveInQueue(ids[2], -1)
                val ordered = ServiceLocator.historyRepository.snapshot().filter { it.id in ids }.sortedBy { it.queueOrder }.map { it.id }
                assertEquals(listOf(ids[0], ids[2], ids[1]), ordered)
                marker.writeText(ordered.joinToString("\n"))
                withTimeout(60_000) { ServiceLocator.historyRepository.observeRecord(ids[0]).first { (it?.downloadedBytes ?: 0) > 256 * 1024 } }
                report("PHYSICAL_PROCESS_READY: force-stop target app now; saved queue and nonzero checkpoint")
                awaitCancellation()
            } finally {
                // Executes on a cancelled harness, but intentionally cannot execute after force-stop.
                withContext(NonCancellable) {
                    ids.forEach { engine.cancel(it, true) }
                    engine.setConcurrency(ServiceLocator.preferences.maxConcurrentDownloads)
                    marker.delete()
                }
            }
        } else {
            val ids = marker.readLines().filter { it.isNotBlank() }
            assertEquals(3, ids.size)
            try {
                val history = ServiceLocator.historyRepository
                withTimeout(15_000) { history.observeAll().first { records -> ids.all { id -> records.any { it.id == id && it.status == DownloadStatus.PAUSED } } } }
                assertEquals(ids, history.snapshot().filter { it.id in ids }.sortedBy { it.queueOrder }.map { it.id })
                val checkpoint = history.getById(ids.first())!!
                assertTrue(checkpoint.downloadedBytes > 0)
                assertTrue(File(checkpoint.partialPath).length() >= checkpoint.downloadedBytes)
                engine.setConcurrency(1)
                ids.forEach { engine.resume(it) }
                withTimeout(240_000) { history.observeAll().first { records -> ids.all { id -> records.any { it.id == id && it.status == DownloadStatus.COMPLETED } } } }
                val hash = requireNotNull(arguments.getString("network_test_sha256"))
                ids.forEach { id ->
                    val file = File(history.getById(id)!!.filePath!!)
                    assertEquals(hash, sha256(file))
                    assertEquals(1, history.snapshot().count { it.id == id })
                }
                report("PHYSICAL_PROCESS_RESUMED: new process, three paused tasks, queue retained, byte-identical completion")
            } finally {
                ids.forEach { engine.cancel(it, true) }
                engine.setConcurrency(ServiceLocator.preferences.maxConcurrentDownloads)
                marker.delete()
            }
        }
    }

    @Test fun wifiLossRetainsCheckpointAndQueueThenResumesExactlyOnce() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("network_test_url")
        assumeTrue("Pass network_test_url and network_test_sha256 for a physical network run", !url.isNullOrBlank())
        val expectedHash = requireNotNull(arguments.getString("network_test_sha256"))
        require(java.net.URI(url!!).host !in setOf("localhost", "127.0.0.1", "::1"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = "physical-network-${UUID.randomUUID()}"
        val preferencesContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(tag, mode)
        }
        val preferences = AppPreferences(preferencesContext).apply { wifiOnly = true }
        val policy = WifiDownloadNetworkPolicy(context, preferences)
        assumeTrue("Start on a real Wi-Fi default network", policy.isAllowed())
        val directory = File(context.cacheDir, tag).apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
        val completedIds = CopyOnWriteArrayList<String>()
        val paths = DownloadPaths(File(directory, "downloads"))
        val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
            paths, scope, "", "VideoDown-PhysicalNetwork-Test", maxConcurrent = 1,
            networkPolicy = policy, onCompleted = { completedIds.add(it.id) })
        try {
            val variant = VideoVariant(url, "physical network")
            fun video(index: Int) = DouyinVideo("$tag-$index", "网络恢复验证 $index",
                platform = VideoPlatform.GENERIC, variants = listOf(variant))
            val first = engine.start(video(1), variant)
            withTimeout(30_000) { engine.progress.first { (it[first]?.downloadedBytes ?: 0) > 0 } }
            val second = engine.start(video(2), variant)
            val third = engine.start(video(3), variant)
            engine.moveInQueue(third, -1)
            val order = history.snapshot().sortedBy { it.queueOrder }.map { it.id }
            assertEquals(listOf(first, third, second), order)
            report("NETWORK_OFF_READY: disable Wi-Fi; Wi-Fi-only policy must stop the real socket")
            withTimeout(90_000) { policy.allowed.first { !it } }
            val networkPaused = withTimeout(15_000) { history.observeRecord(first).first { it?.status == DownloadStatus.PAUSED }!! }
            assertEquals(WIFI_PAUSE_REASON, networkPaused.errorMessage)
            // Join all jobs before reading files; queued tasks must never bypass the network gate.
            engine.pauseAll()
            val checkpoint = history.getById(first)!!
            val partial = File(checkpoint.partialPath)
            val offset = partial.length()
            assertTrue(offset > 0)
            delay(500)
            assertEquals("No writes while disconnected", offset, partial.length())
            assertTrue(completedIds.isEmpty())
            assertEquals(order, history.snapshot().sortedBy { it.queueOrder }.map { it.id })
            report("NETWORK_ON_READY: restore Wi-Fi; partial=$offset persisted=${checkpoint.downloadedBytes}")
            withTimeout(90_000) { policy.allowed.first { it } }
            engine.resumeAll()
            withTimeout(180_000) {
                history.observeAll().first { records -> records.size == 3 && records.all { it.status == DownloadStatus.COMPLETED } }
            }
            withTimeout(5_000) { while (completedIds.size < 3) delay(20) }
            assertEquals("Persisted queue order and exactly one completion each", order, completedIds.toList())
            history.snapshot().forEach { record ->
                val file = File(record.filePath!!)
                assertEquals(expectedHash, sha256(file))
                assertEquals(file.length(), record.totalBytes)
            }
            report("NETWORK_RECOVERED: three byte-identical files, saved queue order, exactly one completion each")
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            directory.deleteRecursively()
            context.deleteSharedPreferences(tag)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
