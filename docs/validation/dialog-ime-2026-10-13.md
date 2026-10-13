# 弹窗页脚被键盘挡住（2026-10-13）

`docs/TODO.md` 里「弹窗页脚在横屏 + 键盘下被键盘挡住」一项的修复与验证记录。
问题由 `validation/real-device-2026-10-12.md` 第三节第 3 条提出，本轮给出实现。

## 一、问题

真机 SHARK PRS-A0 / API 30（393×873dp）**横屏 + 输入法弹起**时，重命名弹窗（`LibraryOrganizationDialog`）
的「保存修改」落在键盘背后，用户必须先收起键盘才能点到。当时的处置只是把用例改成"先收键盘再点"，
产物没修。

## 二、修复前的实测几何（真机）

用一条临时诊断用例量出来的（横屏 + 键盘，px，density 2.75）：

| 量 | 值 |
| :--- | :--- |
| 屏幕 / 状态栏 / 键盘高度 | 1080px / 80px（29.1dp）/ 645px（234.5dp） |
| 弹窗窗口（Compose 根） | 2320×956px，**位置 y = −300px** |
| 标题「重命名与分类」 | 屏幕坐标 y −222..−147px（**整个被推出屏幕顶部**） |
| 「保存修改」 | 屏幕坐标 y 533..581px（键盘顶边在 435px，**在键盘背后**） |
| 「文件名称」输入框 | y −39..1px（贴着屏幕顶边） |

截图见 [before-landscape-ime.png](dialog-ime-2026-10-13/before-landscape-ime.png)：卡片只剩中间一段露在
屏幕与键盘之间，标题看不见、页脚看不见。

## 三、根因（三层，缺一条都修不掉）

1. **系统对浮动弹窗的键盘处理是"平移"而不是"缩放"。** `DialogProperties` 默认
   `decorFitsSystemWindows = true`，弹窗窗口是浮动窗口，软键盘弹起时系统按 ADJUST_PAN 把**整窗向上平移**
   300px 让焦点输入框露出来——代价是标题被推出顶部、固定在底部的动作行正好落到键盘背后。这也是
   `SOFT_INPUT_ADJUST_RESIZE` 单独设置无效的原因（历史上已试过并回退）。
2. **高度上限从来没有生效过。** 原实现用 `LocalWindowInfo.current.containerSize.height` 算
   `heightIn(max = 容器高度 × 0.86)`，但弹窗窗口里 `containerSize` 给的是窗口的**最大边**：横屏下量到
   `2400px`（屏幕长边），于是上限成了 2064px，等于没有上限。
3. **可用高度比固定区域还矮。** 横屏 + 键盘后可用高度只剩约 **129dp**，而"头部（约 78dp）+ 分隔线 +
   动作行（约 64dp）"至少 144dp。无论怎么封顶，Column 都只能把固定的页脚挤扁或裁掉——实测按钮从 40dp
   被压到 **24dp**，文字被横着切掉一半。

## 四、改动

`presentation/components/AppDialog.kt`：

- **新增 `AppDialogContainer`**：`DialogProperties(decorFitsSystemWindows = false)`（官方推荐与
  `usePlatformDefaultWidth = false` 配对使用），容器 `fillMaxSize().systemBarsPadding().imePadding()`，
  卡片在**扣掉系统栏与键盘之后**的区域里居中。可用高度改从 **`BoxWithConstraints` 的布局约束**取，
  不再用 `containerSize`。
- **外部点击关闭自己实现**：容器铺满整屏后，系统判断"点是否在窗口内容之外"恒为"在内部"，
  `dismissOnClickOutside` 等于失效。这里用一层同样铺满的遮罩补回同样的行为，卡片再用
  `consumeTaps()` 吃掉落在自己身上的点击（否则点卡片空白处会穿到遮罩上把弹窗关掉）。
- **高度上限** `appDialogMaxHeightPx(可用高度, 0.86)`：可用高度为 0 时必须归零——`heightIn` 收到负数会
  直接抛 `IllegalArgumentException`（崩溃，而不是"弹窗变小"）。
- **`appDialogScrollsWholeCard`**：可用高度 < `APP_DIALOG_MIN_FIXED_HEIGHT`（200dp）时，头部与动作行
  **一起进滚动区**，滚一下就能点到按钮，而不是让固定页脚被裁掉。200dp 不参与尺寸计算，只在两种排布之间
  取舍，估大了只是多一层滚动。
