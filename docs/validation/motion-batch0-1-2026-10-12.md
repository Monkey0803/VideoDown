# 动效批次 0–1：基础设施与平台自带动画（2026-10-12）

选型依据与后续批次见计划《拾影 · 动画选型与落地计划》。本轮落地**批次 0（基础设施）**与**批次 1 中的四项**
（弹窗入场、页面转场、列表增删移动、进度条补间）。

## 一、执行计划时被证伪的两条假设（重要）

| 计划里的假设 | 实际情况 | 处置 |
| :--- | :--- | :--- |
| 「Compose BOM 升到 2026.09.00（1.12.1 / material3 仍 1.4.0），风险很低」 | 升上去后 `checkDebugAarMetadata` 失败：**11 个依赖要求 compileSdk ≥ 37**，而本工程按契约钉死 `compileSdk = 36` + `buildToolsVersion = 36.1.0` + `android.builder.sdkDownload=false`，本机也只装了 `android-36` | **放弃升级**，`composeBom` 保持 `2026.06.01`。所需 API（`animateItem`、`SharedTransitionScope`、`AnimatedContent`）在 1.11.4 中已存在（已核实），升级并非前置条件 |
| 「时长/缓动直接取 `MaterialTheme.motionScheme`」 | material3 1.4.0 里 `MotionScheme` 与 `MaterialTheme.motionScheme` 是 **`internal`**：`Cannot access 'interface MotionScheme': it is internal in file` | 改为在 `Motion.kt` 自定 token，数值沿用 Material 3 运动 token；等 `MotionScheme` 转公开后可原位替换 |

> 教训记录：`javap` 只能看到 JVM 成员，看不到 Kotlin 的 `internal`。第一版 `Motion.kt` 的结论来自 javap，被一次真实编译推翻。

## 二、批次 0：动效基础设施（已落地）

新增 `presentation/theme/Motion.kt`：

- `AppMotion(enabled)`：`fast()/normal()/slow()` 三个 tween（150 / 250 / 400 ms，配 Material 3 标准、强调减速、
  强调加速三条曲线），以及 `enter(expand)/exit(shrink)` 两套 `EnterTransition`/`ExitTransition`。
- `appMotion()`：进组合时读一次系统动画缩放并 `remember`。
- **总开关**：`motionEnabledFromScale(scale)`——系统动画缩放为 0 时所有 spec 退化为 `snap()`、
  入场退化为 `EnterTransition.None`。这条同时服务设备测试（Espresso 与 Compose 的 `waitForIdle` 都要求关闭动画，
  且无限动画在该档必须静止，否则 Compose 测试永不 idle）与无障碍里「关闭动画」的用户。

新增 `app/src/test/.../theme/MotionTest.kt`：3 项，覆盖 0/负值关闭、正值与小数开启、`NaN`（读设置失败）不误关。

## 三、批次 1：平台自带动画（已落地四项）

| 位置 | 动效 | 实现 |
| :--- | :--- | :--- |
| 23 个卡片式弹窗 | 入场：透明度 0.35→1、缩放 0.94→1 | `AppDialog.kt` 单点改动（`animateFloatAsState` + `graphicsLayer`）。**只做入场**：出场时弹窗窗口已关闭，再挂动画只会让关闭显得迟滞；只动透明度与缩放，不动位移，避免坐标类断言读到中间值 |
| 4 个 tab + 播放器路由 | 页面切换淡入淡出（150 ms） | `MainActivity.kt` 的 `NavHost(enter/exit/popEnter/popExitTransition)` |
| 任务列表 | 项的增删（淡入淡出）与位置变化（normal 曲线） | `HistoryScreen` 的 `Modifier.animateItem(...)`（外层包一层 `Column`） |
| 已存网格与列表 | 同上 | `LibraryScreen` 两处 `Modifier.animateItem(...)` |
| 任务卡进度条、存储预算条 | 比例补间（150 ms） | `animateFloatAsState`；**百分比文字仍用原始值**，读数与断言不受影响 |

### 追加：解析页状态切换（同批完成）

计划里这一项写的是 `AnimatedContent`，落地时**改用等价但更安全的做法**：保持原来的
`if (state.video == null) ... else ResultScreen(...)` 互斥结构不变，只给新出现的一页加 `Modifier.alpha`
淡入（新增 `rememberEntranceAlpha()`，返回 0→1 的系数）。

