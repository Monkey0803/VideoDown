# 任务页搜索框圆角对齐验证（2026-10-10）

## 实现

- 「任务」页（下载任务）与「已存」页的搜索框改为 `shape = MaterialTheme.shapes.large`，即主题里的 `AppShapes.large = 16.dp`。
- 对照对象是「解析」页的链接输入框：它由 `HomeScreen.kt` 的 `Row(...).clip(MaterialTheme.shapes.large)` 包裹，本来就是 `shapes.large`。
- 改之前，两个搜索框用的是 Material 3 `OutlinedTextField` 的默认形状 `extraSmall`（4dp），所以比「解析」页的输入框方得多。
- 只改形状，未改尺寸、间距、文案、图标与交互。

改动位置：[HistoryScreen.kt:188](app/src/main/java/com/videodown/douyin/presentation/history/HistoryScreen.kt#L188)、[LibraryScreen.kt:235](app/src/main/java/com/videodown/douyin/presentation/library/LibraryScreen.kt#L235)。

## 验证

真机 SHARK PRS-A0（Android 11，1080×2400 @440dpi，1dp = 2.75px）逐像素量测顶行内缩（越接近 16dp=44px 说明圆角越大，抗锯齿会让实测略小）：

| 元素 | 顶行左缘 | 直边 | 顶行内缩 |
| :--- | ---: | ---: | ---: |
| 「解析」页链接输入框（对照） | x=148 | x=108 | 40px ≈ 14.5dp |
| 「任务」页搜索框（改后） | x=96 | x=61 | 35px ≈ 12.7dp |

两者都落在 `shapes.large`（16dp）的渲染误差内（搜索框是 1px 描边、输入框是实心填充，抗锯齿表现不同），截图见 `task-search-after.png` 与 `parse-input-reference.png`。

设备用例（真机 SHARK PRS-A0）：

- `TaskSearchDeviceTest` 1/1 通过（`instrumentation-shark-task-search.xml`）；
- `LibraryActionsDeviceTest` 4/4 通过（`instrumentation-shark-library-actions.xml`）。

模拟器 Pixel_10_Pro(AVD)（Android 17 / API 37）：`P2LibraryOrganizationDeviceTest` 3/3 通过（`instrumentation-emulator-library-organization.xml`）。

JVM：`./gradlew testDebugUnitTest` 387 项通过，0 失败、0 错误、0 跳过；`assembleDebug` 成功。

## 验证边界

- 圆角没有自动化断言：Compose 语义树里没有形状信息，用例只能覆盖搜索框仍可输入、筛选仍生效；圆角一致性用截图与像素量测佐证，值来自主题常量 `AppShapes.large = 16.dp`。
- 未在平板、折叠屏、横屏下复核搜索框排布（`docs/TODO.md` 已记录「非手机形态未验证」）。
- `P2LibraryOrganizationDeviceTest#actualRenameAndCategorySurviveRotationAndFilterSavedFile` 在真机 SHARK PRS-A0（API 30）上失败：切横屏后断言「保存修改」不可见，3 项里 1 项失败（原始报告 `instrumentation-shark-library-organization-failure.xml`）。
  - **与本改动无关**：把两个搜索框的 `shape` 还原为默认后重新构建复跑，同一用例以完全相同的断言失败（同样 3 项 / 1 失败），且模拟器 API 37 上 3/3 通过——属于「模拟器通过、API 30 真机失败」的既有差异。
  - 本轮未修复，也未写入 `docs/TODO.md` 的既有失败项清单（该文件正被其他未提交改动占用，交由仓库维护者归档）。
