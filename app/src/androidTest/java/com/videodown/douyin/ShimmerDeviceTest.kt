package com.videodown.douyin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.presentation.components.shimmer
import com.videodown.douyin.presentation.theme.VideoDownTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 骨架屏扫光的两条硬约束：
 *
 * 1. **不能把 `waitForIdle` 卡死**：如果用 `while (true) { delay(…) }` 实现，Compose 测试的空闲判定
 *    永远等不到头，本工程 200 多处 `waitForIdle` / `waitUntil` 会集体挂死。打开扫光后直接
 *    `waitForIdle`，能返回就说明实现走的是 `rememberInfiniteTransition` 这一路。
 * 2. **总开关必须真的影响绘制**：同一时刻，「开」的那块占位必须带着亮带、「关」的那块只有底色。
 *
 * 两条测试环境的硬事实（都是实测踩出来的，不能想当然）：
 *
 * - **无限动画在测试里永不推进**：测试环境会安装 `InfiniteAnimationPolicy`，`Thread.sleep`、
 *   `mainClock.advanceTimeBy`、`advanceTimeByFrame` 三种方式都推不动它。所以「扫光在动」无法用
 *   像素证明，只能证明「装不装这个绘制」；扫光的动感本身靠录制与人工目视。
 * - **整屏截图会骗人**：系统状态栏的时钟/信号图标自己会变，所以比对必须裁剪到被测区域，
 *   否则「扫光没动」会被误判成「动了」。
 */
@RunWith(AndroidJUnit4::class)
class ShimmerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** 只截被测区域（见类注释：整屏会把状态栏的变化算进来）。 */
    private fun frame(tag: String): ByteArray {
        val full = instrumentation.uiAutomation.takeScreenshot()
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val crop = android.graphics.Bitmap.createBitmap(
            full,
            bounds.left.toInt().coerceIn(0, full.width - 1),
            bounds.top.toInt().coerceIn(0, full.height - 1),
            bounds.width.toInt().coerceIn(1, full.width),
            bounds.height.toInt().coerceIn(1, full.height),
        )
        if (crop !== full) full.recycle() // 裁剪覆盖整屏时 createBitmap 返回同一个实例
        return java.io.ByteArrayOutputStream().use { out ->
            crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            crop.recycle()
            out.toByteArray()
        }
    }

    /** 一次组合里并排放「开」和「关」两块同样的占位：`setContent` 每个用例只能调一次。 */
    private fun renderBoth() {
        compose.setContent {
            VideoDownTheme {
                Column {
                    Box(
                        Modifier.size(200.dp)
                            .testTag("shimmer-on")
                            .background(Color(0xFF203040))
                            .shimmer(enabled = true),
                    )
                    Box(
                        Modifier.size(200.dp)
                            .testTag("shimmer-off")
                            .background(Color(0xFF203040))
                            .shimmer(enabled = false),
                    )
                }
            }
        }
        // 开着扫光时这一句还能返回，就是「没有把测试拖死」的证据。
        compose.waitForIdle()
    }

    @Test fun theGateChangesWhatIsRendered() {
        renderBoth()
        val withShimmer = frame("shimmer-on")
        val withoutShimmer = frame("shimmer-off")
        assertFalse("开着扫光应该带亮带、关掉应该只有底色，两者不能一样", withShimmer.contentEquals(withoutShimmer))
    }

    @Test fun anEnabledShimmerStillLetsWaitForIdleReturn() {
        renderBoth()
        // 再等一轮：delay 循环实现会在这里挂到超时，而不是断言失败。
        Thread.sleep(300)
        compose.waitForIdle()
        assertTrue(frame("shimmer-on").isNotEmpty())
    }

    @Test fun aDisabledShimmerDrawsNothingButTheBackdrop() {
        renderBoth()
        // 不比较两张不同时刻的截图（真机上 takeScreenshot 会等待界面稳定并推进动画，
        // 跨截图的时间点不可控），也不写死颜色：真机截图的色彩管理与代码常量并不一致
        // （实测每个像素都对不上）。要证明的性质是**整块平面**：关闭扫光后处处同色。
        val bytes = frame("shimmer-off")
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val first = bitmap.getPixel(0, 0)
        fun channel(pixel: Int, shift: Int) = (pixel shr shift) and 0xFF
        // 真机截图对纯色也有抖动与色彩管理噪声（实测逐像素相等会 99.9% 不通过），
        // 所以量最大通道偏差：亮带会带来十几以上的偏差，噪声只有个位数。
        var maxDelta = 0
        val step = 4
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = bitmap.getPixel(x, y)
                for (shift in listOf(0, 8, 16)) {
                    maxDelta = maxOf(maxDelta, kotlin.math.abs(channel(pixel, shift) - channel(first, shift)))
                }
            }
        }
        bitmap.recycle()
        assertTrue("关闭总开关时占位必须是一整块纯色，不该有任何亮带（最大通道偏差=$maxDelta）", maxDelta <= 6)
    }
}
