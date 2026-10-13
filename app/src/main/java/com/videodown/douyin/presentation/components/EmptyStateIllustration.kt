package com.videodown.douyin.presentation.components

import android.graphics.Matrix
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import androidx.compose.material3.MaterialTheme
import com.videodown.douyin.presentation.theme.appMotion

/** 供设备用例定位这张插图。 */
internal const val EMPTY_STATE_ILLUSTRATION_TAG = "empty-state-illustration"

/**
 * 已存空状态的插图：入场时形状从**圆**morph 到**圆角方形**（与应用图标同族的 squircle）。
 *
 * 为什么是有限动画：本应用禁止常驻的无限动画（见 `docs/validation` 的动效批次记录）。
 * 这里只在出现时 morph 一次，结束即静止；系统动画缩放为 0 时 [appMotion] 的 spec 退化为 `snap()`，
 * 直接落在终态，不做任何动画。
 *
 * [enabled] 只给测试用：设备用例需要在不改系统设置的前提下分别验证「开」与「关」两条路径。
 */
@Composable
internal fun EmptyStateIllustration(
    modifier: Modifier = Modifier,
    size: Dp = 108.dp,
    enabled: Boolean = appMotion().enabled,
) {
    val motion = appMotion()
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        // 总开关关闭时用 snap：首帧就是终态，不产生任何中间形状。
        animationSpec = if (enabled) motion.slow() else snap(),
        label = "empty-state-morph",
    )
    val morph = remember { Morph(emptyStateStartPolygon(), emptyStateEndPolygon()) }
    // 颜色必须在组合作用域里读：drawBehind 的 lambda 不是 @Composable。
    val fill = MaterialTheme.colorScheme.primaryContainer
    // 两个形状都用 4 个顶点，只是圆角半径不同——Morph 要求两侧可一一对应。
    Box(
        modifier
            .size(size)
            .testTag(EMPTY_STATE_ILLUSTRATION_TAG)
            .drawBehind {
                val path = morph.toPath(progress)
                path.transform(
                    Matrix().apply {
                        setScale(this@drawBehind.size.width / 2f, this@drawBehind.size.height / 2f)
                        postTranslate(this@drawBehind.size.width / 2f, this@drawBehind.size.height / 2f)
                    },
                )
                drawPath(path.asComposePath(), fill)
            },
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon = AppIcons.Library,
            contentDescription = null,
            modifier = Modifier.size(size * 0.42f),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * 起点：八顶点 + 圆角拉满 = 圆（9 段曲线）。
 *
 * 段数必须和终点一致，否则 `Morph` 建不出来（空状态会直接崩）。实测规律：
 * n 个顶点时圆角拉满得 n+1 段、圆角中等得 2n+1 段、直角得 n 段。
 */
internal fun emptyStateStartPolygon(): RoundedPolygon = RoundedPolygon(
    numVertices = 8, radius = 1f, centerX = 0f, centerY = 0f,
    rounding = CornerRounding(radius = 1f),
)

/** 终点：四顶点 + 中等圆角 = 圆角方形（同样 9 段）。 */
internal fun emptyStateEndPolygon(): RoundedPolygon = RoundedPolygon(
    numVertices = 4, radius = 1f, centerX = 0f, centerY = 0f,
    rounding = CornerRounding(radius = 0.5f),
)
