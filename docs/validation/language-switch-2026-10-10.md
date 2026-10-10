# 应用内语言切换验证（2026-10-10）

## 实现

- 新增 `core/AppLanguage.kt`：
  - `AppLanguage`（`SYSTEM` / `CHINESE` / `ENGLISH`）带各自写进系统的标签，`fromTag` 是纯映射（只认 `zh`/`zh-…` 与 `en`/`en-…`，认不出的回落到跟随系统）；
  - `appLanguageSwitchSupported()`、`currentAppLanguage(context)`、`applyAppLanguage(context, language)` 三个 Android 侧入口，全部以 `SDK_INT >= 33` 为前提。
- 「我的 → 外观」新增「语言」行与单选弹窗（`presentation/settings/SettingsScreen.kt`）。**应用不存自己的语言偏好**：Android 13 及以上写进系统的按应用语言（`LocaleManager.setApplicationLocales`），所以这一行与系统设置里的「应用语言」永远是同一个值；切换后系统重建 Activity，界面语言当场跟着变。
- Android 12 及以下没有这个能力：弹窗只解释，并给出一枚「前往设置」（`Settings.ACTION_LOCALE_SETTINGS`）。
- 新增 6 条文案 × 中英两套（`settings_language_*`）；语言名刻意用各自的写法（`简体中文` / `English`），两种语言文件里都一样，看不懂当前界面语言的人才找得到自己要选的那一项（`presentation/Labels.kt#appLanguageLabelRes`）。
- README「语言与文案」同步了这一入口。

## 验证

执行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`：

- JVM **391 项通过**，0 失败、0 错误、0 跳过（新增 `AppLanguageTest` 4 项：标签读写往返、`zh`/`en` 各种写法、`zhx`/`english`/`fr-FR`/空值回落；`LocalizationGuardTest` 的 key 一一对应把新增的 6 条中英对照一并纳入）。
- 设备 `LanguageSwitchDeviceTest`：模拟器 Pixel_10_Pro(AVD)（Android 17 / API 37）与真机 SHARK PRS-A0（Android 11 / API 30）**各 1 项通过、0 失败**（`instrumentation-emulator.xml`、`instrumentation-shark.xml`）。
  同一个用例按 `SDK_INT` 分支，两台设备分别走两条真实路径——不是用 `assumeTrue` 跳过另一条（原因见末节）。

实测证据（截图在 `language-switch-2026-10-10/`）：

| 场景 | 设备 | 证据 |
| :--- | :--- | :--- |
| 外观区新增「语言」行 + 三项单选弹窗 | 模拟器 API 37 | `emulator-language-dialog.png`（行值 `简体中文`，选项 跟随系统 / 简体中文 / English） |
| 应用内切到 English 后整屏变英文 | 模拟器 API 37 | `emulator-after-switch-en.png`（My settings / Appearance / Language=English），`cmd locale get-app-locales` 由 `[]` 变 `[en]` |
| Android 12 及以下只解释并给出系统设置入口 | 真机 API 30 | `shark-api30-language-unsupported.png`（行值 `跟随系统`，弹窗只有说明 + 取消 / 前往设置） |

用例结束后 `@After` 把应用语言恢复成 `zh-CN`，两台设备的语言状态与测试前一致（模拟器复核后已清回 `[]`）。

## 未覆盖边界

- **API 33 以下的英文渲染没有经过应用内开关**：那台真机只有系统语言入口，只验证了解释弹窗与「跟随系统」的取值；英文整屏只在 API 37 模拟器上验证（与既有 `LocalizationDeviceTest` 的边界一致）。
- **没有在下载或播放进行中切换语言**：语言切换会重建 Activity，后台服务不受影响是既有设计，但未实测「下载中 / 播放中切换语言」的界面恢复表现。
- **没验证系统那侧的「应用语言」设置页**：只验证了应用写入的语言标签（`cmd locale get-app-locales`）与界面语言随之改变，没有走系统 UI 反向验证。
- 英文资源里的 `简体中文` 是刻意的自称写法；`LocalizationGuardTest` 只管 Kotlin 字面量与两个资源文件的 key 对应，并不反对这一点。
- 未在平板、折叠屏、横屏下验证新增行的排布。
- **（当时）Lint 仍有 53 个 error，全部位于本轮未改动的既有代码**（这批已在 [lint 收敛](lint-cleanup-2026-10-10.md) 处理到 0）（`VaultAuthenticator` 的权限声明、`SettingsScreen` 等处的 `LocalContextGetResourceValueCall` 48 处、`DownloadEngine` 缩进等）；本轮新增的 4 个文件在 lint 报告中为 0 条。

## 关于「假定跳过」的记账（2026-10-10 晚更正）

先说结论：本工具链**不会**因为 `assumeTrue` 的 `AssumptionViolatedException` 让
`connectedDebugAndroidTest` 失败——它只把这条写进 XML 的 `failures` 计数里，任务本身仍判为通过；
设备报告里的 failures 要按「假定跳过」读。本文早先写成「记成 failure 并让任务失败」是误判，
依据是当天 `LanguageSwitchDeviceTest` 的两次失败里**同时**存在真实断言失败，被我归错了因。

当日复核证据：`DownloadSettingsDeviceTest`（6 项、1 项 `assumeTrue` 跳过）与 `VaultDeviceTest`
（3 项、1–2 项跳过）都报 `BUILD SUCCESSFUL`，XML 里 `failures` 却是 1——即 XML 计数包含跳过，
任务判定不含。
