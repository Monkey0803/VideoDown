package com.videodown.douyin

import android.app.Activity
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher

/**
 * 弹窗页脚「完整露在键盘之上」的判据，供各弹窗用例复用。
 *
 * 为什么不能只用 `assertIsDisplayed()`：它只要求**可见部分**不为空——被卡片裁到只剩一条边也算过
 * （真机上就是这条边，肉眼可见按钮被切掉）。
 *
 * 为什么分两条：
 *
 * 1. [footerInsideCard]：动作行会被 Column **挤扁**或裁掉。真机 393dp 高、横屏 + 键盘时量到
 *    按钮只剩 24dp 高（正常 40dp），页脚整体掉出卡片。这条比的是「按钮的**布局**矩形」与
 *    「卡片的矩形」——卡片的裁切来自 Surface 的 `clip(shape)`（graphicsLayer），语义里的
 *    `boundsInRoot` 只反映**布局**裁切，拿"可见高度 vs 布局高度"做判据会漏掉它。
 * 2. [cardInsideScrim]：卡片必须落在容器里被系统栏与键盘内边距收缩后的区域之内。容器
 *    （`AppDialogContainer`）的底部内边距就是键盘顶边，所以"页脚在卡片内 + 卡片在容器内"
 *    合起来就是"页脚不会落在键盘背后"，这两条本身只用 Compose 坐标，不需要屏幕坐标。
 *
 * 键盘本身由用例用 [imeVisible] 断言为前置条件（并在旋转后用 [showDialogKeyboard] 叫出来）：
 * 键盘没弹起来时可用高度还够，上面两条都会平凡通过，什么也证明不了。
 */

/** 输入法是否真的弹着：用例必须以此为前提，否则"页脚不被键盘挡住"证明不了任何事。 */
internal fun imeVisible(activity: Activity): Boolean =
    ViewCompat.getRootWindowInsets(activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

/** 键盘当前高度（px）。 */
internal fun imeBottomPx(activity: Activity): Int =
    ViewCompat.getRootWindowInsets(activity.window.decorView)
        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

/**
 * 等键盘动画停下来。
 *
 * 为什么不能只用 [imeVisible] 当门槛：Insets 一出现它就为真，可那时键盘只弹了一半、可用高度还大，
 * 「页脚完整可见」会**短暂成立**；等键盘真正弹完，布局再变一次，紧接着的断言就翻车
 * （模拟器 API 37 横屏上实测到过这种时序）。这里要求连续两次读到同一个非零高度才算稳定。
 *
 * 每个用例各持一个实例，免得上一次用例的读数把这一次的第一次轮询判成"稳定"。
 */
internal class ImeSettler(private val activity: Activity) {
    private var lastPx = -1
    private var stablePolls = 0

    fun settled(): Boolean {
        val bottom = imeBottomPx(activity)
        stablePolls = if (bottom > 0 && bottom == lastPx) stablePolls + 1 else 0
        lastPx = bottom
        return stablePolls >= 1
    }
}

/**
 * 把输入法叫到**弹窗窗口**上。
 *
 * 为什么需要这个：旋转会把键盘收起来，而输入框仍持有焦点；此时再点输入框也不会重新弹出键盘
 * （Compose 里 `requestFocus` 对已聚焦节点是空操作），用例就会在"键盘没弹起来"的空转状态里
 * 平凡通过。弹窗有自己的窗口与输入连接，`activity.window.currentFocus` 是活动窗口的视图，
 * 对它 `showSoftInput` 不会作用到弹窗，所以用 Espresso 按"当前可聚焦的根"取到弹窗里的
 * `AndroidComposeView`（Compose 的输入连接挂在它上面）再请求。
 */
internal fun showDialogKeyboard() {
    Espresso.onView(composeRootView())
        .inRoot(RootMatchers.isFocusable())
        .perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = ViewMatchers.isAssignableFrom(View::class.java)
            override fun getDescription(): String = "让输入法在弹窗窗口上弹出"
            override fun perform(uiController: UiController, view: View) {
                view.context.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        })
}

/**
 * Compose 的根视图（`AndroidComposeView`）是 internal，引用不到类型，只能按类名匹配；
 * 输入连接挂在它上面，所以要在它上面请求键盘。
 */
private fun composeRootView(): Matcher<View> = object : TypeSafeMatcher<View>() {
    override fun matchesSafely(item: View): Boolean =
        item.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView"

    override fun describeTo(description: Description) {
        description.appendText("Compose 根视图")
    }
}

/**
 * 页脚按钮的**布局矩形**必须整体落在卡片矩形内（容差 1dp）= 卡片没有把它挤扁或裁掉。
 * 取带点击动作的那个节点，因为用户点的是按钮本身，而按钮比它里面的文字高。
 */
internal fun SemanticsNodeInteractionsProvider.footerInsideCard(cardTag: String, text: String): Boolean =
    runCatching {
        val card = onNodeWithTag(cardTag).getBoundsInRoot()
        val action = onNode(hasText(text) and hasClickAction()).getUnclippedBoundsInRoot()
        val tolerance = 1.dp
        action.top >= card.top - tolerance && action.bottom <= card.bottom + tolerance &&
            action.left >= card.left - tolerance && action.right <= card.right + tolerance
    }.getOrDefault(false)

/** 卡片的矩形也必须整体落在容器（底部内边距 = 键盘顶边）之内。 */
internal fun SemanticsNodeInteractionsProvider.cardInsideScrim(cardTag: String, scrimTag: String): Boolean =
    runCatching {
        val card = onNodeWithTag(cardTag).getBoundsInRoot()
        val scrim = onNodeWithTag(scrimTag).getBoundsInRoot()
        val tolerance = 1.dp
        card.top >= scrim.top - tolerance && card.bottom <= scrim.bottom + tolerance
    }.getOrDefault(false)
