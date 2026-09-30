package com.videodown.douyin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.core.ThemeMode
import com.videodown.douyin.data.download.DownloadCompletionNotifier
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.components.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IconIntegrationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** Exercises Android's parser/renderer rather than only checking the generated XML. */
    @Test fun vectorsTintCorrectlyAndSelectedLibraryRetainsTransparentPlayCutout() {
        for (icon in AppIcons.entries) {
            for (size in listOf(16, 20, 24, 32)) {
                for (tint in listOf(Color.rgb(51, 37, 31), Color.rgb(255, 177, 139))) {
                    val bitmap = render(icon.resourceId, size, tint)
                    val pixels = IntArray(size * size)
                    bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
                    assertTrue("${icon.name} at $size must remain visible", pixels.any { Color.alpha(it) > 128 })
                    assertTrue("${icon.name} must use the requested tint", pixels.filter { Color.alpha(it) > 240 }
                        .all { (it and 0xFFFFFF) == (tint and 0xFFFFFF) })
                    bitmap.recycle()
                }
            }
        }
        val selected = render(AppIcons.LibrarySelected.resourceId, 96, Color.RED)
        assertEquals("Play cutout must reveal the theme container", 0, Color.alpha(selected.getPixel(48, 56)))
        assertEquals("Surrounding library remains solid", 255, Color.alpha(selected.getPixel(24, 56)))
        selected.recycle()

        val launcher = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull(launcher.foreground)
        assertNotNull(launcher.background)
        if (android.os.Build.VERSION.SDK_INT >= 33) assertNotNull(launcher.monochrome)
    }

    /** Uses actual navigation and the existing wallpaper-based palette without changing downloads. */
    @Test fun navigationRemainsUsableInLightAndDarkThemes() {
        val preferences = ServiceLocator.preferences
        val original = preferences.themeMode
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                preferences.themeMode = mode
                compose.waitForIdle()
                for ((label, name) in listOf("解析" to "home", "任务" to "tasks", "已存" to "library", "我的" to "settings")) {
                    compose.onNodeWithText(label).performClick().assertIsSelected()
                    compose.waitForIdle()
                    // UiAutomation captures native ripples too; wait for RenderThread to settle.
                    Thread.sleep(500)
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    assertNotNull(screenshot)
                    val output = File(context.cacheDir, "icon-integration/$name-${mode.name.lowercase()}.png")
                    output.parentFile!!.mkdirs()
                    output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
            }
        } finally {
            preferences.themeMode = original
        }
    }

    @Test fun completedNotificationUsesTheNewSingleColorIcon() {
        val preferences = ServiceLocator.preferences
        val originalEnabled = preferences.notifyOnCompletion
        assumeTrue("Requires notification permission on Android 13+", android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val manager = context.getSystemService(NotificationManager::class.java)
        val record = DownloadRecord(id = "icon-${UUID.randomUUID()}", awemeId = "icon-fixture",
            title = "图标通知验证", fileName = "icon-fixture.mp4", status = DownloadStatus.COMPLETED)
        try {
            preferences.notifyOnCompletion = true
            DownloadCompletionNotifier.createChannel(context)
            DownloadCompletionNotifier(context, preferences).notifyCompleted(record)
            compose.waitUntil(5_000) { manager.activeNotifications.any { it.tag == record.id } }
            val posted = manager.activeNotifications.single { it.tag == record.id }.notification
            assertEquals(R.drawable.ic_shiying_notification_complete, posted.smallIcon.resId)
        } finally {
            manager.cancel(record.id, 1002)
            preferences.notifyOnCompletion = originalEnabled
        }
    }

    private fun render(resourceId: Int, size: Int, tint: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, resourceId)!!.mutate()
        drawable.setTint(tint)
        drawable.setBounds(0, 0, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { drawable.draw(Canvas(it)) }
    }
}
