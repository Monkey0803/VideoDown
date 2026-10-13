package com.videodown.douyin.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videodown.douyin.presentation.theme.appMotion

/** 弹窗高度上限占当前窗口高度的比例；正文放得下就整段展示，放不下才滚动。 */
private const val DEFAULT_MAX_HEIGHT_FRACTION = 0.86f

/**
 * 全应用统一的卡片式弹窗骨架。
 *
 * 头部是 `primaryContainer` 图标块 + 标题（可选副标题），正文可滚动，动作行固定在底部——
 * 正文再长也不会把按钮挤出屏幕。正文/动作由调用方给出：推进型操作放一枚通栏
 * `FilledTonalButton`，其余按钮放进 [AppDialogButtonRow]。
 *
 * 只有一句正文加一行「取消 / 确认」时直接用 [AppConfirmDialog]，选一项时用
 * [AppDialogRadioRow] 组列表，避免各弹窗再长出一套自己的间距与字重。
 */
@Composable
internal fun AppDialog(
    onDismissRequest: () -> Unit,
    icon: AppIcons,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    maxHeightFraction: Float = DEFAULT_MAX_HEIGHT_FRACTION,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 弹窗窗口在 usePlatformDefaultWidth = false 下就是整屏，容器高度即屏幕高度；用它取上限。
    val density = LocalDensity.current
    val containerHeight = LocalWindowInfo.current.containerSize.height
    val motion = appMotion()
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 只做入场：出场时弹窗窗口已被关闭，再挂动画只会让关闭显得迟滞。
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val entrance by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = motion.normal(),
            label = "app-dialog-entrance",
        )
        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp).widthIn(max = 420.dp)
                .heightIn(max = with(density) { containerHeight.toDp() } * maxHeightFraction)
                // 只动透明度与缩放：位移会让依赖坐标的断言在动画中途读到中间值。
                .graphicsLayer {
                    alpha = 0.35f + 0.65f * entrance
                    val scale = 0.94f + 0.06f * entrance
                    scaleX = scale
                    scaleY = scale
                },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                AppDialogHeader(icon, title, subtitle)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    // fill = false：内容放得下就整段展示，放不下才把弹窗高度封顶并滚动。
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = actions,
                )
            }
        }
    }
}

/** 标题区：图标块 + 名称（+ 一句话说明）。 */
@Composable
private fun AppDialogHeader(icon: AppIcons, title: String, subtitle: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 底部动作行：次操作靠左（多个之间用 8dp 间隔），推进型操作靠右，
 * 中间放 `Spacer(Modifier.weight(1f))` 把两组分开。
 */
@Composable
internal fun AppDialogButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * 单选行：统一的最小高度、圆角与单选语义；标签由调用方给出，可带一行说明。
 * 放进 `Column(Modifier.selectableGroup())` 即成一组单选。
 */
@Composable
internal fun AppDialogRadioRow(
    selected: Boolean,
    onSelect: () -> Unit,
    minHeight: Dp = 56.dp,
    label: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f), content = label)
    }
}

/** 正文一句话 + 一行「取消 / 确认」的确认弹窗；[dismissText] 为 null 时只留确认。 */
@Composable
internal fun AppConfirmDialog(
    onDismissRequest: () -> Unit,
    icon: AppIcons,
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    subtitle: String? = null,
    destructive: Boolean = false,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    AppDialog(onDismissRequest = onDismissRequest, icon = icon, title = title, subtitle = subtitle, actions = {
        AppDialogButtonRow {
            extraActions()
            dismissText?.let { TextButton(onClick = onDismissRequest) { Text(it) } }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) else ButtonDefaults.buttonColors(),
            ) { Text(confirmText) }
        }
    }) {
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
