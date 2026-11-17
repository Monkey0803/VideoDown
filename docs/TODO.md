# 拾影 功能待办（TODO）

> 更新日期：2026-11-17 ｜ 当前分支：`main` ｜ 版本：1.2.0 / code 7（英文名 Keepsake，更新源走 GitHub Releases）
> 依据：同类应用对标（1DM+、ADM、Snaptube、VidMate、Seal、稞麦视频下载器）与当前源码核查
> 关联：[功能排查与后续顺序](function-backlog-2026-10-09.md)（历史记录，不再更新）、[P4 验收记录](validation/p4-features-2026-10-09.md)、[P5 验收记录](validation/p5-features-2026-10-09.md)

## 使用说明

- 勾选语义：`[ ]` 未开始 ｜ `[~]` 进行中（在行尾注明） ｜ `[x]` 已完成。
- 每项完成标准：**JVM 单测 + 设备用例 + `docs/validation/` 证据**，三者缺一不得勾选。
- 未覆盖的验证边界必须如实写在验证记录里，不得用相邻结果替代。
- 本清单只记录**未实现**的功能；已实现的见文末「已具备」，不要重复立项。

## 前置

- [x] 提交分支 `1.0.2` 工作区既有未提交改动（README、`AppPreferences`、`ServiceLocator`、`DownloadEngine`、`StorageReservations`、`DownloadHistoryStore`、`DownloadStorageRepository`、`DownloadFailure`、`DownloadRecord`、`StorageUsage`、`Downloader`、`SettingsScreen`、`StorageDialog` 及对应测试），再开始 Phase A。已在 Phase A 开始前提交，工作区当时为干净状态。
- [x] 版本号 `versionCode 2 → 3`、`versionName 1.0.1 → 1.0.2`（`app/build.gradle.kts`）。

## Phase A — 表配补齐（已完成）

证据与未覆盖边界见 [P4 验收记录](validation/p4-features-2026-10-09.md)。JVM 262 用例通过；本轮新增设备用例在 Android 17 / API 37 模拟器与 Android 11 / API 30 真机均通过。

### A1 剪贴板链接识别

- [x] `data/remote/ClipboardLinkPolicy.kt`：纯 Kotlin，仅已知平台命中，`GENERIC` 不命中，已忽略的链接按 URL 去重。
- [x] `core/AppPreferences.kt` 新增 `clipboardDetect`（默认 `true`）与 `lastClipboardOffer`。
- [x] `presentation/home/HomeViewModel.kt` 新增 `clipboardOffer` 状态与 `offerClipboardText` / `acceptClipboardOffer` / `dismissClipboardOffer`。
- [x] `presentation/home/HomeScreen.kt` 在窗口获得焦点后才读剪贴板，顺序为 `hasPrimaryClip()` → 描述 MIME → `primaryClip`；提示为横幅而非自动解析。
- [x] `presentation/settings/SettingsScreen.kt` 新增「识别剪贴板链接」开关。
- [x] 测试：`ClipboardLinkPolicyTest`（JVM 6 例）、`ClipboardDetectDeviceTest`（设备 4 例，含关闭开关不读取）。

### A2 通知内单任务暂停 / 继续

- [x] `DownloadService` 新增 `ACTION_PAUSE_ONE` / `ACTION_RESUME_ONE` + `EXTRA_RECORD_ID`。
- [x] `AppPreferences.individualTaskNotifications`（默认 `false`）；每任务一条独立通知，汇总通知保留。
- [x] 通知内不提供删除（不可逆操作需二次确认）。
- [x] 测试：`TaskNotificationDeviceTest`（真实发送通知卡片自身的 PendingIntent，断言仅目标任务暂停，暂停卡片转为「继续」，关闭开关后卡片撤销）。

### A3 Material You 动态取色

- [x] `AppPreferences.dynamicColor`（默认 `false`）。
- [x] `SettingsScreen` 在 API 31+ 显示开关，低版本隐藏。
- [x] `MainActivity` 把 `dynamicColor` 传入 `VideoDownTheme`。
- [x] 测试：`DynamicColorDeviceTest`（API 37 断言调色板确实重绘且偏好持久化；API 30 覆盖「开关不存在」分支）。

### A4 启动器快捷方式

- [x] `LauncherShortcuts.publish` 运行时发布 `quick_parse` / `batch_import` / `open_tasks`（不用静态 `res/xml`，因为 debug 变体的 applicationId 带 `.debug` 后缀）。
- [x] `MainActivity` 处理三个 action，`openTasks` / `openBatchImport` 接线到 `VideoDownApp`。
- [x] 测试：`LauncherShortcutsDeviceTest`（4 例，断言包名归属与落地页面；`QUICK_PARSE` 不显示被动横幅）。

### A5 任务页搜索 / 排序筛选 + 剩余时间

- [x] `presentation/history/TaskQuery.kt` 的 `TaskListOrder` 纯函数；`HistoryViewModel.setTaskQuery`。
- [x] `HistoryScreen` 搜索框与排序 chip，区分「无任务」与「当前筛选无结果」。
- [x] `core/Formatters.kt` 的 `formatEta`，进度行显示「约剩 …」。
- [x] 测试：`TaskListOrderTest`（JVM 8 例）、`FormattersTest` 新增 3 例、`TaskSearchDeviceTest`（设备 1 例，含排序位置断言）。

### A6 文档与版本

- [x] README 增补 P4 功能、开关与功能对照表（第 18–22 行）。
- [x] 新增 [P4 验收记录](validation/p4-features-2026-10-09.md) 及 `docs/validation/p4-features-2026-10-09/` 证据（JVM/模拟器/真机 XML、截图与 summary）。
- [x] `docs/function-backlog-2026-10-09.md` 的「已具备」补记本轮功能。

## Phase A 遗留边界（未完成）

