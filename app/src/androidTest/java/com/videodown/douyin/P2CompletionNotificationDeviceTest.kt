package com.videodown.douyin

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadCompletionNotifier
import com.videodown.douyin.data.generic.validateVideo
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.presentation.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual production downloads, setting UI and system notification card activation. */
@RunWith(AndroidJUnit4::class)
class P2CompletionNotificationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val evidence get() = File(context.cacheDir, "p2-completion-evidence").apply { mkdirs() }
    private val ownedIds = mutableListOf<String>()

    private fun resumedActivity(): MainActivity? {
        var result: MainActivity? = null
        instrumentation.runOnMainSync { result = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
        return result
    }
    private fun report(value: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$value\n") })
    private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let { find(it, text)?.let { match -> return match } }
        return null
    }
    private fun capture(name: String, subtree: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow) {
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun render(node: AccessibilityNodeInfo): String = buildString {
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            append("<node text=\"${android.text.TextUtils.htmlEncode(node.text?.toString().orEmpty())}\" desc=\"${android.text.TextUtils.htmlEncode(node.contentDescription?.toString().orEmpty())}\" clickable=\"${node.isClickable}\" bounds=\"$bounds\">\n")
            for (i in 0 until node.childCount) node.getChild(i)?.let { append(render(it)) }
            append("</node>\n")
        }
        File(evidence, "$name.xml").writeText(subtree?.let(::render).orEmpty())
    }
    private fun setNotificationSetting(enabled: Boolean) {
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("完成时通知").performScrollTo()
        if (ServiceLocator.preferences.notifyOnCompletion != enabled) compose.onNodeWithText("完成时通知").performClick()
        compose.waitForIdle()
        assertEquals(enabled, ServiceLocator.preferences.notifyOnCompletion)
        capture(if (enabled) "setting-enabled" else "setting-disabled")
        compose.onNodeWithText("解析").performClick()
    }
    private suspend fun download(server: FixtureServer, bytes: ByteArray, title: String): DownloadRecord {
        val variant = VideoVariant("${server.baseUrl}/sample.mp4", "720P", sizeBytes = bytes.size.toLong())
        val video = DouyinVideo(UUID.randomUUID().toString(), title, platform = VideoPlatform.GENERIC, variants = listOf(variant))
        val id = ServiceLocator.downloader.start(video, variant).also(ownedIds::add)
        val completed = withTimeout(30_000) { ServiceLocator.historyRepository.observeRecord(id).first {
            it?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.PAUSED)
        }!! }
        assertEquals(completed.errorMessage, DownloadStatus.COMPLETED, completed.status)
        val file = File(completed.filePath!!)
        assertArrayEquals(bytes, file.readBytes()); validateVideo(file)
        report("COMPLETED: title=$title, id=$id, bytes=${file.length()}")
        return completed
    }
    private suspend fun clickNotification(record: DownloadRecord) {
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
        var card: AccessibilityNodeInfo? = null
        withTimeout(10_000) {
            while (card == null) {
                card = instrumentation.uiAutomation.rootInActiveWindow?.takeIf { it.packageName?.toString() == "com.android.systemui" }?.let { find(it, record.title) }
                if (card == null) delay(100)
            }
        }
        var clickable = card!!
        while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
        assertTrue("Notification card must expose a real click action", clickable.isClickable)
        capture("notification-${record.title}", clickable)
        if (!clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val fresh = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, record.title) }
                ?: throw AssertionError("Notification disappeared before actual touch")
            val bounds = android.graphics.Rect().also(fresh::getBoundsInScreen)
            report("SYSTEM_CARD_TOUCH_FALLBACK: actual observed bounds=$bounds")
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, now + if (action == android.view.MotionEvent.ACTION_UP) 80 else 0, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { compose.onAllNodesWithText(record.title).fetchSemanticsNodes().isNotEmpty() &&
            (compose.onAllNodesWithContentDescription("分享").fetchSemanticsNodes().isNotEmpty() ||
             compose.onAllNodesWithText("已存视频").fetchSemanticsNodes().isNotEmpty()) }
        capture("destination-${record.title}")
        // A library containing every record is not a precise destination for this notification.
        compose.onAllNodesWithText(record.title)[0].assertIsDisplayed()
        compose.onNodeWithContentDescription("分享").assertIsDisplayed()
        assertFalse("Completion click must open details rather than the whole library", compose.onAllNodesWithText("已存视频").fetchSemanticsNodes().isNotEmpty())
        report("ACTUAL_NOTIFICATION_CLICK: opened corresponding detail ${record.id}")
    }

    @Test fun productionCompletionSettingAndActualNotificationClicksIdentifyEachRecord() = runBlocking {
        assertTrue("System notifications must be genuinely enabled", DownloadCompletionNotifier.areNotificationsEnabled(context))
        assertTrue("Preserve any existing transfer", ServiceLocator.downloader.progress.value.values.none { it.isActive })
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
        compose.waitUntil(10000) { compose.onAllNodesWithText("解析").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("解析").performClick()
        val original = ServiceLocator.preferences.notifyOnCompletion
        val launchIntent = Intent(resumedActivity()!!.intent)
        val directory = File(context.cacheDir, "p2-completion-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            instrumentation.context.assets.open("landscape-fixture.mp4").use { input -> File(directory, "sample.mp4").outputStream().use { input.copyTo(it) } }
            val bytes = File(directory, "sample.mp4").readBytes()
            FixtureServer(directory).use { server ->
                setNotificationSetting(false)
                val disabled = download(server, bytes, "通知关闭-${UUID.randomUUID().toString().take(8)}")
                delay(300)
                assertFalse(manager.activeNotifications.any { it.tag == disabled.id && it.id == 1002 })
                report("DISABLED: actual completed download posted no notification")
                setNotificationSetting(true)
                val first = download(server, bytes, "通知甲-${UUID.randomUUID().toString().take(8)}")
                val second = download(server, bytes, "通知乙-${UUID.randomUUID().toString().take(8)}")
                withTimeout(5000) { while (listOf(first, second).any { record -> manager.activeNotifications.none { it.tag == record.id && it.id == 1002 } }) delay(50) }
                assertEquals(2, manager.activeNotifications.count { it.tag in listOf(first.id, second.id) && it.id == 1002 })
                assertNotEquals(manager.activeNotifications.single { it.tag == first.id }.notification.contentIntent,
                    manager.activeNotifications.single { it.tag == second.id }.notification.contentIntent)
                report("ENABLED: two actual completed downloads have independent notification cards and PendingIntents")
                clickNotification(first)
                androidx.test.espresso.Espresso.pressBack()
                clickNotification(second)
                assertFalse(resumedActivity()!!.intent.hasExtra(DownloadCompletionNotifier.EXTRA_RECORD_ID))
                androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithText("解析").performClick()
                val oldActivity = resumedActivity() ?: throw AssertionError("No resumed MainActivity before recreation")
                instrumentation.runOnMainSync { oldActivity.recreate() }
                withTimeout(10000) { while (resumedActivity()?.let { it !== oldActivity } != true) delay(50) }
                compose.waitForIdle()
                compose.onNodeWithText("粘贴链接").assertIsDisplayed()
                compose.onNodeWithContentDescription("分享").assertDoesNotExist()
                report("CONSUMED: recreated Home did not reopen completed media")
            }
        } finally {
            withContext(NonCancellable) {
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                ownedIds.forEach { ServiceLocator.downloader.cancel(it, true); manager.cancel(it, 1002) }
                ServiceLocator.preferences.notifyOnCompletion = original
                val finalActivity = resumedActivity()
                instrumentation.runOnMainSync { finalActivity?.intent = launchIntent }
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                delay(500)
                context.startActivity(Intent(launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                compose.waitForIdle()
                directory.deleteRecursively()
                report("CLEANUP: only own ${ownedIds.size} tasks/files removed; completion preference restored to $original")
            }
        }
    }
}
