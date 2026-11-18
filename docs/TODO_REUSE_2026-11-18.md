# 组件与重复代码收敛 TODO（2026-11-18）

## 目标

一种模式一份实现：弹窗窗口、媒体封面、全选控件、主按钮、内联提示、空状态与剪贴板读取
各只保留一处实现，并让批量导入、已存/任务、首页、站点与 Browser 共同使用。

> 状态：已完成。验证证据见 [组件收敛验收记录](validation/component-reuse-2026-11-18.md)。

## 完成标准

- [x] `BatchImportDialog.kt` 不再自行写 `DialogProperties`、`AppDialogContainer`、卡片 `Surface`、封面 `AsyncImage`、全选勾选行或主按钮定义。
- [x] `DialogProperties` / `AppDialogContainer` 只出现在 `components/AppDialog.kt`（`BrowserAssistScreen` / `XLoginScreen` 已迁到 `AppFullScreenDialog`）。
- [x] 已迁移的列表/网格封面只经 `MediaCover`；`TriStateCheckbox` 只出现在 `SelectionControls.kt`。
- [x] 纯逻辑有 JVM 用例；界面迁移有设备用例逐类通过，既有失败项单独注明。
- [x] `AGENTS.md` 写入复用规则，`docs/validation/` 记录命令、结果与未覆盖边界。

## 1. 共享骨架与按钮

- [x] `AppDialogWindow` / `AppFullScreenDialog` / `AppDialogMetrics` 收敛到 `components/AppDialog.kt`；`AppDialog` 与批量导入共用前者，Browser Assist 与 X 登录共用后者。
- [x] `AppPrimaryButton` 从 `HomeScreen` 搬到组件；首页两处与批量导入输入区共用。
- [x] 页脚改走 `AppDialogButtonRow`。

## 2. 封面与选择

- [x] `MediaCover` 提取并迁移：批量导入、Library、History、SiteHub、Browser、Home 选择网格、Home 最近保存、Home 结果 hero。
- [x] `MediaCover` 内部复用 `Modifier.shimmer()`；无模型时不扫光。
- [x] `SelectAllToggle` 提取并迁移：批量导入状态条、Library/History 选择条、Home 结果选择面板。
- [x] `SelectionToolbar` 移到 `components/`；`BatchFailureDetails` 移到 `history/`。

## 3. 提示、空态与剪贴板

- [x] `InlineNotice` 提取并迁移：批量导入通知条、存储清理结果、首页剪贴板横幅。
- [x] `EmptyState` 提取并迁移：Library 与 History 空态（插图只在整库为空时显示）。
- [x] `core/ClipboardText.kt` 收敛四处剪贴板读取（Home 显式/被动、MainActivity 快捷方式、批量导入）。

## 4. 词表与清理

- [x] 批量导入使用 `action_select_all`；删除重复 `import_select_all`。
- [x] 结果选择面板改为三态勾选后删除 `action_deselect_all`，中英同步。
- [x] 删除迁移后不再使用的私有实现与导入。

## 5. 验证与文档

- [x] `testDebugUnitTest`、`compileDebugAndroidTestKotlin` 通过；新增 `MediaCoverStateTest`、`SelectionControlsTest`。
- [x] 迁移相关设备类逐类通过；`BatchFeaturesDeviceTest` 的两条既有失败按基线注明。
- [x] 截图与命令写入 `docs/validation/component-reuse-2026-11-18.md`。