- [x] **独立通知在后台服务退出后无法继续任务**（2026-10-13 完成，证据见 [验收记录](validation/notification-resume-after-service-exit-2026-10-13.md)）：全部任务暂停后服务按设计退出、独立通知随之清除，于是「从通知继续一个已暂停任务」只在还有别的任务传输时可用。**产品选择走 (b)**：服务退出后**保留「已暂停」任务卡**，卡变成非进行态（可划掉）、只带「继续」，点它用 `getForegroundService` 把服务重新拉起来（真实点击在后台，`startService` 会被系统拒绝）；不选 (a)（为已暂停任务保活服务，牵扯 Android 15 前台服务时限与耗电）。配套：卡片身份改成「固定通知 id + 记录 id 当 tag」，任何一方都能凭记录 id 撤卡；服务启动时清上一次的遗留卡；开关关掉时立即收回。**实测坑**：暂停的任务会从 `Downloader.progress` 里被移走，判断"要不要留卡"必须回落到记录状态。遗留边界：后台点击的 FGS 豁免无法在用例里模拟；服务退出后若在应用里删除了那个已暂停任务，卡片会留到被划掉或被点一次；平板/折叠屏未验证。
- [x] **快捷方式的启动器视觉已人工复核**（2026-11-17）：Pixel Launcher 长按菜单正确展示「粘贴并解析 / 一次导入多条 / 下载任务」，三枚橙色图标可辨且三个入口均落到预期页面。应用没有主动请求固定快捷方式，因此「用户手动固定后的桌面图标」不属于当前产品流程，未单独验收。
- [ ] **剪贴板系统提示未断言**：Android 12+ 首次读取他应用剪贴板时系统会弹一次访问提示，属平台行为，无法由应用消除，用例未覆盖。
- [ ] **非手机形态未验证**：本轮未在平板/折叠屏验证任务页新增的搜索框、排序 chip 与通知卡片的排布。

## 既有失败项（非本轮引入，供后续处理）

- [ ] **`P2CompletionNotificationDeviceTest` 在 API 37 模拟器上超时失败**，在 API 30 真机通过。已用「临时注释掉本轮独立通知收集器仍以同样方式失败」证明与本轮改动无关，疑似模拟器 SystemUI 通知栏差异。失败日志保留在 `docs/validation/p4-features-2026-10-09/emulator-api37/P2CompletionNotificationDeviceTest.emulator-failure.xml`。**本轮在真机 SHARK PRS-A0 / API 30 复核：1/1 通过**，XML 存于 `docs/validation/p6-phase-c-2026-10-09/device-api30/P2CompletionNotificationDeviceTest.xml`。
- [x] **`P1RecoveryNavigationDeviceTest#rateLimitedParseOpensSettingsWithoutRepeatingRequest` 等待的文案「打造专属空间」已在 `94a83b9` 从设置页移除**，断言未同步。本轮把断言改为当前实际渲染的页面标题「我的设置」，用例意图不变，修复后 4/4 通过。
- [ ] **`P1DownloadIntegrationTest#known response size is checked again before writing a direct file` 曾偶发一次**（`expected:<STORAGE_FULL> but was:<NETWORK>`，2026-10-10 C1 迁移期间）。**该用例属于 JVM 套件（`app/src/test`），无法在真机上运行**；本轮以 `--rerun-tasks` 连跑 5 次，12 用例 × 5 次全部通过，未修改用例。
- [ ] **`P3PictureInPictureDeviceTest#pipScreenOffThenReturnKeepsActivityAndPlayer` 在 API 37 模拟器上失败**（操作真实熄屏与 PiP）。已用两种独立实验证明与本轮无关：把 `PlayerScreen.kt` 还原到 HEAD 后同样失败；只关闭新增手势覆盖层后同样失败。**在 API 30 真机（SHARK PRS-A0）上已实测通过**：传 `manual_unlock=true` 并由人工在两轮各 120 秒窗口内解锁，四条 PiP 用例 4/4 通过（该条 26.1 秒），XML 存于 `device-api30/P3PictureInPictureDeviceTest.manual-unlock.xml`，两轮解锁后的播放器截图存于 `evidence/pip-manual-unlock-cycle-{1,2}.png`；**默认参数**的真机运行会因设备有锁屏而跳过该条（3 通过 + 1 跳过，XML 存于 `device-api30/P3PictureInPictureDeviceTest.xml`）。因此「模拟器失败、真机通过」这一差异已成立，剩下的只是模拟器差异本身未修复。
  - **2026-10-12 补充：把三项动画缩放设为 0 后该条通过（`P3PictureInPictureDeviceTest` 4/4）**，说明它是动画时序敏感，而不是模拟器能力缺失；Espresso 本来就要求关闭动画，CI 应固定这一档。见 `validation/motion-batch2-2026-10-12.md`。

- [ ] **`BatchFeaturesDeviceTest` 两条在 API 37 模拟器上失败**（2026-11-17 发现）：
  `batchDownloadKeepsIndividualQualityAndSkipsDuplicates` 点击「360P」时 `performScrollTo` 找不到节点；
  `sortingPickerAppliesSelectionAndDismissKeepsCurrentQuery` 断言「文件最大」chip 可见时失败。
  已在改动前基线 `615c104`（仅补上缺失的 `libs.versions.toml` 条目以便构建）上复现完全相同的结果，
  与本轮 I1/I3 无关；批量导出与其余 5 条用例通过。
- [ ] **`AutoGalleryDeviceTest` 在 API 37 模拟器上因「系统通知未启用」前置断言失败**（2026-11-17 发现）：
  同一环境在基线上同样失败，属于设备通知状态而非代码；需要重新允许应用通知后再跑。
- [ ] **`P3PlaybackDeviceTest#retainedViewModelSurvivesActualActivityRecreationWithCurrentPosition` 在 SHARK PRS-A0 / API 30 真机上 10s 超时**（2026-10-12 真机复跑，7 条中 1 条失败；同批在 API 37 模拟器通过）。未进一步定位，疑似真机 Activity 重建更慢或 OEM 行为差异。见 `validation/real-device-2026-10-12.md`。
- [ ] **`P3PictureInPictureDeviceTest#systemPipDismissReleasesVideo` 在 SHARK PRS-A0 / API 30 真机上失败**（`PiP state did not reach expected condition`，2026-10-12；模拟器通过，差异未定位）。同批 `pipScreenOffThenReturnKeepsActivityAndPlayer` 因真机有锁屏而按环境前提跳过（默认参数），与代码无关。见 `validation/real-device-2026-10-12.md`。

## 动效与快捷方式遗留（2026-10-12）

动效批次 0–4 与快捷方式已提交（`feat(motion)` / `feat(library)` / `feat(shortcut)`），证据见
`validation/motion-batch0-1-2026-10-12.md`、`validation/motion-batch2-2026-10-12.md`、
`validation/reorder-persistence-2026-10-12.md`、`validation/motion-batch4-2026-10-12.md`、
`validation/launcher-shortcut-icons-2026-10-12.md`、`validation/shortcut-navigation-2026-10-12.md`。
以下是这些批次留下的遗留项（其中弹窗页脚一条已于 2026-10-13 完成，保留在此便于追溯）：