理由：`AnimatedContent` 会把新旧两页**同时**留在语义树里，`onNodeWithText(...)` 可能匹配到旧页节点；
本仓库有 214 处 `waitForIdle/waitUntil`、大量 `onNodeWithText` 断言，这个风险不值得换一次交叉淡入。
新做法没有重复节点、不改嵌套（8 行改动，无缩进噪音），视觉上同样是「新页面淡入」。

- 输入页与结果页各自淡入；从结果页返回解析页会重放一次。
- 验证：`HomeKeyboardDeviceTest` 5、`HomeResultBackDeviceTest` 3、`QualityCardsDeviceTest` 1、
  `ResultSelectionDeviceTest` 4、`ClipboardDetectDeviceTest` 4、`P2BatchImportDeviceTest` 4、
  `P1FeaturesDeviceTest` 6、`BatchFeaturesDeviceTest` 7、`GenericVideoDeviceTest` 6、
  `XVideoDeviceTest` 1 —— 10 个类 41 条真实用例通过；另有 5 条是 `live_*` / 公共样本的 opt-in
  假设失败（默认跳过是既有的成本控制，不是回归）。

## 四、验证

```bash
./gradlew :app:testDebugUnitTest              # 397 项通过（含 MotionTest 3 项）
./gradlew :app:assembleDebug                  # 成功
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=<类名>
```

设备（Pixel_10_Pro AVD / API 37 / emulator-5554），本轮共跑 17 个类、61 条用例，**全部通过**：

- 弹窗：`SpaceBudgetDeviceTest` 3、`AppUpdateEntryDeviceTest` 6、`LibraryActionsDeviceTest` 4、
  `BatchFeaturesDeviceTest` 7、`AppearanceModeDeviceTest` 1。
- 转场：`Material3NavigationDeviceTest` 3、`LocalizationDeviceTest` 2、`DynamicColorDeviceTest` 1、
  `HomeKeyboardDeviceTest` 5、`IconIntegrationDeviceTest` 4、`LauncherShortcutsDeviceTest` 4、`P2AudioUiDeviceTest` 4。
- 列表：`P2LibraryOrganizationDeviceTest` 3、`P2BatchImportDeviceTest` 4、`TaskSearchDeviceTest` 1、`VaultDeviceTest` 3。
- 进度条：`P1FeaturesDeviceTest` 6。

两处首轮失败经复跑确认是**设备状态**而非代码：`DynamicColorDeviceTest` 需要浅色模式（`cmd uimode night no` 后
1/1 通过）；`IconIntegrationDeviceTest#completedNotificationUsesTheNewSingleColorIcon` 需要通知权限
（`pm grant … POST_NOTIFICATIONS` 后 4/4 通过）——`connectedDebugAndroidTest` 每次重装会清掉运行时权限，
所以这两条要在装包后再授权/切模式。

动效本身是时序，静态截图无法体现，附两段真机录制：

- [motion-demo.mp4](motion-batch0-1-2026-10-12/motion-demo.mp4)（9 秒）：切 tab → 打开存储弹窗 → 返回 → 回解析页。
- [parse-page-fade.mp4](motion-batch0-1-2026-10-12/parse-page-fade.mp4)（8 秒）：冷启动输入页淡入 →
  切到任务 → 切回解析（解析页进场重放）。

## 五、本轮未做（后续批次）

- **解析页以外的逐段动画**：结果页内部的画质卡/多选工具条出现仍为瞬时（只在整页层面淡入过）。
- **状态色补间（`animateColorAsState`）**：收益小、改动面散，未做。
- **批次 2**：已存网格 → 播放器封面 `sharedBounds` 共享元素（需连跑 PiP / 横屏两组用例验证降级）。
- **批次 3**：`sh.calvin.reorderable:3.1.0` 拖拽排序（真实成本在排序持久化，不在手势）。
- **批次 4**：`graphics-shapes` 品牌 morph、Coil 3.6.3 迁移、骨架屏 shimmer（后者必须走本文档第二节的总开关）。

## 六、验证边界

- 未在真机（SHARK PRS-A0 / API 30）复跑本轮改动；未跑 `live_*` 联网组（默认跳过）。
- `AppDialog` 只验证了入场；出场仍为瞬时关闭（有意为之，非缺陷）。
- 本轮的「无限动画禁令」只是约定 + 总开关，**尚未有代码强制**：若后续加了无限动画而忘记接总开关，
  设备测试会挂死而不是报错。批次 4 做 shimmer 时应同时补一条设备用例，覆盖「动画缩放置 0 时不挂死」。
- 弹窗入场动画的截图证据缺失：入场只有 250 ms，`screencap` 的抓取时机不可靠，以录像为准。
