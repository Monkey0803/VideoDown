# 拾影 功能待办（TODO）

> 更新日期：2026-10-13 ｜ 当前分支：`main` ｜ 版本：1.1.1 / code 6（英文名 Keepsake，更新源走 GitHub Releases）
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
- [ ] **快捷方式的桌面视觉未验证**：仅断言动态快捷方式集合与 action 落地行为，未覆盖用户手动固定到桌面后的长按菜单呈现。
- [ ] **剪贴板系统提示未断言**：Android 12+ 首次读取他应用剪贴板时系统会弹一次访问提示，属平台行为，无法由应用消除，用例未覆盖。
- [ ] **非手机形态未验证**：本轮未在平板/折叠屏验证任务页新增的搜索框、排序 chip 与通知卡片的排布。

## 既有失败项（非本轮引入，供后续处理）

- [ ] **`P2CompletionNotificationDeviceTest` 在 API 37 模拟器上超时失败**，在 API 30 真机通过。已用「临时注释掉本轮独立通知收集器仍以同样方式失败」证明与本轮改动无关，疑似模拟器 SystemUI 通知栏差异。失败日志保留在 `docs/validation/p4-features-2026-10-09/emulator-api37/P2CompletionNotificationDeviceTest.emulator-failure.xml`。**本轮在真机 SHARK PRS-A0 / API 30 复核：1/1 通过**，XML 存于 `docs/validation/p6-phase-c-2026-10-09/device-api30/P2CompletionNotificationDeviceTest.xml`。
- [x] **`P1RecoveryNavigationDeviceTest#rateLimitedParseOpensSettingsWithoutRepeatingRequest` 等待的文案「打造专属空间」已在 `94a83b9` 从设置页移除**，断言未同步。本轮把断言改为当前实际渲染的页面标题「我的设置」，用例意图不变，修复后 4/4 通过。
- [ ] **`P1DownloadIntegrationTest#known response size is checked again before writing a direct file` 曾偶发一次**（`expected:<STORAGE_FULL> but was:<NETWORK>`，2026-10-10 C1 迁移期间）。**该用例属于 JVM 套件（`app/src/test`），无法在真机上运行**；本轮以 `--rerun-tasks` 连跑 5 次，12 用例 × 5 次全部通过，未修改用例。
- [ ] **`P3PictureInPictureDeviceTest#pipScreenOffThenReturnKeepsActivityAndPlayer` 在 API 37 模拟器上失败**（操作真实熄屏与 PiP）。已用两种独立实验证明与本轮无关：把 `PlayerScreen.kt` 还原到 HEAD 后同样失败；只关闭新增手势覆盖层后同样失败。**在 API 30 真机（SHARK PRS-A0）上已实测通过**：传 `manual_unlock=true` 并由人工在两轮各 120 秒窗口内解锁，四条 PiP 用例 4/4 通过（该条 26.1 秒），XML 存于 `device-api30/P3PictureInPictureDeviceTest.manual-unlock.xml`，两轮解锁后的播放器截图存于 `evidence/pip-manual-unlock-cycle-{1,2}.png`；**默认参数**的真机运行会因设备有锁屏而跳过该条（3 通过 + 1 跳过，XML 存于 `device-api30/P3PictureInPictureDeviceTest.xml`）。因此「模拟器失败、真机通过」这一差异已成立，剩下的只是模拟器差异本身未修复。
  - **2026-10-12 补充：把三项动画缩放设为 0 后该条通过（`P3PictureInPictureDeviceTest` 4/4）**，说明它是动画时序敏感，而不是模拟器能力缺失；Espresso 本来就要求关闭动画，CI 应固定这一档。见 `validation/motion-batch2-2026-10-12.md`。

- [ ] **`P3PlaybackDeviceTest#retainedViewModelSurvivesActualActivityRecreationWithCurrentPosition` 在 SHARK PRS-A0 / API 30 真机上 10s 超时**（2026-10-12 真机复跑，7 条中 1 条失败；同批在 API 37 模拟器通过）。未进一步定位，疑似真机 Activity 重建更慢或 OEM 行为差异。见 `validation/real-device-2026-10-12.md`。
- [ ] **`P3PictureInPictureDeviceTest#systemPipDismissReleasesVideo` 在 SHARK PRS-A0 / API 30 真机上失败**（`PiP state did not reach expected condition`，2026-10-12；模拟器通过，差异未定位）。同批 `pipScreenOffThenReturnKeepsActivityAndPlayer` 因真机有锁屏而按环境前提跳过（默认参数），与代码无关。见 `validation/real-device-2026-10-12.md`。

## 动效与快捷方式遗留（2026-10-12）

