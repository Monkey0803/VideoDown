package com.videodown.douyin.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 亮/暗两套品牌配色的 WCAG 2.2 对比度下限。
 *
 * 动态取色（Material You）由系统生成、无法在 JVM 侧构造，设备用例另行人工核对；
 * 这里守住的是应用自带的两套主题：正文 ≥ 4.5:1、重要边界 ≥ 3:1。
 */
class ThemeContrastTest {
    private fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertContrast(scheme: String, role: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrast(foreground, background)
        assertTrue("$scheme $role 对比度 ${"%.2f".format(ratio)} 低于 $minimum:1", ratio >= minimum)
    }

    private fun bodyText(scheme: String, colors: ColorScheme) {
        assertContrast(scheme, "onBackground/background", colors.onBackground, colors.background, 4.5)
        assertContrast(scheme, "onSurface/surface", colors.onSurface, colors.surface, 4.5)
        assertContrast(scheme, "onSurfaceVariant/surfaceContainerLow", colors.onSurfaceVariant, colors.surfaceContainerLow, 4.5)
        assertContrast(scheme, "onSurfaceVariant/surfaceContainer", colors.onSurfaceVariant, colors.surfaceContainer, 4.5)
        assertContrast(scheme, "onPrimary/primary", colors.onPrimary, colors.primary, 4.5)
        assertContrast(scheme, "onPrimaryContainer/primaryContainer", colors.onPrimaryContainer, colors.primaryContainer, 4.5)
        assertContrast(scheme, "onSecondaryContainer/secondaryContainer", colors.onSecondaryContainer, colors.secondaryContainer, 4.5)
        assertContrast(scheme, "onTertiaryContainer/tertiaryContainer", colors.onTertiaryContainer, colors.tertiaryContainer, 4.5)
        assertContrast(scheme, "onError/error", colors.onError, colors.error, 4.5)
        assertContrast(scheme, "onErrorContainer/errorContainer", colors.onErrorContainer, colors.errorContainer, 4.5)
        // 重要边界（输入框、卡片描边、未选中图标）仍需 3:1。
        assertContrast(scheme, "outline/background", colors.outline, colors.background, 3.0)
        assertContrast(scheme, "primary/background", colors.primary, colors.background, 3.0)
    }

    /** 固定品牌色被 Material You / 主题重构误改时这里先报警。 */
    @Test fun approvedBrandColorsRemainStable() {
        org.junit.Assert.assertEquals(Color(0xFFFFF8F5), LightColors.background)
        org.junit.Assert.assertEquals(Color(0xFF33251F), LightColors.onSurface)
        org.junit.Assert.assertEquals(Color(0xFFFFE6D8), LightColors.primaryContainer)
        org.junit.Assert.assertEquals(Color(0xFFFFAD75), DarkColors.primary)
    }

    @Test fun lightSchemeMeetsWcagForBodyTextAndBoundaries() {
        bodyText("亮色", LightColors)
    }

    @Test fun darkSchemeMeetsWcagForBodyTextAndBoundaries() {
        bodyText("暗色", DarkColors)
    }

    @Test fun inverseContentRemainsReadable() {
        for ((name, colors) in listOf("亮色" to LightColors, "暗色" to DarkColors)) {
            assertContrast(name, "inverseOnSurface/inverseSurface", colors.inverseOnSurface, colors.inverseSurface, 4.5)
        }
    }
}
