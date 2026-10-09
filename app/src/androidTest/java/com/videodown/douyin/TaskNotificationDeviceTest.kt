package com.videodown.douyin

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadCompletionNotifier
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Two real, deliberately slow transfers, their real per-task notification cards, and the real
 * PendingIntent of a card action — the same path a user's tap takes.
 */
@RunWith(AndroidJUnit4::class)
class TaskNotificationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val evidence get() = File(context.cacheDir, "task-notification-evidence").apply { mkdirs() }
    private val ownedIds = mutableListOf<String>()

    private fun individualNotifications() = manager.activeNotifications.filter { it.id in 1100..1199 }
    private fun titleOf(record: android.app.Notification) = record.extras.getString(Notification.EXTRA_TITLE)
    private fun send(value: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$value\n") })
    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @After fun cleanup() = runBlocking {
        ownedIds.forEach { runCatching { ServiceLocator.downloader.cancel(it, true) } }
        individualNotifications().forEach { manager.cancel(it.tag, it.id) }
        ServiceLocator.preferences.individualTaskNotifications = false
        Unit
    }

    @Test fun independentCardsPauseOnlyTheirOwnTaskAndFollowThePreference() = runBlocking {
        assertTrue("System notifications must be genuinely enabled", DownloadCompletionNotifier.areNotificationsEnabled(context))
        assertTrue("Preserve any existing transfer", ServiceLocator.downloader.progress.value.values.none { it.isActive })
        // A foreground service may only be started while the app itself is in the foreground.
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("解析").fetchSemanticsNodes().isNotEmpty() }
        val originalConcurrency = ServiceLocator.preferences.maxConcurrentDownloads
        val directory = File(context.cacheDir, "task-notification-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            instrumentation.context.assets.open("landscape-fixture.mp4").use { input ->
                File(directory, "sample.mp4").outputStream().use { input.copyTo(it) }
            }
            val bytes = File(directory, "sample.mp4").length()
            ServiceLocator.preferences.maxConcurrentDownloads = 2
            ServiceLocator.preferences.individualTaskNotifications = true
            FixtureServer(directory, slow = true).use { server ->
                val titles = (1..2).map { index -> "独立通知$index-${UUID.randomUUID().toString().take(6)}" }
                titles.forEach { title ->
                    val variant = VideoVariant("${server.baseUrl}/sample.mp4", "720P", sizeBytes = bytes)
                    val video = DouyinVideo(UUID.randomUUID().toString(), title, platform = VideoPlatform.GENERIC, variants = listOf(variant))
                    ServiceLocator.downloader.start(video, variant).also(ownedIds::add)
                }

                withTimeout(20_000) { while (individualNotifications().size < 2) delay(100) }
                val cards = individualNotifications()
                assertEquals("Each running task gets its own card", 2, cards.size)
                cards.forEach { card ->
                    assertTrue("A transferring task must offer 暂停, got ${card.notification.actions?.map { it.title }}",
                        card.notification.actions?.any { it.title?.toString() == "暂停" } == true)
                }
                val summary = manager.activeNotifications.first { it.id == 1001 }
                assertTrue("The summary notification must survive alongside them", summary.notification.actions?.any { it.title?.toString() == "全部暂停" } == true)
                send("CARDS: ${cards.map { "${titleOf(it.notification)} -> ${it.notification.actions?.map { action -> action.title }}" }}")
                capture("two-tasks-two-cards")

                // Drive the card's own PendingIntent, exactly as a tap would.
                val targetTitle = titles.first()
                val target = cards.first { titleOf(it.notification) == targetTitle }
                val pause = target.notification.actions!!.first { it.title?.toString() == "暂停" }
                instrumentation.runOnMainSync { pause.actionIntent.send() }
                val pausedId = ownedIds[titles.indexOf(targetTitle)]
                val paused = withTimeout(15_000) {
                    ServiceLocator.historyRepository.observeRecord(pausedId).first { it?.status == DownloadStatus.PAUSED }
                }
                assertNotNull(paused)
                val otherTitle = titles.last()
                val otherStatus = ServiceLocator.historyRepository.observeRecord(ownedIds[titles.indexOf(otherTitle)]).first()!!.status
                assertTrue("Only the tapped task may pause; the other was $otherStatus",
                    otherStatus == DownloadStatus.RUNNING || otherStatus == DownloadStatus.PENDING)
                send("PAUSED_ONE: $targetTitle -> PAUSED, $otherTitle -> $otherStatus")

                // The paused card must now be able to resume the same task.
                withTimeout(15_000) {
                    while (individualNotifications().none { card ->
                        titleOf(card.notification) == targetTitle &&
                            card.notification.actions?.any { it.title?.toString() == "继续" } == true
                    }) delay(100)
                }
                capture("paused-card-offers-resume")
                send("PAUSED_CARD: offers 继续 for $targetTitle")

                // Turning the preference off withdraws every per-task card; the pause emission is
                // what makes the service re-evaluate.
                ServiceLocator.preferences.individualTaskNotifications = false
                ServiceLocator.downloader.pause(ownedIds[titles.indexOf(otherTitle)])
                withTimeout(15_000) { while (individualNotifications().isNotEmpty()) delay(100) }
                send("PREFERENCE_OFF: all independent cards withdrawn")
            }
        } finally {
            ServiceLocator.preferences.maxConcurrentDownloads = originalConcurrency
            ownedIds.forEach { runCatching { ServiceLocator.downloader.cancel(it, true) } }
            individualNotifications().forEach { manager.cancel(it.tag, it.id) }
            ServiceLocator.preferences.individualTaskNotifications = false
            directory.deleteRecursively()
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            File(evidence, "summary.txt").writeText(
                "independent notification ids seen: 1100..1199\n" +
                    "owned tasks: ${ownedIds.size}\n" +
                    "concurrency preference restored to $originalConcurrency\n",
            )
        }
    }
}
