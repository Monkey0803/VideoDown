package com.videodown.douyin

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.AppPreferences
import com.videodown.douyin.core.ServiceLocator
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Material You is a system-provided palette, so this case asserts what the app itself owns: the row
 * only exists where the platform supports it, the choice persists, and the theme genuinely repaints
 * instead of silently keeping the fixed 拾影 brand colors.
 */
@RunWith(AndroidJUnit4::class)
class DynamicColorDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val evidence get() = File(context.cacheDir, "dynamic-color-evidence").apply { mkdirs() }

    @After fun restore() { ServiceLocator.preferences.dynamicColor = false }

    /** Samples how many pixels still carry the fixed brand primary (0xA64021). */
    private fun brandPrimaryPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val color = bitmap.getPixel(x, y)
                val distance = abs(android.graphics.Color.red(color) - 0xA6) +
                    abs(android.graphics.Color.green(color) - 0x40) +
                    abs(android.graphics.Color.blue(color) - 0x21)
                if (distance <= 24) count++
            }
        }
        return count
    }

    private fun screenshot(name: String): Int {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return brandPrimaryPixels(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun dynamicColorIsOfferedOnlyWhereSupportedAndRepaintsTheTheme() {
        val original = ServiceLocator.preferences.dynamicColor
        try {
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(15_000) { compose.onAllNodesWithText("解析").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("我的").performClick()
            val row = compose.onNodeWithText("动态取色（Material You）")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                row.assertDoesNotExist()
                return
            }
            ServiceLocator.preferences.dynamicColor = false
            compose.waitForIdle()
            row.performScrollTo().assertIsDisplayed()
            val brandedPixels = screenshot("brand-palette")
            assertTrue("The brand palette must actually paint the 拾影 primary", brandedPixels > 0)

            row.performClick()
            compose.waitForIdle()
            assertTrue("The switch must turn dynamic color on", ServiceLocator.preferences.dynamicColor)
            assertTrue("The choice must survive a fresh reader", AppPreferences(context).dynamicColor)
            val dynamicPixels = screenshot("dynamic-palette")
            assertTrue("Material You must repaint the palette (brand=$brandedPixels, dynamic=$dynamicPixels)",
                brandedPixels != dynamicPixels)

            assertTrue(compose.onAllNodesWithText("我的设置").fetchSemanticsNodes().isNotEmpty())
            File(evidence, "summary.txt").writeText(
                "sdk=${Build.VERSION.SDK_INT}\nbrand-primary pixels=$brandedPixels\ndynamic-primary pixels=$dynamicPixels\n" +
                    "preference persisted=true\n",
            )
        } finally {
            ServiceLocator.preferences.dynamicColor = original
        }
    }
}
