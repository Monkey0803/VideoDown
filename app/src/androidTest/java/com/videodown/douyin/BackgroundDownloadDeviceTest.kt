package com.videodown.douyin

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BackgroundDownloadDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun report(message: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$message\n") })
    private fun capture(name: String) {
        val directory = File(context.cacheDir, "background-device-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun render(node: AccessibilityNodeInfo): String = buildString {
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            append("<node text=\"${android.text.TextUtils.htmlEncode(node.text?.toString().orEmpty())}\" bounds=\"$bounds\">\n")
            for (i in 0 until node.childCount) node.getChild(i)?.let { append(render(it)) }
            append("</node>\n")
        }
        File(directory, "$name.xml").writeText(instrumentation.uiAutomation.rootInActiveWindow?.let(::render).orEmpty())
    }
    private fun openApp() = context.startActivity(Intent().setClassName(context.packageName,
        "com.videodown.douyin.presentation.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun foregroundNotification(): Notification? = notifications.activeNotifications
        .firstOrNull { it.id == 1001 }?.notification
    private fun nodeWithText(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { result -> return result } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(15000) {
        while (!condition()) delay(50)
    }
    private suspend fun waitForBytes(id: String, minimum: Long): Long = withTimeout(15000) {
        ServiceLocator.downloader.progress.first { (it[id]?.downloadedBytes ?: 0) > minimum }[id]!!.downloadedBytes
    }

    @Test fun foregroundDownloadSurvivesHomeAndScreenOffThenNotificationPauseAndResume() = runBlocking {
        // Sending the real pause-all action is safe only when this is the sole active task.
        assumeTrue("Preserve pre-existing downloads", ServiceLocator.downloader.progress.value.values.none { it.isActive })
        assumeTrue("Foreground notification must be observable", notifications.areNotificationsEnabled())
        val idTag = "background-${UUID.randomUUID()}"
        var taskId: String? = null
        var slept = false
        try {
            openApp()
            delay(500)
            SlowMp4Server(fixture()).use { server ->
                val variant = VideoVariant(server.url, "device fixture", sizeBytes = server.bytes.size.toLong())
                val video = DouyinVideo(idTag, "后台下载验证", platform = VideoPlatform.GENERIC, variants = listOf(variant))
                val id = ServiceLocator.downloader.start(video, variant).also { taskId = it }
                waitForBytes(id, 0)
                awaitCondition { foregroundNotification() != null }
                assertTrue("Actual service must be foreground", shell("dumpsys activity services ${context.packageName}").contains("isForeground=true"))
                val first = ServiceLocator.downloader.progress.value[id]!!.downloadedBytes
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                val background = waitForBytes(id, first + 128 * 1024)
                report("Background: foreground notification present; Home bytes $first -> $background")
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                if (!keyguard.isKeyguardSecure) {
                    shell("input keyevent 223"); slept = true
                    awaitCondition { !context.getSystemService(PowerManager::class.java).isInteractive }
                    val locked = waitForBytes(id, background + 128 * 1024)
                    report("Background: screen-off bytes $background -> $locked")
                    shell("input keyevent 224"); shell("wm dismiss-keyguard"); slept = false
                    awaitCondition { context.getSystemService(PowerManager::class.java).isInteractive }
                } else report("Background: secure keyguard; screen-off segment omitted to preserve unlock access")
                openApp()
                awaitCondition { foregroundNotification() != null }
                assertTrue("Home transfer must still have foreground service", shell("dumpsys activity services ${context.packageName}").contains("isForeground=true"))
                val action = foregroundNotification()!!.actions.single { it.title.toString() == "全部暂停" }
                action.actionIntent.send()
                val paused = withTimeout(15000) {
                    ServiceLocator.historyRepository.observeRecord(id).first { it?.status == DownloadStatus.PAUSED }!!
                }
                val partial = File(paused.partialPath)
                assertTrue(partial.length() > 0)
                val offset = partial.length()
                delay(300)
                assertEquals("Notification pause must stop writing", offset, partial.length())
                awaitCondition { foregroundNotification() == null }
                report("Background: notification pause retained $offset bytes")
                ServiceLocator.downloader.resume(id)
                val completed = withTimeout(120000) {
                    ServiceLocator.historyRepository.observeRecord(id).first {
                        it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)
                    }!!
                }
                assertEquals(completed.errorMessage, DownloadStatus.COMPLETED, completed.status)
                assertArrayEquals(server.bytes, File(completed.filePath!!).readBytes())
                assertTrue("Resume must use HTTP Range", server.rangeRequests.get() > 0)
                awaitCondition { foregroundNotification() == null }
                report("Background: resumed download completed; foreground notification removed")
            }
        } finally {
            withContext(NonCancellable) {
                if (slept) { shell("input keyevent 224"); shell("wm dismiss-keyguard") }
                taskId?.let { ServiceLocator.downloader.cancel(it, true); notifications.cancel(it, 1002) }
                openApp()
            }
        }
    }

    @Test fun unexpectedServiceStopFailsWithVisibleGuidanceAndRetryCompletes() = runBlocking {
        assumeTrue("Preserve pre-existing downloads", ServiceLocator.downloader.progress.value.values.none { it.isActive })
        var taskId: String? = null
        try {
            openApp(); delay(500)
            SlowMp4Server(fixture()).use { server ->
                val variant = VideoVariant(server.url, "device fixture", sizeBytes = server.bytes.size.toLong())
                val video = DouyinVideo("background-stop-${UUID.randomUUID()}", "后台服务中断验证", platform = VideoPlatform.GENERIC, variants = listOf(variant))
                val id = ServiceLocator.downloader.start(video, variant).also { taskId = it }
                waitForBytes(id, 128 * 1024)
                awaitCondition { foregroundNotification() != null }
                assertTrue(context.stopService(Intent(context, DownloadService::class.java)))
                val failed = withTimeout(15000) { ServiceLocator.historyRepository.observeRecord(id).first { it?.status == DownloadStatus.FAILED }!! }
                assertEquals(DownloadFailureKind.BACKGROUND_SERVICE, failed.failureKind)
                val partial = File(failed.partialPath)
                val offset = partial.length()
                assertEquals("Failure checkpoint must persist actual partial bytes", offset, failed.downloadedBytes)
                assertTrue(offset > 0); delay(300); assertEquals(offset, partial.length())
                awaitCondition { nodeWithText("任务") != null }
                var tab = nodeWithText("任务")!!
                while (!tab.isClickable && tab.parent != null) tab = tab.parent
                assertTrue(tab.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitCondition { nodeWithText("后台下载已停止") != null }
                assertNotNull(nodeWithText("后台下载服务已中断，请返回应用后重试"))
                assertNotNull(nodeWithText("重试下载"))
                capture("unexpected-stop-guidance")
                report("Background: unexpected stopService -> FAILED/BACKGROUND_SERVICE, stable partial $offset; Chinese reason and retry visible")
                var retry = nodeWithText("重试下载")!!
                while (!retry.isClickable && retry.parent != null) retry = retry.parent
                assertTrue(retry.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitForBytes(id, offset)
                val completed = withTimeout(120000) { ServiceLocator.historyRepository.observeRecord(id).first {
                    it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)
                }!! }
                assertEquals(completed.errorMessage, DownloadStatus.COMPLETED, completed.status)
                assertArrayEquals(server.bytes, File(completed.filePath!!).readBytes())
                assertTrue(server.rangeRequests.get() > 0)
                awaitCondition { foregroundNotification() == null }
                report("Background: retry after service loss recreated foreground service and completed identical bytes")
            }
        } finally { withContext(NonCancellable) { taskId?.let { ServiceLocator.downloader.cancel(it, true); notifications.cancel(it, 1002) }; openApp() } }
    }

    @Test fun deniedForegroundStartBecomesBackgroundFailureBeforeAnyHttpAndKeepsPartial() = runBlocking {
        val directory = File(context.cacheDir, "background-denied-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val deniedContext = object : ContextWrapper(context) {
                override fun startForegroundService(service: Intent): ComponentName? = throw SecurityException("Injected start denial")
            }
            try { DownloadService.start(deniedContext); fail("Must surface foreground start denial") }
            catch (error: DownloadServiceException) { assertTrue(error.cause is SecurityException) }
            SlowMp4Server(fixture()).use { server ->
                val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
                val paths = DownloadPaths(File(directory, "downloads"))
                val engine = DownloadEngine(history, ResumableDownloader(ServiceLocator.douyinApiClient.okHttpClient),
                    paths, scope, "", "Background-Device-Test", onTaskScheduled = { DownloadService.start(deniedContext) })
                val partial = File(paths.downloadDir, "denied.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val record = DownloadRecord(UUID.randomUUID().toString(), "fixture", "service denial", sourceUrl = server.url,
                    fileName = "denied.mp4", partialPath = partial.path, status = DownloadStatus.PAUSED, downloadedBytes = 3)
                history.upsert(record)
                engine.resume(record.id)
                val failed = withTimeout(10000) { history.observeRecord(record.id).first { it?.status == DownloadStatus.FAILED }!! }
                assertEquals(DownloadFailureKind.BACKGROUND_SERVICE, failed.failureKind)
                assertEquals(0, server.requests.get())
                assertArrayEquals(byteArrayOf(1, 2, 3), partial.readBytes())
                report("Background: SecurityException wrapped; FAILED/BACKGROUND_SERVICE, zero HTTP, partial preserved")
            }
        } finally { withContext(NonCancellable) { scope.coroutineContext.job.cancelAndJoin(); directory.deleteRecursively() } }
    }

    private fun fixture(): ByteArray = instrumentation.context.assets.open("landscape-fixture.mp4").use {
        it.readBytes().copyOf(8 * 1024 * 1024)
    }
}

/** Loopback only, with a long transfer and byte-range resume; no real disk exhaustion. */
private class SlowMp4Server(val bytes: ByteArray) : java.io.Closeable {
    private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    val requests = AtomicInteger()
    val rangeRequests = AtomicInteger()
    val url = "http://127.0.0.1:${server.localPort}/fixture.mp4"
    init {
        pool.submit {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(socket)
                pool.submit {
                    try { socket.use { connection ->
                        val input = connection.getInputStream().bufferedReader()
                        input.readLine() ?: return@use
                        requests.incrementAndGet()
                        var offset = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", true)) {
                                offset = line.substringAfter('=').substringBefore('-').trim().toInt()
                                rangeRequests.incrementAndGet()
                            }
                        }
                        require(offset in bytes.indices)
                        val output = connection.getOutputStream()
                        val status = if (offset > 0) "206 Partial Content" else "200 OK"
                        output.write(buildString {
                            append("HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nContent-Length: ${bytes.size - offset}\r\nAccept-Ranges: bytes\r\nETag: \"fixture\"\r\n")
                            if (offset > 0) append("Content-Range: bytes $offset-${bytes.size - 1}/${bytes.size}\r\n")
                            append("Connection: close\r\n\r\n")
                        }.toByteArray())
                        for (position in offset until bytes.size step 16384) {
                            output.write(bytes, position, minOf(16384, bytes.size - position)); output.flush(); Thread.sleep(80)
                        }
                    } } catch (_: java.io.IOException) { /* Expected when the downloader pauses. */ }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    finally { sockets.remove(socket) }
                }
            }
        }
    }
    override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; pool.shutdownNow() }
}
