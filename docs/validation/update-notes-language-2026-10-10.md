# 更新说明按界面语言选择（notesEn）验证（2026-10-10）

## 背景

清单只有一个 `notes` 字段，英文用户看到的是中文说明。现在清单可以同时带 `notes`（中文）与 `notesEn`（英文），界面按当前语言取对应的一份。

## 实现

| 文件 | 改动 |
| :--- | :--- |
| `data/update/AppUpdateManifest.kt` | 新增 `notesEn`（`@SerialName("notesEn")`）与纯函数 `notesFor(english)`：优先该语言的一份，缺失时回落到另一份，两版都空就是没有说明（空白串不算）。 |
| `data/update/AppUpdateChecker.kt` | `UpdateCheckResult.UpdateAvailable` 去掉预先解析的 `notes`：数据层没有 Context，选语言是界面层的事，改由界面读 `latest.notesFor(...)`。 |
| `presentation/settings/UpdateViewModel.kt` | `UpdateUiState.Available` 同步去掉 `notes`，只带 `latest` 与两个链接。 |
| `presentation/settings/UpdateDialog.kt` | 弹窗新增 `englishUi` 参数，用 `current.latest.notesFor(englishUi)` 取说明。**语言在调用点算**（`SettingsScreen` 里读 `LocalConfiguration`）：弹窗渲染在独立窗口，那里的资源/配置未必等于应用语言，弹窗自己查会取错。 |
| `scripts/publish-release.sh` | 新增 `--manifest-notes-en-file`，把英文说明写进清单 `notesEn`（两个字段都只取文件第一段）。 |
| `README.md` | 清单字段与发版命令补上 `notesEn`。 |

## 验证

- **JVM：394 项通过，0 失败**（新增 3 例：`keeps both note languages apart` 走真实 HTTP mock 断言 `notesFor` 两侧取值、`falls back to the other language when one note is missing` 覆盖缺失/空白/都没有、`english notes win over the changelog alias` 覆盖别名与英文的优先级）。
- **设备 `AppUpdateEntryDeviceTest` 6/6 通过**（模拟器 Pixel_10_Pro / API 37 与真机 SHARK PRS-A0 / API 30 各跑一次）：
  - `notesFollowTheChineseInterface`：中文界面显示 `中文更新说明…`，且不出现英文说明；
  - `notesFollowTheEnglishInterface`：英文界面显示 `English note…`，且不出现中文说明。
  - 语言用「渲染时提供的英文 `LocalConfiguration` + `LocalContext`」固定，不切系统/应用语言（切语言会重建 Activity、冲掉替身界面），因此两条分支在任何设备上都能确定性断言；已顺带补回被换掉的 `ActivityResultRegistryOwner`（否则 `rememberLauncherForActivityResult` 抛 `No ActivityResultRegistryOwner`）。
  - 注意：弹窗是独立窗口，里面的 `stringResource` 仍按 Activity 资源渲染，所以英文这条只断言**来自清单**的说明文本。
- **对着线上真实清单的端到端验证**（模拟器 + 带默认更新源的 debug 包，临时把线上清单改成 `versionCode 6 / 9.9.9-test`）：
  - 中文界面：「发现新版本：9.9.9-test」+ `中文验证说明：这一版按界面语言取说明。`（`emulator-notes-zh.png`）；
  - 英文界面：「New version available: 9.9.9-test」+ `English verification note: this release picks the note by interface language.`（`emulator-notes-en.png`）；
  - 随后恢复线上清单并复核回到 `1.1.0`。
- 线上 v1.1.0 的 `version.json` 已重新上传为带 `notesEn` 的版本（`versionCode` 仍为 5），并 `curl` 复核两个字段都在。

## 边界与提醒

- **已发布的 1.1.0 APK 不认识 `notesEn`**（它是本次改动之前构建的），装在用户手机上时仍显示中文 `notes`——这是优雅降级，不是错误。新字段从**下一个版本**起生效；本次没有重传 1.1.0 的 APK 附件（已经发布的包不该被替换）。
- 清单里只有这两个语言字段：再加语言需要再扩字段（或改成语言到说明的 map）。
- 英文说明由本次一并撰写，未经母语者校对（与既有英文文案同一状态）。
- 设备测试里那套「CompositionLocal 覆盖语言」的技巧**不能跨对话框窗口**（实测：设置页是英文、弹窗里的资源文案仍是中文），因此英文分支在设备上只能断言清单里的文本；弹窗自身文案的语言由既有的 `LocalizationDeviceTest` 覆盖。