- [x] **动效手感已在模拟器人工复核**（2026-11-17）：动画缩放 1.0 下走查四个主页面切换、弹窗入场和空状态，没有闪白、重叠、残影或明显拖沓；本轮不调整既有 token。真机与帧时间测量仍属于未覆盖边界。
- [x] **弹窗页脚在横屏 + 键盘下被键盘挡住**（2026-10-13 完成，证据见 [验收记录](validation/dialog-ime-2026-10-13.md)）：真机（393×873dp）横屏且输入法弹起时，重命名弹窗的「保存修改」落在键盘背后。根因是三层叠加：① 浮动弹窗对键盘只做 ADJUST_PAN（整窗上移 300px，标题被推出屏幕顶部）；② `LocalWindowInfo.containerSize` 在弹窗窗口里给的是窗口长边（横屏 2400px），原来的 0.86 高度上限从未生效；③ 扣掉键盘后可用高度只剩约 129dp，比「头部 + 动作行」的 144dp 还矮，固定页脚必然被挤扁（实测按钮 40dp → 24dp）。改法：`decorFitsSystemWindows = false` + 容器自己 `systemBarsPadding().imePadding()`、可用高度改从 `BoxWithConstraints` 取、显式外部点击关闭（容器铺满后系统判定失效）、可用高度 < 200dp 时头部与动作行一起进滚动区；`BatchImportDialog` 接入同一容器。**历史结论已失效**：2026-10-12 记录的「在弹窗窗口里读 `WindowInsets.ime` 会让 `P2BatchImportDeviceTest` 4 条全挂 `ComposeNotIdleException`」本轮未复现（真机与模拟器该用例都是 4/4），两条无效路径（`SOFT_INPUT_ADJUST_RESIZE`、按 IME 收紧高度上限）的结论仍成立。遗留边界：平板/折叠屏未验证；键盘弹起时页脚需滚一下才可点（取舍）；真机只跑了弹窗相关类（全量受 10 分钟锁屏限制）。
- [x] **三个动态快捷方式已恢复发布**（2026-11-16）：`LauncherShortcuts.publish` 重新发布「粘贴解析、批量导入、下载任务」，设备测试覆盖集合、图标可见性和三个 intent 的落地行为；2026-11-17 已补做 Pixel Launcher 长按菜单人工复核。
- [ ] **弹窗在非手机形态未验证**：弹窗页脚这次只覆盖手机（真机 393×873dp 横竖屏、模拟器 427×952dp）；平板 / 折叠屏下的高度上限与「整卡滚动」取舍未验证。
- [x] **横屏键盘页脚观感已在模拟器人工确认**（2026-11-17）：批量导入的三个页脚操作均位于键盘上方并可点击；极短高度下输入框改用框内提示，避免浮动标签被滚动区裁掉。真机输入法高度与 OEM 差异仍未覆盖。
- [x] **已存自定义顺序不随备份走**（2026-10-13 完成，证据见 [验收记录](validation/library-order-backup-2026-10-13.md)）：顺序挂在记录上（`DownloadRecord.sortIndex`），而备份只装 `SettingsSnapshot`，所以导出再恢复会丢掉手动排好的那一段。改法：`data/backup/SavedOrder.kt` 定死三条语义（只导出 `sortIndex > 0` 的那段、清洗空串/重复/超长、恢复时「备份顺序在前 + 备份之后才排过的按原相对顺序接在后面」并整段重新落位），`SettingsSnapshot` 加可空 `savedOrder`，`presentation/settings/SettingsBackupFlow.kt` 把导出与恢复各封一处（恢复补报 `SettingsChange.LIBRARY_ORDER`，否则一次只改顺序的恢复会被报成"与当前一致"）。**已知语义**：id 是本机的，换机恢复顺序保持原样（记录不随备份走），已写进 README「已知限制」。遗留边界：系统文档选择器那一步没自动化（与本类既有用例一致）；SAF 写入依赖 API 31+，设备用例只在模拟器上跑；顺序粒度是"手动排过的那一段"，从没排过的记录不进备份。
- [x] **把手隐藏条件只覆盖了「搜索」一种**（2026-10-13 完成，证据见 [验收记录](validation/library-reorder-gate-2026-10-13.md)）：判定搬进 `LibraryQuery` 成为纯逻辑——`ordersManually`（自定义排序 + 无关键词 + 无平台 + 无分类 + 非仅收藏 + 非保险箱）与 `canReorder(selecting, batchBusy)`（再多排两处），`LibraryScreen` 的把手与「切到自定义排序时 seed 一次」各自取用其一。新增 `LibraryReorderGateTest` 穷举六个条件 + 一条不变量（能拖必然 `ordersManually`，否则会出现"能拖但从未 seed 过"的列表），并用删掉 `!favoritesOnly` 的变异证明用例有牙；设备侧新增「多选 + 分类过滤」让把手消失（搜索那条原有）。遗留边界：平台/仅收藏/保险箱/批量忙只穷举在判定层，没有各演一遍端到端；任务页的把手判定是另一条，不在本轮范围。
- [ ] **共享元素未自定义曲线以外的编排**：时长已接到 `AppMotion`（250ms），但未测过更慢/更快的观感差异，也没有对「从列表页进入」以外的入口（首页、任务页）做共享元素。

## Phase B — 差异化（已完成）

证据与未覆盖边界见 [P5 验收记录](validation/p5-features-2026-10-09.md)。同批 JVM 324 用例通过；10 个 Phase B 设备用例在 Android 17 / API 37 模拟器与 Android 11 / API 30 真机均通过。

