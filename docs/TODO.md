# 拾影 功能待办（TODO）

> 更新日期：2026-10-09 ｜ 当前分支：`1.0.2` ｜ 版本：1.0.2 / code 3
> 依据：同类应用对标（1DM+、ADM、Snaptube、VidMate、Seal、稞麦视频下载器）与当前源码核查
> 关联：[功能排查与后续顺序](function-backlog-2026-10-09.md)（历史记录，不再更新）、[P4 验收记录](validation/p4-features-2026-10-09.md)

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

- [ ] **独立通知在后台服务退出后无法继续任务**：全部任务暂停后服务按既有设计退出，独立通知随之清除，因此「从通知继续一个已暂停任务」只在仍有其它任务传输时可用。需要产品决定：是否让服务为已暂停任务保活（涉及 Android 15 前台服务时限与耗电），或改为服务退出后保留通知并由通知动作重新拉起服务。当前未改服务生命周期。
- [ ] **快捷方式的桌面视觉未验证**：仅断言动态快捷方式集合与 action 落地行为，未覆盖用户手动固定到桌面后的长按菜单呈现。
- [ ] **剪贴板系统提示未断言**：Android 12+ 首次读取他应用剪贴板时系统会弹一次访问提示，属平台行为，无法由应用消除，用例未覆盖。
- [ ] **非手机形态未验证**：本轮未在平板/折叠屏验证任务页新增的搜索框、排序 chip 与通知卡片的排布。

## 既有失败项（非本轮引入，供后续处理）

- [ ] **`P2CompletionNotificationDeviceTest` 在 API 37 模拟器上超时失败**，在 API 30 真机通过。已用「临时注释掉本轮独立通知收集器仍以同样方式失败」证明与本轮改动无关，疑似模拟器 SystemUI 通知栏差异。失败日志保留在 `docs/validation/p4-features-2026-10-09/emulator-api37/P2CompletionNotificationDeviceTest.emulator-failure.xml`。
- [x] **`P1RecoveryNavigationDeviceTest#rateLimitedParseOpensSettingsWithoutRepeatingRequest` 等待的文案「打造专属空间」已在 `94a83b9` 从设置页移除**，断言未同步。本轮把断言改为当前实际渲染的页面标题「我的设置」，用例意图不变，修复后 4/4 通过。

## Phase B — 差异化（批准后各自出详细计划）

- [ ] **B1 播放器手势与字幕渲染** — 双击快退/快进 10s、竖向滑动调亮度/音量、长按临时 2×；Media3 `PlayerView` 渲染内嵌字幕并加开关。风险：与全屏/旋转/返回手势冲突，需逐条设备验证。
- [ ] **B2 全局 / 单任务限速** — 令牌桶落在 `ResumableDownloader` 写入循环；必须置于既有空间检查之后，不得破坏 `StorageSpaceGuard` 与预留账本语义。
- [ ] **B3 下载完成自动存入相册（开关）** — 复用 `GalleryExporter`，沿用 Android 8/9 权限分支与失败分类反馈。
- [ ] **B4 继续观看** — `PlaybackRepository` 现仅单条 `checkpoint(id, …)` 查询，需新增批量读接口，把进度回填到「已存」卡片与首页「最近保存」。
- [ ] **B5 音频睡眠定时器** — `AudioPlaybackService` + `PlaybackOptions` 增字段。

## Phase C — 工程与生态

- [ ] **C1 国际化** — 165+ 处硬编码中文迁入 `strings.xml`，新增 `values-en/` 与 `res/xml/locales_config.xml`，移除强制中文覆盖。**工程量最大，单独排期。**
- [ ] **C2 字幕下载 / 内嵌** — yt-dlp `--write-subs` / `--embed-subs`，扩展通用引擎格式清单与「字幕」选择 UI。
- [ ] **C3 播放列表 / 合集批量下载** — 复用既有批量导入的分项选择与重复检查。
- [ ] **C4 私有保险箱 + 应用锁** — 需 `DownloadRecord` 增加可见性标记、库查询过滤、生物识别门禁，并确保导出/分享/通知不泄露标题。
- [ ] **C5 自定义 UA / 代理、链接导入导出与设置备份** — 扩展 `DouyinApiClient.USER_AGENT` 与 `ResumableDownloader` 的请求头注入点。

## 明确不做

DRM 解密、直播录制、整站采集、torrent / magnet、悬浮窗（画中画已具备）、内置站点浏览与信息流、桌面组件（widget，本轮只做快捷方式）、通知内删除。

## 已具备（勿重复立项）

平台与通用网址解析、网页辅助嗅探、批量导入（≤50 条）、画质与文件大小选择、重复提醒、下载队列与排序、暂停续传、HLS/DASH/分轨合并与校验、失败分类恢复、共享空间预留与预算可视化、残留清理、收藏/分类/重命名、相册与自定义目录导出、独立音轨与离线提取、续播与 0.5×–4× 倍速、后台音频、画中画、完成通知与通知内单任务控制、检查更新、深浅色主题、Material You 动态取色、`ACTION_SEND` 分享接入与 `VIEW` 深链、剪贴板链接识别、启动器快捷方式、任务页搜索与排序。

## 验收命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pabi=universal   # 模拟器需 x86_64；真机用默认 arm64
./gradlew assembleDebug && ./gradlew assembleRelease
```

> 通知相关设备用例需要系统已允许应用通知；`-Pandroid.testInstrumentationRunnerArguments.class` 一次只接受一个类。
