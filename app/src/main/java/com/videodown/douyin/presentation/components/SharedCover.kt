package com.videodown.douyin.presentation.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.videodown.douyin.presentation.theme.appMotion

/** 「已存」封面与播放器封面共用同一个共享元素 key。 */
internal fun playerCoverKey(recordId: String) = "player-cover-$recordId"

/**
 * 封面共享元素：在调用方自己的 Modifier 后面按需挂上 `sharedBounds`。
 *
 * 两个作用域为 null 时原样返回（例如设备测试直接渲染单个界面、没有 `SharedTransitionLayout` 祖先），
 * 行为与没有共享过渡时完全一致——这是让老用例不受影响的关键。
 *
 * [inOverlay] 为 true 时把该侧画进共享层（最上面）：源侧不加会被列表项裁掉，目标侧加则与源侧一起在共享层里交接。
 */
@Composable
internal fun Modifier.sharedCover(
    recordId: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    inOverlay: Boolean = false,
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return this
    val motion = appMotion()
    val shared = with(sharedTransitionScope) {
        val state = rememberSharedContentState(playerCoverKey(recordId))
        // 三种实现按「同屏出现几份封面」实测比过（见 docs/validation/motion-batch2-2026-10-12.md 第五节）：
        // sharedElement + 只给源侧进 overlay 是唯一单调收敛的一种（网格 → 视频框 → 交棒给视频），
        // 且过渡期间不遮挡播放器头部与控件。sharedBounds 会在左上角留一份钉住的拷贝，
        // 两侧都进 overlay 则会有两份原样冻住。
        val element = Modifier.sharedElement(
            sharedContentState = state,
            animatedVisibilityScope = animatedVisibilityScope,
            // 默认 spring 在 270 ms 时仍明显落后（实测），换成与全应用一致的 250 ms 曲线。
            boundsTransform = BoundsTransform { _, _ -> motion.normal<Rect>() },
        )
        if (inOverlay) element.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f) else element
    }
    return this.then(shared)
}
