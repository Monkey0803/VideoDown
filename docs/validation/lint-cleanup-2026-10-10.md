# Lint error 收敛与保险箱门禁修复（2026-10-10）

## 目标与结果

`./gradlew lintDebug` 从 **53 errors / 137 warnings / 6 hints** 收敛到 **0 errors / 137 warnings / 6 hints**。
53 个 error 里有两个是真的会在设备上出问题的 bug（下节），其余是代码规范问题。

| 规则 | 数量 | 处置 |
| :--- | ---: | :--- |
| `MissingPermission` | 2 | **真 bug**：清单缺 `USE_BIOMETRIC`，补上（见下节） |
| `NewApi` | 1 | **真 bug**：`BiometricPrompt.Builder.setDeviceCredentialAllowed` 是 API 29 起，原来 API 28 也会走到（见下节） |
| `LocalContextGetResourceValueCall` | 47 | 8 个界面文件里的 `context.getString` 改成组合期读取的 `LocalResources.current`（`resources.getString`） |
| `SuspiciousIndentation` | 1 | `DownloadEngine` 里漏了花括号的 `if`，补显式 `{ }`（行为不变） |
| `UnsafeOptInUsageError` | 1 | `PlayerScreen` 用 media3 的 `showController`，给该 composable 补 `@androidx.annotation.OptIn(UnstableApi::class)` |
| `ViewModelConstructorInComposable` | 1 | `BackupDeviceTest` 里在 composable 内构造 `HomeViewModel`，移到外面构造再传入 |

## 两个真 bug

### 1. 保险箱门禁缺 `USE_BIOMETRIC`

`SystemVaultAuthenticator` 用框架 `BiometricPrompt`，而 `canAuthenticate` 与 `authenticate` 都要求清单声明
`android.permission.USE_BIOMETRIC`。原清单没有它，而代码把异常吞掉（`runCatching { … }.getOrDefault(false)`），
所以真机上表现为**点了「保险箱锁」什么也不弹**，只看到一句「无法验证」。

实测（模拟器 Pixel_10_Pro / API 37，用 `locksettings set-pin 1234` 造出「设备已加密」）：

| 清单 | 现象 | 证据 |
| :--- | :--- | :--- |
| 无 `USE_BIOMETRIC`（反证） | 系统弹窗**完全不出现**，焦点停在 `MainActivity` | `vault-without-permission.png` |
| 有 `USE_BIOMETRIC`（现状） | 系统弹窗出现：`mCurrentFocus=Window{… BiometricPrompt}`，logcat `handleAuthenticate … CredentialAvailable: true`，界面显示「解锁保险箱 / 验证身份后才能查看其中的内容」 | `vault-real-prompt-with-permission.png` |

### 2. Android 9 会崩的验证路径

`authenticate()` 的版本判断原本是「API ≥ 28」，但 `setDeviceCredentialAllowed(true)` 是 **API 29** 才有的方法：
API 28 上只要有锁屏（`isDeviceSecure`）就会走到它，而这次调用在 `runCatching` **之外** → 不是静默失败而是崩溃。

现在门禁统一要求 **Android 10（API 29）及以上**：Android 11 及以上 `setAllowedAuthenticators(生物识别 或 锁屏凭据)`，
Android 10 用 `setDeviceCredentialAllowed(true)`（生物识别为主、锁屏凭据兜底），Android 9 及以下报告不可用。
README 的保险箱段落已同步。

## 验证

- `./gradlew lintDebug` → **0 errors**（`lintDebug` 任务 BUILD SUCCESSFUL）。
- `./gradlew testDebugUnitTest --rerun-tasks` → **394 项通过**，0 失败。
- 设备回归（模拟器 Pixel_10_Pro / API 37，逐套件跑，覆盖本轮改动的每个文件；原始输出 `device-regression.txt`）：

| 套件 | 用例 | 结果 |
| :--- | ---: | :--- |
| `VaultDeviceTest`（保险箱 + 门禁） | 3 | 通过（1 项 `assumeTrue` 跳过：模拟器通知未开） |
| `LibraryActionsDeviceTest`（LibraryActions/LibraryScreen） | 4 | 通过 |
| `P2LibraryOrganizationDeviceTest`（LibraryScreen） | 3 | 通过 |
| `DownloadSettingsDeviceTest`（SettingsScreen） | 6 | 通过（1 项跳过：通知未开） |
| `AppUpdateEntryDeviceTest`（检查更新弹窗） | 6 | 通过 |
| `LanguageSwitchDeviceTest`（语言切换） | 1 | 通过 |
| `BackupDeviceTest`（设置备份 + 本轮改的测试自身） | 3 | 通过 |
| `BrowserAssistDeviceTest`（BrowserAssistScreen） | 1 | 通过 |
| `PlayerGestureDeviceTest` / `PlayerLandscapeDeviceTest` / `P3PlaybackDeviceTest`（PlayerScreen） | 1 / 4 / 7 | 通过 |
| `P2FileExportDeviceTest`（FileExportDialog） | 6 | 通过 |
| `TaskSearchDeviceTest`（HistoryScreen） | 1 | 通过 |
| `P2BatchImportDeviceTest` / `P1RecoveryNavigationDeviceTest`（HomeScreen） | 4 / 4 | 通过 |

  合计 **53 项，0 真实失败**；XML 里那 2 条 `failures` 是 `assumeTrue` 的跳过（见下节）。
- 模拟器实验后已把 PIN 清掉（`locksettings clear`），应用语言与清单都恢复原状。

## 关于「假定跳过」的记账（更正）

本轮顺带纠正了 2026-10-10 早些时候的一个误判：本工具链**不会**因为
`AssumptionViolatedException` 让 `connectedDebugAndroidTest` 失败——它只把这条写进 XML 的
`failures` 计数，任务本身仍判为通过（当日实证：`DownloadSettingsDeviceTest` 6 项、1 项跳过，
`VaultDeviceTest` 3 项、1 项跳过，两者都 `BUILD SUCCESSFUL`，XML `failures` 都是 1）。
早先写「记成 failure 并让任务失败」是因为当时那两次运行里**同时**有真实断言失败，归错了因。
`LanguageSwitchDeviceTest` 的用例注释与 [语言切换验证记录](language-switch-2026-10-10.md) 已同步更正。

## 未覆盖边界

- **Android 9 的保险箱没有实测**：本轮把「API 28 会崩」的路径改成「报告不可用」，依据是 API 级别与 lint，
  手边没有 API 28 设备或 AVD（模拟器只有 API 37，真机 API 30）。
- **保险箱的失败提示不区分原因**：缺权限、无锁屏、用户取消都落到同一句「无法验证」；要区分需要扩
  `VaultAuthenticator` 的返回类型（本轮没做，因为改成「不吞异常」会让 SecurityException 冒泡到界面）。
- **剩 137 个 warning 未处理**：`PluralsCandidate` 37、`UseKtx` 28、`IntentFilterUniqueDataAttributes` 14、
  `GradleDependency`/`NewerVersionAvailable` 20、`UnusedResources` 10 等；本轮只收 error。
- **`LocalResources` 的语义**：`resources` 在组合期读取（随语言/配置变化重算），但它是**值捕获**——同一组合内
  不会自己刷新；本轮改动的都是「界面重建即重建 lambda」的位置，未涉及长生命周期缓存。