- `presentation/home/BatchImportDialog.kt` 接入同一容器（它此前也是"窗口 94% + 固定页脚"，同一类问题）。

## 五、验证

### JVM 单测

`./gradlew :app:testDebugUnitTest` → **417 用例通过**（66 个类）。新增
`AppDialogLayoutTest`（6 例）守住三条不变量：可用高度按系统栏与键盘扣减且**负数归零**、比例夹取到
0..1、整卡滚动的阈值是严格小于。XML 见 [jvm/](dialog-ime-2026-10-13/jvm/)。

### 真机 SHARK PRS-A0 / API 30

| 用例类 | 结果 | 证据 |
| :--- | :--- | :--- |
| `P2LibraryOrganizationDeviceTest` | **4/4 通过** | [P2LibraryOrganizationDeviceTest.xml](dialog-ime-2026-10-13/device-api30/P2LibraryOrganizationDeviceTest.xml) |
| `P2BatchImportDeviceTest` | **4/4 通过** | [P2BatchImportDeviceTest.xml](dialog-ime-2026-10-13/device-api30/P2BatchImportDeviceTest.xml) |

**修复前**（只把产物回退到 HEAD、用例保持新断言）同一条用例是红的：
[P2LibraryOrganizationDeviceTest.before.xml](dialog-ime-2026-10-13/device-api30/P2LibraryOrganizationDeviceTest.before.xml)
（`ComposeTimeoutException: Condition still not satisfied after 8000 ms`，即页脚始终没能完整落进卡片）。

新断言按"页脚完整露在键盘之上"拆成两条独立判据（`DialogFooterAssertions.kt`）：

- `footerInsideCard`：按钮的**布局矩形**必须整体落在卡片矩形内。这条抓"被 Column 挤扁/裁掉"。
  不能用 `assertIsDisplayed()`——它只要求**可见部分**不为空，真机上按钮只剩一条边时它照样通过；
  也不能比"可见高度 vs 布局高度"——卡片的裁切来自 Surface 的 `clip(shape)`（graphicsLayer），
  语义里的 `boundsInRoot` 只反映布局裁切。
- `cardInsideScrim`：卡片必须落在容器被系统栏与键盘内收缩后的区域内。容器的底部内边距就是键盘顶边，
  两条合起来即"页脚不会落在键盘背后"。
- 前置条件 `imeVisible(activity)`：键盘没弹起来时可用高度还够，上面两条会**平凡通过**，所以键盘必须
  真的弹着。修复过程中发现两点并写进了用例：旋转会把键盘收起来而输入框仍持有焦点，**再点它不会重新
  弹出键盘**（Compose 里 `requestFocus` 对已聚焦节点是空操作），要按"当前可聚焦的根"用 Espresso 对
  弹窗里的 Compose 根视图显式 `showSoftInput`。
- 键盘还要**等它稳定下来**（`ImeSettler`：连续两次读到同一个非零高度才算数）。Insets 一出现
  `imeVisible` 就为真，可那时键盘只弹了一半、可用高度还大，判据会短暂成立；等键盘弹完布局再变一次，
  紧接着的断言就翻车。**这条是 2026-10-13 在模拟器 API 37 横屏上真实复现到的**：全量套件里该用例报
  「保存修改必须完整露在键盘之上」，加上稳定门槛后连续 4 次运行都是 4/4。真机证据 XML 采集于加门槛
  之前（用例更严之后未再跑真机，见文末边界）。

新增 `tappingOutsideTheCardDismissesButTappingTheCardBodyDoesNot` 钉住自己实现的外部点击关闭：
点卡片空白处不关、点遮罩左上角关。

### 模拟器 API 37（Pixel_10_Pro）全量

`./gradlew :app:connectedDebugAndroidTest` → **196 条，35 条非通过**，其中 **23 条是
`AssumptionViolatedException`**（`live_*`、公开站点、需要人工解锁等按约定默认跳过），12 条真实失败：

