package com.videodown.douyin.presentation.adaptive

import android.app.Activity
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlin.math.roundToInt
import com.videodown.douyin.presentation.theme.AppSpacing

/**
 * 当前窗口宽度尺寸类。
 *
 * 由 `MainActivity` 用官方 `calculateWindowSizeClass(activity)` 注入；直接渲染页面的预览与
 * 组件测试拿到的默认值是 Compact，与手机竖屏一致。
 */
val LocalWindowWidth = compositionLocalOf { WindowWidth.COMPACT }

/** 当前折痕；无折叠屏或无可分离折痕时为 null。 */
val LocalFoldFeature = compositionLocalOf<FoldFeature?> { null }

/** 页面左右边距（Compact 16dp / Medium+ 24dp），从同一处取，页面不再各写 22dp。 */
@Composable
fun pagePadding(): Dp = AppSpacing.pageHorizontal(LocalWindowWidth.current)

/** 把 Material3 的官方尺寸类映射成本仓库的枚举（其余未知值按 Expanded 处理）。 */
fun windowWidthOf(sizeClass: WindowWidthSizeClass): WindowWidth = when (sizeClass) {
    WindowWidthSizeClass.Compact -> WindowWidth.COMPACT
    WindowWidthSizeClass.Medium -> WindowWidth.MEDIUM
    else -> WindowWidth.EXPANDED
}

/**
 * 收集折叠屏的折痕信息。
 *
 * `WindowInfoTracker` 的流在窗口姿态变化时回调；把像素矩形换算成 dp 后交给纯策略，
 * 页面再据此避开折痕。模拟器没有折叠屏时流不会给出 FoldingFeature，值为 null。
 */
@Composable
fun rememberFoldFeature(activity: Activity): FoldFeature? {
    val density = activity.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
    return produceState<FoldFeature?>(initialValue = null, activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            value = feature?.let {
                val bounds = it.bounds
                FoldFeature(
                    separating = it.isSeparating,
                    verticalHinge = it.orientation == FoldingFeature.Orientation.VERTICAL,
                    leftDp = (bounds.left / density).roundToInt(),
                    topDp = (bounds.top / density).roundToInt(),
                    rightDp = (bounds.right / density).roundToInt(),
                    bottomDp = (bounds.bottom / density).roundToInt(),
                )
            }
        }
    }.value
}
