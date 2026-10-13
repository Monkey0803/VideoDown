# 批次 4：骨架屏 shimmer、Coil 3 迁移、空状态插图（2026-10-12）

选型与批次划分见计划《拾影 · 动画选型与落地计划》。计划里这批有三项：`graphics-shapes` 品牌 morph、
Coil 3.6.3 迁移、骨架屏 shimmer。**三项均已落地**，其中两项与计划的差异有证据：
Coil 只能上到 3.3.0（工具链钉住，第二节），morph 落在「已存空状态插图」（位置由你选定，第四节）。

## 一、骨架屏 shimmer（含「无限动画禁令」的代码强制）

计划第六节留了一条验收边界：无限动画的禁令此前只是约定 + 总开关，**没有代码强制**，要求做 shimmer 时
顺手补一条「动画缩放置 0 时不挂死」的设备用例。这一条已补。

实现：`presentation/components/Shimmer.kt`（新增）

- `Modifier.shimmer(enabled = appMotion().enabled)`：斜向亮带扫过；总开关关闭时**直接返回原 Modifier**，
  连绘制都不装。
- 用 `rememberInfiniteTransition` 而**不是** `while (true) { delay(…) }`：后者会让 `waitForIdle` 永远等下去，
  而本工程有 200 多处 `waitForIdle` / `waitUntil`。
- 首帧就不是空白：`progress = 0` 时亮带已经压在画面左缘（顺带让「开/关总开关」在同一时刻有可观察差异）。
- 接入点：已存封面（`LibraryCover`）与播放器海报——只在**内容还没到位**时扫光（`!coverUrl.isNullOrBlank() && !loaded`），
  加载完成即停，不留常驻的无限动画。

### 两条测试环境的硬事实（都是实测踩出来的）

1. **无限动画在 Compose 测试里永不推进**：测试环境会安装 `InfiniteAnimationPolicy`，
   `Thread.sleep`、`mainClock.advanceTimeBy`、`advanceTimeByFrame` **三种方式都推不动它**
   （三种都试过，像素全等）。所以「扫光在动」在设备上无法用像素断言——能证明的是「装不装这个绘制」，
   动感本身只能靠录制 + 人工目视。这也是为什么总开关那块抽了个纯函数 `shimmerActive()` 去 JVM 测。
2. **整屏截图会骗人**：第一版拿整屏比对时，`enabled = false` 的两张截图竟然不同——因为系统状态栏的
   时钟/信号图标自己在变。把裁剪范围收到被测区域（240dp 方块）后才是真信号。
   反过来说：那次「动画版通过」也是状态栏造成的**假阳性**，收紧裁剪后才暴露了事实 1。

## 二、Coil 迁移：2.7.0 → 3.3.0（不是 3.6.3，有证据链）

计划写的是 3.6.3，实际做不上去，逐版本查了传递依赖：

| Coil | 依赖 | 结果 |
| :--- | :--- | :--- |
| 3.6.3 | JetBrains Compose 1.12.0 → androidx compose 1.12.0 | ❌ 需要 `compileSdk ≥ 37`，本机只有 android-36 |
| 3.5.0 | `kotlin-stdlib 2.4.0` | ❌ 元数据 2.4.0 超出工程编译器（Kotlin 2.2）可读的 2.3.0 |
| 3.4.0 | `kotlin-stdlib 2.3.10` + JB Compose 1.9.3 | 未验证（stdlib 已高于编译器可读范围） |
| **3.3.0** | `kotlin-stdlib 2.2.0` + JB Compose 1.8.2 | ✅ 采用 |

器件与代码改动：

- `coil-compose` / `coil-network-okhttp` 换到 `io.coil-kt.coil3`（3.3.0）。
- `coil.compose.AsyncImage` → `coil3.compose.AsyncImage`（4 个文件）。
- `onSuccess` 的 API 变化：`result.result.drawable`（Coil 2）→ `result.result.image`
  （Coil 3 里 `State.Success.result` 是 `SuccessResult`，图片本体在它的 `image` 上，`width`/`height` 取尺寸）。
- **`coil-network-okhttp` 必须显式加**：Coil 3 把网络取图拆成独立产物，不装它**不报错**，
  只是所有远程封面静默不显示——这正是本工程用例照不到的坑（见下）。

