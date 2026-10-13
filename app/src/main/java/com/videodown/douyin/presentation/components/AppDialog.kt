package com.videodown.douyin.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videodown.douyin.presentation.theme.appMotion

/** 弹窗高度上限占**可用高度**的比例；正文放得下就整段展示，放不下才滚动。 */
private const val DEFAULT_MAX_HEIGHT_FRACTION = 0.86f

/** [AppDialogContainer] 里铺满整屏、负责「点击卡片外关闭」的那一层，供设备用例点击。 */
internal const val APP_DIALOG_SCRIM_TAG = "app-dialog-scrim"

/** 弹窗卡片本身，供设备用例把「页脚有没有被卡片裁掉」量成两个矩形的关系。 */
internal const val APP_DIALOG_CARD_TAG = "app-dialog-card"

/**
 * 头部 + 动作行这两块固定区域在纵向上至少要吃掉的高度（含分隔线与内边距）。
 *
 * 它**不参与任何尺寸计算**，只在「固定页脚」与「整卡滚动」两种排布之间取舍，所以估歪的代价有限：
 * 估大了只是在本就局促的窗口里多一层滚动，估小了才会让动作行被卡片裁掉。按单行标题
 * （20+44+14 = 78dp 头部）、两枚按钮（12+40+12 = 64dp 动作行）与 2dp 分隔线估，取 200dp 留余量。
 */
private val APP_DIALOG_MIN_FIXED_HEIGHT = 200.dp

/** 可用高度：窗口高度扣掉系统栏与软键盘之后剩下的部分，负数一律归零。 */
internal fun appDialogAvailableHeightPx(
    containerHeightPx: Int,
    topInsetPx: Int,
    bottomInsetPx: Int,
): Int = (containerHeightPx - topInsetPx.coerceAtLeast(0) - bottomInsetPx.coerceAtLeast(0)).coerceAtLeast(0)

/**
 * 弹窗内容区的高度上限，单位为像素：可用高度的 [maxFraction]。
 *
 * 可用高度为 0 时必须返回 0 而不是负数——`heightIn` 收到负数会直接抛
 * `IllegalArgumentException`，那是崩溃而不是"弹窗变小"。
 */
internal fun appDialogMaxHeightPx(availableHeightPx: Int, maxFraction: Float): Int {
    if (availableHeightPx <= 0) return 0
    return (availableHeightPx * maxFraction.coerceIn(0f, 1f)).toInt()
}

/**
 * 可用高度连固定区域都放不下时，头部与动作行都不再固定，整张卡片一起滚动。
 *
 * 真机 393dp 高、横屏 + 键盘时可用高度只剩约 129dp，比头部加动作行还矮；这时无论怎么封顶，
 * 固定页脚都会被裁掉，只能把它交给滚动区——滚一下就能点到按钮。
 */
internal fun appDialogScrollsWholeCard(availableHeightPx: Int, minFixedHeightPx: Int): Boolean =
    availableHeightPx < minFixedHeightPx

/**
 * 弹窗容器：铺满整屏，并让开系统栏与软键盘。
 *
 * 为什么要自己让键盘：弹窗窗口是浮动窗口，系统对它的处理是**把整窗向上平移**（ADJUST_PAN）
 * 让焦点输入框露出来，而不是缩放窗口——平移的后果是标题被推出屏幕顶部、固定在底部的动作行
 * 落到键盘背后（真机 393dp 高、横屏 + 键盘时必然出现）。所以窗口改成
 * `decorFitsSystemWindows = false` 自己处理内边距：容器按「系统栏 ∪ 输入法」收缩，卡片在
 * 剩下的区域里居中，标题与动作行始终在可见范围内。
 *
 * 为什么点击卡片外的关闭要自己实现：容器铺满整屏后，系统判断「点在窗口内容之外」时会发现
 * 任何点都在内容之内，`dismissOnClickOutside` 等于失效。这里用一个同样铺满的遮罩补回同样的
 * 行为，代价是卡片必须自己吃掉落在它上面的点击（见 [consumeTaps]），否则点卡片空白处会穿到
 * 遮罩上把弹窗关掉。
 *
 * 可用高度取自**布局约束**（`BoxWithConstraints`，内边距已经扣掉），不要用
 * `LocalWindowInfo.containerSize`：弹窗窗口里它给的是窗口的最大边（横屏拿 2400px 当"高度"），
 * 会把"键盘已经把可用高度压到 129dp"算成 1675px。
 */
