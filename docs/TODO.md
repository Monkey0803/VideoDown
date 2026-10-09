# 拾影 功能待办（TODO）

> 更新日期：2026-10-09 ｜ 当前分支：`1.0.2` ｜ 版本：1.0.1 / code 2
> 依据：同类应用对标（1DM+、ADM、Snaptube、VidMate、Seal、稞麦视频下载器）与当前源码核查
> 关联：[功能排查与后续顺序](function-backlog-2026-10-09.md)（历史记录，不再更新）

## 使用说明

- 勾选语义：`[ ]` 未开始 ｜ `[~]` 进行中（在行尾注明） ｜ `[x]` 已完成。
- 每项完成标准：**JVM 单测 + 设备用例 + `docs/validation/` 证据**，三者缺一不得勾选。
- 未覆盖的验证边界必须如实写在验证记录里，不得用相邻结果替代。
- 本清单只记录**未实现**的功能；已实现的见文末「已具备」，不要重复立项。

## 前置

- [ ] 提交分支 `1.0.2` 工作区既有未提交改动（README、`AppPreferences`、`ServiceLocator`、`DownloadEngine`、`StorageReservations`、`DownloadHistoryStore`、`DownloadStorageRepository`、`DownloadFailure`、`DownloadRecord`、`StorageUsage`、`Downloader`、`SettingsScreen`、`StorageDialog` 及对应测试），再开始 Phase A，避免混提。
- [ ] 版本号 `versionCode 2 → 3`、`versionName 1.0.1 → 1.0.2`（`app/build.gradle.kts`）。

## Phase A — 表配补齐（本轮）

### A1 剪贴板链接识别

- [ ] 新增 `data/remote/ClipboardLinkPolicy.kt`：纯 Kotlin，输入文本 + 上次已提示内容，输出候选；仅已知平台命中，`GENERIC` 不命中。
- [ ] `core/AppPreferences.kt` 新增 `clipboardDetect`（默认 `true`）与 `lastClipboardOffer`。
- [ ] `presentation/home/HomeViewModel.kt` 新增 `clipboardOffer` 状态与 `offerClipboardText` / `acceptClipboardOffer` / `dismissClipboardOffer`。
- [ ] `presentation/home/HomeScreen.kt` 在**窗口获得焦点后**才读剪贴板（`ViewTreeObserver.OnWindowFocusChangeListener`），顺序为 `hasPrimaryClip()` → 描述 MIME → `primaryClip`；提示为横幅而非自动解析。
- [ ] `presentation/settings/SettingsScreen.kt` 新增「识别剪贴板链接」开关。
- [ ] 边界：Android 12+ 系统会弹一次剪贴板访问提示，无法消除，靠“内容变化才读”降到最低；Android 13+ 剪贴板自动清空属正常；忽略后同一内容不得再提示；分享/深链入口与剪贴板提示不叠加。
- [ ] 测试：`ClipboardLinkPolicyTest`（JVM）、`ClipboardDetectDeviceTest`（设备，含关开关不提示）。

### A2 通知内单任务暂停 / 继续

- [ ] `DownloadService` 新增 `ACTION_PAUSE_ONE` / `ACTION_RESUME_ONE` + `EXTRA_RECORD_ID` 并路由到下载器。
- [ ] 新增 `AppPreferences.individualTaskNotifications`（默认 `false`）；开启后每个运行中任务一条独立通知（标题 / 百分比 / 速度 / 暂停或继续 / 点击进任务页），汇总通知保留，任务结束即撤销。
- [ ] 通知内**不提供删除**：不可逆操作需要二次确认，通知栏无法承载。
- [ ] 测试：action 与 `EXTRA_RECORD_ID` 解析的单测；设备用例真实点击后断言仅目标任务暂停。

### A3 Material You 动态取色

- [ ] `AppPreferences.dynamicColor`（默认 `false`，保留暖橙品牌色）。
- [ ] `SettingsScreen` 在「外观模式」下新增开关，API < 31 时隐藏并说明。
- [ ] `MainActivity` 把 `dynamicColor` 传入 `VideoDownTheme`（`Theme.kt` 已支持，属接线）。
- [ ] 测试：扩展 `AppearanceModeDeviceTest`。

### A4 启动器快捷方式

- [ ] 新增 `res/xml/shortcuts.xml`：「粘贴解析」「批量导入」「下载任务」，复用现有矢量图标。
- [ ] `AndroidManifest.xml` 增加 `android.app.shortcuts` meta-data 与三个显式 action。
- [ ] `MainActivity.handleIntent` 与 `VideoDownApp` 接线到对应界面。
- [ ] 测试：新增 `LauncherShortcutsDeviceTest`。

### A5 任务页搜索 / 排序筛选 + 剩余时间

- [ ] `HistoryViewModel` 新增 `taskQuery` / `taskSort`（最新、最早、大小、状态）与 setter；筛选排序抽为可 JVM 测试的纯函数。
- [ ] `HistoryScreen` 增加搜索框与排序控件，区分“无任务”与“当前筛选无结果”。
- [ ] `core/Formatters.kt` 新增 `formatEta`，进度行显示「· 约剩 …」。
- [ ] 测试：`TaskQueryTest`、扩展 `FormattersTest`、设备交互用例。

### A6 文档与版本

- [ ] README 增补本轮功能与开关说明。
- [ ] 新增 `docs/validation/p4-features-<date>.md`，沿用现有验收文档格式。
- [ ] 把 A1–A5 从 `docs/function-backlog-2026-10-09.md` 的「可选新增功能」移入「已具备」。

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

平台与通用网址解析、网页辅助嗅探、批量导入（≤50 条）、画质与文件大小选择、重复提醒、下载队列与排序、暂停续传、HLS/DASH/分轨合并与校验、失败分类恢复、共享空间预留、残留清理、收藏/分类/重命名、相册与自定义目录导出、独立音轨与离线提取、续播与 0.5×–4× 倍速、后台音频、画中画、完成通知、检查更新、深浅色主题、`ACTION_SEND` 分享接入与 `VIEW` 深链。

## 验收命令

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pabi=universal   # 模拟器需 x86_64；真机用默认 arm64
./gradlew assembleDebug && ./gradlew assembleRelease
```