- [x] **B1 播放器手势与字幕渲染** — `PlayerGestures` 纯几何 + 自定义控制层后的画面手势（双击 ±10s、左滑亮度、右滑音量、长按临时 2×）；内嵌字幕由 `PlayerView` 渲染，同名 `.srt`/`.vtt`/`.ass` 作为外部轨道加载，「字幕」开关按轨道存在与否显示并保留。
- [x] **B1.1 沉浸式视频控制与进阶工具** — Compose 控制层统一播放、进度、倍速、循环、轨道、比例、截图、PiP 与全屏；横拖按需抽帧预览，解码失败支持回退、重试与外部打开。音频服务保持不变。证据见 [验收记录](validation/video-player-advanced-2026-11-16.md)。
- [x] **B2 全局 / 单任务限速** — `TokenBucket` + 共享/每任务 `BandwidthLimiter`，钩子在 `ResumableDownloader` 写入前且位于空间检查之后；设置页全局限速与任务卡单任务限速。yt-dlp 引擎任务使用同一上限计算规则并传入原生 `ratelimit`，多引擎进程与 FFmpeg 边界见 Phase H 验收记录。
- [x] **B3 下载完成自动存入相册（开关）** — `AutoGalleryExport` 决定是否/能否复制，完成通知在同一张卡上报告去向；拷贝移出传输协程，不再占用队列名额。
- [x] **B4 继续观看** — `PlaybackRepository.watchProgress()` 返回 `StateFlow<Map<String, WatchProgress>>`，`WatchProgress` 携带文件 size/mtime 用于剔除陈旧进度；「已存」与首页「最近保存」显示进度条与「已看 N%」。
- [x] **B5 音频睡眠定时器** — 独立键保存**绝对 deadline**（未塞进 `PlaybackOptions`，因为它是全局音频设置而非音视频各自的偏好），`AudioPlaybackService` 每 2 秒检查，到点暂停并清零。

## Phase B 遗留边界（未完成）

- [x] **yt-dlp 下载接入原生限速** —— 每个引擎进程按全局/单任务非零上限的较小值设置 `ratelimit`。仍不承诺多个引擎进程共享总桶，FFmpeg 自行拉流的阶段也不受该上限约束。
- [ ] **手势区域未在平板 / 折叠屏验证**，只在手机竖屏与既有横屏用例上验证。
- [ ] **字幕未逐像素断言真的画在画面上**，只验证轨道存在与开关行为；字幕下载/内嵌仍属 Phase C 的 C2。
- [~] **多任务并发时的全局限速总量精度**：引擎任务已由 I5 的共享预算与
  `EngineBandwidthDeviceTest` 在设备上实测（两个真实 yt-dlp 进程合计速率被压在 2× 上限内，且耗时接近
  「两份字节 ÷ 一个上限」）；**直链下载**的共享桶仍只由 JVM 虚拟时间用例覆盖，未在设备上做同样的
  并发吞吐测量。
- [ ] **自动入库在 Android 9 及以下需要预先授权**，未授权时只报告原因，不弹权限请求（后台完成无法弹窗）。

## Phase C — 工程与生态

- [x] **C1 国际化** — **所有用户可见文案都来自 `res/values/` 与 `res/values-en/`**（各 720 条 key，一一对应）；`LocalizationGuardTest` 以棘轮方式守着：`src/main/java` 里**未标注理由的中文串数为 0**，两个语言文件 key 必须一一对应、`locales_config` 必须声明两种语言。基础设施：`res/xml/locales_config.xml`、清单 `android:localeConfig`、移除 `MainActivity` 的强制中文覆盖、`LocaleTestRunner` 把设备测试固定在 zh-CN。完成定义与「非文案」清单见 [P6 验收记录](validation/p6-phase-c-2026-10-09.md)：共处理 **842 条**中文字面量，其中 **约 726 条搬进资源**，**其余原本按「非文案」豁免的 116 处也已搬进资源**（经 `core/AppText.kt`，见 P6 验收记录「方案 2」一节），现在只剩 **2 处**不可本地化——`GalleryExporter` 的 MediaStore 目录名与 `LinkList` 的清单格式标识行（理由是行为/兼容，不是「不是文案」）；另有 `legacyFailureKind` 的 7 条历史消息匹配按函数名豁免。**原则**：`domain`/`core`/`data` 不持有界面文案——它们返回**值、枚举或 key**（`engine:`/`download:`/`service:`/`vault:`/`gallery:`/`x:`/`platform:`/`input:`/`update:`/`generic:`），由 `core/reasonKeyRes(key)` 单点解析、界面成句；持久化的错误只存 key，**旧版本写入的中文消息仍原样可读**。
- [x] **C2 字幕下载 / 内嵌** — 解析结果带出可用的字幕语言，结果页出现「同时保存字幕」选项；引擎按语言下载并**内嵌**（`embedsubtitles` 是命令行专用开关、API 会忽略，必须显式加 `FFmpegEmbedSubtitle` 后处理器）；请求字幕的任务改走引擎下载。两个条目的解析与内嵌已在本地 `<track>` 页面验证，播放页字幕开关随之出现（见 [P6 验收记录](validation/p6-phase-c-2026-10-09.md)）。
- [x] **C3 播放列表 / 合集批量下载** — 「解析合集」开关（默认关闭，上限 20）把整页条目交给既有分项选择；引擎侧 `noplaylist` / `playlistend` 改为由请求驱动；两个条目的枚举与逐条下载已在本地两视频页面验证（见 [P6 验收记录](validation/p6-phase-c-2026-10-09.md)）。
- [x] **C4 私有保险箱 + 应用锁** — `DownloadRecord.isPrivate` + `VaultPolicy`（纯逻辑）统一决定可见性与脱敏；保险箱是独立视图，门禁用系统弹窗（Android 11+ 生物识别或凭据，9/10 凭据）；通知标题、完成通知深链、首页「最近保存」、导出链接清单与自动入库全部不泄露私密标题（见 [P6 验收记录](validation/p6-phase-c-2026-10-09.md)）。
- [ ] **C5 自定义 UA / 代理**（用户明确本轮不做）— 需扩展 `DouyinApiClient.USER_AGENT`、`ResumableDownloader` 的请求头注入点与代理配置，故保留未做。
- [x] **C5′ 链接导入导出与设置备份**（C5 去掉自定义 UA / 代理后的部分）— 版本化 JSON 备份编解码与校验、链接清单导出/导入、SAF 文档读写；见 [P6 验收记录](validation/p6-phase-c-2026-10-09.md)。

## Phase D — 图片、看图器与打包下载（已完成）

