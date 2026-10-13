package com.videodown.douyin.presentation.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 骨架屏总开关的纯逻辑。
 *
 * 为什么这条要在 JVM 而不是设备上测：Compose 的测试环境会安装 `InfiniteAnimationPolicy`，
 * 无限动画在测试里**永不推进**，所以「扫光在动」在设备上无法用像素断言；
 * 设备侧能证明的只是「总开关关闭时不装这个绘制」与「不会把 waitForIdle 卡死」。
 */
class ShimmerGateTest {
    @Test fun `shimmer follows the motion gate`() {
        assertTrue(shimmerActive(motionEnabled = true))
        assertFalse(shimmerActive(motionEnabled = false))
    }
}