动效批次 0–4 与快捷方式已提交（`feat(motion)` / `feat(library)` / `feat(shortcut)`），证据见
`validation/motion-batch0-1-2026-10-12.md`、`validation/motion-batch2-2026-10-12.md`、
`validation/reorder-persistence-2026-10-12.md`、`validation/motion-batch4-2026-10-12.md`、
`validation/launcher-shortcut-icons-2026-10-12.md`、`validation/shortcut-navigation-2026-10-12.md`。
以下是这些批次留下的遗留项（其中弹窗页脚一条已于 2026-10-13 完成，保留在此便于追溯）：

- [ ] **动效手感仍未经人工复核**：测试只能证明「会动、会停、不遮挡」，证明不了快慢与观感。真机录屏（动画缩放 1.0）已入库 `validation/motion-showcase-2026-10-12/motion-on-real-device.mp4`，逐项「觉得不对该改哪里」写在同一目录的文档里；改动点都集中在单个文件（`SharedCover` 的 `boundsTransform`、`Motion` 的 token、`Shimmer` 的 `SWEEP_MS`、`EmptyStateIllustration` 的多边形参数）。
- [x] **弹窗页脚在横屏 + 键盘下被键盘挡住**（2026-10-13 完成，证据见 [验收记录](validation/dialog-ime-2026-10-13.md)）：真机（393×873dp）横屏且输入法弹起时，重命名弹窗的「保存修改」落在键盘背后。根因是三层叠加：① 浮动弹窗对键盘只做 ADJUST_PAN（整窗上移 300px，标题被推出屏幕顶部）；② `LocalWindowInfo.containerSize` 在弹窗窗口里给的是窗口长边（横屏 2400px），原来的 0.86 高度上限从未生效；③ 扣掉键盘后可用高度只剩约 129dp，比「头部 + 动作行」的 144dp 还矮，固定页脚必然被挤扁（实测按钮 40dp → 24dp）。改法：`decorFitsSystemWindows = false` + 容器自己 `systemBarsPadding().imePadding()`、可用高度改从 `BoxWithConstraints` 取、显式外部点击关闭（容器铺满后系统判定失效）、可用高度 < 200dp 时头部与动作行一起进滚动区；`BatchImportDialog` 接入同一容器。**历史结论已失效**：2026-10-12 记录的「在弹窗窗口里读 `WindowInsets.ime` 会让 `P2BatchImportDeviceTest` 4 条全挂 `ComposeNotIdleException`」本轮未复现（真机与模拟器该用例都是 4/4），两条无效路径（`SOFT_INPUT_ADJUST_RESIZE`、按 IME 收紧高度上限）的结论仍成立。遗留边界：平板/折叠屏未验证；键盘弹起时页脚需滚一下才可点（取舍）；真机只跑了弹窗相关类（全量受 10 分钟锁屏限制）。
- [ ] **恢复快捷方式的前置条件未解决**：三个快捷方式已按要求暂时不发布（`LauncherShortcuts.publish` 改为清空动态快捷方式，实现、图标与 intent 处理全部保留）。恢复前必须先解决「长按菜单这条路无法自动化验证」——本轮只能验证「启动器会发的那个 intent」，真实长按菜单无法用 adb 稳定唤出，回归只能靠人工长按。
- [ ] **弹窗在非手机形态未验证**：弹窗页脚这次只覆盖手机（真机 393×873dp 横竖屏、模拟器 427×952dp）；平板 / 折叠屏下的高度上限与「整卡滚动」取舍未验证。
- [ ] **键盘弹起时页脚要滚一下才可点，观感待人工确认**：可用高度只剩约 129dp 时头部与动作行一起进滚动区，自动化只能证明「滚得到、点得到」，证明不了这个取舍在真机上手感如何（见 [验收记录](validation/dialog-ime-2026-10-13.md) 的 `after-landscape-ime.png`）。
- [x] **已存自定义顺序不随备份走**（2026-10-13 完成，证据见 [验收记录](validation/library-order-backup-2026-10-13.md)）：顺序挂在记录上（`DownloadRecord.sortIndex`），而备份只装 `SettingsSnapshot`，所以导出再恢复会丢掉手动排好的那一段。改法：`data/backup/SavedOrder.kt` 定死三条语义（只导出 `sortIndex > 0` 的那段、清洗空串/重复/超长、恢复时「备份顺序在前 + 备份之后才排过的按原相对顺序接在后面」并整段重新落位），`SettingsSnapshot` 加可空 `savedOrder`，`presentation/settings/SettingsBackupFlow.kt` 把导出与恢复各封一处（恢复补报 `SettingsChange.LIBRARY_ORDER`，否则一次只改顺序的恢复会被报成"与当前一致"）。**已知语义**：id 是本机的，换机恢复顺序保持原样（记录不随备份走），已写进 README「已知限制」。遗留边界：系统文档选择器那一步没自动化（与本类既有用例一致）；SAF 写入依赖 API 31+，设备用例只在模拟器上跑；顺序粒度是"手动排过的那一段"，从没排过的记录不进备份。
- [x] **把手隐藏条件只覆盖了「搜索」一种**（2026-10-13 完成，证据见 [验收记录](validation/library-reorder-gate-2026-10-13.md)）：判定搬进 `LibraryQuery` 成为纯逻辑——`ordersManually`（自定义排序 + 无关键词 + 无平台 + 无分类 + 非仅收藏 + 非保险箱）与 `canReorder(selecting, batchBusy)`（再多排两处），`LibraryScreen` 的把手与「切到自定义排序时 seed 一次」各自取用其一。新增 `LibraryReorderGateTest` 穷举六个条件 + 一条不变量（能拖必然 `ordersManually`，否则会出现"能拖但从未 seed 过"的列表），并用删掉 `!favoritesOnly` 的变异证明用例有牙；设备侧新增「多选 + 分类过滤」让把手消失（搜索那条原有）。遗留边界：平台/仅收藏/保险箱/批量忙只穷举在判定层，没有各演一遍端到端；任务页的把手判定是另一条，不在本轮范围。
- [ ] **共享元素未自定义曲线以外的编排**：时长已接到 `AppMotion`（250ms），但未测过更慢/更快的观感差异，也没有对「从列表页进入」以外的入口（首页、任务页）做共享元素。