- [x] **D1 图片是一等媒体类型** — `domain/model/MediaKind.kt`（视频/音频/图片/压缩包）+ `VideoVariant.kind` + `DownloadRecord.mediaKind`；旧历史没有该字段时按视频读取。校验（`DownloadValidation`：图片看文件头、ZIP 看魔数与条目数）、扩展名（`extensionOrDefault`）、MIME（`videoMimeType`）与空间余量（图片 2 MiB）都按类型分派。
- [x] **D2 直链图片** — `GenericVideoRepository.probeDirect` 用 `MediaSignature` 按文件头识别图片并在视频判定之前返回图片条目；新 `data/generic/ImageMedia.kt` 统一构造条目（稳定 `mediaId`、`pageUrl`、Referer）。
- [x] **D3 平台图文** — 抖音 `images` / `image_post_info`（`/share/note/` 优先，修掉"图文帖 BFS 抓到推荐位视频"的隐患）；微博 `pic_infos`（original→largest→large→bmiddle；同一 GIF 条目可选 MP4 转码或 GIF 原图，不重复内容；`livephoto` 作为静帧 + 原始 `.mov` 成对媒体，只有两者均完成才完成任务）；X `photo`（`pbs.twimg.com` + `?name=orig`，`/photo/N` 选中第 N 张）；快手 `photo.atlas`（实测载荷确认字段后实现）。
- [x] **D4 看图器（第三方库）** — `io.github.panpf.zoomimage:zoomimage-compose-coil3:1.4.0`（Apache-2.0，Maven Central，minSdk 21，无原生库）；`ImageViewerScreen` 提供普通库/保险箱分区的全量图片中心、横向翻页、缩略图、3 秒沉浸控制栏、保存/分享/详情/来源与确认删除，缩放/拖动/双击/子采样/Exif 方向仍由 `CoilZoomAsyncImage` 提供。现已增加 3/5/10 秒循环幻灯片、按保存日期分组的三列定位网格，以及 AndroidX ExifInterface 按需读取的 EXIF/XMP 和 GPS 明细；删除后前进、末尾回退、最后一张退出；保险箱每次进入先走系统认证。锁 ZoomImage 1.4.0 的原因（1.5+ 用 Kotlin 2.3/2.4 构建，元数据超出本仓库 Kotlin 2.2.21）写在 `libs.versions.toml` 注释里。
- [x] **D5 打包下载** — `MediaKind.ARCHIVE` + `DownloadRecord.archiveEntries` + `Downloader.startArchive/findArchiveDuplicate`；`DownloadEngine` 的归档分支逐张复用 `ResumableDownloader`（已完成的图跳过、半张图续传、403/410 整帖重解析），`ImageArchiveWriter` 先写 `.tmp` 再改名，`validateArchive` 校验条目数；`ArchivePublisher` 把 ZIP 写进 `Download/拾影`（Android 10+ 走 `MediaStore.Downloads`）。结果页「打包下载（N 张）」+ 重复确认框。
- [x] **D6 网页抓图** — `BrowserMediaDetector` 放行图片扩展名并按魔数/尺寸识别，图片候选（宽高 ≥100px、≥10 KiB）在 `BrowserAssistScreen` 里直接构造条目，不经过 yt-dlp；扫描 JS 增加 `img.currentSrc/src`。
- [x] **D7 文案、图标与路由** — 图片/压缩包在结果页、已存、任务、通知、分享与看图器各自有文案；`MainActivity` 按记录类型分流（图片→看图器、压缩包→系统打开方式、其余→播放器），完成通知深链复用同一条分流；文案资源中英各 **836** 条 string key、一一对应。图片没有单独开底部 tab：`LibraryQuery.mediaKind` + 顶部「类型」筛选（全部/视频/音频/图片/压缩包）复用同一套搜索、排序与批量操作；列表标题/副标题随之改为「已存内容 / N 项」。

验证：JVM 用例 **513** 通过；设备用例在 Pixel_10_Pro（API 37 / arm64 模拟器）上运行 `ImageViewerDeviceTest`、`ImageDownloadDeviceTest`、`ImageArchiveDeviceTest`、`ImageGalleryDeviceTest` 与全量既有用例；未覆盖边界与证据见 [图片下载验证](validation/image-download-2026-11-16.md)。在线验收：X 推文 `2121947540518060428` 已在模拟器上完成解析、3 张图片打包与看图器截图（证据在验证记录「指定 X 推文在线验收」一节）；抖音/微博/快手的真实图文链接仍需调用方在 `live_images` 下提供，默认跳过。

## 明确不做

DRM 解密、直播录制、整站采集、torrent / magnet、悬浮窗（画中画已具备）、不受白名单约束的通用站点信息流、桌面组件（widget，本轮只做快捷方式）、通知内删除。

## Phase E — 站点专区与 KUBB.TV（已完成）

- [x] **E1 可扩展站点层** — 纯 Kotlin `SiteSource` / `SiteChannel` / 能力声明、内存播放会话、独立观看记录与最多 100 条原子 JSON 存储；注册表和存储由 `ServiceLocator` 注入。
- [x] **E2 KUBB.TV 混合浏览** — 16 个非直播原生频道 + 受限 WebView；主域白名单、登录/VIP/支付拦截、外链手势策略、第三方 Cookie/文件/内容/混合内容/多窗口关闭，并保存 URL、滚动位置和 WebView 返回栈。
- [x] **E3 在线播放与下载** — 页面解析和 HLS/直链嗅探并行，多候选选择；在线会话不持久化签名地址，播放器复用倍速、循环、轨道、比例、手势、全屏与 PiP，隐藏截图/抽帧/分享；每 2 秒及退出写入独立续播历史，下载继续使用原始媒体模型和既有下载器。
- [x] **E4 验证与文档** — JVM 覆盖注册表、频道/域名、选集 URL、历史序列化与上限；本地设备 fixture 覆盖首页→站点中心、频道切换、HLS 首帧、续播写入和下载模型映射。真实站点 `live_kubb` / `live_kubb_download` 默认跳过，未在本轮联网执行；结果与边界见 [验收记录](validation/site-hub-kubb-2026-11-16.md)。

按站点登出和从外部浏览器导入 Cookie 也明确不做：`CookieManager` 没有可靠的按域删除接口，且「不读取其他浏览器账户」是本应用的既有承诺。当前只有 X 登录态复用；计划中的 B 站登录只使用应用内网页产生的 Cookie，见 Phase F。

## Phase H — 下载可靠性与一致性（按顺序实施）

> 优先级高于待真机的 G5–G7 与新增站点。每项先完成 JVM 测试，再完成设备用例和
> `docs/validation/` 证据；真实平台临时链接缺失时如实保留未验证边界，不把链接写入仓库。

- [x] **H1 打包内容逐项校验** — 已存在文件跳过前、每张图片下载完成后都校验真实媒体内容；
  Live Photo 的 MOV 校验视频轨。无效文件删除后按过期链接策略刷新/重试，ZIP 最终同时校验
  条目数、名称与每项内容，禁止把 200 错误页或截断文件当成成功图片。
