package com.videodown.douyin.presentation.components

import androidx.graphics.shapes.Morph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空状态插图两个多边形之间的可 morph 性。
 *
 * `Morph` 在两侧形状无法一一对应时会抛异常——那会让空状态直接崩，而不是"动画不好看"。
 * 这条纯逻辑测试把它钉在 JVM 上（设备用例只覆盖渲染与总开关）。
 */
class EmptyStatePolygonTest {
    @Test fun `both shapes expose the same cubics so the morph can be built`() {
        val start = emptyStateStartPolygon()
        val end = emptyStateEndPolygon()
        assertEquals(
            "两侧曲线段数必须一致，否则 Morph 无法一一对应",
            start.cubics.size,
            end.cubics.size,
        )
        // 构造本身不抛异常是核心断言（形状无法一一对应时 Morph 会抛）。
        val morph = Morph(start, end)
        // morph 的段数是配对结果，不保证等于源形状段数；要保证的是各进度上一致。
        val atStart = morph.asCubics(0f).size
        assertTrue("morph 至少要产出一段曲线", atStart > 0)
        assertEquals(atStart, morph.asCubics(0.5f).size)
        assertEquals(atStart, morph.asCubics(1f).size)
    }

    @Test fun `the two shapes actually differ and stay inside the unit box`() {
        val start = emptyStateStartPolygon()
        val end = emptyStateEndPolygon()
        val morph = Morph(start, end)
        // 形状必须真的有差别：圆的控制点与圆角方形的控制点不能一模一样。
        val startAnchors = start.cubics.flatMap { listOf(it.anchor0X, it.anchor0Y) }
        val endAnchors = end.cubics.flatMap { listOf(it.anchor0X, it.anchor0Y) }
        assertTrue("起点与终点必须是不同的形状", startAnchors != endAnchors)

        // 绘制时按控件尺寸缩放，所以坐标必须落在以原点为中心的单位范围内。
        listOf(morph.asCubics(0f), morph.asCubics(1f)).forEach { cubics ->
            cubics.forEach { cubic ->
                listOf(cubic.anchor0X, cubic.anchor1X).forEach { x ->
                    assertTrue("x=$x 超出单位范围", x in -1.05f..1.05f)
                }
            }
        }
    }
}