## Phase B — 差异化（已完成）

证据与未覆盖边界见 [P5 验收记录](validation/p5-features-2026-10-09.md)。同批 JVM 324 用例通过；10 个 Phase B 设备用例在 Android 17 / API 37 模拟器与 Android 11 / API 30 真机均通过。

- [x] **B1 播放器手势与字幕渲染** — `PlayerGestures` 纯几何 + 仅在系统控件隐藏时接管的覆盖层（双击 ±10s、左滑亮度、右滑音量、长按临时 2×）；内嵌字幕由 `PlayerView` 渲染，同名 `.srt`/`.vtt`/`.ass` 作为外部轨道加载，「字幕」开关按轨道存在与否显示并保留。
- [x] **B2 全局 / 单任务限速** — `TokenBucket` + 共享/每任务 `BandwidthLimiter`，钩子在 `ResumableDownloader` 写入前且位于空间检查之后；设置页全局限速与任务卡单任务限速。引擎下载不受限速（边界见验收记录）。
- [x] **B3 下载完成自动存入相册（开关）** — `AutoGalleryExport` 决定是否/能否复制，完成通知在同一张卡上报告去向；拷贝移出传输协程，不再占用队列名额。
- [x] **B4 继续观看** — `PlaybackRepository.watchProgress()` 返回 `StateFlow<Map<String, WatchProgress>>`，`WatchProgress` 携带文件 size/mtime 用于剔除陈旧进度；「已存」与首页「最近保存」显示进度条与「已看 N%」。
- [x] **B5 音频睡眠定时器** — 独立键保存**绝对 deadline**（未塞进 `PlaybackOptions`，因为它是全局音频设置而非音视频各自的偏好），`AudioPlaybackService` 每 2 秒检查，到点暂停并清零。

## Phase B 遗留边界（未完成）

- [ ] **分片引擎下载（yt-dlp / FFmpeg）不受限速影响** —— 它们是独立进程，无法按字节节流。若要覆盖，需要在引擎侧改用可限速的下载器或代理。
- [ ] **手势区域未在平板 / 折叠屏验证**，只在手机竖屏与既有横屏用例上验证。
- [ ] **字幕未逐像素断言真的画在画面上**，只验证轨道存在与开关行为；字幕下载/内嵌仍属 Phase C 的 C2。
- [ ] **未测量多任务并发时的全局限速总量精度**，共享桶只由 JVM 虚拟时间用例覆盖。
- [ ] **自动入库在 Android 9 及以下需要预先授权**，未授权时只报告原因，不弹权限请求（后台完成无法弹窗）。

## Phase C — 工程与生态