- [x] **H2 安全断点续传** — 下载记录以可空默认值保存 `Last-Modified`；续传优先用 ETag，
  否则用 Last-Modified 发送 `If-Range`。刷新签名地址后没有可靠校验器、或校验器已改变时，
  删除旧分片并从零开始，禁止拼接不同版本的远程内容。
- [x] **H3 图片完整性校验** — 在魔数之后校验 JPEG 结束标记、PNG chunk/IEND、GIF trailer、
  WebP RIFF 长度、BMP 声明长度与 HEIC/AVIF BMFF box 边界。明确截断的文件失败；容器有效但
  系统不支持解码的 HEIC/AVIF 仍允许保存。
- [~] **H4 真实平台回归入口** — 扩展 opt-in 设备用例，分别接受抖音、微博、快手、X 和图片直链，
  统一覆盖解析、单图下载、多图打包、文件校验和相册落地；补 API 26/28 存储授权、拒绝和保留
  应用内副本路径。没有有效作品链接时，测试入口可完成，真实执行证据保持未完成且不阻塞 H5/H6。
- [x] **H5 直链与归档 Retry-After / 重试状态** — 直链、Live Photo 和图片归档支持秒数及
  RFC 1123 日期格式；429/503 按服务端时间重试，缺失时沿用有上限的指数退避，
  401/403/404/410 先尝试一次地址刷新。任务卡显示「等待重试 · N 秒」，倒计时只作为运行时状态；
  yt-dlp / FFmpeg 引擎的重试对齐与跨进程冷却由 Phase I 继续追踪。
- [x] **H6 yt-dlp 原生限速** — 引擎请求向 yt-dlp 传入全局/单任务非零上限的较小值；
  引擎启动后修改设置从下一次任务或重试生效。明确边界：多个引擎进程不共享总桶，FFmpeg 合并阶段
  不限速；直链下载仍使用现有精确的全局共享限速。

## Phase I — 下载闭环强化（按顺序实施）

> 重新评估后的优先级为 I1 → I3；三项完成前不新增下载平台或媒体格式。I4/I5 属于后续增强。
> 每项仍须同时具备 JVM 测试、设备测试和 `docs/validation/` 证据。H4 的真实平台与 API 26/28
> 验收继续作为独立发布条件，不在本阶段重复立项。

- [x] **I1 引擎重试与持久化冷却**（2026-11-17 完成，证据见 [验收记录](validation/engine-retry-cooldown-2026-11-17.md)）—
  yt-dlp / HLS / DASH / 分轨与直链共用 `DownloadRetryPolicy`：NETWORK、429/503 三轮有界重试，
  `Retry-After` 优先、缺失时 1.5s 起指数退避且单次封顶 15 分钟；Python 协议沿异常因果链取
  `HTTPError.response`，只回传状态码与 `Retry-After`（不泄露 URL、Cookie 与原始日志），503 归为
  NETWORK。`DownloadRecord` 新增可空默认字段 `nextRetryAtMillis` / `retryAttempt` /
  `retryHttpStatus`，自动继续按冷却跳过且不消耗预算，用户「立即重试」用一次性信号放行并清零预算；
  暂停、成功、重新下载都清冷却。任务卡倒计时优先用实时进度、重启后回落到记录字段，冷却期间
  「更多任务操作」出现「立即重试」。JVM 591 例通过（新增策略 11 例、引擎重试/冷却 5 例、历史兼容 2 例），
  `DownloadRetryStatusDeviceTest` 在 API 37 模拟器 3/3 通过。**未覆盖**：没有真实站点 429/503
  链接，引擎侧真实 `Retry-After` 只经代码评审与假引擎覆盖；多引擎进程之间的冷却协调留给 I5；
  一次全量复跑出现过 `retryNow` 用例超时与 `QueueReorderTest` 读空历史的偶发失败，
  `retryNow` 的信号登记顺序缺陷已修复、`QueueReorderTest` 根因未定位。
- [x] **I2 视频 / 音频尾部与轨道完整性校验**（2026-11-17 完成，证据见 [验收记录](validation/media-integrity-tail-2026-11-17.md)）—
  新增纯 Kotlin `MediaIntegrityPolicy`：按任务意图推导必须存在的轨道（音频任务只要音轨；
  `+` 选择器 / 单独音频 URL、格式 / `SEPARATE` 要求两条都有；单一格式只要求视频轨），
  声明时长的 90% 处为尾部阈值，容器无时长时明确报告未知。`EngineMediaDownloader` 的
  `validateMediaFile` 在首样本之外做轨道与尾部检查（`MISSING_TRACK` / `TRUNCATED` /
  `NO_VIDEO` / `NO_AUDIO`），尾部从 90% 处向后最多读 512 个样本；直链下载按记录类型分派，
  未知 `Content-Length` 与提前断流的截断文件不再落成成功记录。校验失败删除未发布成品、
  保留 `.part` / `.ytdl` 分片，不做逐帧解码。新增 `faststart-fixture.mp4`（`moov` 前移、
  `stco` 同步修正）用于「截尾但仍可打开」的真实验证。JVM 615 例通过（新增策略 8 例），
  `MediaIntegrityDeviceTest` 5/5 通过，`GenericVideoDeviceTest` / `P2AudioDeviceTest` /
  `SubtitleDownloadDeviceTest` / `PlaylistCollectionDeviceTest` 复跑通过。**未覆盖**：
  无声明时长的容器仍只做首样本校验；不检测中间空洞；缺音轨的重试收益、真实 CDN 断流、
  API 26/28 与平板/折叠屏未验证。
- [x] **I3 相册与公共目录导出幂等化**（2026-11-17 完成，证据见 [验收记录](validation/gallery-export-idempotency-2026-11-17.md)）—
  新增 `ExportReceipt` / `GalleryExportReceiptStore`：回执由「记录 id + 每个源文件的名称/大小/
  修改时间指纹 + 目标 URI 列表」组成，原子落盘 `filesDir/export-receipts.json`；
  `find` 在目标被删或指纹已变时失效并清掉条目，90 天不用的回执自动清理。
  `GalleryExporter` 返回 `Saved` / `AlreadySaved`，`replaceExisting = true` 才再写一份；
  Live Photo 静帧/MOV 一起写、一起记，任一步失败删除本次创建的条目。`ArchivePublisher`
  对 ZIP 公共下载同样判重。已存操作面板弹「已经保存过 / 再保存一份」确认框，播放页与看图器的
  提示条提供「再存一份」动作。JVM 607 例通过（新增回执判定 9 例、存储状态机 7 例），
  `GalleryExportReceiptDeviceTest` 4/4 通过（真实 MediaStore：重复保存、删目标失效、同名不同内容、
  Live Photo 成对失效、半套回滚、ZIP 重复发布）。**未覆盖**：升级前没有回执，升级后第一次保存
  会再写一份；批量保存把「已保存」计为成功、不逐项弹确认；Android 9 及以下与 OEM 相册回收站、
  平板/折叠屏未验证。