## 三、验证

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug          # 409 项 JVM 通过
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=<类名>
```

新增 JVM 3 项：`ShimmerGateTest`、`EmptyStatePolygonTest`（2 项）（总开关纯逻辑；放在 JVM 是因为无限动画在设备测试里不可观察）。

新增设备 7 项：

- `ShimmerDeviceTest` 3 项：**开着扫光时 `waitForIdle` 仍能返回**（这就是计划点名的那条强制）、
  总开关开/关在同一时刻渲染不同、关闭时占位是静止纯底色。
- `CoilImageLoadingDeviceTest` 1 项：起一个只服务一次的**本地 HTTP**，让 Coil 真的走一次网络取图并断言
  `onSuccess` 触发——网络取图器缺失时的静默失败在这里会红。
- `EmptyStateIllustrationDeviceTest` 3 项：空库时插画出现且动画会停、形状确实变化（面积度量）、
  总开关关闭时静止后与动画落在同一形状。

Coil 迁移的**像素级回归**：`PlayerCoverTransitionDeviceTest` 重跑后，取帧用例的封面色块统计与迁移前完全一致
（单份拷贝、428k 像素、位置 `(268,348)-(1008,1064)` 正好是视频框），说明本地文件封面在 Coil 3 下照常渲染。
另外 `PlayerCoverPosterDeviceTest` 2、`LibraryReorderDeviceTest` 3、`LibraryActionsDeviceTest` 4、
`BatchFeaturesDeviceTest` 7、`P2AudioUiDeviceTest` 4 全绿。

## 四、`graphics-shapes` 空状态插图（已按你选的位置落地）

你选了「已存空状态插图」，于是按这个位置做完：入场时形状从**圆**morph 到**圆角方形**（与应用图标同族的
squircle）。放这里是因为它只在整个已存为空时出现——被搜索/过滤筛空是另一回事，那时**不插画**（配图会让人困惑）。

实现：`presentation/components/EmptyStateIllustration.kt`（新增），接入 `LibraryScreen` 的空状态。

- 依赖 `androidx.graphics:graphics-shapes:1.1.0`（探针确认与 `compileSdk 36` 无冲突）。
- 手写绘制：`Morph(start, end).toPath(progress)` → `Matrix` 缩放到控件尺寸 → `drawPath`。
  material3 1.4 不提供任何形状 API，所以没有现成的可借用。
- **有限动画**：`animateFloatAsState(0→1, motion.slow())`，走总开关——关闭时 spec 退化为 `snap()`，
  首帧即终态，不产生任何中间形状。组件暴露 `enabled` 参数只为设备用例能在不改系统设置的前提下验两条路径。

![已存空状态插图](motion-batch4-2026-10-12/empty-library.png)

### 三个实测踩到的坑

1. **`Morph` 要求两侧曲线段数可一一对应，否则构造直接抛异常**——那会让空状态**崩**，而不是"动画不好看"。
   第一版取「4 顶点圆角拉满 ↔ 4 顶点圆角 0.32」当场就抛。实测出规律后按它配对：
   n 个顶点时圆角拉满得 `n+1` 段、圆角中等得 `2n+1` 段、直角得 `n` 段；
   最终用 **8 顶点满圆角（圆，9 段）↔ 4 顶点 0.5 圆角（圆角方形，9 段）**——段数对齐且形状差异明显。
   这条不变量由 JVM 用例 `EmptyStatePolygonTest` 钉住。
2. **暂停测试时钟必须在 `setContent` 之前**：否则 `waitForIdle` 会把入场动画直接跑完，
   「起点」拍到的是终态，整条用例退化成自欺欺人的恒等比较（第一版就是这样，两边像素完全相同）。
3. **「取中间帧」这类时序断言在本环境不可靠**：暂停时钟后 `uiAutomation.takeScreenshot` 的呈现时机与
   Compose 的动画进度对不上（实测推进 200ms 时画面已经是终态）。所以形状断言改成**不依赖时刻的度量**：
   亮色像素面积——圆 → 圆角方形是四角被填满、面积必然变大（`theShapeMorphsAndThenStops`），
   而总开关关闭时应当**静止后与动画落在同一形状**（`theGateLandsOnTheSameFinalShape`，容差 2%：
   两块插画相隔 16dp，裁剪窗口的亚像素对齐会让抗锯齿边缘有几百像素出入）。

## 五、未覆盖的边界

1. **Coil 3.4+ 在本环境不可用**：不是取舍而是被钉住的工具链挡住（`compileSdk 36` + Kotlin 2.2）。
   要往上走必须先动工程版本（compileSdk 37 / Kotlin 2.4），那是项目级决定。
2. shimmer 与 morph 的**动感本身未经目视确认**：测试只能证明「装/不装绘制」「形状确实变了」「会停」，
   扫速、缓动与形状中途是否好看仍需看一次录屏或真机。
3. 未在真机（SHARK PRS-A0 / API 30）复跑；未跑 `live_*` 联网组。
4. 已存封面在**解码失败**时不会再关闭扫光（`onSuccess` 没触发就一直扫）——目前没有失败态的视觉降级，属已知小缺口。
5. 空状态插图的形状参数（8 顶点圆 / 4 顶点 0.5 圆角）是几何上可配对的**保守选择**，
   不是设计稿定稿；要更强烈的形变需要设计给形状。
