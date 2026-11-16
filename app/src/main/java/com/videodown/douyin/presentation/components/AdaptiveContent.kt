package com.videodown.douyin.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 主页面的统一内容宽度。
 *
 * 手机继续铺满；横屏、平板和折叠屏把内容限制在可读宽度并居中，避免输入框、筛选条和任务卡
 * 被横向拉得过长。系统栏安全区由外层 Scaffold 统一处理，这里只负责内容尺寸。
 */
@Composable
fun AdaptivePage(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.widthIn(max = 840.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            content = content,
        )
    }
}
