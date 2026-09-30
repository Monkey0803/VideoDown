package com.videodown.douyin.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun lightThemeTextIsReadable() = verifyContrast(LightColors)
    @Test fun darkThemeTextIsReadable() = verifyContrast(DarkColors)

    @Test fun approvedBrandColorsRemainStable() {
        assertEquals(Color(0xFFFFF8F5), LightColors.background)
        assertEquals(Color(0xFF33251F), LightColors.onSurface)
        assertEquals(Color(0xFFFFE6D8), LightColors.primaryContainer)
        assertEquals(Color(0xFFFFAD75), DarkColors.primary)
    }

    private fun verifyContrast(scheme: ColorScheme) {
        val pairs = listOf(
            scheme.onPrimary to scheme.primary,
            scheme.onPrimaryContainer to scheme.primaryContainer,
            scheme.onSecondary to scheme.secondary,
            scheme.onTertiary to scheme.tertiary,
            scheme.onBackground to scheme.background,
            scheme.onSurface to scheme.surfaceContainerLow,
            scheme.onSurfaceVariant to scheme.surfaceContainerHighest,
            scheme.primary to scheme.surfaceContainerLow,
            scheme.secondary to scheme.surfaceContainerLow,
            scheme.tertiary to scheme.surfaceContainerLow,
            scheme.error to scheme.surfaceContainerLow,
            scheme.inverseOnSurface to scheme.inverseSurface,
        )
        pairs.forEach { (foreground, background) ->
            val ratio = contrast(foreground, background)
            assertTrue("Text contrast $ratio must reach 4.5:1", ratio >= 4.5)
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