- [x] **I4 已完成文件健康检查与修复**（2026-11-17 完成，证据见 [验收记录](validation/library-file-health-2026-11-17.md)）—
  完成时把 `fileSizeBytes` / `fileModifiedAt` 写进记录（可空默认，旧记录读到 `null` 只做存在性判断）。
  新增纯 Kotlin `StoredFileHealth` / `FileHealthPolicy`（MISSING / HEALTHY / UNKNOWN / CHANGED / CORRUPT）
  与 `LibraryFileInspector`：低成本检查只 stat（路径、大小、修改时间），用户主动「检查文件」时才复用
  下载完成校验做结构检查；不做全量哈希、不逐帧解码，结构检查在 IO 线程执行。
  已存操作面板新增「检查文件」，缺失/变化/损坏时给出对话框：「重新下载」按原地址重启同一条记录
  （保留收藏、分类、隐私与手动排序）、「重新解析」把原页面地址交给解析页。JVM 630 例通过
  （新增健康策略 8 例、检查器 6 例、重启保留元数据 1 例），`LibraryFileHealthDeviceTest` 6/6、
  `LibraryFileHealthUiDeviceTest` 2/2 通过。**未覆盖**：链接过期后「重新解析」的真实导航只做了
  代码接线、未端到端验证；不检测中间空洞；文件大小/修改时间变化只提示「变化」，不自动删除；
  平板/折叠屏与 API 26/28 未验证。
- [x] **I5 引擎网络控制收敛**（2026-11-17 完成，证据见 [验收记录](validation/engine-bandwidth-2026-11-17.md)）—
  新增 `EngineBandwidthGovernor`：跨会话共享的全局令牌桶 + 每会话单任务桶，引擎上报进度时把
  累计字节换算成增量，按债务 ÷ 上限算出暂停时长，用 `SIGSTOP`/`SIGCONT` 暂停整个引擎进程组，
  多个 yt-dlp 进程合起来不超过全局限速；上限每次事件重新读取，运行中改设置立即生效；
  单次暂停封顶 15 秒（安全阀），不限速时零开销。任务卡在「跟随全局」时显示「全局限速 …」，
  任务自己有上限时显示「限速 …」。终止路径不变：Wi-Fi 切换、暂停、取消、进程退出仍终止整个
  进程组。JVM 639 例通过（新增预算账本 10 例），`EngineBandwidthDeviceTest` 2/2 通过
  （真实 yt-dlp：单任务与两个任务并行都压在 1.8× 上限内，且总耗时达到共享预算应有的量级）；
  `GenericVideoDeviceTest` / `DownloadRetryStatusDeviceTest` / `TaskSearchDeviceTest` 回归通过。
  **边界**：「FFmpeg 自行拉流也限速」在本应用不适用（引擎从不把拉流交给 FFmpeg，FFmpeg 只做
  本地合并/提取）；暂停式节流是近似、短期可高于上限；站点不上报进度时预算会少算；
  真实站点并发限速与平板/折叠屏、API 26/28 未验证。

## Phase G — 用户体验收敛（按顺序实施）

> 优先级高于新增站点。每项必须按 G1 → G7 顺序推进；前一项达到 JVM 测试、设备测试和
> `docs/validation/` 证据三项完成标准后，下一项才能标记为进行中。

- [x] **G1 首次使用与高质量空状态** — 首次进入解析页用不打断操作的引导说明
  「粘贴或分享链接 → 选择内容 → 在已存中查看」；空状态提供粘贴入口和受支持内容说明；
  权限仍只在真正需要时申请。引导关闭后持久化，不在升级后重复打扰。
- [x] **G2 下载结果跨进程恢复** — 持久化最小的未查看完成、暂停、失败结果；应用被系统杀死后
  再启动仍能看到一次结果摘要，进入对应页面或关闭后清除；不得把全部历史异常重新算成未读。
- [x] **G3 异常恢复主操作统一** — 任务卡对网络、Wi-Fi、后台服务、空间、登录、链接过期和引擎
  故障统一展示「一句原因 + 一个推荐主操作」，次要诊断继续收进更多操作，避免同一失败给出多个
  同等级按钮。
- [x] **G4 权限与运行环境就地说明** — 通知、Android 8/9 存储权限、后台服务与画中画只在相关
  功能入口检查；缺失时说明影响并提供直达系统设置，不新增启动时的权限轰炸。
- [x] **G5 无障碍与大屏模拟器验收** — 用启用 TalkBack 的手机模拟器、200% 字体、平板/折叠屏
  模拟器和分屏走完解析、任务、已存、设置主链并修复发现的问题；不包含外接键盘与键盘焦点顺序验收。
- [x] **G6 登录态表达与重新登录** — 网站与账号页明确展示当前登录状态、适用站点和清理范围；
  登录被拒时优先提供重新登录，不能确定是过期还是权限不足时保持中性文案。
- [x] **G7 信息密度、动效与键盘手感人工收敛** — 默认只展示高频操作，高级能力按内容渐进出现；
  人工复核转场节奏、横屏键盘弹窗页脚和启动器快捷方式视觉，并记录取舍与未覆盖边界。

## Phase F — 哔哩哔哩支持（后续）

- [x] **E1 链接与投稿解析**（2026-11-17 完成，证据见 [验收记录](validation/bilibili-e1-2026-11-17.md)）—
  新增 `VideoPlatform.BILIBILI`、纯 Kotlin `BilibiliLink`（只认 `/video/BV…` 与 `/video/av…`，
  保留显式 `?p=N`，丢弃分享参数）与 `BilibiliShortLinkResolver`（逐跳校验，只跟随跳到可信 B 站视频页的
  `b23.tv`）。番剧、直播、课程、音频、空间/频道列表与缺 ID 路径返回 `bilibili:unsupported`，
  不交给引擎；仓库把引擎结果重标平台、写回归一化来源地址并补 Referer。合集枚举沿用「解析合集」开关
  与 20 P 上限。JVM 652 例通过（新增链接归一化 8 例、短链展开 5 例），`BilibiliDeviceTest` 6 条
  （4 条离线 + 1 条真实在线解析已通过 + 1 条多 P 合集按默认前提跳过）。
  **未覆盖**：本轮没有多 P 链接，分 P 枚举未实测；`b23.tv` 只用本地 302 验证；番剧/直播按 URL 形态拒绝；
  登录态、DASH 下载与字幕过滤分别属于 E3/E2/E4。