@Composable
internal fun AppDialogContainer(
    onDismissRequest: () -> Unit,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.(availableHeightPx: Int) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
        contentAlignment = contentAlignment,
    ) {
        Box(
            Modifier.matchParentSize().testTag(APP_DIALOG_SCRIM_TAG)
                .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } },
        )
        content(constraints.maxHeight)
    }
}

/** 卡片内的非交互区域也要「吃掉」点击，否则会穿到底下的遮罩上把弹窗关掉。 */
internal fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/**
 * 全应用统一的卡片式弹窗骨架。
 *
 * 头部是 `primaryContainer` 图标块 + 标题（可选副标题），正文可滚动，动作行固定在底部——
 * 正文再长也不会把按钮挤出屏幕。正文/动作由调用方给出：推进型操作放一枚通栏
 * `FilledTonalButton`，其余按钮放进 [AppDialogButtonRow]。
 *
 * 高度上限按**可用高度**（窗口扣掉系统栏与键盘）取比例，所以键盘弹起时弹窗会自动变矮、
 * 正文继续滚动，而不是被键盘盖住。
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
    val density = LocalDensity.current
    val motion = appMotion()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 官方推荐的组合：整屏弹窗自己处理 WindowInsets，否则 IME 进出时窗口只会被平移。
            decorFitsSystemWindows = false,
            // 容器铺满整屏，系统的「点在内容之外」判断恒为「在内部」，改由 AppDialogContainer 实现。
            dismissOnClickOutside = false,
        ),
    ) {
        // 只做入场：出场时弹窗窗口已被关闭，再挂动画只会让关闭显得迟滞。
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val entrance by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = motion.normal(),
            label = "app-dialog-entrance",
        )
        AppDialogContainer(onDismissRequest) { availableHeightPx ->
            val maxHeight = with(density) { appDialogMaxHeightPx(availableHeightPx, maxHeightFraction).toDp() }
            val scrollWholeCard = appDialogScrollsWholeCard(
                availableHeightPx,
                with(density) { APP_DIALOG_MIN_FIXED_HEIGHT.roundToPx() },
            )
            Surface(
                modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp).widthIn(max = 420.dp)
                    .heightIn(max = maxHeight)
                    .consumeTaps()
                    .testTag(APP_DIALOG_CARD_TAG)
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
                    if (scrollWholeCard) {
                        // 可用高度连头部加动作行都放不下（横屏 + 键盘）：两块一起进滚动区，
                        // 滚一下就能点到按钮，而不是让页脚被卡片裁掉。
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            AppDialogHeader(icon, title, subtitle)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AppDialogContent(content)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AppDialogActions(actions)
                        }
                    } else {
                        AppDialogHeader(icon, title, subtitle)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AppDialogContent(
                            // fill = false：内容放得下就整段展示，放不下才把弹窗高度封顶并滚动。
                            content = content,
                            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AppDialogActions(actions)
                    }
                }
            }
        }
    }
}

/** 正文区：内边距与行距的唯一来源；[modifier] 由调用方决定要不要加权重与滚动。 */
@Composable
private fun AppDialogContent(content: @Composable ColumnScope.() -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** 动作行：内边距与行距的唯一来源，固定底部与并入滚动区共用同一份尺寸。 */
@Composable
private fun AppDialogActions(actions: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = actions,
    )
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
