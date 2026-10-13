package com.videodown.douyin.presentation.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 全应用统一的动效参数。
 *
 * 自己定义时长与缓动，而不是取 Material3 的 `MotionScheme`：可用的 material3 版本里
 * `MotionScheme` 与 `MaterialTheme.motionScheme` 都还是 `internal`（见 docs/validation 记录），
 * 应用代码引用不到。这里的数值沿用 Material 3 的运动 token（150 / 250 / 400 ms 与三条标准曲线），
 * 与 Material 组件自身的节奏一致；等到 `MotionScheme` 转公开后可原位替换。
 *
 * 系统动画缩放为 0（开发者选项或无障碍里的「关闭动画」）时所有 spec 退化为 [snap]、入场退化为
 * [EnterTransition.None]，界面直接跳到终态——设备测试（Espresso 与 Compose 的 `waitForIdle`）都要求
 * 关闭动画，无限动画也必须在这一档保持静止，否则 Compose 测试永远不会 idle。
 */
@Immutable
internal class AppMotion(val enabled: Boolean) {

    /** 进入/展开：稍长、减速曲线。 */
    fun <T> normal(): FiniteAnimationSpec<T> = spec(DEFAULT_MS, DECELERATE)

    /** 小范围变化：颜色、进度、开关。 */
    fun <T> fast(): FiniteAnimationSpec<T> = spec(FAST_MS, STANDARD)

    /** 大范围结构变化：布局切换、共享元素。 */
    fun <T> slow(): FiniteAnimationSpec<T> = spec(SLOW_MS, STANDARD)

    /** 出现时的编排：淡入 +（默认）纵向展开。 */
    fun enter(expand: Boolean = true): EnterTransition = if (!enabled) EnterTransition.None else {
        val fade = tween<Float>(DEFAULT_MS, easing = DECELERATE)
        if (expand) fadeIn(fade) + expandVertically(tween(DEFAULT_MS, easing = DECELERATE)) else fadeIn(fade)
    }

    /** 消失时的编排：淡出 +（默认）纵向收起。 */
    fun exit(shrink: Boolean = true): ExitTransition = if (!enabled) ExitTransition.None else {
        val fade = tween<Float>(FAST_MS, easing = ACCELERATE)
        if (shrink) fadeOut(fade) + shrinkVertically(tween(FAST_MS, easing = ACCELERATE)) else fadeOut(fade)
    }

    private fun <T> spec(durationMillis: Int, easing: Easing): FiniteAnimationSpec<T> =
        if (enabled) tween(durationMillis, easing = easing) else snap()

    companion object {
        const val FAST_MS = 150
        const val DEFAULT_MS = 250
        const val SLOW_MS = 400

        /** Material 3 标准曲线：常规位移与淡入淡出。 */
        val STANDARD: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /** 强调减速：元素进入视野。 */
        val DECELERATE: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /** 强调加速：元素离开视野。 */
        val ACCELERATE: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    }
}

/** 取当前组合的动效参数；进入组合时读一次系统动画缩放。 */
@Composable
internal fun appMotion(): AppMotion {
    val context = LocalContext.current
    val scale = remember(context) { animatorDurationScale(context) }
    return remember(scale) { AppMotion(motionEnabledFromScale(scale)) }
}

/**
 * 内容首次出现时的淡入系数（0→1），用于整页/整段的入场。
 *
 * 之所以不用 `AnimatedContent` 做状态切换：那会把新旧两页同时留在语义树里，断言可能读到旧节点；
 * 这里保持调用方原有的 `if/else`，旧内容立即移除，只让新内容淡入。
 */
@Composable
internal fun rememberEntranceAlpha(): Float {
    val motion = appMotion()
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = motion.normal(),
        label = "entrance-alpha",
    )
    return alpha
}

/**
 * 系统动画缩放是否允许播放动画。
 *
 * 缩放为 0 时返回 false；读不到（NaN）按允许动画处理，避免读设置失败时整个界面失去过渡。
 */
internal fun motionEnabledFromScale(scale: Float): Boolean = scale.isNaN() || scale > 0f

/** 读系统动画缩放；读不到或读失败时按「允许动画」处理。 */
private fun animatorDurationScale(context: Context): Float = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)
