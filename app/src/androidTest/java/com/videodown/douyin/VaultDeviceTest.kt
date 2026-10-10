package com.videodown.douyin

import android.app.Activity
import android.app.Notification
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.VaultAuthenticator
import com.videodown.douyin.data.download.DownloadCompletionNotifier
import com.videodown.douyin.data.gallery.GalleryAutoSave
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.VaultPolicy
import com.videodown.douyin.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The vault hides private records everywhere they could appear, and only opens after the gate.
 *
 * The system prompt itself cannot be answered by a test, so the gate is replaced by a fake — both
 * answers are exercised, and everything behind the gate is the real code.
 */
@RunWith(AndroidJUnit4::class)
class VaultDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var gate: FakeGate
    private lateinit var original: VaultAuthenticator
    private var originalNotify = true
    private lateinit var directory: File
    private lateinit var publicRecord: DownloadRecord
    private lateinit var privateRecord: DownloadRecord
    private val publicTitle = "公开视频-${UUID.randomUUID().toString().take(6)}"
    private val privateTitle = "私密视频-${UUID.randomUUID().toString().take(6)}"

    /** Answers both possible outcomes of the system prompt. */
    private class FakeGate(var granted: Boolean = true, var available: Boolean = true) : VaultAuthenticator {
        var calls = 0
        override fun isAvailable(context: Context): Boolean = available
        override fun authenticate(activity: Activity, onResult: (Boolean) -> Unit): Boolean {
            calls++
            onResult(granted)
            return true
        }
    }

    private fun seed(title: String, private: Boolean): DownloadRecord {
        val file = File(directory, "${UUID.randomUUID()}.mp4").apply { writeBytes(ByteArray(2_048) { (it % 251).toByte() }) }
        val record = DownloadRecord(
            id = UUID.randomUUID().toString(), awemeId = UUID.randomUUID().toString().take(8), title = title,
            fileName = file.name, filePath = file.path, status = DownloadStatus.COMPLETED,
            resolutionLabel = "720P", downloadedBytes = file.length(), totalBytes = file.length(),
            isPrivate = private,
        )
        runBlocking { ServiceLocator.historyRepository.upsert(record) }
        return record
    }

    @Before fun setup() {
        directory = File(context.cacheDir, "vault-${UUID.randomUUID()}").apply { mkdirs() }
        original = ServiceLocator.vaultAuthenticator
        gate = FakeGate()
        ServiceLocator.vaultAuthenticator = gate
        originalNotify = ServiceLocator.preferences.notifyOnCompletion
        ServiceLocator.preferences.notifyOnCompletion = true
        publicRecord = seed(publicTitle, private = false)
        privateRecord = seed(privateTitle, private = true)
        compose.waitUntil(30_000) { compose.onAllNodesWithText("已存").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() = runBlocking {
        ServiceLocator.vaultAuthenticator = original
        ServiceLocator.preferences.notifyOnCompletion = originalNotify
        NotificationManagerCompat.from(context).cancelAll()
        listOf(publicRecord, privateRecord).forEach {
            ServiceLocator.historyRepository.delete(it.id)
            it.filePath?.let { path -> File(path).delete() }
        }
        directory.deleteRecursively()
        Unit
    }

    private fun evidence(name: String, text: String) {
        File(context.cacheDir, "vault-evidence").apply { mkdirs() }
            .let { File(it, name).writeText(text) }
    }

    private fun openLibrary() {
        compose.onAllNodesWithText("已存").onFirst().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("vault-chip").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun privateTitleNodes() = compose.onAllNodesWithText(privateTitle, substring = true).fetchSemanticsNodes().size

    @Test fun theVaultKeepsPrivateItemsOutUntilTheGateIsPassed() {
        // 首页「最近保存」has no gate of its own, so it must never list a private record.
        compose.waitUntil(20_000) { compose.onAllNodesWithText(publicTitle, substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("首页最近保存 must not list a private record", 0, compose.onAllNodesWithText(privateTitle, substring = true).fetchSemanticsNodes().size)
        openLibrary()
        compose.onNodeWithText(publicTitle, substring = true).assertExists()
        assertEquals("A locked vault shows nothing", 0, privateTitleNodes())

        // The user fails the prompt: the vault stays shut.
        gate.granted = false
        compose.onNodeWithTag("vault-chip").performClick()
        compose.waitForIdle()
        assertEquals(1, gate.calls)
        assertEquals("A refused prompt must not open anything", 0, privateTitleNodes())

        // The user passes it: only now do private items appear, and the ordinary ones step aside.
        gate.granted = true
        compose.onNodeWithTag("vault-chip").performClick()
        compose.waitUntil(10_000) { privateTitleNodes() > 0 }
        assertEquals(2, gate.calls)
        assertEquals("The vault is its own view", 0, compose.onAllNodesWithText(publicTitle, substring = true).fetchSemanticsNodes().size)

        // Locking puts it back behind the gate without another prompt.
        compose.onNodeWithTag("vault-lock").performClick()
        compose.waitUntil(10_000) { privateTitleNodes() == 0 }
        assertEquals(2, gate.calls)
        compose.onNodeWithText(publicTitle, substring = true).assertExists()
        evidence("vault-gate.txt", "locked=hidden refused=hidden unlocked=visible relocked=hidden prompts=${gate.calls}")
    }

    @Test fun anUnavailableGateSaysSoInsteadOfOpening() {
        gate.available = false
        openLibrary()
        compose.onNodeWithTag("vault-chip").performClick()
        compose.waitForIdle()
        assertEquals("No prompt may be attempted on a device without a lock", 0, gate.calls)
        assertEquals(0, privateTitleNodes())
        evidence("vault-unavailable.txt", "available=false prompts=0 private=hidden")
    }

    @Test fun theTaskListAndTheCompletionNotificationNeverNameAPrivateRecord() {
        openLibrary()
        compose.onAllNodesWithText("任务").onFirst().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(context.getString(com.videodown.douyin.R.string.vault_locked_title)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("The task list must not name it", 0, compose.onAllNodesWithText(privateTitle).fetchSemanticsNodes().size)

        assumeTrue("Notifications are disabled on this device", NotificationManagerCompat.from(context).areNotificationsEnabled())
        runBlocking { DownloadCompletionNotifier(context, ServiceLocator.preferences).notifyCompleted(privateRecord, GalleryAutoSave.Skipped) }
        val posted = NotificationManagerCompat.from(context).activeNotifications.firstOrNull { it.id == 1002 }
        assertNotNull("The completion card should have been posted", posted)
        val extras = posted!!.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        assertEquals(context.getString(com.videodown.douyin.R.string.notification_download_complete), title)
        assertEquals(context.getString(com.videodown.douyin.R.string.vault_locked_title), text)
        listOf(text, big).forEach { assertFalse("A private title must not reach the notification: $it", it.contains(privateTitle)) }
        assertTrue(big.startsWith(context.getString(com.videodown.douyin.R.string.vault_locked_title)))
        evidence("vault-notification.txt", "title=$title text=$text big=$big")
    }
}