- [x] **E2 DASH 下载**（2026-11-17 完成，证据见 [验收记录](validation/bilibili-e2-2026-11-17.md)）—
  复用既有引擎与 `MediaKind`：元数据解析把每个纯视频 DASH 格式与最佳音轨配成同一个画质选择
  （`SEPARATE` + `formatSelector = 视频+音频`，引擎合并为单一文件），合并结果在下载完成时走 I2 的
  轨道与尾部校验，缺音轨/截断会失败。DRM 格式逐条过滤，整条只有 DRM 时拒绝解析；直播录制、
  付费与权限越权仍不尝试。JVM 654 例通过（新增 B 站 DASH 选择 3 例：画质配最佳音轨、DRM 过滤、
  全 DRM 拒绝），设备用例 `BilibiliDeviceTest#liveBilibiliDownloadMergesVideoAndAudio` 用真实公开
  视频（链接按运行参数传入，不入库）完成下载、合并与双轨校验并通过（27.9 秒）。
  **未覆盖**：大会员/4K/HDR/杜比画质与需要登录的内容未验证（E3）；真实付费内容与地区限制未构造；
  多 P 合集仍缺真实链接；本轮只跑了一次在线下载。
- [x] **E3 应用内网页登录态**（2026-11-17 完成，证据见 [验收记录](validation/bilibili-e3-2026-11-17.md)）—
  设置 → 账号页新增「哔哩哔哩登录」，复用网页登录容器在应用内登录；登录态由系统 WebView 的
  `CookieManager` 持有，以非空 `SESSDATA` 判断，`BrowserSessionStore.bilibiliSignedIn()` 每次现查、
  不缓存过期时间。`extractorCookieUrls` 对 B 站页面追加 `www.bilibili.com` / `api.bilibili.com`
  两个 Cookie 来源；仓库先试登录态、登录被拒再退回匿名、两条路都要求授权时返回
  `bilibili:session_auth_failed`（`LOGIN_REQUIRED`）。清除网页登录态会同时刷新两个站点的状态。
  JVM 658 例通过（新增登录态判断 4 例）；设备 `BilibiliLoginDeviceTest` 2/2 通过（真实
  CookieManager 识别与导出、清除后立即未登录），`BilibiliDeviceTest` 7 条通过（新增 3 条会话顺序用例）
  加 3 条在线用例按前提跳过。**未覆盖**：真实账号登录、登录后画质差异、大会员/付费内容的否定性验证、
  `SESSDATA` 过期探测均未自动化；只访问正常网页可播放内容靠代码约束。
- [x] **E4 字幕与验证**（2026-11-17 完成，证据见 [验收记录](validation/bilibili-e4-2026-11-17.md)）—
  `GenericMetadataParser.textSubtitleLanguages` 在解析层过滤 `danmaku` / `live_chat` / `comments`，
  同时作用于真实字幕与机器字幕，界面和引擎请求都不再出现弹幕；文本字幕的下载/内嵌沿用 C2 路径。
  JVM 661 例通过（新增过滤 3 例）；设备用例在真实公开视频（运行时参数，链接不入库）下断言
  `subtitleLanguages` / `autoCaptionLanguages` 不含 `danmaku`（8.1 秒），连同 E1/E2 的在线解析与
  真实下载验收一起留证；默认跳过的 `live_bilibili*` 入口齐全。
  **未覆盖**：B 站真实字幕的下载与内嵌未实测；只过滤已知的非文本轨；多 P 合集与真实 `b23.tv`
  仍缺链接；平板/折叠屏与 API 26/28 未验证。

## 已具备（勿重复立项）

平台与通用网址解析、网页辅助嗅探、批量导入（≤50 条）、画质与文件大小选择、重复提醒、下载队列与排序、暂停续传、HLS/DASH/分轨合并与校验、失败分类恢复、共享空间预留与预算可视化、残留清理、收藏/分类/重命名、相册与自定义目录导出、独立音轨与离线提取、续播与 0.5×–4× 倍速、后台音频、画中画、完成通知与通知内单任务控制、检查更新、深浅色主题、Material You 动态取色、`ACTION_SEND` 分享接入与 `VIEW` 深链、剪贴板链接识别、启动器快捷方式、任务页搜索与排序。

2026-10-09 起还具备：播放手势（双击快进退、竖滑亮度音量、长按 2×）、字幕渲染与开关（含同名 sidecar）、全局与单任务限速、下载后自动存入相册、继续观看进度回填、音频睡眠定时器。

2026-11-16 起还具备：**X（Twitter）登录态复用** —— 「我的 → X（Twitter）登录」登录一次，需要授权的推文在匿名路径失败后自动改用本机登录态解析与下载；有效期完全由 X 的 `Set-Cookie` 决定（App 不设 TTL、不在启动/升级/失败时清除），Cookie 过期或被服务端拒绝时给出专用提示并指引重新登录（`x:session_auth_failed`）。JVM 用例、设备用例（`XLoginSessionDeviceTest` 6 例 + 两阶段 `XLoginPersistenceDeviceTest`）与证据见 [X 登录态复用验证](validation/x-login-session-2026-11-16.md)。

## 验收命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pabi=universal   # 模拟器需 x86_64；真机用默认 arm64
./gradlew assembleDebug && ./gradlew assembleRelease
```

> 通知相关设备用例需要系统已允许应用通知；`-Pandroid.testInstrumentationRunnerArguments.class` 一次只接受一个类。

2026-11-16 起还具备：**图片、图文与打包下载** —— 图片直链、抖音图文、微博多图、X 多图、快手图集与网页抓图；看图器用 ZoomImage 缩放/子采样，并支持循环幻灯片、日期网格定位、EXIF/XMP 与 GPS 明细；「打包下载」一次任务产出一个 ZIP（暂停续传、条目校验、写入 `Download/拾影`）。证据见 [图片下载验证](validation/image-download-2026-11-16.md) 与 [图片浏览增强验证](validation/image-viewer-enhancements-2026-11-16.md)。
