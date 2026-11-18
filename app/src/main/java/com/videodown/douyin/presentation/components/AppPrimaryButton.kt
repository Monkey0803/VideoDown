package com.videodown.douyin.presentation.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 全宽主操作按钮：通栏、56dp、加载中只留转圈。
 *
 * 这是「推进型操作」的唯一实现：首页的解析/保存与批量导入的解析都用它，
 * 页面不再自己写一枚尺寸相近的 Button。
 */
@Composable
internal fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    trailingIcon: AppIcons? = AppIcons.Forward,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(text, fontWeight = FontWeight.Bold)
            if (trailingIcon != null) {
                Spacer(Modifier.width(8.dp))
                AppIcon(trailingIcon, null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
