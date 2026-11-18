# 组件与重复代码收敛验收记录（2026-11-18）

## 范围

- 弹窗窗口、媒体封面、全选控件、主按钮、内联提示、空状态与剪贴板读取各收敛为一处实现。
- 批量导入改用共享的弹窗骨架/主按钮/封面/全选/提示；Library、History、SiteHub、Browser、Home 的重复封面与选择条同步迁移。
- `SelectionToolbar` 从 `library` 包移到 `presentation/components/`；`BatchFailureDetails` 留在 `history/`。
- 不改变业务路径：50 条上限、顺序解析、提交时查重、无新依赖/持久化/路由。

## 自动化结果

### JVM 与 Android 测试编译

```text
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew testDebugUnitTest compileDebugAndroidTestKotlin
```

结果：通过。

- 新增 `MediaCoverStateTest`（4 例）：无模型/空串/可加载/已加载时的扫光判定。
- 新增 `SelectionControlsTest`（3 例）：全选优先于部分、部分为 Indeterminate、空为 Off。
- `LocalizationGuardTest`：删除重复 key 后中英仍一一对应，中文预算 0。

### 设备测试

环境：Pixel 10 Pro AVD，Android 17 / API 37。以下逐类运行
`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<class>`：

| 类 | 结果 | 说明 |
|---|---|---|
| `P2BatchImportDeviceTest` | 10/10 | 弹窗骨架、主按钮、三态全选、剪贴板粘贴、横屏+键盘、200% 字体 |
| `ResultSelectionDeviceTest` | 4/4 | 结果选择面板改用三态全选；取消路径改为再点一次「全选」 |
| `BatchFeaturesDeviceTest` | 5/7 | 两条为既有失败：`batchDownloadKeepsIndividualQualityAndSkipsDuplicates`（找不到「360P」节点）与 `sortingPickerAppliesSelectionAndDismissKeepsCurrentQuery`（「文件最大」chip 不可见），已在 `docs/TODO.md` 记录为基线 `615c104` 可复现，与本轮无关 |
| `TaskSearchDeviceTest` | 2/2 | 任务卡封面迁移，`task-cover-*` 保留 |
| `LibraryFileHealthDeviceTest` | 6/6 | Library 封面迁移 |
| `LibraryFileHealthUiDeviceTest` | 2/2 | 同上 |
| `LibraryReorderDeviceTest` | 4/4 | 同上 + 选择条 |
| `LibraryKindFilterDeviceTest` | 1/1 | 同上 |
| `PlayerCoverTransitionDeviceTest` | 1/1 | 封面共享元素未受 `MediaCover` 影响 |
| `SiteHubDeviceTest` | 5/5 | 观看记录封面迁移，`site-watch-cover-*` 保留 |
| `BrowserAssistDeviceTest` | 6/6 | 全屏弹窗外壳迁移；WebView/返回键行为不变 |
| `CoilImageLoadingDeviceTest` | 1/1 | 共享封面加载 |
| `HomeKeyboardDeviceTest` | 5/5 | 迁移后键盘/焦点路径不变；原「下载偏好」断言已按设置分类改为「下载与网络」（既有过期断言，非本轮引入） |
| `P2LibraryOrganizationDeviceTest` | 4/4 | `AppDialog` 骨架迁移后页脚判据仍成立 |
| `ClipboardDetectDeviceTest` | 4/4 | 剪贴板读取收敛到 `readClipboardText` |
| `LauncherShortcutsDeviceTest` | 9/9 | 快捷方式与批量导入关闭锚点 |
| `SpaceBudgetDeviceTest` | 3/3 | 存储提示改用 `InlineNotice` |
| `GenericVideoDeviceTest` | 7 通过 + 2 跳过 | 两条按既有前提（`process_exit_phase`、`generic_live_url`）跳过 |
| `ImageDownloadDeviceTest` | 4/4 | 首轮并跑时的偶发失败在单独重跑后消失；封面/结果页迁移不影响该路径 |
| `DownloadRetryStatusDeviceTest` | 3/3 | 任务状态行与封面 |
| `EmptyStateIllustrationDeviceTest` | 4/4 | `EmptyState` 迁移后插图/形状/任务页空态不变 |
| `LibraryActionsDeviceTest` | 4/4 | 已存封面/长按/删除路径 |
| `XLoginSessionDeviceTest` | 7/7 | 全离线；会清空 WebView Cookie（登录隔离类）。同时把「未登录」断言限定到 X 行，避开与哔哩哔哩行的重复匹配 |

说明：设备测试均使用覆盖安装（`android.injected.androidTest.leaveApksInstalledAfterRun=true`），没有卸载应用。

## 视觉证据

目录：[`docs/validation/component-reuse-2026-11-18/`](component-reuse-2026-11-18/)

| 文件 | 内容 |
|---|---|
| `input-light.png` / `input-dark.png` | 批量导入输入态：共享 `AppDialogWindow` + 全宽 `AppPrimaryButton` |
| `results-light.png` / `results-dark.png` | 批量导入结果态：`MediaCover` 占位、`SelectAllToggle`、`InlineNotice`、失败行单条重试 |

真实 CDN 封面只在任务页/已存页由本地缓存与历史数据展示；批量导入截图的封面为空占位，不代表加载失败。

> `library-selection.png` / `task-list.png` / `home.png` 含本机相册内容，只作为本地人工验收产物，未提交到仓库。


## 未覆盖边界

- 未在真机（OEM 输入法、折叠屏、分屏）验证；仅 Pixel 10 Pro AVD。
- 200% 字体与横屏+键盘由 `P2BatchImportDeviceTest` 覆盖，未做 OEM 字体布局人工走查。
- TalkBack 完整朗读未做；只覆盖关键控件语义名与 48dp 触控。
- 批量导入结果截图的封面为测试宿主假数据（`coverUrl = null`），未联调真实 CDN 缩略图。
- 播放器视频面、看图器缩略图与日期网格、圆形头像按语义保留原实现，未迁移到 `MediaCover`。
- `BatchFeaturesDeviceTest` 两条既有失败保持未修复（与本轮无关，见 `docs/TODO.md`）。
