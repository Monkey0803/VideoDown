package com.videodown.douyin.presentation.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动画总开关的判定：只有系统明确把动画缩放设为 0 才停动画，读不到/异常/正常值都保持动画。
 * 这里只覆盖纯函数，读 [android.provider.Settings] 的那一段在设备上验证。
 */
class MotionTest {
    @Test fun `zero scale disables motion so tests and reduced-motion users get a still ui`() {
        assertFalse(motionEnabledFromScale(0f))
        assertFalse(motionEnabledFromScale(-1f))
    }

    @Test fun `any positive scale keeps motion enabled`() {
        assertTrue(motionEnabledFromScale(1f))
        assertTrue(motionEnabledFromScale(0.5f))
        assertTrue(motionEnabledFromScale(5f))
    }

    @Test fun `unreadable scale does not silently kill motion`() {
        assertTrue(motionEnabledFromScale(Float.NaN))
    }
}
