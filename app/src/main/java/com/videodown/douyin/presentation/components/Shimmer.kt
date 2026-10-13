package com.videodown.douyin.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import com.videodown.douyin.presentation.theme.appMotion

/**
 * 骨架屏的扫光。
 *
 * 两件事必须同时成立：
 *
 * 1. **走总开关**：[enabled] 为 false（系统动画缩放为 0，即开发者选项或无障碍里的「关闭动画」）
 *    时不做任何动画，只留静态底色。这与 [appMotion] 的约定一致。
 * 2. **用 `rememberInfiniteTransition` 而不是 `while (true) { delay(…) }`**：Compose 的测试时钟
 *    把前者的动画排除在「空闲」判定之外，后者的 delay 循环会让 `waitForIdle` 永远等下去。
 *    本工程的设备测试有 200 多处 `waitForIdle` / `waitUntil`，一次误用就会把用例挂死。
 *    设备用例 `ShimmerDeviceTest` 就是钉住这一条的。
 */
@Composable
internal fun Modifier.shimmer(
    enabled: Boolean = appMotion().enabled,
    color: Color = Color.White.copy(alpha = 0.06f),
): Modifier {
    if (!shimmerActive(enabled)) return this
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SWEEP_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-progress",
    )
    return drawWithContent {
        drawContent()
        // 一条斜向亮带扫过画面。progress = 0 时亮带已经压在画面左缘（而不是还在画外），
        // 这样首帧就看得见扫光、不必空等一圈；也让「总开关开/关」在同一时刻有可观察的差异。
        val width = size.width.coerceAtLeast(1f)
        val travel = width * 2f
        val start = -travel / 2f + progress * travel * 1.5f
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, color, Color.Transparent),
                start = Offset(start, 0f),
                end = Offset(start + travel, size.height),
            ),
        )
    }
}

/** 扫光一次的时间；比动效 token 慢，因为它是循环的背景装饰，快了会抢注意力。 */
private const val SWEEP_MS = 1100

/**
 * 骨架屏是否应该真的扫光。
 *
 * 抽成纯函数是为了能被 JVM 测试覆盖：Compose 测试环境装了 `InfiniteAnimationPolicy`，
 * 无限动画在测试里不会推进，所以「扫光有没有动」在设备测试里无法用像素证明，
 * 能证明的是「总开关关闭时压根不装这个绘制」。见 `ShimmerGateTest` 与 `ShimmerDeviceTest`。
 */
internal fun shimmerActive(motionEnabled: Boolean): Boolean = motionEnabled