- [x] **C1 国际化** — **所有用户可见文案都来自 `res/values/` 与 `res/values-en/`**（各 720 条 key，一一对应）；`LocalizationGuardTest` 以棘轮方式守着：`src/main/java` 里**未标注理由的中文串数为 0**，两个语言文件 key 必须一一对应、`locales_config` 必须声明两种语言。基础设施：`res/xml/locales_config.xml`、清单 `android:localeConfig`、移除 `MainActivity` 的强制中文覆盖、`LocaleTestRunner` 把设备测试固定在 zh-CN。完成定义与「非文案」清单见 [P6 验收记录](validation/p6-phase-c-2026-10-09.md)：共处理 **842 条**中文字面量，其中 **约 726 条搬进资源**，**其余原本按「非文案」豁免的 116 处也已搬进资源**（经 `core/AppText.kt`，见 P6 验收记录「方案 2」一节），现在只剩 **2 处**不可本地化——`GalleryExporter` 的 MediaStore 目录名与 `LinkList` 的清单格式标识行（理由是行为/兼容，不是「不是文案」）；另有 `legacyFailureKind` 的 7 条历史消息匹配按函数名豁免。**原则**：`domain`/`core`/`data` 不持有界面文案——它们返回**值、枚举或 key**（`engine:`/`download:`/`service:`/`vault:`/`gallery:`/`x:`/`platform:`/`input:`/`update:`/`generic:`），由 `core/reasonKeyRes(key)` 单点解析、界面成句；持久化的错误只存 key，**旧版本写入的中文消息仍原样可读**。
- [x] **C2 字幕下载 / 内嵌** — 解析结果带出可用的字幕语言，结果页出现「同时保存字幕」选项；引擎按语言下载并**内嵌**（`embedsubtitles` 是命令行专用开关、API 会忽略，必须显式加 `FFmpegEmbedSubtitle` 后处理器）；请求字幕的任务改走引擎下载。两个条目的解析与内嵌已在本地 `<track>` 页面验证，播放页字幕开关随之出现（见 [P6 验收记录](validation/p6-phase-c-2026-10-09.md)）。
- [x] **C3 播放列表 / 合集批量下载** — 「解析合集」开关（默认关闭，上限 20）把整页条目交给既有分项选择；引擎侧 `noplaylist` / `playlistend` 改为由请求驱动；两个条目的枚举与逐条下载已在本地两视频页面验证（见 [P6 验收记录](validation/p6-phase-c-2026-10-09.md)）。
- [x] **C4 私有保险箱 + 应用锁** — `DownloadRecord.isPrivate` + `VaultPolicy`（纯逻辑）统一决定可见性与脱敏；保险箱是独立视图，门禁用系统弹窗（Android 11+ 生物识别或凭据，9/10 凭据）；通知标题、完成通知深链、首页「最近保存」、导出链接清单与自动入库全部不泄露私密标题（见 [P6 验收记录](validation/p6-phase-c-2026-10-09.md)）。
- [ ] **C5 自定义 UA / 代理**（用户明确本轮不做）— 需扩展 `DouyinApiClient.USER_AGENT`、`ResumableDownloader` 的请求头注入点与代理配置，故保留未做。
- [x] **C5′ 链接导入导出与设置备份**（C5 去掉自定义 UA / 代理后的部分）— 版本化 JSON 备份编解码与校验、链接清单导出/导入、SAF 文档读写；见 [P6 验收记录](validation/p6-phase-c-2026-10-09.md)。

## 明确不做

DRM 解密、直播录制、整站采集、torrent / magnet、悬浮窗（画中画已具备）、内置站点浏览与信息流、桌面组件（widget，本轮只做快捷方式）、通知内删除。

## 已具备（勿重复立项）

平台与通用网址解析、网页辅助嗅探、批量导入（≤50 条）、画质与文件大小选择、重复提醒、下载队列与排序、暂停续传、HLS/DASH/分轨合并与校验、失败分类恢复、共享空间预留与预算可视化、残留清理、收藏/分类/重命名、相册与自定义目录导出、独立音轨与离线提取、续播与 0.5×–4× 倍速、后台音频、画中画、完成通知与通知内单任务控制、检查更新、深浅色主题、Material You 动态取色、`ACTION_SEND` 分享接入与 `VIEW` 深链、剪贴板链接识别、启动器快捷方式、任务页搜索与排序。

2026-10-09 起还具备：播放手势（双击快进退、竖滑亮度音量、长按 2×）、字幕渲染与开关（含同名 sidecar）、全局与单任务限速、下载后自动存入相册、继续观看进度回填、音频睡眠定时器。

## 验收命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pabi=universal   # 模拟器需 x86_64；真机用默认 arm64
./gradlew assembleDebug && ./gradlew assembleRelease
```

> 通知相关设备用例需要系统已允许应用通知；`-Pandroid.testInstrumentationRunnerArguments.class` 一次只接受一个类。
