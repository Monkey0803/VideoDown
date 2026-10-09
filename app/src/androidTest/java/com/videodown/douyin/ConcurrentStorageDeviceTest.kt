package com.videodown.douyin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.*
import com.videodown.douyin.data.generic.EngineMediaDownloader
import com.videodown.douyin.data.generic.validateVideo
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Simulates exhaustion; never fills the device or touches the user's download history. */
@RunWith(AndroidJUnit4::class)
class ConcurrentStorageDeviceTest {
    @Test fun engineMonitorStopsForStorageFailureAndReleasedLeaseAllowsResume() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "storage-monitor-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            createMediaFixtures(context, directory)
            FixtureServer(directory, throttleSegments = true).use { server ->
                val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
                val paths = DownloadPaths(File(directory, "downloads"))
                val simulatedFree = AtomicLong(100L * 1024 * 1024)
                val reachedProgress = CompletableDeferred<Unit>()
                val holdOnce = AtomicBoolean(true)
                val repository = object : DownloadHistoryRepository by history {
                    override suspend fun upsert(record: DownloadRecord) {
                        history.upsert(record)
                        if (record.status == DownloadStatus.RUNNING && record.downloadedBytes > 0 &&
                            holdOnce.compareAndSet(true, false)) {
                            reachedProgress.complete(Unit)
                            // Suspend progress before reducing free space, so only the monitor
                            // can detect the simulated exhaustion and cancel this subprocess.
                            awaitCancellation()
                        }
                    }
                }
                val baseline = engineProcesses()
                val engine = DownloadEngine(repository, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
                    paths, scope, "", "Storage-Device-Test", availableBytes = simulatedFree::get,
                    engineDownloader = EngineMediaDownloader(ServiceLocator.videoEngine, ServiceLocator.browserSessions,
                        File(directory, "sessions")))
                val parsed = ServiceLocator.genericRepository.parseVideos(server.baseUrl + "/sample.mpd") as AppResult.Success
                val video = parsed.value.selectedVideo
                val id = engine.start(video, video.defaultVariant!!)
                withTimeout(45000) { reachedProgress.await() }
                val owned = engineProcesses().filterKeys { it !in baseline }
                simulatedFree.set(0)
                val failed = withTimeout(10000) {
                    history.observeRecord(id).first { it?.status == DownloadStatus.FAILED }!!
                }
                assertEquals(DownloadFailureKind.STORAGE_FULL, failed.failureKind)
                withTimeout(10000) { while (id in engine.activeTaskIds()) delay(25) }
                if (owned.isNotEmpty()) {
                    withTimeout(5000) {
                        while (owned.any { (pid, command) -> engineProcesses()[pid] == command }) delay(25)
                    }
                    report("Storage monitor: ${owned.size} observed runtime process(es) exited")
                } else report("Storage monitor: /proc did not expose a runtime PID; process exit not directly covered")
                val footprint = taskDirectoryBytes(paths.engineDirectory(id))
                delay(400)
                assertEquals("Stopped task must not continue writing", footprint, taskDirectoryBytes(paths.engineDirectory(id)))
                // Enough for one task's reserve, not two: a leaked lease prevents this resume.
                simulatedFree.set(40L * 1024 * 1024)
                engine.resume(id)
                val completed = withTimeout(90000) {
                    history.observeRecord(id).first { it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
                }
                assertEquals(completed.errorMessage, DownloadStatus.COMPLETED, completed.status)
                validateVideo(File(completed.filePath!!))
                assertFalse(paths.engineDirectory(id).exists())
                report("Storage monitor: STORAGE_FULL then resume completed after lease release")
            }
        } finally {
            withContext(NonCancellable) { scope.coroutineContext.job.cancelAndJoin(); directory.deleteRecursively() }
        }
    }

    private fun engineProcesses(): Map<Int, String> = File("/proc").listFiles().orEmpty().mapNotNull { entry ->
        val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
        val command = runCatching { File(entry, "cmdline").readText().replace('\u0000', ' ') }.getOrNull()
            ?: return@mapNotNull null
        if (command.contains("libpython") || command.contains("libffmpeg")) pid to command else null
    }.toMap()
}