| 失败 | 判定 |
| :--- | :--- |
| `AutoGalleryDeviceTest`、`P2CompletionNotificationDeviceTest`、`TaskNotificationDeviceTest`（通知权限） | 环境：模拟器未授予应用通知权限（AGENTS.md 已注明） |
| `BackgroundDownloadDeviceTest#unexpectedServiceStop...`（15s 超时） | 环境：同上，前台服务/通知 |
| `P2PhysicalBackgroundAudioDeviceTest` | 环境：需要 `manual_unlock` 人工解锁选项 |
| `P2ReleaseUiBridgeDeviceTest` ×3 | 环境：该用例驱动**release** UI，模拟器上没装 release 包 |
| `P2ExportFailureDeviceTest#realProxyFdEnospc...`（系统文档选择器里 `tapSystem` 断言失败） | 偶发：**单独重跑该类 2/2 通过**；同一轮里 `P2ExportPickerDeviceTest` 在真机也报过 `System picker control not found: [Documents]`，是系统选择器导航的老问题 |
| `DynamicColorDeviceTest`、`EmptyStateIllustrationDeviceTest#theSettledShapeIsTheRoundedSquare`、`WeiboGifDeviceTest#selectingAnotherGif...` | **与本轮无关**：把两个产物文件 `git stash` 回 HEAD 后原样同样失败（settled 面积 65245 vs 65092 vs 64961，每次都是"停在圆上"），且前两条根本不经过 `AppDialog`（一条只渲染 `LibraryScreen` 空状态插图，一条只量主题调色板） |

**两条弹窗相关类在模拟器上同样通过**（`P2LibraryOrganizationDeviceTest` 4/4、
`P2BatchImportDeviceTest` 4/4），而且**没有出现历史记录里的 `ComposeNotIdleException`**：
2026-10-12 的结论是"在弹窗窗口里读 `WindowInsets.ime` 会让 `P2BatchImportDeviceTest` 4 条全挂"，
本轮用的是 `decorFitsSystemWindows = false` + `imePadding()`（同一个 inset 状态），
真机与模拟器上该用例都是 4/4，未复现。

## 六、边界（未覆盖，如实记录）

- **平板 / 折叠屏未验证**：本轮只在手机（真机 393×873dp 横竖屏、模拟器 427×952dp）上验证。
- **真机全量套件未完成**：真机 10 分钟自动锁屏，全量跑（17 分钟）后半程大量用例报
  `No compose hierarchies found in the app`（活动起不来）。本轮真机只跑了上面两个弹窗相关类
  （跑之前把 `screen_off_timeout` 临时调到 30 分钟，跑完已恢复为 600000）；模拟器侧才是全量结果。
  真机那份 XML 采集于 `ImeSettler` 稳定门槛加入之前；此后未再跑真机（设备会自行锁屏，需要人工解锁），
  门槛本身是在模拟器上复现并通过的。
- **键盘弹起时页脚在滚动区里，需要滚一下才能点到**：这是本轮选择的取舍（可用高度只有 129dp，
  头部 + 动作行放不下）。是否可接受需要人工确认观感，见 `after-landscape-ime.png`。
- **动效未重新取证**：容器改了内边距与居中方式，弹窗入场动画（透明度 + 缩放）与手感未重新录屏；
  断言只保证"会动、会停"。
- **`LocalWindowInfo.containerSize` 在弹窗窗口里不可信**这一点只在 `AppDialog` 里规避了；
  `HomeScreen` 仍在用它（那里是活动窗口，未发现问题），没有一并改。
- **模拟器状态会影响结果**：模拟器锁屏/未解锁时整批用例会以 `No compose hierarchies found in the app`
  失败；本轮结论都是在解锁并 `screen_off_timeout=30min` 的条件下取得的。

## 七、证据清单

```
docs/validation/dialog-ime-2026-10-13/
├── before-landscape-ime.png        修复前：横屏 + 键盘，标题被推出屏幕、页脚在键盘背后
├── after-landscape-ime.png         修复后：横屏 + 键盘，页脚完整露在键盘之上（头部按设计滚出视野）
├── device-api30/
│   ├── P2LibraryOrganizationDeviceTest.before.xml   产物回退到 HEAD 的红灯
│   ├── P2LibraryOrganizationDeviceTest.xml          4/4
│   └── P2BatchImportDeviceTest.xml                  4/4
├── emulator-api37/
│   ├── connectedAndroidTest.full.xml               196 条全量结果（35 条非通过，见上表）
│   ├── P2LibraryOrganizationDeviceTest.xml         4/4
│   └── P2BatchImportDeviceTest.xml                 4/4
└── jvm/
    ├── summary.xml                                 417 通过 / 0 失败
    └── AppDialogLayoutTest.xml                     新增 6 例
```

复现命令：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest            # 真机默认 arm64；模拟器需 x86_64/universal
```

真机证据截图由用例自身落盘到应用缓存目录（`cache/dialog-evidence/`），跑完用
`adb exec-out run-as com.videodown.douyin.debug cat cache/dialog-evidence/…png` 取回。
