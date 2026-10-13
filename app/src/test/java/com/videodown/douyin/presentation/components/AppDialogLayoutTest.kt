package com.videodown.douyin.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹窗高度上限与「整卡滚动」判定的纯逻辑。
 *
 * 为什么这条在 JVM 而不是设备上测：真机上键盘高度、系统栏高度、窗口是否被系统平移都由系统决定，
 * 设备用例只能证明"某一台机器上的某一次布局对了"；这里守的是与设备无关的不变量——
 * 键盘把可用高度压没时不能给出负数上限（`heightIn(max = …)` 收到负数会直接抛异常），
 * 以及"固定页脚放不下就换成整卡滚动"的取舍边界。
 */
class AppDialogLayoutTest {
    @Test fun `available height subtracts system bars and keyboard from the window`() {
        // 393dp 高、29dp 状态栏、235dp 键盘。
        assertEquals(129, appDialogAvailableHeightPx(393, 29, 235))
        // 没有键盘时只剩系统栏。
        assertEquals(348, appDialogAvailableHeightPx(393, 29, 16))
    }

    @Test fun `insets that swallow the whole window clamp to zero instead of going negative`() {
        assertEquals(0, appDialogAvailableHeightPx(400, 30, 400))
        // 键盘比窗口还高（横屏 + 大键盘）同样必须归零。
        assertEquals(0, appDialogAvailableHeightPx(300, 24, 600))
    }

    @Test fun `bogus negative insets do not inflate the available height past the window`() {
        assertEquals(400, appDialogAvailableHeightPx(400, -50, -50))
    }

    @Test fun `max height is the fraction of the available height`() {
        // 348 * 0.86 = 299。
        assertEquals(299, appDialogMaxHeightPx(348, 0.86f))
        assertEquals(0, appDialogMaxHeightPx(0, 0.86f))
    }

    @Test fun `fraction is clamped to zero through one`() {
        assertEquals(400, appDialogMaxHeightPx(400, 1f))
        assertEquals(400, appDialogMaxHeightPx(400, 4f))
        assertEquals(0, appDialogMaxHeightPx(400, -1f))
    }

    @Test fun `narrow space falls back to scrolling the whole card`() {
        // 真机横屏 + 键盘只剩 129dp，比头部加动作行还矮：固定页脚会被裁掉，必须整卡滚动。
        assertTrue(appDialogScrollsWholeCard(129, 200))
        // 横屏无键盘（348dp）与竖屏有键盘（593dp）都还放得下固定页脚。
        assertFalse(appDialogScrollsWholeCard(348, 200))
        assertFalse(appDialogScrollsWholeCard(593, 200))
        // 边界是严格小于：正好放得下时保持固定页脚。
        assertFalse(appDialogScrollsWholeCard(200, 200))
    }
}
