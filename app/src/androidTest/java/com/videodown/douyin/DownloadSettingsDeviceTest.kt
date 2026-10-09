package com.videodown.douyin

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppPreferences
import com.videodown.douyin.core.ThemeMode
import com.videodown.douyin.data.download.DownloadCompletionNotifier
import com.videodown.douyin.data.download.WifiDownloadNetworkPolicy
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Android API integration tests; each preference store and notification tag is isolated. */
@RunWith(AndroidJUnit4::class)
class DownloadSettingsDeviceTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testId = "settings-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(testId, mode)
    }
    private val preferences = AppPreferences(context)
    private val manager = target.getSystemService(NotificationManager::class.java)
    private val record = DownloadRecord(id = testId, awemeId = "fixture", title = "设置回归测试",
        resolutionLabel = "720P", status = DownloadStatus.COMPLETED)

    @After fun cleanup() {
        manager.cancel(testId, 1002)
        target.deleteSharedPreferences(testId)
    }

    @Test fun preferencesSurviveRecreation() {
        assertFalse(preferences.wifiOnly)
        assertFalse(preferences.notifyOnCompletion)
        preferences.wifiOnly = true
        preferences.notifyOnCompletion = true
        val reopened = AppPreferences(context)
        assertTrue(reopened.wifiOnly)
        assertTrue(reopened.notifyOnCompletion)
        preferences.wifiOnly = false
        preferences.notifyOnCompletion = false
        assertFalse(AppPreferences(context).wifiOnly)
        assertFalse(AppPreferences(context).notifyOnCompletion)
    }

    @Test fun autoResumeDefaultsToOnAndPersistsBothChoices() {
        assertTrue(preferences.autoResume)
        assertTrue(preferences.autoResumeChanges.value)
        assertTrue(AppPreferences(context).autoResume)
        preferences.autoResume = false
        assertFalse(preferences.autoResumeChanges.value)
        assertFalse(AppPreferences(context).autoResume)
        preferences.autoResume = true
        assertTrue(AppPreferences(context).autoResume)
    }

    @Test fun themeModeDefaultsToSystemAndPersistsEachChoice() {
        assertEquals(ThemeMode.SYSTEM, preferences.themeMode)
        ThemeMode.entries.forEach { mode ->
            preferences.themeMode = mode
            assertEquals(mode, preferences.themeModeChanges.value)
            assertEquals(mode, AppPreferences(context).themeMode)
        }
        context.getSharedPreferences("shiying_preferences", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", "unknown").commit()
        assertEquals(ThemeMode.SYSTEM, AppPreferences(context).themeMode)
    }

    @Test fun disabledCompletionPreferencePostsNothing() {
        preferences.notifyOnCompletion = false
        DownloadCompletionNotifier(context, preferences).notifyCompleted(record)
        assertFalse(manager.activeNotifications.any { it.tag == testId })
    }

    @Test fun enabledCompletionPreferencePostsIndependentNotification() {
        assumeTrue("Enable app notifications before running this integration test",
            DownloadCompletionNotifier.areNotificationsEnabled(target))
        preferences.notifyOnCompletion = true
        DownloadCompletionNotifier(context, preferences).notifyCompleted(record)
        val deadline = System.currentTimeMillis() + 3000
        while (manager.activeNotifications.none { it.tag == testId } && System.currentTimeMillis() < deadline) {
            Thread.sleep(30)
        }
        val posted = manager.activeNotifications.single { it.tag == testId }
        assertEquals(DownloadCompletionNotifier.CHANNEL_ID, posted.notification.channelId)
        assertEquals("下载完成", posted.notification.extras.getString("android.title"))
        assertNotNull(posted.notification.contentIntent)
        assertEquals(1002, posted.id)
    }

    @Test fun wifiRestrictionReactsToRealNetworkAndPreferenceChanges() = runBlocking {
        val policy = WifiDownloadNetworkPolicy(context, preferences)
        val wifiWasEnabled = shell("cmd wifi status").contains("Wifi is enabled", ignoreCase = true)
        preferences.wifiOnly = true
        try {
            shell("svc wifi enable")
            withTimeout(20_000) { policy.allowed.first { it } }
            assertTrue(policy.isAllowed())
            shell("svc wifi disable")
            withTimeout(10_000) { policy.allowed.first { !it } }
            // Connectivity callbacks and activeNetwork snapshots settle asynchronously.
            withTimeout(10_000) { while (policy.isAllowed()) kotlinx.coroutines.delay(30) }
            assertFalse(policy.isAllowed())
            preferences.wifiOnly = false
            withTimeout(3000) { policy.allowed.first { it } }
            assertTrue(policy.isAllowed())
            preferences.wifiOnly = true
            withTimeout(3000) { policy.allowed.first { !it } }
            shell("svc wifi enable")
            withTimeout(20_000) { policy.allowed.first { it } }
            assertTrue(policy.isAllowed())
        } finally {
            shell(if (wifiWasEnabled) "svc wifi enable" else "svc wifi disable")
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
