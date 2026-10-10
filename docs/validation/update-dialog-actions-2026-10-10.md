# 更新对话框动作行验证（2026-10-10）

## 实现

- 「我的 → 检查更新」对话框的三个动作（更新源 / 重新检查 / 完成）改由 M3 动作行直接排布：两个次要操作不再被 `Column` 包住，而是作为动作行的同级子项发出（`presentation/settings/UpdateDialog.kt`）。
- M3 的动作行是自带换行的 `AlertDialogFlowRow`：宽度放得下时三个按钮同处一行并右对齐；放不下时按 M3 规则换行，确认按钮落在次要操作上方。
- 改动原因：`Column` 把「更新源 / 重新检查」固定成上下两行，右侧的「完成」再占一行，界面呈现 2×2 的四个按钮位，而不是一行三个。

## 验证

执行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`：JVM 测试 387 项通过，0 失败、0 错误、0 跳过；Debug 应用与 Android 测试包构建成功。

新增设备用例 `AppUpdateEntryDeviceTest#actionsShareOneRowWhenWidthAllows`：打开检查更新对话框，断言「更新源」「重新检查」「完成」三个按钮顶部坐标一致（同处一行）且从左到右排列。

| 设备 | 系统 | 屏幕 | 字号 | 结果 |
| :--- | :--- | :--- | :--- | :--- |
| SHARK PRS-A0 | 11 | 1080×2400 @440dpi（393dp 宽） | font_scale 0.86 | `AppUpdateEntryDeviceTest` 4 项 OK，0 失败 |
| Pixel_10_Pro(AVD) | 17 | 1280×2856 @480dpi（427dp 宽） | font_scale 1.0 | `AppUpdateEntryDeviceTest` 4 项 OK，0 失败 |

反证：把 `UpdateDialog` 临时改回 `Column` 写法后，同一用例在 SHARK PRS-A0 上失败——顶部坐标 `[174.5, 222.5, 174.5]`（「重新检查」比另两个低 48dp），改回后通过。用例确实能发现被修掉的布局，而不是恒真断言。

证据目录 `update-dialog-actions-2026-10-10/`：`results.md`（汇总）、`actions-one-row-zh.png` 与 `actions-one-row-zh.xml`（真机界面与 `uiautomator` 坐标：三个按钮同为 `top=1395`）、`instrumentation-shark.xml` 与 `instrumentation-emulator.xml`（两台设备的原始测试报告）。

## 验证边界

- 未在平板、横屏、分屏与超大字号下逐一验证；对话框宽度仍由 M3 默认 `DialogMinWidth = 280dp` 决定，动作行只在 280dp − 48dp = 232dp 内排布。
- 中文界面实测可排成一行：截图设备（393dp 宽、font_scale 0.86）下三个按钮合计约 205dp。
- 英文界面**排不下一行**：Pixel_10_Pro(AVD) 实测 `Update source` 117dp、`Check again` 103dp、`Done` 58dp，合计 294dp > 232dp，动作行按 M3 规则换行，`Done` 落在两个次要操作上方单独一行（M3 动作行的既定换行顺序：确认动作在上）。若要英文也排成一行，只能缩短英文按钮文案（如 `Source` / `Recheck`）或缩小按钮内边距，两者都改动既有文案或视觉规范，本次未做。
- 本次只改动作行排布，未改按钮文案、启用条件与点击行为。
